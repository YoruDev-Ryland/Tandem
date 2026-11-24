using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;
using Tandem.Protocol;
using Tandem.Server.Backup;
using Tandem.Server.Blobs;
using Tandem.Server.Data;
using Tandem.Server.Events;
using Tandem.Server.Retention;
using Tandem.Server.Tests.Infrastructure;

namespace Tandem.Server.Tests.Blobs;

[Collection("Memory")]
public class FilesMoverTests : IDisposable
{
    private const string Admin = "admin@example.com";
    private static readonly TimeSpan Soon = TimeSpan.FromSeconds(20);
    private readonly List<string> _dirs = [];

    public void Dispose()
    {
        foreach (var d in _dirs)
            try { Directory.Delete(d, recursive: true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }

    /// <summary>Test seams: a pause after the first copy pass and a look at each written copy before it is verified.</summary>
    private sealed class Hooks : IMoveHooks
    {
        public Func<CancellationToken, Task>? AfterCopyPass { get; set; }
        public Action<string>? AfterFileWritten { get; set; }

        public Task AfterCopyPassAsync(CancellationToken ct) => AfterCopyPass?.Invoke(ct) ?? Task.CompletedTask;

        public Task AfterFileWrittenAsync(string copyPath, CancellationToken ct)
        {
            AfterFileWritten?.Invoke(copyPath);
            return Task.CompletedTask;
        }

        public Func<Func<Task>, Task>? SaveSwitch { get; set; }
        public Task SaveSwitchAsync(Func<Task> save) => SaveSwitch?.Invoke(save) ?? save();

        public Func<int, Task>? BeforeReadBack { get; set; }
        public Task BeforeReadBackAsync(int attempt) => BeforeReadBack?.Invoke(attempt) ?? Task.CompletedTask;
    }

    private sealed class NoContainer : IMountTable
    {
        public bool InContainer => false;
        public string? MountPointOf(string path) => null;
    }

    /// <summary>Records every NotifyAll with whether the hold was on at that moment.</summary>
    private sealed class RecordingHub(IEventHub inner, FilesGate gate) : IEventHub
    {
        public List<(string Type, string? Topic, bool Holding)> All { get; } = [];
        public void NotifyAll(string type, string? topic = null)
        {
            lock (All) All.Add((type, topic, gate.Holding));
            inner.NotifyAll(type, topic);
        }
        public void NotifyDevice(Guid deviceId) => inner.NotifyDevice(deviceId);
        public void NotifyUser(Guid userId, string topic) => inner.NotifyUser(userId, topic);
        public void CloseDevice(Guid deviceId) => inner.CloseDevice(deviceId);
        public void CloseUser(Guid userId) => inner.CloseUser(userId);
        public IEventSubscription Subscribe(Guid userId, Guid? deviceId, CancellationToken ct) => inner.Subscribe(userId, deviceId, ct);
    }

    private sealed record Server(ServerFactory Factory, Hooks Hooks, HttpClient Device, HttpClient Admin) : IDisposable
    {
        public FilesLocation Files => Factory.Services.GetRequiredService<FilesLocation>();
        public FilesMover Mover => Factory.Services.GetRequiredService<FilesMover>();
        public FilesGate Gate => Factory.Services.GetRequiredService<FilesGate>();
        public void Dispose() => Factory.Dispose();
    }

    private static async Task<Server> Start(Action<IServiceCollection>? extra = null, bool allowRegistration = false,
        MoveTimings? timings = null)
    {
        var hooks = new Hooks();
        var factory = new ServerFactory();
        factory.Settings["Tandem:MinFreeBytes"] = "1";
        if (allowRegistration) factory.Settings["Tandem:AllowRegistration"] = "true";
        factory.ConfigureExtraServices = s =>
        {
            s.RemoveAll<IMoveHooks>();
            s.AddSingleton<IMoveHooks>(hooks);
            s.RemoveAll<IMountTable>();
            s.AddSingleton<IMountTable, NoContainer>();
            s.RemoveAll<MoveTimings>();
            s.AddSingleton(timings ?? new MoveTimings
            {
                ReadBackFirstDelay = TimeSpan.FromMilliseconds(10), ReadBackMaxDelay = TimeSpan.FromMilliseconds(40),
                LateCommitDelay = TimeSpan.FromMilliseconds(20),
            });
            extra?.Invoke(s);
        };
        var (device, _) = await factory.Register(Admin);
        return new Server(factory, hooks, device, await factory.WebLogin(Admin));
    }

    private string NewDir(string name = "target")
    {
        var parent = Directory.CreateTempSubdirectory("tandem-move-").FullName;
        _dirs.Add(parent);
        return Path.Combine(parent, name);
    }

    private static string Hash(byte[] bytes) => Convert.ToHexStringLower(SHA256.HashData(bytes));

    private static async Task<string> PutBlob(HttpClient device, string text)
    {
        var bytes = Encoding.UTF8.GetBytes(text);
        var content = new ByteArrayContent(bytes);
        content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        var r = await device.PutAsync($"/v1/blobs/{Hash(bytes)}", content);
        Assert.True(r.IsSuccessStatusCode, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        return Hash(bytes);
    }

    private static async Task<string> GetBlob(HttpClient device, string hash)
    {
        var r = await device.GetAsync($"/v1/blobs/{hash}");
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        return await r.Content.ReadAsStringAsync();
    }

    private static Task<HttpResponseMessage> StartMove(HttpClient admin, string path) =>
        admin.PostAsJsonAsync("/v1/admin/storage/move", new MovePathRequest(path));

    private static async Task<MoveStatus?> GetStatus(HttpClient admin)
    {
        var r = await admin.GetAsync("/v1/admin/storage/move");
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        var text = await r.Content.ReadAsStringAsync();
        return string.IsNullOrWhiteSpace(text) || text == "null" ? null : JsonSerializer.Deserialize<MoveStatus>(text, TestApi.Json);
    }

    private static async Task<MoveStatus> WaitFinished(HttpClient admin)
    {
        var until = DateTime.UtcNow + Soon;
        while (DateTime.UtcNow < until)
        {
            var s = await GetStatus(admin);
            if (s is { Phase: MovePhase.Done or MovePhase.Failed }) return s;
            await Task.Delay(20);
        }
        throw new TimeoutException("The move did not finish.");
    }

    private static async Task<string?> StoredSetting(ServerFactory f, string key)
    {
        using var scope = f.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        return (await db.ServerSettings.AsNoTracking().FirstOrDefaultAsync(s => s.Key == key))?.Value;
    }

    private static string[] Blobs(string root) =>
        Directory.Exists(root)
            ? Directory.EnumerateFiles(root, "*", SearchOption.AllDirectories).Select(f => Path.GetFileName(f))
                .Where(BlobHash.IsValid).Order(StringComparer.Ordinal).ToArray()
            : [];

    /// <summary>A pause in the job after its first copy pass: the test runs its step and then lets the job go on.</summary>
    private static TaskCompletionSource PauseAfterCopy(Server s, out TaskCompletionSource reached)
    {
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var hit = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        reached = hit;
        s.Hooks.AfterCopyPass = async ct =>
        {
            hit.TrySetResult();
            await release.Task.WaitAsync(ct);
        };
        return release;
    }

    [Fact]
    public async Task Move_copies_every_blob_verifies_and_switches()
    {
        using var s = await Start();
        var hashes = new[] { await PutBlob(s.Device, "one"), await PutBlob(s.Device, "two"), await PutBlob(s.Device, "three") };
        var oldRoot = s.Files.Root;
        var target = NewDir();

        var check = await (await s.Admin.PostAsJsonAsync("/v1/admin/storage/move/check", new MovePathRequest(target)))
            .ReadAsync<MoveCheckResult>();
        Assert.True(check.Ok);
        Assert.Equal(11, check.BytesToCopy);

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        var status = await WaitFinished(s.Admin);

        Assert.Equal(MovePhase.Done, status.Phase);
        Assert.Null(status.Error);
        Assert.Equal(3, status.FilesDone);
        Assert.Equal(3, status.FilesTotal);
        Assert.Equal(11, status.BytesDone);
        Assert.Equal(oldRoot, status.OldLocation);
        Assert.Equal(11, status.OldBytes);
        Assert.Equal(target, s.Files.Root);
        Assert.False(s.Gate.Moving);

        var setting = JsonSerializer.Deserialize<FilesLocationSetting>((await StoredSetting(s.Factory, FilesLocation.Key))!,
            new JsonSerializerOptions(JsonSerializerDefaults.Web))!;
        Assert.Equal(target, setting.Path);
        Assert.Equal(setting.Id, FilesLocation.ReadMarker(target));

        Assert.Equal(hashes.Order(StringComparer.Ordinal), Blobs(target));
        Assert.Equal(hashes.Order(StringComparer.Ordinal), Blobs(oldRoot)); // the old copy stays until asked
        Assert.False(File.Exists(Path.Combine(target, FilesMover.JournalName)));
        Assert.Empty(Directory.EnumerateFiles(target, "*.copy-*", SearchOption.AllDirectories));
        Assert.Equal("two", await GetBlob(s.Device, hashes[1]));

        // A new upload lands in the new folder only.
        var four = await PutBlob(s.Device, "four");
        Assert.Contains(four, Blobs(target));
        Assert.DoesNotContain(four, Blobs(oldRoot));
    }

    [Fact]
    public async Task A_blob_written_during_the_copy_is_in_the_new_location()
    {
        using var s = await Start();
        await PutBlob(s.Device, "before");
        var target = NewDir();
        var release = PauseAfterCopy(s, out var reached);

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        await reached.Task.WaitAsync(Soon);
        Assert.Equal(MovePhase.Copying, (await GetStatus(s.Admin))!.Phase);
        var during = await PutBlob(s.Device, "during the copy"); // still accepted: no hold yet
        release.SetResult();

        var status = await WaitFinished(s.Admin);
        Assert.Equal(MovePhase.Done, status.Phase);
        Assert.Contains(during, Blobs(target));
        Assert.Equal(2, status.FilesTotal);
        Assert.Equal("during the copy", await GetBlob(s.Device, during));
    }

    [Fact]
    public async Task A_blob_deleted_during_the_copy_is_not_left_in_the_new_location()
    {
        using var s = await Start();
        var kept = await PutBlob(s.Device, "kept");
        var doomed = await PutBlob(s.Device, "deleted meanwhile");
        var target = NewDir();
        var release = PauseAfterCopy(s, out var reached);

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        await reached.Task.WaitAsync(Soon);
        Assert.Contains(doomed, Blobs(target));
        // A prune that was already under way when the move began deletes its file from the current folder.
        File.Delete(Directory.GetFiles(s.Files.Root, doomed, SearchOption.AllDirectories).Single());
        release.SetResult();

        var status = await WaitFinished(s.Admin);
        Assert.Equal(MovePhase.Done, status.Phase);
        Assert.Equal([kept], Blobs(target));
        Assert.Equal(1, status.FilesTotal);
        Assert.Equal(4, status.OldBytes);
    }

    private static Task<HttpResponseMessage> TryPut(HttpClient device, string text)
    {
        var content = new ByteArrayContent(Encoding.UTF8.GetBytes(text));
        content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        return device.PutAsync($"/v1/blobs/{Hash(Encoding.UTF8.GetBytes(text))}", content);
    }

    [Fact]
    public async Task A_switch_save_that_fails_without_saving_fails_the_move_and_removes_the_copy()
    {
        using var s = await Start();
        var hash = await PutBlob(s.Device, "stays");
        var oldRoot = s.Files.Root;
        var target = NewDir();
        s.Hooks.SaveSwitch = _ => throw new IOException("database is locked");

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        var status = await WaitFinished(s.Admin);

        Assert.Equal(MovePhase.Failed, status.Phase);
        Assert.EndsWith("Nothing was changed.", status.Error);
        Assert.Equal(oldRoot, s.Files.Root);
        Assert.Null(await StoredSetting(s.Factory, FilesLocation.Key));
        Assert.False(Directory.Exists(target));
        Assert.Equal("stays", await GetBlob(s.Device, hash));
    }

    [Fact]
    public async Task A_switch_save_that_throws_after_saving_completes_the_move()
    {
        using var s = await Start();
        var hash = await PutBlob(s.Device, "moved anyway");
        var target = NewDir();
        s.Hooks.SaveSwitch = async save =>
        {
            await save();
            throw new IOException("connection reset after commit");
        };

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        var status = await WaitFinished(s.Admin);

        Assert.Equal(MovePhase.Done, status.Phase);
        Assert.Equal(target, s.Files.Root);
        Assert.Contains(hash, Blobs(target));
        Assert.Equal("moved anyway", await GetBlob(s.Device, hash));
    }

    [Fact]
    public async Task While_the_switch_outcome_is_unknown_uploads_stay_on_hold_and_no_move_starts()
    {
        using var s = await Start();
        await PutBlob(s.Device, "x");
        var target = NewDir();
        var attempts = 0;
        var answer = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        s.Hooks.SaveSwitch = async save =>
        {
            await save();
            throw new IOException("connection reset after commit");
        };
        s.Hooks.BeforeReadBack = _ =>
        {
            Interlocked.Increment(ref attempts);
            return answer.Task.IsCompleted ? Task.CompletedTask : throw new IOException("database unreachable");
        };

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        var until = DateTime.UtcNow + Soon;
        while (Volatile.Read(ref attempts) < 8 && DateTime.UtcNow < until) await Task.Delay(10);
        Assert.True(Volatile.Read(ref attempts) >= 8); // past any fixed number of tries

        Assert.True(s.Gate.Holding);
        var put = await TryPut(s.Device, "while unknown");
        Assert.Equal(HttpStatusCode.ServiceUnavailable, put.StatusCode);
        Assert.Equal("server_moving", (await put.ReadAsync<ErrorResponse>()).Code);
        Assert.Equal(HttpStatusCode.Conflict, (await StartMove(s.Admin, NewDir())).StatusCode);
        s.Mover.Cancel(); // too late: the switch may be stored
        Assert.True(s.Gate.Holding);

        answer.SetResult();
        var status = await WaitFinished(s.Admin);
        Assert.Equal(MovePhase.Done, status.Phase);
        Assert.Equal(target, s.Files.Root);
        var after = await PutBlob(s.Device, "after");
        Assert.Contains(after, Blobs(target));
    }

    [Fact]
    public async Task A_target_whose_marker_vanished_before_the_switch_fails_the_move()
    {
        using var s = await Start();
        await PutBlob(s.Device, "x");
        var oldRoot = s.Files.Root;
        var target = NewDir();
        s.Hooks.AfterCopyPass = _ =>
        {
            File.Delete(Path.Combine(target, FilesLocation.MarkerName));
            return Task.CompletedTask;
        };

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        var status = await WaitFinished(s.Admin);

        Assert.Equal(MovePhase.Failed, status.Phase);
        Assert.Equal("The new folder changed during the move. Nothing was changed.", status.Error);
        Assert.Equal(oldRoot, s.Files.Root);
        Assert.Null(await StoredSetting(s.Factory, FilesLocation.Key));
    }

    [Fact]
    public async Task A_copy_that_vanished_before_the_switch_fails_the_move()
    {
        using var s = await Start();
        var hash = await PutBlob(s.Device, "x");
        var target = NewDir();
        s.Hooks.AfterCopyPass = _ =>
        {
            File.Delete(Directory.GetFiles(target, hash, SearchOption.AllDirectories).Single());
            return Task.CompletedTask;
        };

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        var status = await WaitFinished(s.Admin);

        Assert.Equal(MovePhase.Failed, status.Phase);
        Assert.Equal("The new folder changed during the move. Nothing was changed.", status.Error);
        Assert.Null(await StoredSetting(s.Factory, FilesLocation.Key));
    }

    [Fact]
    public async Task Remove_old_still_keeps_a_referenced_blob_missing_from_the_new_location()
    {
        using var s = await Start();
        var kept = await PutBlob(s.Device, "only in the old copy");
        var gone = await PutBlob(s.Device, "in both");
        var oldRoot = s.Files.Root;
        var target = NewDir();
        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        Assert.Equal(MovePhase.Done, (await WaitFinished(s.Admin)).Phase);
        File.Delete(Directory.GetFiles(target, kept, SearchOption.AllDirectories).Single()); // lost from the new folder

        Assert.Equal(HttpStatusCode.NoContent, (await s.Admin.PostAsync("/v1/admin/storage/move/remove-old", null)).StatusCode);

        Assert.Equal([kept], Blobs(oldRoot));
        Assert.DoesNotContain(gone, Blobs(oldRoot));
        var status = (await GetStatus(s.Admin))!;
        Assert.Equal(1, status.OldKept);
        Assert.Equal(oldRoot, status.OldLocation);
        Assert.Equal(20, status.OldBytes);
    }

    [Fact]
    public async Task Remove_old_refuses_while_the_new_folder_is_missing()
    {
        using var s = await Start();
        var hash = await PutBlob(s.Device, "x");
        var oldRoot = s.Files.Root;
        var target = NewDir();
        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        Assert.Equal(MovePhase.Done, (await WaitFinished(s.Admin)).Phase);
        File.Delete(Path.Combine(target, FilesLocation.MarkerName)); // an unmounted disk

        var r = await s.Admin.PostAsync("/v1/admin/storage/move/remove-old", null);
        Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
        Assert.Equal("files_unavailable", (await r.ReadAsync<ErrorResponse>()).Code);
        Assert.Contains(hash, Blobs(oldRoot));
    }

    [Fact]
    public async Task A_failed_move_after_a_check_removes_the_folder_the_check_created()
    {
        using var s = await Start();
        await PutBlob(s.Device, "x");
        var target = Path.Combine(NewDir("parent"), "files");
        var check = await (await s.Admin.PostAsJsonAsync("/v1/admin/storage/move/check", new MovePathRequest(target)))
            .ReadAsync<MoveCheckResult>();
        Assert.True(check.Ok);
        Assert.True(Directory.Exists(target)); // the check made it
        s.Hooks.AfterFileWritten = path => File.WriteAllText(path, "broken");

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        Assert.Equal(MovePhase.Failed, (await WaitFinished(s.Admin)).Phase);

        Assert.False(Directory.Exists(Path.GetDirectoryName(target)));
    }

    [Fact]
    public async Task A_hold_that_cannot_drain_in_time_fails_the_move()
    {
        using var s = await Start(timings: new MoveTimings { HoldTimeout = TimeSpan.FromMilliseconds(200) });
        var hash = await PutBlob(s.Device, "x");
        var target = NewDir();
        IDisposable? writer = null;
        s.Hooks.AfterCopyPass = async ct => writer = await s.Gate.EnterWriteAsync(ct); // a write that does not finish

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        var status = await WaitFinished(s.Admin);
        writer?.Dispose();

        Assert.Equal(MovePhase.Failed, status.Phase);
        Assert.Equal("Uploads did not pause in time. Nothing was changed.", status.Error);
        Assert.False(s.Gate.Holding);
        Assert.False(Directory.Exists(target));
        Assert.Equal("x", await GetBlob(s.Device, hash));
    }

    /// <summary>Stores files.location for the target directly, as a save that committed behind the job's back would.</summary>
    private static async Task StoreLocation(ServerFactory f, string target)
    {
        using var scope = f.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        db.ServerSettings.Add(new ServerSetting
        {
            Key = FilesLocation.Key,
            Value = JsonSerializer.Serialize(new FilesLocationSetting(target, FilesLocation.ReadMarker(target)!.Value),
                new JsonSerializerOptions(JsonSerializerDefaults.Web)),
        });
        await db.SaveChangesAsync();
    }

    [Fact]
    public async Task A_stored_switch_is_made_current_without_writing_again()
    {
        using var s = await Start();
        var hash = await PutBlob(s.Device, "x");
        var oldRoot = s.Files.Root;
        var target = NewDir();
        s.Hooks.SaveSwitch = async _ =>
        {
            await StoreLocation(s.Factory, target); // committed, but the job only sees the error
            throw new IOException("connection reset after commit");
        };

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        var status = await WaitFinished(s.Admin);

        Assert.Equal(MovePhase.Done, status.Phase);
        Assert.Equal(target, s.Files.Root);
        Assert.Equal(oldRoot, status.OldLocation);
        // No second write: the stored move record is still the running one (the next start reads the switch).
        Assert.Contains("\"copying\"", await StoredSetting(s.Factory, FilesMover.Key), StringComparison.OrdinalIgnoreCase);
        Assert.Contains(hash, Blobs(target));
    }

    [Fact]
    public async Task A_switch_that_commits_late_is_found_by_the_second_read()
    {
        using var s = await Start();
        var hash = await PutBlob(s.Device, "late");
        var target = NewDir();
        s.Hooks.SaveSwitch = _ => throw new IOException("timeout");
        s.Hooks.BeforeReadBack = async attempt =>
        {
            if (attempt == 2) await StoreLocation(s.Factory, target); // the commit landed after the first read
        };

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        var status = await WaitFinished(s.Admin);

        Assert.Equal(MovePhase.Done, status.Phase);
        Assert.Equal(target, s.Files.Root);
        Assert.Equal("late", await GetBlob(s.Device, hash));
    }

    [Fact]
    public async Task Remove_old_deletes_blobs_pruned_after_the_switch()
    {
        using var s = await Start();
        var pruned = await PutBlob(s.Device, "unreferenced");
        var oldRoot = s.Files.Root;
        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, NewDir())).StatusCode);
        Assert.Equal(MovePhase.Done, (await WaitFinished(s.Admin)).Phase);
        s.Factory.Time.Advance(TimeSpan.FromHours(2));
        Assert.Equal(1, (await s.Factory.Services.GetRequiredService<Pruner>().RunOnceAsync(default)).Blobs);
        Assert.Contains(pruned, Blobs(oldRoot));

        Assert.Equal(HttpStatusCode.NoContent, (await s.Admin.PostAsync("/v1/admin/storage/move/remove-old", null)).StatusCode);

        Assert.Empty(Blobs(oldRoot));
        var status = (await GetStatus(s.Admin))!;
        Assert.Equal(0, status.OldKept);
        Assert.Null(status.OldLocation);
    }

    [Fact]
    public async Task Remove_old_deletes_a_deleted_accounts_old_files()
    {
        using var s = await Start(allowRegistration: true);
        var (other, otherKey) = await s.Factory.Register("other@example.com");
        var theirs = await PutBlob(other, "theirs");
        var oldRoot = s.Files.Root;
        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, NewDir())).StatusCode);
        Assert.Equal(MovePhase.Done, (await WaitFinished(s.Admin)).Phase);
        Assert.Equal(HttpStatusCode.NoContent, (await s.Admin.DeleteAsync($"/v1/admin/users/{otherKey.UserId}")).StatusCode);
        Assert.Contains(theirs, Blobs(oldRoot));

        Assert.Equal(HttpStatusCode.NoContent, (await s.Admin.PostAsync("/v1/admin/storage/move/remove-old", null)).StatusCode);

        Assert.False(Directory.Exists(Path.Combine(oldRoot, otherKey.UserId.ToString("N"))));
        var status = (await GetStatus(s.Admin))!;
        Assert.Equal(0, status.OldKept);
        Assert.Null(status.OldLocation);
    }

    [Fact]
    public async Task A_corrupt_copy_fails_the_move_and_nothing_switches()
    {
        using var s = await Start();
        var hash = await PutBlob(s.Device, "precious save");
        var oldRoot = s.Files.Root;
        var target = NewDir();
        s.Hooks.AfterFileWritten = path =>
        {
            var bytes = File.ReadAllBytes(path);
            bytes[0] ^= 0xFF;
            File.WriteAllBytes(path, bytes);
        };

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        var status = await WaitFinished(s.Admin);

        Assert.Equal(MovePhase.Failed, status.Phase);
        Assert.EndsWith("Nothing was changed.", status.Error);
        Assert.Equal(oldRoot, s.Files.Root);
        Assert.Null(await StoredSetting(s.Factory, FilesLocation.Key));
        Assert.Empty(Blobs(target));
        Assert.Equal("precious save", await GetBlob(s.Device, hash));
    }

    [Fact]
    public async Task A_failed_move_leaves_the_old_location_and_removes_only_its_copy()
    {
        using var s = await Start();
        await PutBlob(s.Device, "a");
        await PutBlob(s.Device, "b");
        var oldRoot = s.Files.Root;
        var before = Blobs(oldRoot);
        var target = NewDir();
        Directory.CreateDirectory(target);
        var parent = Path.GetDirectoryName(target)!;
        var unrelated = Path.Combine(parent, "keep-me.txt");
        File.WriteAllText(unrelated, "not Tandem's");
        var calls = 0;
        s.Hooks.AfterFileWritten = path =>
        {
            if (++calls == 2) File.WriteAllText(path, "broken"); // the first file is copied fine, the second is not
        };

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        var status = await WaitFinished(s.Admin);

        Assert.Equal(MovePhase.Failed, status.Phase);
        Assert.True(Directory.Exists(target)); // it was there before the move
        Assert.Empty(Directory.EnumerateFileSystemEntries(target)); // its marker, copies and folders are gone
        Assert.Equal("not Tandem's", File.ReadAllText(unrelated));
        Assert.Equal(before, Blobs(oldRoot));
        Assert.True(File.Exists(Path.Combine(oldRoot, FilesLocation.MarkerName)));
        Assert.Equal(oldRoot, s.Files.Root);
        Assert.False(s.Gate.Moving);
    }

    [Fact]
    public async Task A_failed_move_removes_a_target_folder_it_created()
    {
        using var s = await Start();
        await PutBlob(s.Device, "a");
        var target = Path.Combine(NewDir("parent"), "new", "files");
        s.Hooks.AfterFileWritten = path => File.WriteAllText(path, "broken");

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        Assert.Equal(MovePhase.Failed, (await WaitFinished(s.Admin)).Phase);

        Assert.False(Directory.Exists(Path.GetDirectoryName(target)));
    }

    [Fact]
    public async Task Cancel_before_the_switch_changes_nothing()
    {
        using var s = await Start();
        var hash = await PutBlob(s.Device, "keep");
        var oldRoot = s.Files.Root;
        var target = NewDir();
        _ = PauseAfterCopy(s, out var reached);

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        await reached.Task.WaitAsync(Soon);
        Assert.Contains(hash, Blobs(target)); // copied, not yet switched
        Assert.Equal(HttpStatusCode.NoContent, (await s.Admin.PostAsync("/v1/admin/storage/move/cancel", null)).StatusCode);

        var status = await WaitFinished(s.Admin);
        Assert.Equal(MovePhase.Failed, status.Phase);
        Assert.Equal("The move was cancelled. Nothing was changed.", status.Error);
        Assert.Equal(oldRoot, s.Files.Root);
        Assert.Null(await StoredSetting(s.Factory, FilesLocation.Key));
        Assert.False(Directory.Exists(target)); // the check created it; the cleanup removes it again
        Assert.Equal("keep", await GetBlob(s.Device, hash));
        Assert.False(s.Gate.Moving);
        Assert.False(s.Gate.Holding);
    }

    [Fact]
    public async Task A_second_move_while_one_runs_is_409()
    {
        using var s = await Start();
        await PutBlob(s.Device, "x");
        var release = PauseAfterCopy(s, out var reached);

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, NewDir())).StatusCode);
        await reached.Task.WaitAsync(Soon);
        var second = NewDir();
        var r = await StartMove(s.Admin, second);
        Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
        Assert.Equal("move_running", (await r.ReadAsync<ErrorResponse>()).Code);
        Assert.False(Directory.Exists(second)); // not even checked

        release.SetResult();
        Assert.Equal(MovePhase.Done, (await WaitFinished(s.Admin)).Phase);
    }

    [Fact]
    public async Task A_bad_target_is_400_bad_path_with_the_check()
    {
        using var s = await Start();
        var r = await StartMove(s.Admin, "relative/path");
        Assert.Equal(HttpStatusCode.BadRequest, r.StatusCode);
        var text = await r.Content.ReadAsStringAsync();
        using var doc = JsonDocument.Parse(text);
        Assert.Equal("bad_path", doc.RootElement.GetProperty("code").GetString());
        var check = doc.RootElement.GetProperty("check").Deserialize<MoveCheckResult>(TestApi.Json)!;
        Assert.False(check.Ok);
        Assert.Equal("absolute", check.Items[0].Name);
        Assert.Null(await GetStatus(s.Admin));
    }

    [Fact]
    public async Task Remove_old_deletes_the_old_root_only_on_request()
    {
        using var s = await Start();
        var hash = await PutBlob(s.Device, "moved");
        var oldRoot = s.Files.Root;
        var target = NewDir();

        var early = await s.Admin.PostAsync("/v1/admin/storage/move/remove-old", null);
        Assert.Equal(HttpStatusCode.Conflict, early.StatusCode); // nothing to remove yet

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        Assert.Equal(MovePhase.Done, (await WaitFinished(s.Admin)).Phase);
        Assert.Contains(hash, Blobs(oldRoot));

        Assert.Equal(HttpStatusCode.NoContent, (await s.Admin.PostAsync("/v1/admin/storage/move/remove-old", null)).StatusCode);

        Assert.Empty(Blobs(oldRoot));
        Assert.True(Directory.Exists(s.Factory.DataDirectory));
        Assert.True(File.Exists(Path.Combine(s.Factory.DataDirectory, "tandem.db")) ||
                    Directory.EnumerateFiles(s.Factory.DataDirectory).Any()); // the database is untouched
        Assert.Contains(hash, Blobs(target));
        Assert.Equal("moved", await GetBlob(s.Device, hash));
        var after = (await GetStatus(s.Admin))!;
        Assert.Null(after.OldLocation);
        Assert.Equal(0, after.OldBytes);
        Assert.Equal(HttpStatusCode.Conflict, (await s.Admin.PostAsync("/v1/admin/storage/move/remove-old", null)).StatusCode);
    }

    [Fact]
    public async Task Remove_old_during_a_move_is_503_server_moving()
    {
        using var s = await Start();
        await PutBlob(s.Device, "x");
        var release = PauseAfterCopy(s, out var reached);
        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, NewDir())).StatusCode);
        await reached.Task.WaitAsync(Soon);

        var r = await s.Admin.PostAsync("/v1/admin/storage/move/remove-old", null);
        Assert.Equal(HttpStatusCode.ServiceUnavailable, r.StatusCode);
        Assert.Equal("server_moving", (await r.ReadAsync<ErrorResponse>()).Code);

        release.SetResult();
        await WaitFinished(s.Admin);
    }

    [Fact]
    public async Task Restart_marks_a_running_move_failed_and_cleans_its_copy()
    {
        using var s = await Start();
        var hash = await PutBlob(s.Device, "survives");
        var oldRoot = s.Files.Root;
        var target = NewDir();
        Directory.CreateDirectory(target);
        _ = PauseAfterCopy(s, out var reached);

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        await reached.Task.WaitAsync(Soon);
        Assert.Contains(hash, Blobs(target));
        var stored = await StoredSetting(s.Factory, FilesMover.Key);
        Assert.NotNull(stored);

        // A second mover over the same database and folders is what the server holds after a restart.
        var restarted = ActivatorUtilities.CreateInstance<FilesMover>(s.Factory.Services);
        await ((IHostedService)restarted).StartAsync(CancellationToken.None);

        Assert.Equal(MovePhase.Failed, restarted.Status!.Phase);
        Assert.Equal("The server restarted during the move. Nothing was changed.", restarted.Status.Error);
        Assert.Empty(Directory.EnumerateFileSystemEntries(target));
        Assert.Equal(oldRoot, s.Files.Root);
        Assert.Contains(hash, Blobs(oldRoot));
        Assert.Null(await StoredSetting(s.Factory, FilesLocation.Key));
        var persisted = await StoredSetting(s.Factory, FilesMover.Key);
        Assert.Contains("\"failed\"", persisted, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task Restart_after_the_switch_keeps_the_new_location()
    {
        using var s = await Start();
        var hash = await PutBlob(s.Device, "kept");
        var target = NewDir();
        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, target)).StatusCode);
        Assert.Equal(MovePhase.Done, (await WaitFinished(s.Admin)).Phase);

        var restarted = ActivatorUtilities.CreateInstance<FilesMover>(s.Factory.Services);
        await ((IHostedService)restarted).StartAsync(CancellationToken.None);

        Assert.Equal(MovePhase.Done, restarted.Status!.Phase);
        Assert.NotNull(restarted.Status.OldLocation);
        Assert.Contains(hash, Blobs(target));
    }

    [Fact]
    public async Task Pruner_and_backup_skip_while_moving()
    {
        using var s = await Start();
        var hash = await PutBlob(s.Device, "unreferenced");
        s.Factory.Time.Advance(TimeSpan.FromHours(2)); // past the blob grace period: a prune would delete it
        var folder = NewDir("backups");
        Directory.CreateDirectory(folder);
        (await s.Admin.PutAsync("/v1/admin/backup", JsonContent.Create(
            new BackupSettingsDto(true, BackupTargetKind.Folder, folder, null, 3, 7, false, null), options: TestApi.Json)))
            .EnsureSuccessStatusCode();
        var release = PauseAfterCopy(s, out var reached);

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, NewDir())).StatusCode);
        await reached.Task.WaitAsync(Soon);
        Assert.True(s.Gate.Moving);

        Assert.Equal(PruneResult.None, await s.Factory.Services.GetRequiredService<Pruner>().RunOnceAsync(default));
        Assert.Contains(hash, Blobs(s.Files.Root));
        Assert.Null(await s.Factory.Services.GetRequiredService<BackupService>().RunIfDueAsync(default));
        var manual = await s.Admin.PostAsync("/v1/admin/backup/run", null);
        Assert.Equal(HttpStatusCode.ServiceUnavailable, manual.StatusCode);
        Assert.Equal("server_moving", (await manual.ReadAsync<ErrorResponse>()).Code);
        Assert.Empty(Directory.EnumerateFiles(folder));

        release.SetResult();
        Assert.Equal(MovePhase.Done, (await WaitFinished(s.Admin)).Phase);
        Assert.False(s.Gate.Moving);
        // Both resume after the move, in the new folder.
        Assert.Equal(1, (await s.Factory.Services.GetRequiredService<Pruner>().RunOnceAsync(default)).Blobs);
        Assert.DoesNotContain(hash, Blobs(s.Files.Root));
    }

    [Fact]
    public async Task Account_deletion_during_a_move_is_503_server_moving()
    {
        using var s = await Start(allowRegistration: true);
        var (other, otherKey) = await s.Factory.Register("other@example.com");
        await PutBlob(other, "theirs");
        var release = PauseAfterCopy(s, out var reached);
        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, NewDir())).StatusCode);
        await reached.Task.WaitAsync(Soon);

        var r = await s.Admin.DeleteAsync($"/v1/admin/users/{otherKey.UserId}");
        Assert.Equal(HttpStatusCode.ServiceUnavailable, r.StatusCode);
        Assert.Equal("server_moving", (await r.ReadAsync<ErrorResponse>()).Code);
        Assert.Equal("15", r.Headers.RetryAfter?.Delta?.TotalSeconds.ToString());
        Assert.Equal("theirs", await GetBlob(other, Hash(Encoding.UTF8.GetBytes("theirs"))));

        release.SetResult();
        Assert.Equal(MovePhase.Done, (await WaitFinished(s.Admin)).Phase);
        Assert.Equal(HttpStatusCode.NoContent, (await s.Admin.DeleteAsync($"/v1/admin/users/{otherKey.UserId}")).StatusCode);
    }

    [Fact]
    public async Task Account_deletion_with_the_files_folder_missing_is_503_files_unavailable()
    {
        using var s = await Start(allowRegistration: true);
        var (_, otherKey) = await s.Factory.Register("other@example.com");
        var gone = NewDir("gone");
        await s.Files.SwitchAsync(s.Factory.Services.CreateScope().ServiceProvider.GetRequiredService<TandemDbContext>(),
            gone, Guid.NewGuid(), default); // a custom folder whose marker is not there: an unmounted NAS
        Assert.False(s.Files.CheckPresent());

        var r = await s.Admin.DeleteAsync($"/v1/admin/users/{otherKey.UserId}");
        Assert.Equal(HttpStatusCode.ServiceUnavailable, r.StatusCode);
        Assert.Equal("files_unavailable", (await r.ReadAsync<ErrorResponse>()).Code);
        using var scope = s.Factory.Services.CreateScope();
        Assert.True(await scope.ServiceProvider.GetRequiredService<TandemDbContext>().Users.AnyAsync(u => u.Id == otherKey.UserId));
    }

    [Fact]
    public async Task Storage_events_are_sent_at_hold_start_and_end()
    {
        RecordingHub? hub = null;
        using var s = await Start(services =>
        {
            services.RemoveAll<IEventHub>();
            services.AddSingleton<IEventHub>(sp => hub = new RecordingHub(
                new EventHub(sp.GetRequiredService<TimeProvider>(), sp.GetRequiredService<IOptions<ServerOptions>>()),
                sp.GetRequiredService<FilesGate>()));
        });
        await PutBlob(s.Device, "x");

        Assert.Equal(HttpStatusCode.Accepted, (await StartMove(s.Admin, NewDir())).StatusCode);
        Assert.Equal(MovePhase.Done, (await WaitFinished(s.Admin)).Phase);

        List<(string Type, string? Topic, bool Holding)> all;
        lock (hub!.All) all = [.. hub.All];
        var storage = all.Where(e => e is { Type: "storage", Topic: null }).ToList();
        Assert.Equal(2, storage.Count);
        Assert.True(storage[0].Holding);
        Assert.False(storage[1].Holding);
        Assert.Contains(all, e => e is { Type: "changed", Topic: "storage" });
        Assert.True(all.Count(e => e is { Type: "changed", Topic: "storage" }) >= 3); // copying, holding, done
    }

    [Fact]
    public async Task A_storage_event_reaches_every_stream_as_its_own_type()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register(Admin);
        using var cts = new CancellationTokenSource(Soon);
        using var response = await device.GetAsync("/v1/events", HttpCompletionOption.ResponseHeadersRead, cts.Token);
        await using var stream = await response.Content.ReadAsStreamAsync(cts.Token);
        using var reader = new StreamReader(stream);
        factory.Services.GetRequiredService<IEventHub>().NotifyAll("storage");

        string? line;
        while ((line = await reader.ReadLineAsync(cts.Token)) is not null && line != "event: storage") { }
        Assert.Equal("event: storage", line);
        Assert.Equal("data: {\"type\":\"storage\"}", await reader.ReadLineAsync(cts.Token));
    }

    [Fact]
    public async Task Home_attention_flags_a_missing_files_folder_for_admins()
    {
        using var s = await Start();
        Assert.False((await s.Admin.GetOk<HomeResponse>("/v1/web/home")).Attention.FilesMissing);
        using (var scope = s.Factory.Services.CreateScope())
            await s.Files.SwitchAsync(scope.ServiceProvider.GetRequiredService<TandemDbContext>(), NewDir("gone"), Guid.NewGuid(), default);
        s.Files.CheckPresent();
        Assert.True((await s.Admin.GetOk<HomeResponse>("/v1/web/home")).Attention.FilesMissing);
    }

    [Fact]
    public async Task Non_admin_gets_403()
    {
        using var s = await Start(allowRegistration: true);
        await s.Factory.Register("user@example.com");
        var user = await s.Factory.WebLogin("user@example.com");
        var target = NewDir();

        foreach (var r in new[]
                 {
                     await user.PostAsJsonAsync("/v1/admin/storage/move/check", new MovePathRequest(target)),
                     await StartMove(user, target),
                     await user.GetAsync("/v1/admin/storage/move"),
                     await user.PostAsync("/v1/admin/storage/move/cancel", null),
                     await user.PostAsync("/v1/admin/storage/move/remove-old", null),
                 })
            Assert.Equal(HttpStatusCode.Forbidden, r.StatusCode);
        Assert.False(Directory.Exists(target));
    }
}
