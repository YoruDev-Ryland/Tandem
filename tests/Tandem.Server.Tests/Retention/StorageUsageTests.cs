using System.Net;
using System.Net.Http.Json;
using System.Text;
using Tandem.Protocol;
using Tandem.Server.Data;
using Tandem.Server.Saves;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.Extensions.DependencyInjection;

namespace Tandem.Server.Tests.Retention;

public class StorageUsageTests
{
    private static ServerFactory NewFactory()
    {
        var factory = new ServerFactory();
        factory.Settings["Tandem:AllowRegistration"] = "true";
        return factory;
    }

    private static async Task<ManifestEntry> Blob(HttpClient device, string content, string path = "data.dat") =>
        (await Fixtures.Manifest(device, (path, Encoding.UTF8.GetBytes(content)))).Single();

    private static async Task InDb(ServerFactory f, Func<TandemDbContext, Task> action)
    {
        using var scope = f.Services.CreateScope();
        await action(scope.ServiceProvider.GetRequiredService<TandemDbContext>());
    }

    /// <summary>One item with a version per manifest (Seq 1..n); the head is the last one.</summary>
    private static async Task SeedItem(ServerFactory f, Guid userId, string game, ItemKind kind, params ManifestEntry[][] manifests)
    {
        var item = new SaveItem { Id = Guid.NewGuid(), UserId = userId, EmulatorId = "eden", Kind = kind, GameKey = game, DisplayName = game + " name" };
        var versions = manifests.Select((m, i) => new SaveVersion
        {
            Id = Guid.NewGuid(), ItemId = item.Id, Seq = i + 1, ReceivedUtc = f.Time.GetUtcNow(), ManifestJson = Manifests.ToJson(m),
            ManifestHash = Manifests.Hash(m), TotalSize = m.Sum(e => e.Size),
        }).ToList();
        item.HeadVersionId = versions[^1].Id;
        await InDb(f, async db =>
        {
            db.SaveItems.Add(item);
            db.SaveVersions.AddRange(versions);
            await db.SaveChangesAsync();
        });
    }

    private static Task<StorageUsage> Get(HttpClient c) => c.GetAsync("/v1/settings/storage").ContinueWith(t => t.Result.ReadAsync<StorageUsage>()).Unwrap();

    [Fact]
    public async Task Games_list_every_game_with_current_and_older_bytes()
    {
        using var f = NewFactory();
        var (device, key) = await f.Register("a@example.com");
        var v1 = await Blob(device, new string('1', 100));
        var v2 = await Blob(device, new string('2', 200));
        var v3 = await Blob(device, new string('3', 400));
        await SeedItem(f, key.UserId, "G1", ItemKind.Save, [v1], [v2], [v3]);

        var usage = await Get(device);

        var g = Assert.Single(usage.Games);
        Assert.Equal(("eden", "G1", "G1 name"), (g.EmulatorId, g.GameKey, g.DisplayName));
        Assert.Equal(700, g.Bytes);
        Assert.Equal(400, g.CurrentBytes);
        Assert.Equal(300, g.OlderBytes);
        Assert.Equal(3, g.Versions);
        Assert.Equal(["Save"], g.Kinds);
        Assert.Equal(300, usage.OlderBytes);
    }

    [Fact]
    public async Task Games_beyond_the_top_ten_are_all_listed()
    {
        using var f = NewFactory();
        var (device, key) = await f.Register("a@example.com");
        for (var i = 0; i < 12; i++)
            await SeedItem(f, key.UserId, $"G{i:00}", ItemKind.Save, [await Blob(device, new string((char)('a' + i), 10 + i))]);

        var usage = await Get(device);

        Assert.Equal(12, usage.Games.Count);
        Assert.Equal(10, usage.TopGames.Count);
    }

    [Fact]
    public async Task A_blob_shared_by_head_and_older_counts_as_current_only()
    {
        using var f = NewFactory();
        var (device, key) = await f.Register("a@example.com");
        var shared = await Blob(device, new string('s', 100));
        var old = await Blob(device, new string('o', 50));
        await SeedItem(f, key.UserId, "G1", ItemKind.Save, [shared, old], [shared]);

        var usage = await Get(device);

        var g = Assert.Single(usage.Games);
        Assert.Equal(150, g.Bytes);
        Assert.Equal(100, g.CurrentBytes);
        Assert.Equal(50, g.OlderBytes);
        Assert.Equal(50, usage.OlderBytes);
    }

    [Fact]
    public async Task Kinds_of_a_game_with_a_save_and_a_state_are_distinct_and_sorted()
    {
        using var f = NewFactory();
        var (device, key) = await f.Register("a@example.com");
        var a = await Blob(device, "aaa");
        var b = await Blob(device, "bbbb");
        await SeedItem(f, key.UserId, "G1", ItemKind.State, [a]);
        await SeedItem(f, key.UserId, "G1", ItemKind.Save, [b]);

        var g = Assert.Single((await Get(device)).Games);

        Assert.Equal(["Save", "State"], g.Kinds);
        Assert.Equal(2, g.Versions);
    }

    [Fact]
    public async Task By_kind_splits_saves_states_configs_vault_and_lost()
    {
        using var f = NewFactory();
        var (device, key) = await f.Register("a@example.com");
        var save = await Blob(device, new string('a', 10));
        var state = await Blob(device, new string('b', 20));
        var cfg = await Blob(device, new string('c', 30));
        var raw = await Blob(device, new string('r', 5));
        var vault = await Blob(device, new string('v', 40));
        var lost = await Blob(device, new string('l', 50));
        await SeedItem(f, key.UserId, "G1", ItemKind.Save, [save]);
        await SeedItem(f, key.UserId, "G1", ItemKind.State, [state]);
        await InDb(f, async db =>
        {
            var bundle = new ConfigBundle { Id = Guid.NewGuid(), UserId = key.UserId, EmulatorId = "eden", OwnerDeviceId = key.DeviceId };
            var cv = new ConfigVersion { Id = Guid.NewGuid(), BundleId = bundle.Id, Seq = 1, ReceivedUtc = f.Time.GetUtcNow(),
                ManifestJson = Manifests.ToJson([cfg]), ManifestHash = Manifests.Hash([cfg]), TotalSize = cfg.Size };
            bundle.HeadVersionId = cv.Id;
            db.ConfigBundles.Add(bundle);
            db.ConfigVersions.Add(cv);
            db.RawConfigUploads.Add(new RawConfigUpload { DeviceId = key.DeviceId, EmulatorId = "eden", ManifestJson = Manifests.ToJson([raw]) });
            var vi = new VaultItem { Id = Guid.NewGuid(), UserId = key.UserId, Category = "bios", EmulatorId = "", Name = "bios.bin" };
            var vv = new VaultVersion { Id = Guid.NewGuid(), ItemId = vi.Id, Seq = 1, ReceivedUtc = f.Time.GetUtcNow(),
                ManifestJson = Manifests.ToJson([vault]), ManifestHash = Manifests.Hash([vault]), TotalSize = vault.Size };
            vi.HeadVersionId = vv.Id;
            db.VaultItems.Add(vi);
            db.VaultVersions.Add(vv);
            db.LostItems.Add(new LostItem { Id = Guid.NewGuid(), UserId = key.UserId, EmulatorId = "eden", Kind = ItemKind.Save, GameKey = "G9",
                ManifestJson = Manifests.ToJson([lost]), ManifestHash = Manifests.Hash([lost]), TotalSize = lost.Size,
                Reason = LostItem.GameDeleted, LostUtc = f.Time.GetUtcNow() });
            await db.SaveChangesAsync();
        });

        var k = (await Get(device)).ByKind;

        Assert.Equal(new StorageKinds(10, 20, 35, 40, 50), k);
    }

    [Fact]
    public async Task Server_figures_are_null_for_a_non_admin()
    {
        using var f = NewFactory();
        await f.Register("admin@example.com");
        var (user, _) = await f.Register("user@example.com");

        Assert.Null((await Get(user)).Server);
    }

    [Fact]
    public async Task Server_figures_for_an_admin_include_all_accounts_files_bytes()
    {
        using var f = NewFactory();
        var (admin, _) = await f.Register("admin@example.com");
        var (user, _) = await f.Register("user@example.com");
        await Blob(admin, new string('a', 100));
        await Blob(user, new string('u', 250));
        Directory.CreateDirectory(Path.Combine(f.DataDirectory, "art"));
        await File.WriteAllBytesAsync(Path.Combine(f.DataDirectory, "art", "x.png"), new byte[777]);

        var usage = await Get(admin);

        Assert.Equal(100, usage.TotalBytes);
        var s = Assert.IsType<StorageServer>(usage.Server);
        Assert.Equal(350, s.FilesBytes);
        Assert.Equal(777, s.ArtBytes);
        Assert.True(s.DefaultLocation);
        Assert.Equal(Path.Combine(f.DataDirectory, "blobs"), s.FilesLocation);
        Assert.Null(s.Move);
        Assert.True(s.MinFreeBytes > 0);
        Assert.True(s.DatabaseBytes > 0);
    }

    [Fact]
    public async Task Database_bytes_include_the_wal()
    {
        using var f = NewFactory();
        var (admin, _) = await f.Register("admin@example.com");
        var dbFile = Path.Combine(f.DataDirectory, "tandem.db");

        var s = (await Get(admin)).Server!;

        // EF Core's SQLite runs in WAL mode, so registering left recent writes in the -wal file.
        var wal = new FileInfo(dbFile + "-wal");
        Assert.True(wal.Exists && wal.Length > 0, "the test needs a non-empty -wal file");
        Assert.Equal(new FileInfo(dbFile).Length + wal.Length, s.DatabaseBytes);
    }

    [Fact]
    public async Task Storage_view_round_trips_and_defaults_to_map()
    {
        using var f = NewFactory();
        var (device, _) = await f.Register("a@example.com");

        Assert.Equal("map", (await (await device.GetAsync("/v1/settings/storage-view")).ReadAsync<StorageViewDto>()).View);

        var put = await device.PutAsJsonAsync("/v1/settings/storage-view", new StorageViewDto("blocks"), TestApi.Json);
        Assert.Equal("blocks", (await put.ReadAsync<StorageViewDto>()).View);
        await device.PutAsJsonAsync("/v1/settings/storage-view", new StorageViewDto("bars"), TestApi.Json);
        Assert.Equal("bars", (await (await device.GetAsync("/v1/settings/storage-view")).ReadAsync<StorageViewDto>()).View);
    }

    [Fact]
    public async Task Storage_view_rejects_unknown_values()
    {
        using var f = NewFactory();
        var (device, _) = await f.Register("a@example.com");

        var response = await device.PutAsJsonAsync("/v1/settings/storage-view", new StorageViewDto("pie"), TestApi.Json);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Contains("bad_value", await response.Content.ReadAsStringAsync());
    }
}
