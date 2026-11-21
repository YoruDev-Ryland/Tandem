using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Tandem.Protocol;
using Tandem.Server.Data;
using Tandem.Server.Retention;
using Tandem.Server.Saves;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Testcontainers.PostgreSql;

namespace Tandem.Server.Tests;

public class RetentionTests
{
    private static ServerFactory NewFactory()
    {
        var factory = new ServerFactory();
        factory.Settings["Tandem:AllowRegistration"] = "true";
        return factory;
    }

    private sealed record User(HttpClient Device, Guid UserId, Guid DeviceId);

    private static async Task<User> NewUser(ServerFactory f, string email = "a@example.com")
    {
        var (device, key) = await f.Register(email);
        return new User(device, key.UserId, key.DeviceId);
    }

    /// <summary>Uploads a blob with the given content and returns its manifest entry.</summary>
    private static async Task<ManifestEntry> Blob(HttpClient device, string content, string path = "data.dat") =>
        (await Fixtures.Manifest(device, (path, Encoding.UTF8.GetBytes(content)))).Single();

    private static string Json(params ManifestEntry[] entries) => Manifests.ToJson(entries);

    private static async Task<T> InDb<T>(ServerFactory f, Func<TandemDbContext, Task<T>> query)
    {
        using var scope = f.Services.CreateScope();
        return await query(scope.ServiceProvider.GetRequiredService<TandemDbContext>());
    }

    private static Task InDb(ServerFactory f, Func<TandemDbContext, Task> action) =>
        InDb(f, async db => { await action(db); return 0; });

    /// <summary>A save item with one version per manifest (Seq 1..n), head = the last one unless <paramref name="headSeq"/>.</summary>
    private static async Task<(Guid ItemId, List<Guid> Versions)> SeedSave(ServerFactory f, Guid userId, string game,
        IReadOnlyList<ManifestEntry[]> manifests, int? headSeq = null, Action<int, SaveVersion>? tweak = null)
    {
        var item = new SaveItem { Id = Guid.NewGuid(), UserId = userId, EmulatorId = "eden", Kind = ItemKind.Save, GameKey = game };
        var versions = manifests.Select((m, i) => new SaveVersion
        {
            Id = Guid.NewGuid(), ItemId = item.Id, Seq = i + 1, ReceivedUtc = f.Time.GetUtcNow(), ManifestJson = Json(m),
            ManifestHash = Manifests.Hash(m), TotalSize = m.Sum(e => e.Size),
        }).ToList();
        for (var i = 0; i < versions.Count; i++) tweak?.Invoke(i + 1, versions[i]);
        item.HeadVersionId = versions[(headSeq ?? versions.Count) - 1].Id;
        await InDb(f, async db =>
        {
            db.SaveItems.Add(item);
            db.SaveVersions.AddRange(versions);
            await db.SaveChangesAsync();
        });
        return (item.Id, versions.Select(v => v.Id).ToList());
    }

    private static async Task<(Guid BundleId, List<Guid> Versions)> SeedConfig(ServerFactory f, User u,
        IReadOnlyList<ManifestEntry[]> manifests, Action<int, ConfigVersion>? tweak = null)
    {
        var bundle = new ConfigBundle { Id = Guid.NewGuid(), UserId = u.UserId, EmulatorId = "eden", OwnerDeviceId = u.DeviceId };
        var versions = manifests.Select((m, i) => new ConfigVersion
        {
            Id = Guid.NewGuid(), BundleId = bundle.Id, Seq = i + 1, ReceivedUtc = f.Time.GetUtcNow(), ManifestJson = Json(m),
            ManifestHash = Manifests.Hash(m), TotalSize = m.Sum(e => e.Size),
        }).ToList();
        for (var i = 0; i < versions.Count; i++) tweak?.Invoke(i + 1, versions[i]);
        bundle.HeadVersionId = versions[^1].Id;
        await InDb(f, async db =>
        {
            db.ConfigBundles.Add(bundle);
            db.ConfigVersions.AddRange(versions);
            await db.SaveChangesAsync();
        });
        return (bundle.Id, versions.Select(v => v.Id).ToList());
    }

    private static async Task<(Guid ItemId, List<Guid> Versions)> SeedVault(ServerFactory f, Guid userId,
        IReadOnlyList<ManifestEntry[]> manifests)
    {
        var item = new VaultItem { Id = Guid.NewGuid(), UserId = userId, Category = "bios", EmulatorId = "", Name = "bios.bin" };
        var versions = manifests.Select((m, i) => new VaultVersion
        {
            Id = Guid.NewGuid(), ItemId = item.Id, Seq = i + 1, ReceivedUtc = f.Time.GetUtcNow(), ManifestJson = Json(m),
            ManifestHash = Manifests.Hash(m), TotalSize = m.Sum(e => e.Size),
        }).ToList();
        item.HeadVersionId = versions[^1].Id;
        await InDb(f, async db =>
        {
            db.VaultItems.Add(item);
            db.VaultVersions.AddRange(versions);
            await db.SaveChangesAsync();
        });
        return (item.Id, versions.Select(v => v.Id).ToList());
    }

    private static async Task<HttpResponseMessage> PutRetention(HttpClient c, int saves, int configs, int vault) =>
        await c.PutAsJsonAsync("/v1/settings/retention", new RetentionSettingsDto(saves, configs, vault), TestApi.Json);

    private static async Task SetRetention(HttpClient c, int saves = 30, int configs = 30, int vault = 5)
    {
        var r = await PutRetention(c, saves, configs, vault);
        Assert.True(r.IsSuccessStatusCode, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
    }

    private static Task<PruneResult> Prune(ServerFactory f) =>
        f.Services.GetRequiredService<Pruner>().RunOnceAsync(CancellationToken.None);

    private static Task<List<int>> SaveSeqs(ServerFactory f, Guid itemId) =>
        InDb(f, db => db.SaveVersions.Where(v => v.ItemId == itemId).OrderBy(v => v.Seq).Select(v => v.Seq).ToListAsync());

    private static Task<List<int>> ConfigSeqs(ServerFactory f, Guid bundleId) =>
        InDb(f, db => db.ConfigVersions.Where(v => v.BundleId == bundleId).OrderBy(v => v.Seq).Select(v => v.Seq).ToListAsync());

    private static Task<List<int>> VaultSeqs(ServerFactory f, Guid itemId) =>
        InDb(f, db => db.VaultVersions.Where(v => v.ItemId == itemId).OrderBy(v => v.Seq).Select(v => v.Seq).ToListAsync());

    private static string BlobPath(ServerFactory f, Guid userId, string hash) =>
        Path.Combine(f.DataDirectory, "blobs", userId.ToString("N"), hash[..2], hash[2..4], hash);

    /// <summary>Row and file both present (true), or both gone (false). Anything else fails.</summary>
    private static async Task<bool> BlobKept(ServerFactory f, Guid userId, string hash)
    {
        var row = await InDb(f, db => db.UserBlobs.AnyAsync(b => b.UserId == userId && b.Hash == hash));
        var file = File.Exists(BlobPath(f, userId, hash));
        Assert.True(row == file, $"Blob {hash[..8]}: row={row} file={file}");
        return row;
    }

    private static ManifestEntry[] M(params ManifestEntry[] e) => e;

    private static void PastGrace(ServerFactory f) => f.Time.Advance(TimeSpan.FromHours(2));

    // ---- settings ----

    [Fact]
    public async Task Retention_settings_default_validate_and_round_trip()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        Assert.Equal(new RetentionSettingsDto(30, 30, 5), await (await u.Device.GetAsync("/v1/settings/retention")).ReadAsync<RetentionSettingsDto>());

        foreach (var (s, c, v) in new[] { (0, 30, 5), (30, 1001, 5), (30, 30, 0), (-1, 30, 5) })
        {
            var bad = await PutRetention(u.Device, s, c, v);
            Assert.Equal(HttpStatusCode.BadRequest, bad.StatusCode);
            Assert.Contains("bad_value", await bad.Content.ReadAsStringAsync());
        }

        await SetRetention(u.Device, 1, 1000, 7);
        Assert.Equal(new RetentionSettingsDto(1, 1000, 7), await (await u.Device.GetAsync("/v1/settings/retention")).ReadAsync<RetentionSettingsDto>());
        Assert.True(new ServerOptions().EnablePruning);
        Assert.Equal(60, new ServerOptions().PruneIntervalMinutes);
    }

    // ---- version retention ----

    [Fact]
    public async Task Keeps_newest_N_and_head()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        await SetRetention(u.Device, saves: 2);
        var blobs = new List<ManifestEntry>();
        for (var i = 1; i <= 5; i++) blobs.Add(await Blob(u.Device, $"v{i}"));
        var (item, _) = await SeedSave(f, u.UserId, "G", blobs.Select(b => M(b)).ToList(), headSeq: 2);
        PastGrace(f);

        var result = await Prune(f);

        Assert.Equal([2, 4, 5], await SaveSeqs(f, item));
        Assert.Equal(2, result.Versions);
        Assert.False(await BlobKept(f, u.UserId, blobs[0].Hash));
        Assert.True(await BlobKept(f, u.UserId, blobs[1].Hash));
        Assert.False(await BlobKept(f, u.UserId, blobs[2].Hash));
        Assert.True(await BlobKept(f, u.UserId, blobs[3].Hash));
        Assert.True(await BlobKept(f, u.UserId, blobs[4].Hash));

        var activity = await InDb(f, db => db.ActivityEvents.Where(a => a.UserId == u.UserId && a.Kind == "pruned").ToListAsync());
        var row = Assert.Single(activity);
        Assert.StartsWith("Pruned 2 old versions, freed ", row.Message);
        Assert.EndsWith(" MB", row.Message);

        // Idempotent: nothing more to prune, and no second activity row.
        var again = await Prune(f);
        Assert.Equal(new PruneResult(0, 0, 0), again);
        Assert.Equal(1, await InDb(f, db => db.ActivityEvents.CountAsync(a => a.UserId == u.UserId && a.Kind == "pruned")));
    }

    [Fact]
    public async Task Never_prunes_pinned_conflict_or_known_versions()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        await SetRetention(u.Device, saves: 1, configs: 1, vault: 1);
        var b = new List<ManifestEntry>();
        for (var i = 1; i <= 6; i++) b.Add(await Blob(u.Device, $"s{i}"));

        // v1 pinned, v2 variant, v3 open conflict, v4 known by the device, v5 plain (pruned), v6 head.
        var (item, saveVersions) = await SeedSave(f, u.UserId, "G", b.Select(x => M(x)).ToList(), tweak: (seq, v) =>
        {
            if (seq == 1) v.Pinned = true;
            if (seq == 2) v.Variant = "Variant 1";
            if (seq == 3) v.ConflictReason = ConflictReason.Concurrent;
        });
        await InDb(f, async db =>
        {
            db.DeviceItemStates.Add(new DeviceItemState { DeviceId = u.DeviceId, ItemId = item, KnownVersionId = saveVersions[3] });
            await db.SaveChangesAsync();
        });

        // Configs: v1 applied by the device, v2 pinned, v3 plain (pruned), v4 head.
        var c = new List<ManifestEntry>();
        for (var i = 1; i <= 4; i++) c.Add(await Blob(u.Device, $"c{i}", "config.ini"));
        var (bundle, configVersions) = await SeedConfig(f, u, c.Select(x => M(x)).ToList(), (seq, v) => v.Pinned = seq == 2);
        await InDb(f, async db =>
        {
            db.DeviceConfigs.Add(new DeviceConfig { DeviceId = u.DeviceId, EmulatorId = "eden", BundleId = bundle, AppliedVersionId = configVersions[0] });
            await db.SaveChangesAsync();
        });

        // Vault: v1 held by the device, v2 plain (pruned), v3 head.
        var vb = new List<ManifestEntry>();
        for (var i = 1; i <= 3; i++) vb.Add(await Blob(u.Device, $"b{i}", "bios.bin"));
        var (vault, vaultVersions) = await SeedVault(f, u.UserId, vb.Select(x => M(x)).ToList());
        await InDb(f, async db =>
        {
            db.DeviceVaultStates.Add(new DeviceVaultState { DeviceId = u.DeviceId, ItemId = vault, VersionId = vaultVersions[0] });
            await db.SaveChangesAsync();
        });
        PastGrace(f);

        var result = await Prune(f);

        Assert.Equal([1, 2, 3, 4, 6], await SaveSeqs(f, item));
        Assert.Equal([1, 2, 4], await ConfigSeqs(f, bundle));
        Assert.Equal([1, 3], await VaultSeqs(f, vault));
        Assert.Equal(3, result.Versions);
        Assert.False(await BlobKept(f, u.UserId, b[4].Hash));
        Assert.False(await BlobKept(f, u.UserId, c[2].Hash));
        Assert.False(await BlobKept(f, u.UserId, vb[1].Hash));
        foreach (var kept in new[] { b[0], b[1], b[2], b[3], b[5], c[0], c[1], c[3], vb[0], vb[2] })
            Assert.True(await BlobKept(f, u.UserId, kept.Hash));
    }

    [Fact]
    public async Task Never_prunes_conflict_versions_even_resolved()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        await SetRetention(u.Device, saves: 1, configs: 1);
        var b = new List<ManifestEntry>();
        for (var i = 1; i <= 4; i++) b.Add(await Blob(u.Device, $"s{i}"));
        var (item, _) = await SeedSave(f, u.UserId, "G", b.Select(x => M(x)).ToList(), tweak: (seq, v) =>
        {
            if (seq == 1) { v.ConflictReason = ConflictReason.Concurrent; v.Resolved = true; }
            if (seq == 2) { v.ConflictReason = ConflictReason.FirstSync; v.Resolved = true; }
        });
        var c = new List<ManifestEntry>();
        for (var i = 1; i <= 3; i++) c.Add(await Blob(u.Device, $"c{i}", "config.ini"));
        var (bundle, _) = await SeedConfig(f, u, c.Select(x => M(x)).ToList(), (seq, v) =>
        {
            if (seq == 1) { v.ConflictReason = ConflictReason.Concurrent; v.Resolved = true; }
        });
        PastGrace(f);

        await Prune(f);

        Assert.Equal([1, 2, 4], await SaveSeqs(f, item));
        Assert.Equal([1, 3], await ConfigSeqs(f, bundle));
        Assert.True(await BlobKept(f, u.UserId, b[0].Hash));
        Assert.True(await BlobKept(f, u.UserId, c[0].Hash));
    }

    [Fact]
    public async Task Never_prunes_version_referenced_by_pending_delivery()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        await SetRetention(u.Device, saves: 1, configs: 1, vault: 1);
        var b = new List<ManifestEntry>();
        for (var i = 1; i <= 4; i++) b.Add(await Blob(u.Device, $"s{i}"));
        var (item, sv) = await SeedSave(f, u.UserId, "G", b.Select(x => M(x)).ToList());
        var c = new List<ManifestEntry>();
        for (var i = 1; i <= 2; i++) c.Add(await Blob(u.Device, $"c{i}", "config.ini"));
        var (bundle, cv) = await SeedConfig(f, u, c.Select(x => M(x)).ToList());
        var vb = new List<ManifestEntry>();
        for (var i = 1; i <= 2; i++) vb.Add(await Blob(u.Device, $"b{i}", "bios.bin"));
        var (vault, vv) = await SeedVault(f, u.UserId, vb.Select(x => M(x)).ToList());

        var now = f.Time.GetUtcNow();
        await InDb(f, async db =>
        {
            // Pending: keeps save v1, config v1, vault v1.
            db.Deliveries.Add(new Delivery { Id = Guid.NewGuid(), DeviceId = u.DeviceId, Kind = DeliveryKind.Save, TargetId = item, VersionId = sv[0], CreatedUtc = now });
            db.Deliveries.Add(new Delivery
            {
                Id = Guid.NewGuid(), DeviceId = u.DeviceId, Kind = DeliveryKind.Config, TargetId = bundle, VersionId = cv[0], CreatedUtc = now,
                NotesJson = JsonSerializer.Serialize(new { manifest = new[] { c[0] }, missingLocalKeys = Array.Empty<string>() }, TestApi.Json),
            });
            db.Deliveries.Add(new Delivery { Id = Guid.NewGuid(), DeviceId = u.DeviceId, Kind = DeliveryKind.Vault, TargetId = vault, VersionId = vv[0], CreatedUtc = now });
            // Superseded (closed by the server) and acked: do not keep save v2 / v3.
            db.Deliveries.Add(new Delivery { Id = Guid.NewGuid(), DeviceId = u.DeviceId, Kind = DeliveryKind.Save, TargetId = item, VersionId = sv[1], CreatedUtc = now, AckedUtc = now, Superseded = true });
            db.Deliveries.Add(new Delivery { Id = Guid.NewGuid(), DeviceId = u.DeviceId, Kind = DeliveryKind.Save, TargetId = item, VersionId = sv[2], CreatedUtc = now, AckedUtc = now, Result = DeliveryResult.Failed });
            await db.SaveChangesAsync();
        });
        PastGrace(f);

        await Prune(f);

        Assert.Equal([1, 4], await SaveSeqs(f, item));
        Assert.Equal([1, 2], await ConfigSeqs(f, bundle));
        Assert.Equal([1, 2], await VaultSeqs(f, vault));
        Assert.True(await BlobKept(f, u.UserId, b[0].Hash));
        Assert.False(await BlobKept(f, u.UserId, b[1].Hash));
        Assert.False(await BlobKept(f, u.UserId, b[2].Hash));
    }

    [Fact]
    public async Task Pinned_through_the_api_survives_prune()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        await SetRetention(u.Device, saves: 1);
        var b = new List<ManifestEntry>();
        for (var i = 1; i <= 3; i++) b.Add(await Blob(u.Device, $"v{i}"));
        var (item, sv) = await SeedSave(f, u.UserId, "G", b.Select(x => M(x)).ToList());
        PastGrace(f);

        (await u.Device.PostAsync($"/v1/saves/{item}/pin/{sv[0]}", null)).EnsureSuccessStatusCode();
        await Prune(f);

        Assert.Equal([1, 3], await SaveSeqs(f, item));
        Assert.True(await BlobKept(f, u.UserId, b[0].Hash));
    }

    [Fact]
    public async Task Pin_waits_for_the_user_lock()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        var b = await Blob(u.Device, "v1");
        var (item, sv) = await SeedSave(f, u.UserId, "G", [M(b)]);
        Task<HttpResponseMessage> pin;
        using (await f.Services.GetRequiredService<UserLocks>().Acquire(u.UserId))
        {
            pin = u.Device.PostAsync($"/v1/saves/{item}/pin/{sv[0]}", null);
            await Task.Delay(300);
            Assert.False(pin.IsCompleted, "Pinning must wait for the user's lock (a prune may be running).");
        }
        (await pin).EnsureSuccessStatusCode();
        Assert.True(await InDb(f, db => db.SaveVersions.Where(v => v.Id == sv[0]).Select(v => v.Pinned).SingleAsync()));
    }

    [Fact]
    public async Task Version_delete_skips_versions_flagged_after_selection()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        var b = new List<ManifestEntry>();
        for (var i = 1; i <= 4; i++) b.Add(await Blob(u.Device, $"s{i}"));
        // Selected as plain candidates, then flagged before the delete runs.
        var (item, sv) = await SeedSave(f, u.UserId, "G", b.Select(x => M(x)).ToList(), tweak: (seq, v) =>
        {
            if (seq == 1) v.Pinned = true;
            if (seq == 2) v.Variant = "Variant 1";
            if (seq == 3) v.ConflictReason = ConflictReason.Concurrent;
        });
        var c = new List<ManifestEntry>();
        for (var i = 1; i <= 3; i++) c.Add(await Blob(u.Device, $"c{i}", "config.ini"));
        var (bundle, cv) = await SeedConfig(f, u, c.Select(x => M(x)).ToList(), (seq, v) =>
        {
            if (seq == 1) v.Pinned = true;
            if (seq == 2) v.ConflictReason = ConflictReason.FirstSync;
        });

        var deleted = await InDb(f, db => Pruner.DeleteVersionsAsync(db, u.UserId, sv, cv, [], CancellationToken.None));

        Assert.Equal(2, deleted);
        Assert.Equal([1, 2, 3], await SaveSeqs(f, item));
        Assert.Equal([1, 2], await ConfigSeqs(f, bundle));
    }

    [Fact]
    public async Task Put_text_rewrites_a_missing_file_when_the_row_exists()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        var e = await Blob(u.Device, "[UI]\nkey=value\n", "config.ini");
        File.Delete(BlobPath(f, u.UserId, e.Hash));

        var (hash, _) = await InDb(f, async db =>
        {
            using var scope = f.Services.CreateScope();
            return await scope.ServiceProvider.GetRequiredService<Tandem.Server.Configs.ConfigTransform>()
                .PutTextAsync(u.UserId, "[UI]\nkey=value\n", CancellationToken.None);
        });

        Assert.Equal(e.Hash, hash);
        Assert.True(await BlobKept(f, u.UserId, e.Hash));
        Assert.Equal("[UI]\nkey=value\n", await File.ReadAllTextAsync(BlobPath(f, u.UserId, e.Hash)));
    }

    // ---- blob cleanup ----

    [Fact]
    public async Task Unreferenced_blobs_deleted_shared_blobs_kept()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        await SetRetention(u.Device, saves: 1);
        var shared = await Blob(u.Device, "shared", "a.dat");
        var only1 = await Blob(u.Device, "only in v1", "b.dat");
        var orphan = await Blob(u.Device, "never referenced");
        var raw = await Blob(u.Device, "raw config", "config.ini");
        var (item, _) = await SeedSave(f, u.UserId, "G", [M(shared, only1), M(shared)]);
        await InDb(f, async db =>
        {
            db.RawConfigUploads.Add(new RawConfigUpload { Id = Guid.NewGuid(), DeviceId = u.DeviceId, EmulatorId = "eden", ManifestJson = Json(raw), ReceivedUtc = f.Time.GetUtcNow() });
            await db.SaveChangesAsync();
        });
        PastGrace(f);

        var result = await Prune(f);

        Assert.Equal([2], await SaveSeqs(f, item));
        Assert.True(await BlobKept(f, u.UserId, shared.Hash));
        Assert.True(await BlobKept(f, u.UserId, raw.Hash));
        Assert.False(await BlobKept(f, u.UserId, only1.Hash));
        Assert.False(await BlobKept(f, u.UserId, orphan.Hash));
        Assert.Equal(new PruneResult(1, 2, only1.Size + orphan.Size), result);
    }

    [Fact]
    public async Task Blob_younger_than_grace_is_kept_even_if_unreferenced()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        var fresh = await Blob(u.Device, "in flight");

        f.Time.Advance(TimeSpan.FromMinutes(59));
        await Prune(f);
        Assert.True(await BlobKept(f, u.UserId, fresh.Hash));

        f.Time.Advance(TimeSpan.FromMinutes(2));
        await Prune(f);
        Assert.False(await BlobKept(f, u.UserId, fresh.Hash));
    }

    [Fact]
    public async Task Missing_check_and_reupload_restart_the_grace_period()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        var asked = await Blob(u.Device, "asked about");
        var reput = await Blob(u.Device, "put again");
        PastGrace(f);

        // The device asks which blobs are missing, is told it has this one, so it will not upload it again.
        var missing = await (await u.Device.PostAsJsonAsync("/v1/blobs/missing", new MissingBlobsRequest([asked.Hash])))
            .ReadAsync<MissingBlobsResponse>();
        Assert.Empty(missing.Missing);
        await Blob(u.Device, "put again");
        f.Time.Advance(TimeSpan.FromMinutes(30));

        await Prune(f);
        Assert.True(await BlobKept(f, u.UserId, asked.Hash));
        Assert.True(await BlobKept(f, u.UserId, reput.Hash));
    }

    [Fact]
    public async Task Config_delivery_expanded_blobs_are_kept()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        var c = await Blob(u.Device, "canonical", "config.ini");
        var pending = await Blob(u.Device, "expanded pending", "config.ini");
        var recent = await Blob(u.Device, "expanded acked recently", "config.ini");
        var old = await Blob(u.Device, "expanded acked long ago", "config.ini");
        var (bundle, cv) = await SeedConfig(f, u, [M(c)]);

        string Notes(ManifestEntry e) =>
            JsonSerializer.Serialize(new { manifest = new[] { e }, missingLocalKeys = new[] { "Input/key" } }, TestApi.Json);
        var now = f.Time.GetUtcNow();
        await InDb(f, async db =>
        {
            db.Deliveries.Add(new Delivery { Id = Guid.NewGuid(), DeviceId = u.DeviceId, Kind = DeliveryKind.Config, TargetId = bundle, VersionId = cv[0], CreatedUtc = now - TimeSpan.FromDays(30), NotesJson = Notes(pending) });
            db.Deliveries.Add(new Delivery { Id = Guid.NewGuid(), DeviceId = u.DeviceId, Kind = DeliveryKind.Config, TargetId = bundle, VersionId = cv[0], CreatedUtc = now - TimeSpan.FromDays(6), AckedUtc = now, Result = DeliveryResult.Applied, NotesJson = Notes(recent) });
            db.Deliveries.Add(new Delivery { Id = Guid.NewGuid(), DeviceId = u.DeviceId, Kind = DeliveryKind.Config, TargetId = bundle, VersionId = cv[0], CreatedUtc = now - TimeSpan.FromDays(8), AckedUtc = now - TimeSpan.FromDays(8), Result = DeliveryResult.Applied, NotesJson = Notes(old) });
            await db.SaveChangesAsync();
        });
        PastGrace(f);

        await Prune(f);

        Assert.True(await BlobKept(f, u.UserId, c.Hash));
        Assert.True(await BlobKept(f, u.UserId, pending.Hash));
        Assert.True(await BlobKept(f, u.UserId, recent.Hash));
        Assert.False(await BlobKept(f, u.UserId, old.Hash));
    }

    [Fact]
    public async Task Gc_holds_user_lock()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        var head = await Blob(u.Device, "head");
        var reused = await Blob(u.Device, "an old blob about to be referenced");
        var (item, _) = await SeedSave(f, u.UserId, "G", [M(head)]);
        PastGrace(f);

        Task<PruneResult> prune;
        using (await f.Services.GetRequiredService<UserLocks>().Acquire(u.UserId))
        {
            // An upload holds the lock and commits a version referencing the old, so far unreferenced, blob.
            prune = Prune(f);
            await Task.Delay(300);
            Assert.False(prune.IsCompleted, "The GC must wait for the user's lock.");
            await InDb(f, async db =>
            {
                db.SaveVersions.Add(new SaveVersion
                {
                    Id = Guid.NewGuid(), ItemId = item, Seq = 2, ReceivedUtc = f.Time.GetUtcNow(), ManifestJson = Json(reused),
                    ManifestHash = Manifests.Hash([reused]), TotalSize = reused.Size,
                });
                await db.SaveChangesAsync();
            });
        }
        await prune;

        Assert.True(await BlobKept(f, u.UserId, reused.Hash));
        Assert.True(await BlobKept(f, u.UserId, head.Hash));
    }

    [Fact]
    public async Task Upload_rechecks_blobs_under_the_lock()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        var blob = await Blob(u.Device, "removed while the upload waits");
        Task<HttpResponseMessage> upload;
        using (await f.Services.GetRequiredService<UserLocks>().Acquire(u.UserId))
        {
            upload = u.Device.PostJsonAsync("/v1/saves/upload",
                new SaveUploadRequest("eden", ItemKind.Save, "G", "", "", null, [blob], null));
            await Task.Delay(300); // the upload passes its first blob check, then waits for the lock
            // What a GC run under the lock would do: the blob is gone when the upload gets the lock.
            await InDb(f, db => db.UserBlobs.Where(b => b.UserId == u.UserId && b.Hash == blob.Hash).ExecuteDeleteAsync());
        }

        var response = await upload;
        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Contains("missing_blobs", await response.Content.ReadAsStringAsync());
        Assert.Equal(0, await InDb(f, db => db.SaveVersions.CountAsync()));
    }

    [Fact]
    public async Task Pruning_is_per_user()
    {
        using var f = NewFactory();
        var a = await NewUser(f, "a@example.com");
        var b = await NewUser(f, "b@example.com");
        await SetRetention(a.Device, saves: 1);

        var aBlobs = new List<ManifestEntry>();
        var bBlobs = new List<ManifestEntry>();
        for (var i = 1; i <= 3; i++)
        {
            aBlobs.Add(await Blob(a.Device, $"v{i}"));
            bBlobs.Add(await Blob(b.Device, $"v{i}")); // same content, so same hashes, held separately per user
        }
        var (aItem, _) = await SeedSave(f, a.UserId, "G", aBlobs.Select(x => M(x)).ToList());
        var (bItem, _) = await SeedSave(f, b.UserId, "G", bBlobs.Select(x => M(x)).ToList());
        PastGrace(f);

        await Prune(f);

        Assert.Equal([3], await SaveSeqs(f, aItem));
        Assert.Equal([1, 2, 3], await SaveSeqs(f, bItem));
        Assert.False(await BlobKept(f, a.UserId, aBlobs[0].Hash));
        foreach (var x in bBlobs) Assert.True(await BlobKept(f, b.UserId, x.Hash));
        Assert.Equal(1, await InDb(f, db => db.ActivityEvents.CountAsync(e => e.UserId == a.UserId && e.Kind == "pruned")));
        Assert.Equal(0, await InDb(f, db => db.ActivityEvents.CountAsync(e => e.UserId == b.UserId && e.Kind == "pruned")));
    }

    // ---- storage ----

    [Fact]
    public async Task Storage_usage_sums_unique_blobs()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        var shared = await Blob(u.Device, new string('s', 1000), "a.dat");
        var g1Only = await Blob(u.Device, new string('x', 300), "b.dat");
        var g2 = await Blob(u.Device, new string('y', 200), "a.dat");
        var cfg = await Blob(u.Device, new string('c', 50), "config.ini");
        var bios = await Blob(u.Device, new string('b', 70), "bios.bin");
        await SeedSave(f, u.UserId, "G1", [M(shared, g1Only), M(shared)]);
        await SeedSave(f, u.UserId, "G2", [M(shared), M(g2)]);
        await SeedConfig(f, u, [M(cfg)]);
        await SeedVault(f, u.UserId, [M(bios)]);
        var other = await NewUser(f, "b@example.com");
        await Blob(other.Device, new string('o', 5000));

        var usage = await (await u.Device.GetAsync("/v1/settings/storage")).ReadAsync<StorageUsage>();

        Assert.Equal(1000 + 300 + 200 + 50 + 70, usage.TotalBytes);
        var eden = Assert.Single(usage.ByEmulator, r => r.EmulatorId == "eden");
        Assert.Equal(1000 + 300 + 200 + 50, eden.Bytes);
        Assert.Equal(70, Assert.Single(usage.ByEmulator, r => r.EmulatorId == "").Bytes);
        Assert.Equal(["G1", "G2"], usage.TopGames.Select(g => g.GameKey));
        Assert.Equal(1300, usage.TopGames[0].Bytes);
        Assert.Equal(1200, usage.TopGames[1].Bytes);
    }

    // ---- background service ----

    [Fact]
    public async Task Background_pruner_runs_on_its_interval_when_enabled()
    {
        using var f = NewFactory();
        f.Settings["Tandem:EnablePruning"] = "true";
        f.Settings["Tandem:PruneIntervalMinutes"] = "10";
        var u = await NewUser(f);
        await SetRetention(u.Device, saves: 1);
        var blobs = new List<ManifestEntry>();
        for (var i = 1; i <= 3; i++) blobs.Add(await Blob(u.Device, $"v{i}"));
        var (item, _) = await SeedSave(f, u.UserId, "G", blobs.Select(x => M(x)).ToList());

        for (var i = 0; i < 100 && (await SaveSeqs(f, item)).Count > 1; i++)
        {
            f.Time.Advance(TimeSpan.FromMinutes(11));
            await Task.Delay(50);
        }
        Assert.Equal([3], await SaveSeqs(f, item));
    }

    [Fact]
    public async Task Background_pruner_is_off_in_tests_by_default()
    {
        using var f = NewFactory();
        var u = await NewUser(f);
        await SetRetention(u.Device, saves: 1);
        var blobs = new List<ManifestEntry>();
        for (var i = 1; i <= 3; i++) blobs.Add(await Blob(u.Device, $"v{i}"));
        var (item, _) = await SeedSave(f, u.UserId, "G", blobs.Select(x => M(x)).ToList());

        for (var i = 0; i < 10; i++)
        {
            f.Time.Advance(TimeSpan.FromMinutes(61));
            await Task.Delay(30);
        }
        Assert.Equal([1, 2, 3], await SaveSeqs(f, item));
    }

    // ---- postgres ----

    [DockerFact]
    [Trait("Category", "Postgres")]
    public async Task Prune_and_gc_on_postgres()
    {
        await using var container = new PostgreSqlBuilder("postgres:17-alpine").Build();
        await container.StartAsync();
        using var f = NewFactory();
        f.Settings["Tandem:DatabaseProvider"] = "postgres";
        f.Settings["Tandem:DatabaseConnection"] = container.GetConnectionString();
        var u = await NewUser(f);
        await SetRetention(u.Device, saves: 1);
        var shared = await Blob(u.Device, "shared", "a.dat");
        var only1 = await Blob(u.Device, "only in v1", "b.dat");
        var (item, sv) = await SeedSave(f, u.UserId, "G", [M(shared, only1), M(shared), M(shared)], tweak: (seq, v) => v.Pinned = seq == 2);
        PastGrace(f);

        var result = await Prune(f);

        Assert.Equal([2, 3], await SaveSeqs(f, item));
        Assert.True(await BlobKept(f, u.UserId, shared.Hash));
        Assert.False(await BlobKept(f, u.UserId, only1.Hash));
        Assert.Equal(new PruneResult(1, 1, only1.Size), result);
        var usage = await (await u.Device.GetAsync("/v1/settings/storage")).ReadAsync<StorageUsage>();
        Assert.Equal(shared.Size, usage.TotalBytes);
    }
}
