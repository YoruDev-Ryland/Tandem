using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Tandem.Protocol;
using Tandem.Server.Data;
using Tandem.Server.Events;
using Tandem.Server.Http;

namespace Tandem.Server.Blobs;

/// <summary>Test seams in the move job; the server registers <see cref="NoMoveHooks"/>.</summary>
public interface IMoveHooks
{
    /// <summary>After the first copy pass, before the hold.</summary>
    Task AfterCopyPassAsync(CancellationToken ct);

    /// <summary>After a copy is written and flushed to disk, before it is read back and verified.</summary>
    Task AfterFileWrittenAsync(string copyPath, CancellationToken ct);

    /// <summary>Runs the save that stores the switch (a test may make it throw, before or after saving).</summary>
    Task SaveSwitchAsync(Func<Task> save);

    /// <summary>Before each read-back of files.location after a failed switch save (a test may make it throw).</summary>
    Task BeforeReadBackAsync(int attempt);
}

public sealed class NoMoveHooks : IMoveHooks
{
    public Task AfterCopyPassAsync(CancellationToken ct) => Task.CompletedTask;
    public Task AfterFileWrittenAsync(string copyPath, CancellationToken ct) => Task.CompletedTask;
    public Task SaveSwitchAsync(Func<Task> save) => save();
    public Task BeforeReadBackAsync(int attempt) => Task.CompletedTask;
}

/// <summary>The move's waits: how long in-flight writes may take to finish for the hold, and the backoff between
/// read-backs of a switch whose save failed. Tests shorten them.</summary>
public sealed class MoveTimings
{
    public TimeSpan HoldTimeout { get; init; } = TimeSpan.FromSeconds(60);
    public TimeSpan ReadBackFirstDelay { get; init; } = TimeSpan.FromSeconds(1);
    public TimeSpan ReadBackMaxDelay { get; init; } = TimeSpan.FromSeconds(30);
    /// <summary>Before a "not stored" read-back is believed, the wait for a second read (a commit can land late).</summary>
    public TimeSpan LateCommitDelay { get; init; } = TimeSpan.FromSeconds(2);
}

/// <summary>
/// Moves the stored files to another folder while the server runs. One move at a time, in the background:
/// <list type="number">
/// <item><b>Copy</b>: writes its marker in the target, then copies every blob under the current root to the same
/// relative path there, through a <c>*.copy-*</c> name: flushed to disk, read back, its SHA-256 compared with the file
/// name (blobs are named by their hash), and only then renamed. Uploads go on into the current folder.</item>
/// <item><b>Hold</b>: takes <see cref="FilesGate.HoldAsync"/> (device uploads answer 503, server writes wait); not within
/// 60 s, the move fails. Under the hold it copies what was added since, and drops its copies of files deleted since.</item>
/// <item><b>Switch</b>: stores <c>files.location</c> and the finished status in one save, swaps
/// <see cref="FilesLocation"/>, releases the hold.</item>
/// </list>
/// <para>Every file and folder the job creates in the target is listed in <see cref="JournalName"/> there before (or,
/// for a finished copy, right after) it is created; a failure or cancel before the switch deletes exactly those, its
/// marker and the journal, and nothing else, and never touches the current folder. The running move is stored as the
/// server setting <see cref="Key"/>, so a restart does the same cleanup (<see cref="StartAsync"/>).</para>
/// <para>While a move runs <see cref="FilesGate.Moving"/> is set: the pruner and backups skip, and account deletion and
/// <see cref="RemoveOldAsync"/> answer 503 server_moving. The job never takes <see cref="Saves.UserLocks"/> or
/// <see cref="Saves.MaintenanceLock"/>: server-side blob writes wait on the gate while holding a user's lock, so taking
/// one under the hold could deadlock. Deletes that were already under way when the move began take the gate's shared
/// side, so the last pass under the hold sees them.</para>
/// </summary>
public sealed class FilesMover(IServiceScopeFactory scopes, FilesLocation files, FilesGate gate, MoveTargetCheck checker,
    IEventHub hub, IMoveHooks hooks, MoveTimings timings, TimeProvider time, IOptions<ServerOptions> options, ILogger<FilesMover> logger)
    : IHostedService
{
    public const string Key = "files.move";
    public const string JournalName = ".tandem-move-journal";
    public const string Unchanged = "Nothing was changed.";
    public const string CancelledError = "The move was cancelled. " + Unchanged;
    public const string RestartedError = "The server restarted during the move. " + Unchanged;
    public const string HoldTimeoutError = "Uploads did not pause in time. " + Unchanged;
    public const string SourceMissingError = "The current files folder went missing during the move. " + Unchanged;
    public const string TargetChangedError = "The new folder changed during the move. " + Unchanged;
    private static readonly TimeSpan ProgressInterval = TimeSpan.FromSeconds(2);

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { Converters = { new JsonStringEnumConverter() } };

    /// <summary>What <see cref="Key"/> holds: the status, the id of the marker the job wrote, the topmost target folder the
    /// move created (removed again on failure if empty), and whether a failed move's cleanup finished.</summary>
    internal sealed record MoveRecord(MoveStatus Status, Guid Id, string? CreatedDir, bool Cleaned);

    private sealed class MoveFailedException(string message) : Exception(message);

    private sealed class Job(string from, string target, Guid id, string? createdDir, Guid? sourceMarker)
    {
        public string From { get; } = from;
        public string Target { get; } = target;
        public Guid Id { get; } = id;
        public string? CreatedDir { get; } = createdDir;
        public Guid? SourceMarker { get; } = sourceMarker;
        public CancellationTokenSource Cts { get; } = new();
        public Task Task { get; set; } = Task.CompletedTask;
        public HashSet<string> Copied { get; } = new(StringComparer.Ordinal);
        public StreamWriter? Journal { get; set; }
        public string? StopReason { get; set; }
        /// <summary>Folders this job created below the target, in creation order.</summary>
        public List<string> CreatedDirs { get; } = [];
        public DateTimeOffset LastNotify { get; set; }
        public long SourceBytes { get; set; }
    }

    private readonly object _sync = new();
    private Job? _job;
    private bool _removing;
    private volatile MoveRecord? _record;
    /// <summary>Set once <see cref="StartAsync"/> has dealt with a move a restart cut short; no move starts before.</summary>
    private volatile bool _recovered;
    /// <summary>Per checked folder, the topmost folder a successful check created for it (cleanup removes it if empty).</summary>
    private readonly Dictionary<string, string> _checkCreated = new(StringComparer.Ordinal);

    public MoveStatus? Status => _record?.Status;

    // ---- requests ----

    /// <summary>The checks for a target folder, with the bytes a move would copy now.</summary>
    public MoveCheckResult Check(string? path)
    {
        RequireSourcePresent();
        var created = TopmostMissing(path);
        var result = checker.Run(path ?? "", BytesToCopy());
        lock (_sync)
        {
            if (!result.Ok) _checkCreated.Remove(result.Path); // a failed check removes what it created
            else if (created is not null) _checkCreated[result.Path] = created;
        }
        return result;
    }

    /// <summary>Checks the folder and starts the move in the background. False (with the failed check) when the folder
    /// is refused; 409 move_running when a move (or the removal of an old copy, or the startup check of the last move) is
    /// running; 409 old_copy_pending while the last move's old copy is still there (a second move would orphan it).</summary>
    public bool TryStart(string path, out MoveCheckResult check)
    {
        lock (_sync)
        {
            if (!_recovered)
                throw new ApiException(409, "move_running", "The server is still checking the last move. Try again in a moment.");
            if (_job is not null || _removing)
                throw new ApiException(409, "move_running", "A move of the stored files is already running.");
            if (_record is { Status: { Phase: MovePhase.Done, OldLocation: not null } })
                throw new ApiException(409, "old_copy_pending", "Remove the old copy from the last move first.");
            RequireSourcePresent();
            var from = files.Root;
            var createdDir = TopmostMissing(path);
            check = checker.Run(path ?? "", BytesToCopy());
            if (!check.Ok)
            {
                _checkCreated.Remove(check.Path);
                return false;
            }
            // The folder may come from an earlier check of the same path, which left it in place.
            if (_checkCreated.Remove(check.Path, out var earlier)) createdDir ??= earlier;

            var job = new Job(from, check.Path, Guid.NewGuid(), createdDir, FilesLocation.ReadMarker(from));
            var status = new MoveStatus(MovePhase.Copying, from, job.Target, 0, 0, 0, 0, null, null, 0, time.GetUtcNow());
            _record = new MoveRecord(status, job.Id, createdDir, Cleaned: false);
            _job = job;
            gate.Moving = true;
            job.Task = Task.Run(() => RunAsync(job));
            return true;
        }
    }

    /// <summary>Stops a running move before its switch; nothing happens after it (or with no move).</summary>
    public void Cancel()
    {
        Job? job;
        lock (_sync) job = _job;
        if (job is null) return;
        job.StopReason ??= CancelledError;
        try { job.Cts.Cancel(); } catch (ObjectDisposedException) { }
    }

    /// <summary>Deletes the old copy a finished move left (see <see cref="DeleteOldCopy"/>: never a blob a user still
    /// holds that the current folder lacks). 503 server_moving while a move runs; 409 when there is no old copy; 503
    /// files_unavailable while the current folder is missing.</summary>
    public async Task RemoveOldAsync(CancellationToken ct)
    {
        MoveRecord record;
        string old;
        lock (_sync)
        {
            if (_job is not null || gate.Moving || _removing)
                throw ApiException.ServerMoving("The stored files are being moved. Try again when the move is done.");
            if (_record is not { Status: { Phase: MovePhase.Done, OldLocation: { } o } } r)
                throw new ApiException(409, "no_old_copy", "There is no old copy of the stored files to remove.");
            record = r;
            old = o;
            _removing = true;
        }
        try
        {
            if (!SafeToRemove(old))
                throw new ApiException(409, "no_old_copy", "The old folder holds the current files or the server's data; it is left alone.");
            if (!files.CheckPresent())
                throw new ApiException(503, "files_unavailable", "The new files folder is missing. The old copy is kept until it is back.");
            var current = files.Root;
            var missing = await Task.Run(() => MissingFromCurrent(old, current), ct);
            var referenced = await ReferencedAsync(missing, ct);
            var (kept, keptBytes) = await Task.Run(() => DeleteOldCopy(old, current, missing, referenced), ct);
            var done = record with
            {
                Status = kept == 0
                    ? record.Status with { OldLocation = null, OldBytes = 0, OldKept = 0 }
                    : record.Status with { OldBytes = keptBytes, OldKept = kept },
            };
            await PersistAsync(done, CancellationToken.None);
            _record = done;
            if (kept == 0) logger.LogInformation("Removed the old copy of the stored files in {Folder}", old);
            else logger.LogWarning("Removed the old copy of the stored files in {Folder} except {Kept} files the new folder {Current} does not hold",
                old, kept, current);
            hub.NotifyAll("changed", "storage");
        }
        finally
        {
            lock (_sync) _removing = false;
        }
    }

    // ---- the job ----

    private async Task RunAsync(Job job)
    {
        var ct = job.Cts.Token;
        MoveRecord? final = null;
        // Once the switch is stored the new folder is the current one: nothing after it may run the failure cleanup.
        var switched = false;
        try
        {
            await PersistAsync(_record!, ct);
            hub.NotifyAll("changed", "storage");
            // The check left the folder in place; if it is gone now (an unmounted disk) this fails rather than recreate it.
            job.Journal = new StreamWriter(new FileStream(Path.Combine(job.Target, JournalName), FileMode.CreateNew, FileAccess.Write, FileShare.Read));
            FilesLocation.WriteMarker(job.Target, job.Id);

            await CopyPassAsync(job, final: false, ct);
            await hooks.AfterCopyPassAsync(ct);

            Update(job, s => s with { Phase = MovePhase.Holding }, notify: true);
            var holding = gate.HoldAsync(timings.HoldTimeout, ct); // sets Holding before its first await
            hub.NotifyAll("storage");
            IDisposable? hold;
            try { hold = await holding; }
            catch { hub.NotifyAll("storage"); throw; }
            if (hold is null)
            {
                hub.NotifyAll("storage");
                throw new MoveFailedException(HoldTimeoutError);
            }
            try
            {
                if (!SourceStillThere(job)) throw new MoveFailedException(SourceMissingError);
                await CopyPassAsync(job, final: true, ct);
                RequireTargetIntact(job);
                SyncCreatedDirectories(job);
                ct.ThrowIfCancellationRequested(); // the last point a cancel is taken
                final = await SwitchAsync(job);
                switched = true;
            }
            finally
            {
                hold.Dispose();
                hub.NotifyAll("storage");
            }
            CloseJournal(job);
            TryDeleteFile(Path.Combine(job.Target, JournalName));
            logger.LogInformation("Moved the stored files from {From} to {To}", job.From, job.Target);
        }
        catch (Exception ex) when (switched)
        {
            // The move is done and stored; only a step after it failed (an event, closing or deleting the journal).
            logger.LogError(ex, "The stored files moved to {To}, but a step after the switch failed", job.Target);
            CloseJournal(job);
            TryDeleteFile(Path.Combine(job.Target, JournalName)); // else the next start removes it
        }
        catch (Exception ex)
        {
            final = await FailAsync(job, ex);
        }

        gate.Moving = false;
        lock (_sync)
        {
            _record = final!;
            _job = null;
        }
        job.Cts.Dispose();
        try { hub.NotifyAll("changed", "storage"); }
        catch (Exception ex) { logger.LogError(ex, "Could not announce the end of the move"); }
    }

    private async Task<MoveRecord> FailAsync(Job job, Exception ex)
    {
        var error = ex switch
        {
            MoveFailedException => ex.Message,
            OperationCanceledException when job.Cts.IsCancellationRequested => job.StopReason ?? CancelledError,
            IOException or UnauthorizedAccessException => $"The copy failed: {ex.Message} {Unchanged}",
            _ => $"The move failed: {ex.Message} {Unchanged}",
        };
        if (ex is MoveFailedException or OperationCanceledException) logger.LogWarning("Moving the stored files failed: {Error}", error);
        else logger.LogError(ex, "Moving the stored files failed");
        CloseJournal(job);

        var failed = _record!.Status with { Phase = MovePhase.Failed, Error = error };
        var cleaned = Cleanup(job.Target, job.Id, job.CreatedDir, logger);
        var record = new MoveRecord(failed, job.Id, job.CreatedDir, cleaned);
        try { await PersistAsync(record, CancellationToken.None); }
        catch (Exception persist) { logger.LogError(persist, "Could not store the failed move"); }
        return record;
    }

    /// <summary>One pass over the current root. The first copies everything; the last (under the hold) copies what is
    /// new and deletes this job's copies of files that were deleted since.</summary>
    private async Task CopyPassAsync(Job job, bool final, CancellationToken ct)
    {
        var entries = EnumerateBlobs(job.From);
        List<(string Rel, long Size)> todo;
        if (!final)
        {
            todo = entries;
            Update(job, s => s with { FilesTotal = entries.Count, BytesTotal = entries.Sum(e => e.Size) }, notify: true);
        }
        else
        {
            var present = entries.Select(e => e.Rel).ToHashSet(StringComparer.Ordinal);
            foreach (var rel in job.Copied.Where(r => !present.Contains(r)).ToList())
            {
                File.Delete(Path.Combine(job.Target, rel)); // only ever this job's own copy
                job.Copied.Remove(rel);
            }
            todo = entries.Where(e => !job.Copied.Contains(e.Rel)).ToList();
            Update(job, s => s with { FilesTotal = job.Copied.Count + todo.Count, FilesDone = job.Copied.Count,
                BytesTotal = entries.Sum(e => e.Size), BytesDone = entries.Where(e => job.Copied.Contains(e.Rel)).Sum(e => e.Size) }, notify: false);
        }

        foreach (var (rel, size) in todo)
        {
            ct.ThrowIfCancellationRequested();
            var copied = await CopyOneAsync(job, rel, ct);
            // A file deleted before it was copied is simply not part of the move.
            Update(job, s => copied ? s with { FilesDone = s.FilesDone + 1, BytesDone = s.BytesDone + size }
                : s with { FilesTotal = s.FilesTotal - 1, BytesTotal = s.BytesTotal - size }, notify: false);
            if (time.GetUtcNow() - job.LastNotify >= ProgressInterval) Notify(job);
        }
        if (final) job.SourceBytes = _record!.Status.BytesTotal; // what the old copy holds
    }

    /// <summary>Copies one blob via a temp name, flushed to disk, read back and verified against its name, then renamed.
    /// False when the source was deleted before it could be read.</summary>
    private async Task<bool> CopyOneAsync(Job job, string rel, CancellationToken ct)
    {
        var source = Path.Combine(job.From, rel);
        var dest = Path.Combine(job.Target, rel);
        var dir = Path.GetDirectoryName(dest)!;
        EnsureDirectories(job, dir);
        var temp = $"{dest}.copy-{Guid.NewGuid():N}";
        Log(job, 't', Path.GetRelativePath(job.Target, temp));
        try
        {
            FileStream input;
            try
            {
                input = new FileStream(source, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete, 81920,
                    FileOptions.Asynchronous | FileOptions.SequentialScan);
            }
            catch (Exception ex) when (ex is FileNotFoundException or DirectoryNotFoundException) { return false; }
            await using (input)
            await using (var output = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None, 81920,
                             FileOptions.Asynchronous | FileOptions.SequentialScan))
            {
                await input.CopyToAsync(output, ct);
                await output.FlushAsync(ct);
                output.Flush(flushToDisk: true);
            }
            await hooks.AfterFileWrittenAsync(temp, ct);
            if (!string.Equals(await HashAsync(temp, ct), Path.GetFileName(rel), StringComparison.Ordinal))
                throw new MoveFailedException($"A copied file did not match the original ({rel}). {Unchanged}");
            File.Move(temp, dest, overwrite: false); // the target was empty: an existing file is not this job's to replace
            Log(job, 'f', rel);
            job.Copied.Add(rel);
            Durability.SyncDirectory(dir);
            return true;
        }
        finally
        {
            TryDeleteFile(temp); // gone already after the rename
        }
    }

    /// <summary>Creates the folders below the target that do not exist yet, listing each in the journal first.</summary>
    private static void EnsureDirectories(Job job, string dir)
    {
        var missing = new Stack<string>();
        for (var d = dir; !string.Equals(d, job.Target, StringComparison.Ordinal) && !Directory.Exists(d); d = Path.GetDirectoryName(d)!)
            missing.Push(d);
        while (missing.TryPop(out var d))
        {
            Log(job, 'd', Path.GetRelativePath(job.Target, d));
            Directory.CreateDirectory(d);
            job.CreatedDirs.Add(d);
        }
    }

    private static void Log(Job job, char kind, string rel)
    {
        job.Journal!.WriteLine($"{kind} {rel}");
        job.Journal.Flush();
    }

    private static void CloseJournal(Job job)
    {
        try { job.Journal?.Dispose(); }
        catch (IOException) { }
        job.Journal = null;
    }

    /// <summary>Under the hold, before the switch: the target still holds this move's marker and every copy.</summary>
    private static void RequireTargetIntact(Job job)
    {
        if (FilesLocation.ReadMarker(job.Target) != job.Id || job.Copied.Any(rel => !File.Exists(Path.Combine(job.Target, rel))))
            throw new MoveFailedException(TargetChangedError);
    }

    /// <summary>The new folders' entries (and the marker's) on disk before the switch is stored: each file's own folder
    /// was synced after its rename, so this covers the folders themselves.</summary>
    private static void SyncCreatedDirectories(Job job)
    {
        foreach (var d in job.CreatedDirs) Durability.SyncDirectory(Path.GetDirectoryName(d)!);
        Durability.SyncDirectory(job.Target);
        // Every level from the target's parent up to the parent of the topmost folder the check created.
        var top = job.CreatedDir is { } created && (string.Equals(created, job.Target, StringComparison.Ordinal)
                                                   || MoveTargetCheck.IsInside(job.Target, created, orSame: false))
            ? Path.GetDirectoryName(created)
            : Path.GetDirectoryName(job.Target);
        for (var d = Path.GetDirectoryName(job.Target); d is not null; d = Path.GetDirectoryName(d))
        {
            Durability.SyncDirectory(d);
            if (string.Equals(d, top, StringComparison.Ordinal)) break;
        }
    }

    /// <summary>The current root is still the folder the move started from (an unmounted disk would look empty, and the
    /// last pass would then drop every copy).</summary>
    private static bool SourceStillThere(Job job) =>
        Directory.Exists(job.From) && FilesLocation.ReadMarker(job.From) == job.SourceMarker;

    /// <summary>Stores files.location and the finished status in one save, then swaps the location. Runs under the
    /// hold. When the save throws, whether it was stored is read back, retried with backoff for as long as the database
    /// does not answer: until then the hold stays, so no blob is written to either folder. Stored: the location is made
    /// current without writing again. Not stored (twice, a moment apart): it throws, and the move fails and cleans up.</summary>
    private async Task<MoveRecord> SwitchAsync(Job job)
    {
        var status = _record!.Status;
        var done = new MoveRecord(status with { Phase = MovePhase.Done, Error = null, OldLocation = job.From, OldBytes = job.SourceBytes },
            job.Id, job.CreatedDir, Cleaned: true);
        try
        {
            using var scope = scopes.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
            await TrackAsync(db, done, CancellationToken.None);
            await hooks.SaveSwitchAsync(() => files.SwitchAsync(db, job.Target, job.Id, CancellationToken.None)); // both settings in one save
            return done;
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Storing the switch to {Target} failed; uploads stay on hold until it is known whether it was stored", job.Target);
            var attempt = 0;
            var delay = timings.ReadBackFirstDelay;
            async Task<bool> ReadUntilAnsweredAsync()
            {
                bool? answer;
                while ((answer = await SwitchStoredAsync(job.Target, job.Id, ++attempt)) is null) delay = await BackOffAsync(delay);
                return answer.Value;
            }

            var stored = await ReadUntilAnsweredAsync();
            if (!stored)
            {
                // A commit can land after the error is reported: believe "not stored" only when a second read agrees.
                await Task.Delay(timings.LateCommitDelay, CancellationToken.None);
                stored = await ReadUntilAnsweredAsync();
            }
            if (!stored) throw;

            // Stored (both settings went in the same save): make it current in memory, with no further write, so a
            // database that reads but does not write cannot keep the hold.
            files.UseStored(job.Target, job.Id);
            logger.LogWarning("The switch to {Target} was stored despite the error; the move is done", job.Target);
            return done;
        }
    }

    private async Task<TimeSpan> BackOffAsync(TimeSpan delay)
    {
        await Task.Delay(delay, CancellationToken.None);
        var next = delay * 2;
        return next > timings.ReadBackMaxDelay ? timings.ReadBackMaxDelay : next;
    }

    /// <summary>Whether files.location names this target with this id; null when that cannot be read.</summary>
    private async Task<bool?> SwitchStoredAsync(string target, Guid id, int attempt = 1)
    {
        try
        {
            await hooks.BeforeReadBackAsync(attempt);
            using var scope = scopes.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
            var row = await db.ServerSettings.AsNoTracking().FirstOrDefaultAsync(s => s.Key == FilesLocation.Key);
            if (row is null) return false;
            var setting = JsonSerializer.Deserialize<FilesLocationSetting>(row.Value, Json);
            return setting is not null && setting.Id == id && string.Equals(setting.Path, target, StringComparison.Ordinal);
        }
        catch (Exception ex)
        {
            logger.LogError(ex, "Could not read the stored files location");
            return null;
        }
    }

    private void Update(Job job, Func<MoveStatus, MoveStatus> change, bool notify)
    {
        lock (_sync)
        {
            var r = _record!;
            _record = r with { Status = change(r.Status) };
        }
        if (notify) Notify(job);
    }

    private void Notify(Job job)
    {
        job.LastNotify = time.GetUtcNow();
        hub.NotifyAll("changed", "storage");
    }

    // ---- restart ----

    /// <summary>A move stored as running did not finish: when its switch was stored it is done (the old copy measured
    /// again); otherwise its partial copy is deleted and it is marked failed. A failed move whose switch was
    /// stored anyway (a commit that landed after the move gave up) is made done when the new folder still holds its
    /// marker and every file of the old one; otherwise files.location goes back to the old folder, so it never names a
    /// folder the cleanup emptied. A finished move's status (and old copy) is loaded. No move starts before this ran.</summary>
    public async Task StartAsync(CancellationToken cancellationToken)
    {
        try { await RecoverAsync(cancellationToken); }
        finally { _recovered = true; }
    }

    private async Task RecoverAsync(CancellationToken cancellationToken)
    {
        MoveRecord? record;
        try
        {
            using var scope = scopes.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
            var row = await db.ServerSettings.AsNoTracking().FirstOrDefaultAsync(s => s.Key == Key, cancellationToken);
            if (row is null) return;
            record = JsonSerializer.Deserialize<MoveRecord>(row.Value, Json);
        }
        catch (JsonException ex)
        {
            logger.LogError(ex, "The stored move status is unreadable; it is left as it is");
            return;
        }
        if (record?.Status is not { } status) return;

        var running = status.Phase is MovePhase.Copying or MovePhase.Holding;
        if (status.Phase == MovePhase.Done)
        {
            if (string.Equals(files.Root, status.To, StringComparison.Ordinal))
                TryDeleteFile(Path.Combine(status.To, JournalName)); // left if the server stopped right after the switch
            _record = record;
            return;
        }

        switch (await SwitchStoredAsync(status.To, record.Id))
        {
            // Running: the switch was stored and uploads may have gone to the new folder since, so it is the current one
            // even if it is missing now (an unmounted disk is waited for, never replaced by the stale old copy). Failed:
            // uploads went on in the old folder, so the new one is used only if it is whole.
            case true when running || FilesLocation.ReadMarker(status.To) == record.Id && HoldsEvery(status.To, status.From):
                TryDeleteFile(Path.Combine(status.To, JournalName));
                var (count, bytes) = Measure(status.From, status.FilesTotal, status.BytesTotal);
                record = record with { Status = status with { Phase = MovePhase.Done, Error = null, OldLocation = status.From,
                    OldBytes = bytes, FilesTotal = count, FilesDone = count, BytesTotal = bytes, BytesDone = bytes, OldKept = 0 }, Cleaned = true };
                if (!running) logger.LogWarning("A failed move of the stored files to {To} was stored after all; it is done", status.To);
                break;
            case true:
                // A failed move stored after all, but the new folder is not whole (its cleanup ran, or uploads went on in
                // the old one): the old folder, which has every file, is the current one again.
                record = record with { Cleaned = record.Cleaned || Cleanup(status.To, record.Id, record.CreatedDir, logger) };
                await RevertLocationAsync(record, status.From);
                logger.LogWarning("A move of the stored files to {To} was stored but the folder is not whole; the files stay in {From}",
                    status.To, status.From);
                return;
            case false when !running && record.Cleaned:
                _record = record; // an ordinary failed move
                return;
            case false:
                var cleaned = Cleanup(status.To, record.Id, record.CreatedDir, logger);
                record = record with { Status = status with { Phase = MovePhase.Failed, Error = running ? RestartedError : status.Error },
                    Cleaned = cleaned };
                logger.LogWarning("A move of the stored files to {To} did not finish; its partial copy was removed", status.To);
                break;
            default:
                _record = running ? record with { Status = status with { Phase = MovePhase.Failed, Error = RestartedError } } : record;
                return; // nothing is deleted while the stored location cannot be read
        }
        _record = record;
        await PersistAsync(record, CancellationToken.None);
    }

    /// <summary>Points files.location back at the old folder (no setting for the default one) and stores the failed
    /// record, in one save; then reloads the location.</summary>
    private async Task RevertLocationAsync(MoveRecord record, string from)
    {
        _record = record;
        using var scope = scopes.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        await files.StageAsync(db, from, CancellationToken.None);
        await TrackAsync(db, record, CancellationToken.None);
        await db.SaveChangesAsync(CancellationToken.None);
        await files.LoadAsync(db, CancellationToken.None);
    }

    /// <summary>Every blob of <paramref name="other"/> is in <paramref name="root"/> at the same relative path.</summary>
    private static bool HoldsEvery(string root, string other) =>
        EnumerateBlobs(other).All(b => File.Exists(Path.Combine(root, b.Rel)));

    /// <summary>The blobs a folder holds (count and bytes); the fallback when it is not there.</summary>
    private static (int Count, long Bytes) Measure(string root, int fallbackCount, long fallbackBytes)
    {
        if (!Directory.Exists(root)) return (fallbackCount, fallbackBytes);
        try
        {
            var blobs = EnumerateBlobs(root);
            return (blobs.Count, blobs.Sum(b => b.Size));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return (fallbackCount, fallbackBytes); }
    }

    /// <summary>A running move is cancelled and cleaned up (as a restart would) before the server stops.</summary>
    public async Task StopAsync(CancellationToken cancellationToken)
    {
        Job? job;
        lock (_sync) job = _job;
        if (job is null) return;
        job.StopReason = RestartedError;
        try { job.Cts.Cancel(); } catch (ObjectDisposedException) { }
        try { await job.Task.WaitAsync(cancellationToken); }
        catch (OperationCanceledException) { }
    }

    // ---- files ----

    /// <summary>Deletes what the move created in the target: the files and folders in its journal (folders only when
    /// empty), its marker when it holds this move's id, the journal, and the target folders the move created when empty.
    /// Never anything else. True when all of that went.</summary>
    internal static bool Cleanup(string target, Guid id, string? createdDir, ILogger logger)
    {
        var ok = true;
        var journal = Path.Combine(target, JournalName);
        var dirs = new List<string>();
        try
        {
            if (File.Exists(journal))
                foreach (var line in File.ReadAllLines(journal))
                {
                    if (line.Length < 3 || line[1] != ' ') continue;
                    var full = Path.GetFullPath(Path.Combine(target, line[2..]));
                    if (!MoveTargetCheck.IsInside(full, target, orSame: false)) continue; // never outside the target
                    if (line[0] == 'd') dirs.Add(full);
                    else if (line[0] is 'f' or 't' && !TryDeleteFile(full)) ok = false;
                }
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            logger.LogError(ex, "Could not read the move journal in {Target}", target);
            return false;
        }
        for (var i = dirs.Count - 1; i >= 0; i--) TryDeleteEmptyDirectory(dirs[i]);
        if (FilesLocation.ReadMarker(target) == id && !TryDeleteFile(Path.Combine(target, FilesLocation.MarkerName))) ok = false;
        if (!TryDeleteFile(journal)) ok = false;
        if (createdDir is not null && (string.Equals(target, createdDir, StringComparison.Ordinal) || MoveTargetCheck.IsInside(target, createdDir, orSame: false)))
            for (var d = target; d is not null; d = Path.GetDirectoryName(d))
            {
                if (!TryDeleteEmptyDirectory(d)) break;
                if (string.Equals(d, createdDir, StringComparison.Ordinal)) break;
            }
        if (!ok) logger.LogError("Some files of the failed move could not be removed from {Target}", target);
        return ok;
    }

    /// <summary>Every blob under the root (relative path and size). Markers, journals, temp and copy files are not
    /// blobs: only files named by a hash are moved.</summary>
    private static List<(string Rel, long Size)> EnumerateBlobs(string root)
    {
        var list = new List<(string, long)>();
        if (!Directory.Exists(root)) return list;
        var walk = new EnumerationOptions { RecurseSubdirectories = true, AttributesToSkip = 0, IgnoreInaccessible = false };
        foreach (var info in new DirectoryInfo(root).EnumerateFiles("*", walk))
        {
            if (!BlobHash.IsValid(info.Name)) continue;
            try { list.Add((Path.GetRelativePath(root, info.FullName), info.Length)); }
            catch (FileNotFoundException) { } // deleted while listing
        }
        return list;
    }

    public long BytesToCopy() => EnumerateBlobs(files.Root).Sum(e => e.Size);

    private void RequireSourcePresent()
    {
        if (!files.CheckPresent())
            throw new ApiException(503, "files_unavailable", "The stored files folder is missing. Nothing is stored until it is back.");
    }

    private static async Task<string> HashAsync(string path, CancellationToken ct)
    {
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 81920,
            FileOptions.Asynchronous | FileOptions.SequentialScan);
        return Convert.ToHexStringLower(await SHA256.HashDataAsync(stream, ct));
    }

    /// <summary>The first folder of the path that does not exist yet (what checking and moving will create), or null.</summary>
    private static string? TopmostMissing(string? path)
    {
        if (string.IsNullOrWhiteSpace(path) || !Path.IsPathFullyQualified(path)) return null;
        string full;
        try { full = Path.TrimEndingDirectorySeparator(Path.GetFullPath(path)); }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException) { return null; }
        string? missing = null;
        for (var d = full; d is not null && !Directory.Exists(d); d = Path.GetDirectoryName(d)) missing = d;
        return missing;
    }

    /// <summary>The old copy never holds the current files or the data directory (the database).</summary>
    private bool SafeToRemove(string old)
    {
        if (!Path.IsPathFullyQualified(old)) return false;
        var full = Path.TrimEndingDirectorySeparator(Path.GetFullPath(old));
        var current = Path.TrimEndingDirectorySeparator(Path.GetFullPath(files.Root));
        var data = Path.TrimEndingDirectorySeparator(Path.GetFullPath(options.Value.DataDirectory));
        return !MoveTargetCheck.IsInside(current, full, orSame: true) && !MoveTargetCheck.IsInside(full, current, orSame: true)
               && !MoveTargetCheck.IsInside(data, full, orSame: true);
    }

    /// <summary>The old copy's blobs, per user folder: (user id, hash, relative path, size).</summary>
    private static IEnumerable<(Guid User, string Hash, string Rel, FileInfo File)> OldBlobs(string root)
    {
        if (!Directory.Exists(root)) yield break;
        var walk = new EnumerationOptions { RecurseSubdirectories = true, AttributesToSkip = 0, IgnoreInaccessible = false };
        foreach (var dir in Directory.EnumerateDirectories(root))
        {
            if (!Guid.TryParseExact(Path.GetFileName(dir), "N", out var user)) continue;
            foreach (var info in new DirectoryInfo(dir).EnumerateFiles("*", walk))
                if (BlobHash.IsValid(info.Name)) yield return (user, info.Name, Path.GetRelativePath(root, info.FullName), info);
        }
    }

    /// <summary>The old blobs the current root has no file for.</summary>
    private static HashSet<(Guid, string)> MissingFromCurrent(string root, string current) =>
        OldBlobs(root).Where(b => !File.Exists(Path.Combine(current, b.Rel))).Select(b => (b.User, b.Hash)).ToHashSet();

    /// <summary>Of those, the ones a user still holds (a UserBlobs row): the only copy of a stored save.</summary>
    private async Task<HashSet<(Guid, string)>> ReferencedAsync(HashSet<(Guid User, string Hash)> missing, CancellationToken ct)
    {
        var referenced = new HashSet<(Guid, string)>();
        if (missing.Count == 0) return referenced;
        using var scope = scopes.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        foreach (var group in missing.GroupBy(m => m.User))
            foreach (var chunk in group.Select(m => m.Hash).Chunk(500))
            {
                var user = group.Key;
                foreach (var hash in await db.UserBlobs.AsNoTracking().Where(b => b.UserId == user && chunk.Contains(b.Hash))
                             .Select(b => b.Hash).ToListAsync(ct))
                    referenced.Add((user, hash));
            }
        return referenced;
    }

    /// <summary>Only Tandem's entries, in the per-user folders (named by a user id). A blob file goes when the current
    /// root holds the same relative path, or when it was found missing there and no user holds it any more (pruned, or
    /// its account deleted, after the switch). A missing blob a user still holds is kept and counted, and so is one that
    /// went missing from the current root after that was checked. Upload temp files go too; anything else stays. Then
    /// the emptied folders, and, when no blob was kept, the marker, a journal and the folder itself if nothing else is
    /// left. Returns the kept blobs' count and bytes.</summary>
    private static (int Kept, long Bytes) DeleteOldCopy(string root, string current, HashSet<(Guid, string)> missing,
        HashSet<(Guid, string)> referenced)
    {
        if (!Directory.Exists(root)) return (0, 0);
        var kept = 0;
        long bytes = 0;
        foreach (var (user, hash, rel, info) in OldBlobs(root).ToList())
        {
            var key = (user, hash);
            if (File.Exists(Path.Combine(current, rel)) || missing.Contains(key) && !referenced.Contains(key)) info.Delete();
            else
            {
                kept++;
                bytes += info.Length;
            }
        }
        var walk = new EnumerationOptions { RecurseSubdirectories = true, AttributesToSkip = 0, IgnoreInaccessible = false };
        foreach (var dir in Directory.EnumerateDirectories(root))
        {
            if (!Guid.TryParseExact(Path.GetFileName(dir), "N", out _)) continue;
            foreach (var tmp in Directory.EnumerateFiles(dir, "*.tmp-*", walk)) TryDeleteFile(tmp);
            foreach (var sub in Directory.EnumerateDirectories(dir, "*", walk).OrderByDescending(d => d.Length))
                TryDeleteEmptyDirectory(sub);
            TryDeleteEmptyDirectory(dir);
        }
        if (kept == 0)
        {
            TryDeleteFile(Path.Combine(root, FilesLocation.MarkerName));
            TryDeleteFile(Path.Combine(root, JournalName));
            TryDeleteEmptyDirectory(root);
        }
        return (kept, bytes);
    }

    private async Task PersistAsync(MoveRecord record, CancellationToken ct)
    {
        using var scope = scopes.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        await TrackAsync(db, record, ct);
        await db.SaveChangesAsync(ct);
    }

    private static async Task TrackAsync(TandemDbContext db, MoveRecord record, CancellationToken ct)
    {
        var text = JsonSerializer.Serialize(record, Json);
        var row = await db.ServerSettings.FirstOrDefaultAsync(s => s.Key == Key, ct);
        if (row is null) db.ServerSettings.Add(new ServerSetting { Key = Key, Value = text });
        else row.Value = text;
    }

    private static bool TryDeleteFile(string path)
    {
        try
        {
            File.Delete(path);
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return false; }
    }

    private static bool TryDeleteEmptyDirectory(string dir)
    {
        try
        {
            if (!Directory.Exists(dir) || Directory.EnumerateFileSystemEntries(dir).Any()) return false;
            Directory.Delete(dir);
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return false; }
    }
}
