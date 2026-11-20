using System.Formats.Tar;
using System.IO.Compression;
using System.IO.Pipelines;
using System.Text.Json;
using EmuSync.Protocol;
using EmuSync.Server.Blobs;
using EmuSync.Server.Data;
using EmuSync.Server.Events;
using EmuSync.Server.Saves;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace EmuSync.Server.Backup;

/// <summary>
/// The off-server backup. Daily at <see cref="BackupSettingsDto.HourUtc"/> (server time from <see cref="TimeProvider"/>)
/// when enabled, or on demand. A run holds the <see cref="MaintenanceLock"/> throughout, so the pruner never deletes a
/// blob file while it is being read; uploads carry on, and rows they write after the snapshot are just not in it.
/// <para>A run: export every table in one read transaction to temp files under DATA_DIR/tmp (rows only; blobs are read
/// in place), then stream tar → gzip → (encryption) → target through a pipe, never holding the archive in memory or
/// on disk. Only after the new backup is stored are backups beyond <see cref="BackupSettingsDto.Keep"/> deleted.
/// Every run writes one history row and raises <see cref="BackupFinished"/>.</para>
/// </summary>
public sealed class BackupService(IServiceScopeFactory scopes, MaintenanceLock maintenance, IDomainEvents events,
    IOptions<ServerOptions> options, TimeProvider time, ILogger<BackupService> logger) : BackgroundService
{
    public const int HistoryShown = 20;
    public const int HistoryKeep = 50;
    public const int MaxErrorLength = 1000;
    private static readonly TimeSpan CheckInterval = TimeSpan.FromMinutes(1);

    private int _active;
    private CancellationToken _stopping = CancellationToken.None;

    /// <summary>Removes the row-export directories (they hold password hashes) a crashed or killed run left under
    /// DATA_DIR/tmp. Only for startup, when no run is in progress.</summary>
    public static void SweepStaleTemps(string dataDirectory)
    {
        var dir = Path.Combine(dataDirectory, "tmp");
        if (!Directory.Exists(dir)) return;
        foreach (var d in Directory.EnumerateDirectories(dir, "backup-*"))
        {
            try { Directory.Delete(d, recursive: true); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    public bool IsRunning => Volatile.Read(ref _active) > 0;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _stopping = stoppingToken;
        if (!options.Value.EnableBackups) return;
        while (!stoppingToken.IsCancellationRequested)
        {
            try { await RunIfDueAsync(stoppingToken); }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { return; }
            catch (Exception ex) { logger.LogError(ex, "Backup schedule check failed"); }

            try { await Task.Delay(CheckInterval, time, stoppingToken); }
            catch (OperationCanceledException) { return; }
        }
    }

    /// <summary>Due when today's (or, before the hour, yesterday's) slot at <paramref name="hourUtc"/> has passed and
    /// no run (scheduled or manual, ok or not) started since. So a newly enabled schedule runs within a minute, a failed
    /// run is not retried until the next slot, and a server that was down at the hour catches up when it starts.</summary>
    public static bool IsDue(DateTimeOffset now, int hourUtc, DateTimeOffset? lastRun)
    {
        var utc = now.ToUniversalTime();
        var slot = new DateTimeOffset(utc.Year, utc.Month, utc.Day, hourUtc, 0, 0, TimeSpan.Zero);
        if (slot > utc) slot = slot.AddDays(-1);
        return lastRun is null || lastRun < slot;
    }

    public async Task<BackupRunInfo?> RunIfDueAsync(CancellationToken ct)
    {
        BackupSettingsDto settings;
        DateTimeOffset? last;
        using (var scope = scopes.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>();
            settings = await BackupSettings.LoadAsync(db, ct);
            if (!settings.Enabled) return null;
            last = await db.BackupRuns.AsNoTracking().OrderByDescending(r => r.Id).Select(r => (DateTimeOffset?)r.Utc).FirstOrDefaultAsync(ct);
        }
        if (!IsDue(time.GetUtcNow(), settings.HourUtc, last)) return null;
        // A manual run already in progress counts as this slot's run: skip rather than queue a second one behind it.
        return Interlocked.CompareExchange(ref _active, 1, 0) == 0 ? await RunClaimedAsync(ct) : null;
    }

    /// <summary>Starts a run on the thread pool unless one is already running.</summary>
    public bool TryStartInBackground()
    {
        if (Interlocked.CompareExchange(ref _active, 1, 0) != 0) return false; // atomic start-if-idle
        _ = Task.Run(async () =>
        {
            try { await RunClaimedAsync(_stopping); }
            catch (OperationCanceledException) { }
            catch (Exception ex) { logger.LogError(ex, "Backup run failed"); }
        });
        return true;
    }

    /// <summary>One backup run with the current settings (run regardless of <see cref="BackupSettingsDto.Enabled"/>).
    /// Failures are recorded and returned, not thrown; only cancellation throws.</summary>
    public Task<BackupRunInfo> RunOnceAsync(CancellationToken ct)
    {
        Interlocked.Increment(ref _active);
        return RunClaimedAsync(ct);
    }

    /// <summary>The run itself; the caller has already counted it in <c>_active</c>, and this releases that.</summary>
    private async Task<BackupRunInfo> RunClaimedAsync(CancellationToken ct)
    {
        try
        {
            using (await maintenance.AcquireAsync(ct))
            {
                var started = time.GetUtcNow();
                string? name = null;
                long bytes = 0;
                string? error = null;
                try
                {
                    BackupSettingsDto settings;
                    using (var scope = scopes.CreateScope())
                        settings = await BackupSettings.LoadAsync(scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>(), ct);
                    var target = BackupTargets.Create(settings);
                    name = BackupFormat.NameFor(started, settings.Encrypt);
                    bytes = await WriteAsync(target, name, settings, started, ct);
                    logger.LogInformation("Backup {Name} written ({Bytes} bytes)", name, bytes);
                    error = await PruneTargetAsync(target, name, settings.Keep, ct);
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested)
                {
                    await RecordAsync(new BackupRun { Utc = started, Ok = false, Name = name, Error = "The backup was cancelled (server shutting down)." });
                    throw;
                }
                catch (Exception ex)
                {
                    error = Describe(ex);
                    bytes = 0;
                    logger.LogError("Backup failed: {Error}", error);
                }

                var run = new BackupRun { Utc = started, Ok = error is null, Bytes = bytes, Name = name, Error = Truncate(error) };
                await RecordAsync(run);
                await events.Raise(new BackupFinished(run.Ok, run.Error), CancellationToken.None);
                return new BackupRunInfo(run.Utc, run.Ok, run.Bytes, run.Error);
            }
        }
        finally
        {
            Interlocked.Decrement(ref _active);
        }
    }

    private static string Describe(Exception ex) => ex switch
    {
        BackupException => ex.Message,
        Amazon.Runtime.AmazonServiceException s => $"S3 error: {s.Message}",
        IOException or UnauthorizedAccessException => $"Could not write the backup: {ex.Message}",
        _ => $"{ex.GetType().Name}: {ex.Message}",
    };

    private static string? Truncate(string? s) => s is null || s.Length <= MaxErrorLength ? s : s[..MaxErrorLength];

    private async Task RecordAsync(BackupRun run)
    {
        using var scope = scopes.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>();
        db.BackupRuns.Add(run);
        await db.SaveChangesAsync(CancellationToken.None);
        var old = await db.BackupRuns.OrderByDescending(r => r.Id).Skip(HistoryKeep).Select(r => r.Id).ToListAsync(CancellationToken.None);
        if (old.Count > 0) await db.BackupRuns.Where(r => old.Contains(r.Id)).ExecuteDeleteAsync(CancellationToken.None);
    }

    /// <summary>Deletes backups beyond the newest <paramref name="keep"/> (by the time in their names). Only names of the
    /// backup pattern are ever considered. Returns an error message when that failed (the new backup is still there).</summary>
    private async Task<string?> PruneTargetAsync(IBackupTarget target, string written, int keep, CancellationToken ct)
    {
        try
        {
            var names = await target.ListAsync(ct);
            // Same second: the encrypted and plain names sort by timestamp first either way.
            var doomed = names.Where(BackupFormat.IsBackupName).OrderByDescending(n => n, StringComparer.Ordinal)
                .Skip(Math.Max(1, keep)).Where(n => n != written).ToList();
            foreach (var name in doomed)
            {
                await target.DeleteAsync(name, ct);
                logger.LogInformation("Deleted old backup {Name}", name);
            }
            return null;
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            return $"The backup {written} was written, but old backups could not be removed: {Describe(ex)}";
        }
    }

    /// <summary>Writes the archive to the target and returns its size in bytes.</summary>
    private async Task<long> WriteAsync(IBackupTarget target, string name, BackupSettingsDto settings, DateTimeOffset started,
        CancellationToken ct)
    {
        var dataDir = options.Value.DataDirectory;
        var tmp = Directory.CreateDirectory(Path.Combine(dataDir, "tmp", $"backup-{Guid.NewGuid():N}")).FullName;
        try
        {
            using var scope = scopes.CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>();
            var store = scope.ServiceProvider.GetRequiredService<IBlobStore>();
            var export = await BackupDatabase.ExportAsync(db, tmp, BackupSettings.RedactForExport, ct);

            var pipe = new Pipe(new PipeOptions(pauseWriterThreshold: 8 << 20, resumeWriterThreshold: 4 << 20));
            var produce = ProduceAsync(pipe.Writer, export, store, settings, started, ct);
            var counting = new CountingStream(pipe.Reader.AsStream(leaveOpen: true));
            try
            {
                await target.WriteAsync(name, counting, ct);
            }
            catch (Exception ex)
            {
                await pipe.Reader.CompleteAsync(ex); // unblocks the producer: its next write throws
                try { await produce; } catch { /* the target's failure is the one to report */ }
                throw;
            }
            await pipe.Reader.CompleteAsync();
            await produce;
            return counting.Count;
        }
        finally
        {
            try { Directory.Delete(tmp, recursive: true); }
            catch (IOException ex) { logger.LogWarning(ex, "Could not delete backup temp dir {Dir}", tmp); }
            catch (UnauthorizedAccessException ex) { logger.LogWarning(ex, "Could not delete backup temp dir {Dir}", tmp); }
        }
    }

    /// <summary>tar → gzip → (encrypt) → pipe. The pipe is completed with the exception on failure, so the target
    /// sees a failed read rather than an end of stream and stores nothing.</summary>
    private static async Task ProduceAsync(PipeWriter writer, BackupDatabase.Export export, IBlobStore store, BackupSettingsDto settings,
        DateTimeOffset started, CancellationToken ct)
    {
        await Task.Yield();
        try
        {
            await using var sink = writer.AsStream(leaveOpen: true);
            var encrypt = settings.Encrypt
                ? BackupCrypto.Encrypt(sink, settings.Passphrase ?? throw new BackupException("Encryption is on but no passphrase is set."))
                : null;
            await using (encrypt)
            {
                await using (var gzip = new GZipStream((Stream?)encrypt ?? sink, CompressionLevel.Optimal, leaveOpen: true))
                {
                    await using (var tar = new TarWriter(gzip, TarEntryFormat.Pax, leaveOpen: true))
                    {
                        await WriteEntriesAsync(tar, export, store, started, ct);
                    }
                }
                if (encrypt is not null) await encrypt.FinishAsync(ct);
            }
            await sink.FlushAsync(ct);
            await writer.CompleteAsync();
        }
        catch (Exception ex)
        {
            await writer.CompleteAsync(ex);
            throw;
        }
    }

    private static async Task WriteEntriesAsync(TarWriter tar, BackupDatabase.Export export, IBlobStore store, DateTimeOffset started,
        CancellationToken ct)
    {
        var manifest = new BackupManifest(BackupFormat.FormatVersion, BackupFormat.ServerVersion, started, export.Provider,
            export.Tables.ToDictionary(t => t.Table, t => t.Rows), export.Blobs.Count, export.Blobs.Sum(b => b.Size));
        using (var json = new MemoryStream(JsonSerializer.SerializeToUtf8Bytes(manifest, BackupFormat.ManifestJson)))
            await tar.WriteEntryAsync(Entry(BackupFormat.ManifestEntry, json, started), ct);

        foreach (var table in export.Tables)
        {
            await using var file = new FileStream(table.Path, FileMode.Open, FileAccess.Read, FileShare.Read, 81920,
                FileOptions.Asynchronous | FileOptions.SequentialScan);
            await tar.WriteEntryAsync(Entry(BackupFormat.DbDir + table.Table + ".jsonl", file, started), ct);
        }

        foreach (var (userId, hash, _) in export.Blobs)
        {
            await using var blob = await store.OpenAsync(userId, hash, ct)
                ?? throw new BackupException(
                    $"Blob {hash} of user {userId} is recorded but its file is missing from the data directory; no backup was written.");
            await tar.WriteEntryAsync(Entry($"{BackupFormat.BlobDir}{userId:N}/{hash}", blob, started), ct);
        }
    }

    private static PaxTarEntry Entry(string name, Stream data, DateTimeOffset time) =>
        new(TarEntryType.RegularFile, name) { DataStream = data, ModificationTime = time };
}
