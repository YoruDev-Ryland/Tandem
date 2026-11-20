using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text;
using Amazon.Runtime;
using Amazon.S3;
using Amazon.S3.Model;
using EmuSync.Protocol;
using EmuSync.Server.Backup;
using EmuSync.Server.Cli;
using EmuSync.Server.Data;
using EmuSync.Server.Events;
using EmuSync.Server.Retention;
using EmuSync.Server.Saves;
using EmuSync.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Testcontainers.Minio;
using Testcontainers.PostgreSql;

namespace EmuSync.Server.Tests;

public sealed class BackupTests : IDisposable
{
    private readonly List<string> _folders = [];

    public void Dispose()
    {
        foreach (var folder in _folders)
        {
            try { Directory.Delete(folder, recursive: true); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }

    private const string Game = "0100F2C0115B6000";
    private const string Admin = "admin@example.com";

    private static ServerFactory NewFactory()
    {
        var factory = new ServerFactory();
        factory.Settings["EmuSync:AllowRegistration"] = "true";
        return factory;
    }

    private string NewFolder()
    {
        var folder = Directory.CreateTempSubdirectory("emusync-backup-target-").FullName;
        _folders.Add(folder);
        return folder;
    }

    private static BackupService Backups(ServerFactory f) => f.Services.GetRequiredService<BackupService>();

    private static async Task<T> InDb<T>(ServerFactory f, Func<EmuSyncDbContext, Task<T>> query)
    {
        using var scope = f.Services.CreateScope();
        return await query(scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>());
    }

    private static async Task<BackupSettingsResponse> PutSettings(HttpClient admin, BackupSettingsDto settings)
    {
        var r = await admin.PutAsync("/v1/admin/backup", JsonContent.Create(settings, options: TestApi.Json));
        Assert.True(r.StatusCode == HttpStatusCode.OK, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        return await r.ReadAsync<BackupSettingsResponse>();
    }

    private static Task<BackupSettingsResponse> UseFolder(HttpClient admin, string folder, int keep = 7, bool encrypt = false,
        string? passphrase = null) =>
        PutSettings(admin, new BackupSettingsDto(true, BackupTargetKind.Folder, folder, null, 3, keep, encrypt, passphrase));

    private static async Task<SaveUploadResponse> Upload(HttpClient device, string content, Guid? baseId)
    {
        var manifest = await Fixtures.Manifest(device, ("data.dat", Encoding.UTF8.GetBytes(content)));
        var r = await device.PostJsonAsync("/v1/saves/upload",
            new SaveUploadRequest("eden", ItemKind.Save, Game, "", "", baseId, manifest, "Zelda"));
        Assert.True(r.StatusCode == HttpStatusCode.OK, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        return await r.ReadAsync<SaveUploadResponse>();
    }

    /// <summary>An admin with two versions of one save (and so blobs, activity, device state).</summary>
    private static async Task<(HttpClient Device, DeviceKeyResponse Key, SaveUploadResponse V2)> Seed(ServerFactory f)
    {
        var (device, key) = await f.Register(Admin);
        var v1 = await Upload(device, "version one", null);
        var v2 = await Upload(device, "version two", v1.VersionId);
        return (device, key, v2);
    }

    private static async Task<(int Code, string Output, string Error)> Cli(ServerFactory f, params string[] args)
    {
        var output = new StringWriter();
        var error = new StringWriter();
        var code = await AdminCli.RunAsync(args, f.Services, output, error, CancellationToken.None);
        return (code, output.ToString(), error.ToString());
    }

    private static string[] BackupFiles(string folder) =>
        Directory.EnumerateFiles(folder).Select(Path.GetFileName).Where(n => n!.StartsWith("emusync-backup-", StringComparison.Ordinal))
            .Order(StringComparer.Ordinal).ToArray()!;

    private static async Task<Dictionary<string, int>> Counts(ServerFactory f) => await InDb(f, async db => new Dictionary<string, int>
    {
        ["Users"] = await db.Users.CountAsync(),
        ["Devices"] = await db.Devices.CountAsync(),
        ["SaveItems"] = await db.SaveItems.CountAsync(),
        ["SaveVersions"] = await db.SaveVersions.CountAsync(),
        ["DeviceItemStates"] = await db.DeviceItemStates.CountAsync(),
        ["UserBlobs"] = await db.UserBlobs.CountAsync(),
        ["ActivityEvents"] = await db.ActivityEvents.CountAsync(),
        ["Deliveries"] = await db.Deliveries.CountAsync(),
        ["WebSessions"] = await db.WebSessions.CountAsync(),
        ["ServerSettings"] = await db.ServerSettings.CountAsync(),
        ["UserSettings"] = await db.UserSettings.CountAsync(),
    });

    /// <summary>The restored server holds the same versions, the same blob bytes, and the old device key still works.</summary>
    private static async Task AssertRestored(ServerFactory source, ServerFactory restored, DeviceKeyResponse key)
    {
        var expected = await InDb(source, db => db.SaveVersions.AsNoTracking().OrderBy(v => v.Seq).ToListAsync());
        var actual = await InDb(restored, db => db.SaveVersions.AsNoTracking().OrderBy(v => v.Seq).ToListAsync());
        Assert.Equal(expected.Select(v => (v.Id, v.ItemId, v.Seq, v.BaseVersionId, v.ManifestHash, v.ManifestJson, v.DeviceId, v.TotalSize)),
            actual.Select(v => (v.Id, v.ItemId, v.Seq, v.BaseVersionId, v.ManifestHash, v.ManifestJson, v.DeviceId, v.TotalSize)));
        Assert.Equal(expected.Select(v => v.ReceivedUtc.ToUnixTimeMilliseconds()), actual.Select(v => v.ReceivedUtc.ToUnixTimeMilliseconds()));
        var item = await InDb(restored, db => db.SaveItems.AsNoTracking().SingleAsync());
        Assert.Equal(expected[^1].Id, item.HeadVersionId);

        var device = restored.Client();
        device.DefaultRequestHeaders.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", key.DeviceKey);
        foreach (var v in actual)
        foreach (var entry in Manifests.FromJson(v.ManifestJson))
        {
            var r = await device.GetAsync($"/v1/blobs/{entry.Hash}");
            Assert.Equal(HttpStatusCode.OK, r.StatusCode);
            Assert.Equal(entry.Hash, Fixtures.Sha256(await r.Content.ReadAsByteArrayAsync()));
        }
        await restored.WebLogin(Admin); // password hash came across
    }

    private static async Task AssertStillEmpty(ServerFactory f)
    {
        Assert.Equal(0, await InDb(f, db => db.Users.CountAsync()));
        Assert.Equal(0, await InDb(f, db => db.SaveVersions.CountAsync()));
        var blobs = Path.Combine(f.DataDirectory, "blobs");
        Assert.True(!Directory.Exists(blobs) || !Directory.EnumerateFileSystemEntries(blobs).Any());
    }

    [Fact]
    public async Task Folder_backup_then_restore_into_fresh_server_preserves_saves_and_versions()
    {
        var folder = NewFolder();
        using var source = NewFactory();
        var (admin, key, _) = await Seed(source);
        await UseFolder(admin, folder);

        var run = await Backups(source).RunOnceAsync(CancellationToken.None);

        Assert.True(run.Ok, run.Error);
        var file = Assert.Single(BackupFiles(folder));
        Assert.Equal("emusync-backup-20261119-120000.tar.gz", file);
        Assert.Equal(new FileInfo(Path.Combine(folder, file)).Length, run.Bytes);
        Assert.DoesNotContain(Directory.EnumerateFiles(folder), n => !Path.GetFileName(n).StartsWith("emusync-backup-", StringComparison.Ordinal));

        using var fresh = NewFactory();
        await fresh.Client().GetAsync("/healthz");
        var (code, output, error) = await Cli(fresh, "restore-backup", Path.Combine(folder, file));

        Assert.True(code == 0, error);
        Assert.Contains("Restored", output);
        Assert.Equal(await Counts(source), await Counts(fresh));
        await AssertRestored(source, fresh, key);
    }

    [Fact]
    public async Task Encrypted_backup_restores_with_passphrase_and_fails_with_wrong_one()
    {
        var folder = NewFolder();
        using var source = NewFactory();
        var (admin, key, _) = await Seed(source);
        var settings = await UseFolder(admin, folder, encrypt: true, passphrase: "correct horse");
        Assert.True(settings.PassphraseSet);

        var run = await Backups(source).RunOnceAsync(CancellationToken.None);

        Assert.True(run.Ok, run.Error);
        var file = Path.Combine(folder, Assert.Single(BackupFiles(folder)));
        Assert.EndsWith(".tar.gz.enc", file);
        var bytes = await File.ReadAllBytesAsync(file);
        Assert.Equal("ESBK1"u8.ToArray(), bytes[..5]);
        Assert.Equal(new byte[] { 0, 0x10, 0, 0 }, bytes[21..25]); // chunk size 1048576, big-endian

        using var fresh = NewFactory();
        await fresh.Client().GetAsync("/healthz");
        var wrong = await Cli(fresh, "restore-backup", file, "--passphrase", "wrong horse");
        Assert.Equal(1, wrong.Code);
        Assert.Contains("passphrase", wrong.Error);
        await AssertStillEmpty(fresh);
        var none = await Cli(fresh, "restore-backup", file);
        Assert.Equal(1, none.Code);
        Assert.Contains("passphrase", none.Error);

        var ok = await Cli(fresh, "restore-backup", file, "--passphrase", "correct horse");

        Assert.True(ok.Code == 0, ok.Error);
        await AssertRestored(source, fresh, key);
        // Secrets never travel inside a backup, and a restored server does not start writing to the old target.
        var restoredSettings = await (await fresh.WebLogin(Admin)).GetAsync("/v1/admin/backup");
        var view = await restoredSettings.ReadAsync<BackupSettingsResponse>();
        Assert.False(view.PassphraseSet);
        Assert.False(view.Enabled);
        Assert.Equal(folder, view.FolderPath);
    }

    [Fact]
    public async Task Truncated_encrypted_backup_is_rejected()
    {
        var folder = NewFolder();
        using var source = NewFactory();
        var (admin, _, _) = await Seed(source);
        var noise = RandomNumberGenerator.GetBytes(3 * 1024 * 1024); // incompressible: the archive spans several chunks
        await Fixtures.Manifest(admin, ("noise.bin", noise));
        await UseFolder(admin, folder, encrypt: true, passphrase: "pw");
        Assert.True((await Backups(source).RunOnceAsync(CancellationToken.None)).Ok);
        var file = Path.Combine(folder, Assert.Single(BackupFiles(folder)));

        var length = new FileInfo(file).Length;
        const int header = 5 + 16 + 4;
        const long chunk = 1048576 + 16;
        var complete = (length - header - 1) / chunk; // whole chunks before the final one
        Assert.True(complete >= 3);
        var cut = Path.Combine(NewFolder(), "emusync-backup-20261119-120000.tar.gz.enc");
        await using (var src = File.OpenRead(file))
        await using (var dst = File.Create(cut))
        {
            var buffer = new byte[header + complete * chunk];
            await src.ReadExactlyAsync(buffer);
            await dst.WriteAsync(buffer);
        }

        using var fresh = NewFactory();
        await fresh.Client().GetAsync("/healthz");
        var (code, _, error) = await Cli(fresh, "restore-backup", cut, "--passphrase", "pw");

        Assert.Equal(1, code);
        Assert.Contains("truncated", error);
        await AssertStillEmpty(fresh);

        // The decrypting stream on its own refuses it too.
        await using var raw = File.OpenRead(cut);
        await using var decrypt = BackupCrypto.Decrypt(raw, "pw");
        await Assert.ThrowsAsync<BackupException>(() => decrypt.CopyToAsync(Stream.Null));
    }

    [Fact]
    public async Task Keeps_only_newest_N_backups()
    {
        var folder = NewFolder();
        await File.WriteAllTextAsync(Path.Combine(folder, "notes.txt"), "mine");
        await File.WriteAllTextAsync(Path.Combine(folder, "emusync-backup-old.tar.gz"), "not ours by name");
        Directory.CreateDirectory(Path.Combine(folder, "emusync-backup-20200101-000000.tar.gz.d"));
        using var f = NewFactory();
        var (admin, _, _) = await Seed(f);
        await UseFolder(admin, folder, keep: 2);

        var names = new List<string>();
        for (var i = 0; i < 4; i++)
        {
            var run = await Backups(f).RunOnceAsync(CancellationToken.None);
            Assert.True(run.Ok, run.Error);
            names.Add(BackupFormat.NameFor(f.Time.GetUtcNow(), encrypted: false));
            f.Time.Advance(TimeSpan.FromDays(1));
        }

        Assert.Equal(names[2..], BackupFiles(folder).Where(BackupFormat.IsBackupName));
        Assert.True(File.Exists(Path.Combine(folder, "notes.txt")));
        Assert.True(File.Exists(Path.Combine(folder, "emusync-backup-old.tar.gz")));
        Assert.True(Directory.Exists(Path.Combine(folder, "emusync-backup-20200101-000000.tar.gz.d")));

        var history = await (await admin.GetAsync("/v1/admin/backup/history")).ReadAsync<List<BackupRunInfo>>();
        Assert.Equal(4, history.Count);
        Assert.All(history, h => Assert.True(h.Ok));
        Assert.Equal(f.Time.GetUtcNow() - TimeSpan.FromDays(1), history[0].Utc); // newest first
    }

    [Fact]
    public async Task Restore_refuses_non_empty_server()
    {
        var folder = NewFolder();
        using var source = NewFactory();
        var (admin, _, _) = await Seed(source);
        await UseFolder(admin, folder);
        Assert.True((await Backups(source).RunOnceAsync(CancellationToken.None)).Ok);
        var file = Path.Combine(folder, Assert.Single(BackupFiles(folder)));

        using var withUser = NewFactory();
        await withUser.Register("someone@example.com");
        var users = await Cli(withUser, "restore-backup", file);
        Assert.Equal(1, users.Code);
        Assert.Contains("users", users.Error);
        Assert.Equal(1, await InDb(withUser, db => db.Users.CountAsync()));

        using var withBlobs = NewFactory();
        await withBlobs.Client().GetAsync("/healthz");
        var stray = Path.Combine(withBlobs.DataDirectory, "blobs", "x");
        Directory.CreateDirectory(Path.GetDirectoryName(stray)!);
        await File.WriteAllTextAsync(stray, "left over");
        var blobs = await Cli(withBlobs, "restore-backup", file);
        Assert.Equal(1, blobs.Code);
        Assert.Contains("blob", blobs.Error);
        Assert.Equal(0, await InDb(withBlobs, db => db.Users.CountAsync()));
        Assert.True(File.Exists(stray));

        var usage = await Cli(withBlobs, "restore-backup");
        Assert.Equal(1, usage.Code);
        Assert.Contains("restore-backup <file>", usage.Error);
        Assert.Equal(1, (await Cli(withBlobs, "no-such-command")).Code);
    }

    [DockerFact]
    [Trait("Category", "S3")]
    public async Task S3_target_roundtrip()
    {
        // The MinIO project no longer publishes public images; Chainguard's build is the same server.
        await using var minio = new MinioBuilder("chainguard/minio:latest").Build();
        await minio.StartAsync();
        var endpoint = minio.GetConnectionString();
        using var s3 = new AmazonS3Client(new BasicAWSCredentials(minio.GetAccessKey(), minio.GetSecretKey()),
            new AmazonS3Config { ServiceURL = endpoint, ForcePathStyle = true, AuthenticationRegion = "us-east-1" });
        await s3.PutBucketAsync("backups");
        await s3.PutObjectAsync(new PutObjectRequest { BucketName = "backups", Key = "emusync/notes.txt", ContentBody = "mine" });
        await s3.PutObjectAsync(new PutObjectRequest
            { BucketName = "backups", Key = "emusync-backup-20200101-000000.tar.gz", ContentBody = "outside the prefix" });

        using var source = NewFactory();
        var (admin, key, _) = await Seed(source);
        await Fixtures.Manifest(admin, ("noise.bin", RandomNumberGenerator.GetBytes(9 * 1024 * 1024))); // more than one part
        var target = new S3Target(endpoint, "backups", "emusync", minio.GetAccessKey(), minio.GetSecretKey(), null);
        var view = await PutSettings(admin, new BackupSettingsDto(true, BackupTargetKind.S3, null, target, 3, 1, true, "pw"));
        Assert.Null(view.S3!.SecretKey);
        Assert.Equal(minio.GetAccessKey(), view.S3.AccessKey);

        for (var i = 0; i < 2; i++)
        {
            var run = await Backups(source).RunOnceAsync(CancellationToken.None);
            Assert.True(run.Ok, run.Error);
            source.Time.Advance(TimeSpan.FromDays(1));
        }

        var keys = (await s3.ListObjectsV2Async(new ListObjectsV2Request { BucketName = "backups" })).S3Objects!
            .Select(o => o.Key).Order(StringComparer.Ordinal).ToList();
        Assert.Equal(["emusync-backup-20200101-000000.tar.gz", "emusync/emusync-backup-20261120-120000.tar.gz.enc", "emusync/notes.txt"], keys);

        var file = Path.Combine(NewFolder(), "emusync-backup-20261120-120000.tar.gz.enc");
        var stored = BackupTargets.Create(new BackupSettingsDto(true, BackupTargetKind.S3, null, target, 3, 1, true, "pw"));
        Assert.Equal(["emusync-backup-20261120-120000.tar.gz.enc"], await stored.ListAsync(CancellationToken.None));
        await using (var read = await stored.OpenReadAsync("emusync-backup-20261120-120000.tar.gz.enc", CancellationToken.None))
        await using (var dst = File.Create(file))
            await read.CopyToAsync(dst);

        using var fresh = NewFactory();
        await fresh.Client().GetAsync("/healthz");
        var (code, _, error) = await Cli(fresh, "restore-backup", file, "--passphrase", "pw");
        Assert.True(code == 0, error);
        await AssertRestored(source, fresh, key);
    }

    [Fact]
    public async Task Backup_settings_are_admin_only_and_never_return_the_passphrase()
    {
        using var f = NewFactory();
        var (admin, _) = await f.Register(Admin);
        var (user, _) = await f.Register("user@example.com");

        var defaults = await (await admin.GetAsync("/v1/admin/backup")).ReadAsync<BackupSettingsResponse>();
        Assert.Equal(new BackupSettingsResponse(false, BackupTargetKind.Folder, null, null, 3, 7, false, false), defaults);

        var noPass = await admin.PutAsync("/v1/admin/backup", JsonContent.Create(
            new BackupSettingsDto(true, BackupTargetKind.Folder, "/mnt/nas", null, 3, 7, true, null), options: TestApi.Json));
        Assert.Equal(HttpStatusCode.BadRequest, noPass.StatusCode);
        Assert.Equal("bad_value", (await noPass.ReadAsync<ErrorResponse>()).Code);
        foreach (var bad in new[]
                 {
                     new BackupSettingsDto(true, BackupTargetKind.Folder, "/mnt/nas", null, 24, 7, false, null),
                     new BackupSettingsDto(true, BackupTargetKind.Folder, "/mnt/nas", null, 3, 0, false, null),
                     new BackupSettingsDto(true, BackupTargetKind.Folder, "relative/path", null, 3, 7, false, null),
                     new BackupSettingsDto(true, BackupTargetKind.Folder, null, null, 3, 7, false, null),
                     new BackupSettingsDto(true, BackupTargetKind.S3, null, null, 3, 7, false, null),
                     new BackupSettingsDto(true, BackupTargetKind.S3, null, new S3Target("not a url", "b", null, "a", "s", null), 3, 7, false, null),
                 })
        {
            var r = await admin.PutAsync("/v1/admin/backup", JsonContent.Create(bad, options: TestApi.Json));
            Assert.Equal(HttpStatusCode.BadRequest, r.StatusCode);
            Assert.Equal("bad_value", (await r.ReadAsync<ErrorResponse>()).Code);
        }

        var set = await UseFolder(admin, "/mnt/nas", encrypt: true, passphrase: "secret passphrase");
        Assert.True(set.PassphraseSet);
        var raw = await (await admin.GetAsync("/v1/admin/backup")).Content.ReadAsStringAsync();
        Assert.DoesNotContain("secret passphrase", raw);
        Assert.Contains("\"passphraseSet\":true", raw);
        // Omitting the passphrase keeps it; an empty one clears it (and then Encrypt is refused).
        Assert.True((await UseFolder(admin, "/mnt/nas", keep: 3, encrypt: true)).PassphraseSet);
        var clear = await admin.PutAsync("/v1/admin/backup", JsonContent.Create(
            new BackupSettingsDto(true, BackupTargetKind.Folder, "/mnt/nas", null, 3, 7, true, ""), options: TestApi.Json));
        Assert.Equal(HttpStatusCode.BadRequest, clear.StatusCode);
        Assert.False((await UseFolder(admin, "/mnt/nas", passphrase: "")).PassphraseSet);

        // An S3 secret is write-only too.
        var s3 = await PutSettings(admin, new BackupSettingsDto(true, BackupTargetKind.S3, null,
            new S3Target("http://minio:9000", "bucket", "p", "access", "very secret key", "eu-west-1"), 4, 7, false, null));
        Assert.Null(s3.S3!.SecretKey);
        Assert.DoesNotContain("very secret key", await (await admin.GetAsync("/v1/admin/backup")).Content.ReadAsStringAsync());
        Assert.Equal(HttpStatusCode.OK, (await admin.PutAsync("/v1/admin/backup", JsonContent.Create(new BackupSettingsDto(true,
            BackupTargetKind.S3, null, new S3Target("http://minio:9000", "bucket", "p", "access", null, "eu-west-1"), 4, 7, false, null),
            options: TestApi.Json))).StatusCode);

        foreach (var r in new[]
                 {
                     await user.GetAsync("/v1/admin/backup"), await user.PostAsync("/v1/admin/backup/run", null),
                     await user.GetAsync("/v1/admin/backup/history"),
                     await user.PutAsync("/v1/admin/backup", JsonContent.Create(defaults, options: TestApi.Json)),
                 })
            Assert.Equal(HttpStatusCode.Forbidden, r.StatusCode);
    }

    [Fact]
    public async Task Run_now_endpoint_runs_in_background_and_records_history()
    {
        var folder = NewFolder();
        using var f = NewFactory();
        var (admin, _, _) = await Seed(f);
        await UseFolder(admin, folder);

        Assert.Equal(HttpStatusCode.Accepted, (await admin.PostAsync("/v1/admin/backup/run", null)).StatusCode);

        List<BackupRunInfo> history = [];
        for (var i = 0; i < 200 && history.Count == 0; i++)
        {
            await Task.Delay(50);
            history = await (await admin.GetAsync("/v1/admin/backup/history")).ReadAsync<List<BackupRunInfo>>();
        }
        var run = Assert.Single(history);
        Assert.True(run.Ok, run.Error);
        Assert.Null(run.Error);
        Assert.True(run.Bytes > 0);
        Assert.Single(BackupFiles(folder));
    }

    [Fact]
    public async Task Running_is_exposed_and_a_second_run_or_scheduled_run_is_refused_while_one_runs()
    {
        var folder = NewFolder();
        using var f = NewFactory();
        var (admin, _, _) = await Seed(f);
        await UseFolder(admin, folder);
        Assert.False((await (await admin.GetAsync("/v1/admin/backup")).ReadAsync<BackupSettingsResponse>()).Running);

        var maintenance = f.Services.GetRequiredService<MaintenanceLock>();
        using (await maintenance.AcquireAsync()) // the started run waits here, still counted as running
        {
            Assert.Equal(HttpStatusCode.Accepted, (await admin.PostAsync("/v1/admin/backup/run", null)).StatusCode);
            Assert.True((await (await admin.GetAsync("/v1/admin/backup")).ReadAsync<BackupSettingsResponse>()).Running);

            var second = await admin.PostAsync("/v1/admin/backup/run", null);
            Assert.Equal(HttpStatusCode.Conflict, second.StatusCode);
            Assert.Null(await Backups(f).RunIfDueAsync(CancellationToken.None)); // due (never ran), but one is running
        }

        for (var i = 0; i < 200 && Backups(f).IsRunning; i++) await Task.Delay(50);
        Assert.False((await (await admin.GetAsync("/v1/admin/backup")).ReadAsync<BackupSettingsResponse>()).Running);
        Assert.Single(BackupFiles(folder));
    }

    [Fact]
    public void Concurrent_starts_start_exactly_one_run()
    {
        using var f = NewFactory();
        f.Client();
        var results = new bool[16];
        Parallel.For(0, results.Length, i => results[i] = Backups(f).TryStartInBackground());
        Assert.Equal(1, results.Count(r => r));
    }

    [Fact]
    public async Task Startup_removes_stale_backup_temp_directories()
    {
        using var f = NewFactory();
        var stale = Directory.CreateDirectory(Path.Combine(f.DataDirectory, "tmp", "backup-0123456789abcdef")).FullName;
        await File.WriteAllTextAsync(Path.Combine(stale, "users.jsonl"), "{\"passwordHash\":\"secret\"}");
        var other = Directory.CreateDirectory(Path.Combine(f.DataDirectory, "tmp", "keep-me")).FullName;

        f.Client(); // starts the server

        Assert.False(Directory.Exists(stale));
        Assert.True(Directory.Exists(other));
    }

    [Fact]
    public async Task Missing_blob_file_fails_the_run_and_writes_nothing()
    {
        var folder = NewFolder();
        using var f = NewFactory();
        var (admin, key, v2) = await Seed(f);
        await UseFolder(admin, folder);
        var finished = new ConcurrentQueue<BackupFinished>();
        f.Services.GetRequiredService<IDomainEvents>().Subscribe<BackupFinished>((e, _) => { finished.Enqueue(e); return Task.CompletedTask; });
        var hash = Manifests.FromJson(await InDb(f, db => db.SaveVersions.Where(v => v.Id == v2.VersionId).Select(v => v.ManifestJson).SingleAsync()))
            .Single().Hash;
        File.Delete(Path.Combine(f.DataDirectory, "blobs", key.UserId.ToString("N"), hash[..2], hash[2..4], hash));

        var run = await Backups(f).RunOnceAsync(CancellationToken.None);

        Assert.False(run.Ok);
        Assert.Contains(hash, run.Error);
        Assert.Empty(Directory.EnumerateFileSystemEntries(folder));
        var e = Assert.Single(finished);
        Assert.False(e.Ok);
        Assert.Equal(run.Error, e.Error);

        // Unconfigured target: also a recorded failure, not an exception.
        await PutSettings(admin, new BackupSettingsDto(false, BackupTargetKind.Folder, Path.Combine(folder, "not-mounted"), null, 3, 7, false, null));
        var missingFolder = await Backups(f).RunOnceAsync(CancellationToken.None);
        Assert.False(missingFolder.Ok);
        Assert.Contains("does not exist", missingFolder.Error);
        Assert.Equal(2, finished.Count);
        Assert.Equal(2, (await (await admin.GetAsync("/v1/admin/backup/history")).ReadAsync<List<BackupRunInfo>>()).Count);
    }

    [Fact]
    public async Task Pruning_waits_for_a_running_backup()
    {
        using var f = NewFactory();
        await Seed(f);
        var maintenance = f.Services.GetRequiredService<MaintenanceLock>();

        Task<PruneResult> prune;
        using (await maintenance.AcquireAsync())
        {
            prune = f.Services.GetRequiredService<Pruner>().RunOnceAsync(CancellationToken.None);
            await Task.Delay(300);
            Assert.False(prune.IsCompleted);
        }
        await prune.WaitAsync(TimeSpan.FromSeconds(10));
    }

    [Fact]
    public void Scheduled_run_is_due_once_per_day_at_hour_utc()
    {
        var noon = DateTimeOffset.Parse("2026-11-19T12:00:00Z");
        Assert.True(BackupService.IsDue(noon, 3, null));
        Assert.False(BackupService.IsDue(noon, 3, noon.AddHours(-8))); // ran at 04:00 today
        Assert.True(BackupService.IsDue(noon, 3, noon.AddHours(-10))); // last ran 02:00, before today's 03:00 slot
        Assert.False(BackupService.IsDue(noon, 13, noon.AddHours(-12))); // today's 13:00 slot is still ahead
        Assert.True(BackupService.IsDue(noon.AddHours(1), 13, noon.AddHours(-12)));
        Assert.False(BackupService.IsDue(noon.AddHours(1), 13, noon.AddHours(1)));
    }

    [DockerFact]
    [Trait("Category", "Postgres")]
    public async Task Sqlite_backup_restores_into_postgres()
    {
        var folder = NewFolder();
        using var source = NewFactory();
        var (admin, key, v2) = await Seed(source);
        await UseFolder(admin, folder);
        Assert.True((await Backups(source).RunOnceAsync(CancellationToken.None)).Ok);
        var file = Path.Combine(folder, Assert.Single(BackupFiles(folder)));

        await using var container = new PostgreSqlBuilder("postgres:17-alpine").Build();
        await container.StartAsync();
        using var pg = NewFactory();
        pg.Settings["EmuSync:DatabaseProvider"] = "postgres";
        pg.Settings["EmuSync:DatabaseConnection"] = container.GetConnectionString();
        await pg.Client().GetAsync("/healthz");

        var (code, _, error) = await Cli(pg, "restore-backup", file);

        Assert.True(code == 0, error);
        Assert.Equal(await Counts(source), await Counts(pg));
        await AssertRestored(source, pg, key);

        // Identity sequences were moved past the restored ids: new activity rows insert fine.
        var device = pg.Client();
        device.DefaultRequestHeaders.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", key.DeviceKey);
        var v3 = await Upload(device, "version three on postgres", v2.VersionId);
        Assert.Equal(SaveUploadStatus.Accepted, v3.Status);

        // And back out of Postgres into SQLite.
        var pgFolder = NewFolder();
        await UseFolder(await pg.WebLogin(Admin), pgFolder);
        Assert.True((await Backups(pg).RunOnceAsync(CancellationToken.None)).Ok);
        using var back = NewFactory();
        await back.Client().GetAsync("/healthz");
        var again = await Cli(back, "restore-backup", Path.Combine(pgFolder, Assert.Single(BackupFiles(pgFolder))));
        Assert.True(again.Code == 0, again.Error);
        Assert.Equal(3, await InDb(back, db => db.SaveVersions.CountAsync()));
    }
}
