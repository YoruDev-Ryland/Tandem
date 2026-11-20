using System.IO.Compression;
using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using EmuSync.Protocol;
using EmuSync.Server.Data;
using EmuSync.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace EmuSync.Server.Tests;

public class WebApiTests
{
    private const string Email = "a@example.com";

    private static ServerFactory NewFactory()
    {
        var factory = new ServerFactory();
        factory.Settings["EmuSync:AllowRegistration"] = "true";
        return factory;
    }

    private static Task<HttpResponseMessage> Status(HttpClient device, params EmulatorStatus[] emulators) =>
        device.PutAsJsonAsync("/v1/devices/me/status", new DeviceStatusRequest("android", "1.0", true, emulators,
            new Dictionary<string, string> { ["{INTERNAL}"] = "/storage/emulated/0" }));

    private static async Task<SaveUploadResponse> Save(HttpClient device, string content, Guid? baseId, string game = "G1",
        string emulator = "eden", ItemKind kind = ItemKind.Save, string slot = "", string core = "", string? name = null,
        string path = "data.dat")
    {
        var manifest = await Fixtures.Manifest(device, (path, Encoding.UTF8.GetBytes(content)));
        var r = await device.PostJsonAsync("/v1/saves/upload", new SaveUploadRequest(emulator, kind, game, slot, core, baseId, manifest, name));
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        return await r.ReadAsync<SaveUploadResponse>();
    }

    private static async Task<T> InDb<T>(ServerFactory f, Func<EmuSyncDbContext, Task<T>> q)
    {
        using var scope = f.Services.CreateScope();
        return await q(scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>());
    }

    private static async Task<ZipArchive> ReadZip(HttpResponseMessage r)
    {
        var ms = new MemoryStream(await r.Content.ReadAsByteArrayAsync());
        return new ZipArchive(ms);
    }

    private static async Task<T> Get<T>(HttpClient c, string url)
    {
        var r = await c.GetAsync(url);
        Assert.True(r.IsSuccessStatusCode, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        return await r.ReadAsync<T>();
    }

    [Fact]
    public async Task Home_continue_orders_by_newest_head()
    {
        using var f = NewFactory();
        var (odin, _) = await f.Register(Email, deviceName: "Odin");
        var web = await f.WebLogin(Email);
        await Save(odin, "a", null, "OLD", name: "Old game");
        f.Time.Advance(TimeSpan.FromMinutes(5));
        await Save(odin, "b", null, "NEW", name: "New game");

        var home = await Get<HomeResponse>(web, "/v1/web/home");

        Assert.Equal(["NEW", "OLD"], home.Continue.Select(c => c.GameKey));
        Assert.Equal("Odin", home.Continue[0].NewestDeviceName);
        Assert.Equal("New game", home.Continue[0].DisplayName);
        Assert.Single(home.Devices);
        Assert.NotEmpty(home.Recent);
    }

    [Fact]
    public async Task Home_attention_counts_conflicts_and_shizuku_off()
    {
        using var f = NewFactory();
        var (odin, odinKey) = await f.Register(Email, deviceName: "Odin");
        var (thor, _) = await f.PairSecondDevice(odin, "Thor");
        var web = await f.WebLogin(Email);
        Assert.True((await odin.PutAsJsonAsync("/v1/devices/me/status", new DeviceStatusRequest("android", "1.0", false, [], new Dictionary<string, string>()))).IsSuccessStatusCode);

        var first = await Save(odin, "v1", null, "G1");
        await Save(odin, "v2", first.VersionId, "G1");
        var concurrent = await Save(thor, "v3", first.VersionId, "G1");   // based on an old head
        Assert.Equal(SaveUploadStatus.Conflict, concurrent.Status);
        var firstSync = await Save(thor, "other", null, "G2");            // thor's first sync of G2 ...
        await Save(odin, "x", null, "G2");                                 // ... odin got there first
        var fs = await Save(thor, "y", null, "G2");
        Assert.Equal(SaveUploadStatus.Conflict, fs.Status);
        _ = firstSync;

        var home = await Get<HomeResponse>(web, "/v1/web/home");

        Assert.True(home.Attention.OpenConflicts >= 1);
        Assert.True(home.Attention.FirstSyncReviews >= 1);
        Assert.Equal([odinKey.DeviceId], home.Attention.ShizukuOffDevices);
        Assert.Equal(0, home.Attention.FailedDeliveries24h);
    }

    [Fact]
    public async Task Games_merges_installs_and_saves()
    {
        using var f = NewFactory();
        var (odin, odinKey) = await f.Register(Email, deviceName: "Odin");
        var web = await f.WebLogin(Email);
        await odin.PutAsJsonAsync("/v1/devices/me/installs/eden", new InstallsRequest([new InstalledGame("INSTALLED_ONLY", "Installed Only", null), new InstalledGame("BOTH", null, null)]));
        await Save(odin, "a", null, "BOTH", name: "Both Game");
        await Save(odin, "a", null, "SAVE_ONLY");
        await Save(odin, "s", null, "BOTH", kind: ItemKind.State, slot: "1", core: "x");

        var games = await Get<GameRow[]>(web, "/v1/web/games");

        Assert.Equal(3, games.Length);
        var both = games.Single(g => g.GameKey == "BOTH");
        Assert.Equal([odinKey.DeviceId], both.InstalledOn);
        Assert.NotNull(both.SaveItemId);
        Assert.Equal(1, both.StateCount);
        Assert.Equal(odinKey.DeviceId, both.NewestOnDeviceId);
        Assert.Empty(games.Single(g => g.GameKey == "SAVE_ONLY").InstalledOn);
        var installed = games.Single(g => g.GameKey == "INSTALLED_ONLY");
        Assert.Null(installed.SaveItemId);
        Assert.Equal("Installed Only", installed.DisplayName);

        Assert.Single(await Get<GameRow[]>(web, "/v1/web/games?q=installed%20only"));
        Assert.Empty(await Get<GameRow[]>(web, "/v1/web/games?emulator=dolphin"));
    }

    [Fact]
    public async Task Game_detail_lists_versions_with_head_flag()
    {
        using var f = NewFactory();
        var (odin, _) = await f.Register(Email, deviceName: "Odin");
        var web = await f.WebLogin(Email);
        var v1 = await Save(odin, "a", null);
        var v2 = await Save(odin, "b", v1.VersionId);

        var detail = await Get<GameDetail>(web, "/v1/web/games/eden/G1");

        var item = Assert.Single(detail.Items);
        Assert.Equal(v2.VersionId, item.HeadVersionId);
        Assert.Equal([2, 1], item.Versions.Select(v => v.Seq));
        Assert.Equal([true, false], item.Versions.Select(v => v.IsHead));
        Assert.Equal("Odin", item.Versions[0].DeviceName);
        Assert.Equal(HttpStatusCode.NotFound, (await web.GetAsync("/v1/web/games/eden/NOPE")).StatusCode);
    }

    [Fact]
    public async Task Game_detail_handles_gamekey_with_slash()
    {
        using var f = NewFactory();
        var (odin, _) = await f.Register(Email, deviceName: "Odin");
        var web = await f.WebLogin(Email);
        await Save(odin, "a", null, "Pokemon - Emerald/sub", emulator: "retroarch");

        var raw = await Get<GameDetail>(web, "/v1/web/games/retroarch/Pokemon%20-%20Emerald/sub");
        var encoded = await Get<GameDetail>(web, "/v1/web/games/retroarch/Pokemon%20-%20Emerald%2Fsub");

        Assert.Equal("Pokemon - Emerald/sub", raw.Row.GameKey);
        Assert.Equal("Pokemon - Emerald/sub", encoded.Row.GameKey);
    }

    [Fact]
    public async Task Download_single_file_streams_bytes()
    {
        using var f = NewFactory();
        var (odin, _) = await f.Register(Email);
        var web = await f.WebLogin(Email);
        var v = await Save(odin, "hello save", null, path: "dir/slot0.sav");

        var r = await web.GetAsync($"/v1/saves/versions/{v.VersionId}/download");

        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal("hello save", await r.Content.ReadAsStringAsync());
        Assert.Equal("slot0.sav", r.Content.Headers.ContentDisposition!.FileNameStar ?? r.Content.Headers.ContentDisposition.FileName);
        Assert.Equal(HttpStatusCode.NotFound, (await web.GetAsync($"/v1/saves/versions/{Guid.NewGuid()}/download")).StatusCode);
    }

    [Fact]
    public async Task Download_multi_file_is_a_zip()
    {
        using var f = NewFactory();
        var (odin, _) = await f.Register(Email);
        var web = await f.WebLogin(Email);
        var manifest = await Fixtures.Manifest(odin, ("a.sav", "A"u8.ToArray()), ("sub/b.sav", "B"u8.ToArray()));
        var up = await (await odin.PostJsonAsync("/v1/saves/upload", new SaveUploadRequest("eden", ItemKind.Save, "G1", "", "", null, manifest, null))).ReadAsync<SaveUploadResponse>();

        var r = await web.GetAsync($"/v1/saves/versions/{up.VersionId}/download");

        using var zip = await ReadZip(r);
        Assert.Equal(["a.sav", "sub/b.sav"], zip.Entries.Select(e => e.FullName).Order());
    }

    [Fact]
    public async Task Export_zip_contains_head_saves()
    {
        using var f = NewFactory();
        var (odin, _) = await f.Register(Email);
        var web = await f.WebLogin(Email);
        var a = await Save(odin, "old", null, "G1");
        await Save(odin, "new", a.VersionId, "G1");
        await Save(odin, "ra", null, "G2", emulator: "retroarch");

        var r = await web.GetAsync("/v1/export.zip?emulator=eden");

        using var zip = await ReadZip(r);
        var entry = Assert.Single(zip.Entries);
        Assert.Equal("eden/G1/save/default/data.dat", entry.FullName);
        using var reader = new StreamReader(entry.Open());
        Assert.Equal("new", await reader.ReadToEndAsync());

        using var all = await ReadZip(await web.GetAsync("/v1/export.zip"));
        Assert.Equal(2, all.Entries.Count);
    }

    [Fact]
    public async Task Export_zip_streams_and_sanitizes_paths()
    {
        using var f = NewFactory();
        var (odin, _) = await f.Register(Email);
        var web = await f.WebLogin(Email);
        await Save(odin, "x", null, "../evil:name*?", emulator: "retroarch", slot: "a|b");

        var r = await web.GetAsync("/v1/export.zip", HttpCompletionOption.ResponseHeadersRead);

        Assert.Equal("application/zip", r.Content.Headers.ContentType!.MediaType);
        using var zip = await ReadZip(r);
        var name = Assert.Single(zip.Entries).FullName;
        Assert.DoesNotContain("..", name);
        Assert.DoesNotContain(":", name);
        Assert.DoesNotContain("*", name);
        Assert.DoesNotContain("|", name);
        Assert.StartsWith("retroarch/", name);
        Assert.EndsWith("/data.dat", name);
    }

    private static MultipartFormDataContent Form(string game, params (string path, string text)[] files)
    {
        var form = new MultipartFormDataContent
        {
            { new StringContent("eden"), "emulatorId" }, { new StringContent("Save"), "kind" },
            { new StringContent(game), "gameKey" }, { new StringContent(""), "slot" }, { new StringContent(""), "core" },
        };
        foreach (var (path, text) in files)
            form.Add(new ByteArrayContent(Encoding.UTF8.GetBytes(text)), "files", path);
        return form;
    }

    [Fact]
    public async Task Web_upload_creates_new_head()
    {
        using var f = NewFactory();
        var (odin, odinKey) = await f.Register(Email);
        var web = await f.WebLogin(Email);
        var first = await Save(odin, "old", null, "G1");

        var r = await web.PostAsync("/v1/web/saves/upload", Form("G1", ("data.dat", "from pc"), ("sub/x.bin", "x")));

        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        var up = await r.ReadAsync<SaveUploadResponse>();
        Assert.Equal(SaveUploadStatus.Accepted, up.Status);
        Assert.NotEqual(first.VersionId, up.VersionId);
        var (head, device, seq, baseId) = await InDb(f, async db =>
        {
            var v = await db.SaveVersions.SingleAsync(x => x.Id == up.VersionId);
            return (await db.SaveItems.Where(i => i.Id == up.ItemId).Select(i => i.HeadVersionId).SingleAsync(), v.DeviceId, v.Seq, v.BaseVersionId);
        });
        Assert.Equal(up.VersionId, head);
        Assert.Null(device);
        Assert.Equal(2, seq);
        Assert.Equal(first.VersionId, baseId);
        var dl = await web.GetAsync($"/v1/saves/versions/{up.VersionId}/download");
        using var zip = await ReadZip(dl);
        Assert.Equal(["data.dat", "sub/x.bin"], zip.Entries.Select(e => e.FullName).Order());
        _ = odinKey;
    }

    [Fact]
    public async Task Web_upload_validates_input()
    {
        using var f = NewFactory();
        await f.Register(Email);
        var web = await f.WebLogin(Email);

        Assert.Equal(HttpStatusCode.BadRequest, (await web.PostAsync("/v1/web/saves/upload", Form("G1"))).StatusCode);
        var bad = Form("G1", ("data.dat", "x"));
        var r = await web.PostAsync("/v1/web/saves/upload", bad);
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        var traversal = await web.PostAsync("/v1/web/saves/upload", Form("G2", ("../x", "x")));
        Assert.Equal(HttpStatusCode.BadRequest, traversal.StatusCode);
    }

    [Fact]
    public async Task Web_upload_on_open_conflict_is_409()
    {
        using var f = NewFactory();
        var (odin, _) = await f.Register(Email, deviceName: "Odin");
        var (thor, _) = await f.PairSecondDevice(odin, "Thor");
        var web = await f.WebLogin(Email);
        var v1 = await Save(odin, "a", null);
        await Save(odin, "b", v1.VersionId);
        var c = await Save(thor, "c", v1.VersionId);
        Assert.Equal(SaveUploadStatus.Conflict, c.Status);

        var r = await web.PostAsync("/v1/web/saves/upload", Form("G1", ("data.dat", "pc")));

        Assert.Equal(HttpStatusCode.Conflict, r.StatusCode);
        Assert.Equal("conflict_open", (await r.ReadAsync<ErrorResponse>()).Code);
    }

    [Fact]
    public async Task Web_upload_rejects_file_over_max_blob_bytes()
    {
        using var f = NewFactory();
        f.Settings["EmuSync:MaxBlobBytes"] = "10";
        await f.Register(Email);
        var web = await f.WebLogin(Email);

        var r = await web.PostAsync("/v1/web/saves/upload", Form("G1", ("data.dat", "this is longer than ten bytes")));

        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, r.StatusCode);
    }

    [Fact]
    public async Task Spa_fallback_serves_index_html()
    {
        using var f = NewFactory();
        var root = Directory.CreateTempSubdirectory("emusync-web-").FullName;
        try
        {
            File.WriteAllText(Path.Combine(root, "index.html"), "<html>SPA-INDEX</html>");
            Directory.CreateDirectory(Path.Combine(root, "assets"));
            File.WriteAllText(Path.Combine(root, "assets", "app.js"), "console.log(1)");
            f.Settings["EmuSync:WebRoot"] = root;
            var client = f.CreateClient(); // no protocol header: static web files are exempt

            Assert.Contains("SPA-INDEX", await client.GetStringAsync("/"));
            Assert.Contains("SPA-INDEX", await client.GetStringAsync("/games/eden/abc"));
            Assert.Equal("console.log(1)", await client.GetStringAsync("/assets/app.js"));
            Assert.Equal("ok", await client.GetStringAsync("/healthz"));
            Assert.Equal(HttpStatusCode.UpgradeRequired, (await client.GetAsync("/v1/web/home")).StatusCode);
            Assert.Equal(HttpStatusCode.NotFound, (await f.Client().GetAsync("/v1/nope")).StatusCode);
        }
        finally { Directory.Delete(root, true); }
    }

    [Fact]
    public async Task Missing_web_root_serves_not_built_page()
    {
        using var f = NewFactory();
        f.Settings["EmuSync:WebRoot"] = Path.Combine(f.DataDirectory, "nope");

        var body = await f.CreateClient().GetStringAsync("/");

        Assert.Contains("EmuSync server is running. The web app is not built yet.", body);
    }

    [Fact]
    public async Task Device_detail_and_config_overview()
    {
        using var f = NewFactory();
        var (odin, odinKey) = await f.Register(Email, deviceName: "Odin");
        var web = await f.WebLogin(Email);
        await Status(odin, new EmulatorStatus("retroarch", "com.retroarch", "1.0", ["mgba"]));
        await odin.PutAsJsonAsync("/v1/devices/me/installs/retroarch", new InstallsRequest([new InstalledGame("A", null, null)]));
        await Save(odin, "abc", null, "A", emulator: "retroarch");

        var d = await Get<DeviceDetail>(web, $"/v1/web/devices/{odinKey.DeviceId}");

        Assert.Equal("Odin", d.Summary.Name);
        Assert.Equal(["mgba"], d.Emulators.Single().Cores);
        Assert.Equal("/storage/emulated/0", d.Roots["{INTERNAL}"]);
        Assert.Equal(1, d.Installs.Single().Games);
        Assert.Equal(3, d.StorageBytes);
        Assert.Equal(HttpStatusCode.NotFound, (await web.GetAsync($"/v1/web/devices/{Guid.NewGuid()}")).StatusCode);
        Assert.NotNull(await Get<ConfigOverview>(web, "/v1/web/configs"));
    }

    [Fact]
    public async Task Setup_checklist_lists_missing_emulators_cores_and_folders()
    {
        using var f = NewFactory();
        var (thor, _) = await f.Register(Email, deviceName: "Thor");
        var (odin, odinKey) = await f.PairSecondDevice(thor, "Odin");
        var web = await f.WebLogin(Email);
        await Status(thor, new EmulatorStatus("retroarch", "com.retroarch", "1", ["mgba", "melonds"]), new EmulatorStatus("eden", "dev.eden", "1", null));
        await Status(odin, new EmulatorStatus("retroarch", "com.retroarch", "1", ["MGBA"]));
        var saved = await Save(thor, "s", null, "G1", emulator: "retroarch");

        // A config delivery that needed a folder the new device has not granted, and a save still waiting.
        await InDb(f, async db =>
        {
            var bundle = new ConfigBundle { Id = Guid.NewGuid(), UserId = (await db.Devices.FindAsync(odinKey.DeviceId))!.UserId, EmulatorId = "retroarch", OwnerDeviceId = odinKey.DeviceId };
            db.ConfigBundles.Add(bundle);
            db.Deliveries.Add(new Delivery
            {
                Id = Guid.NewGuid(), DeviceId = odinKey.DeviceId, Kind = DeliveryKind.Save, TargetId = saved.ItemId, VersionId = saved.VersionId,
                CreatedUtc = f.Time.GetUtcNow(),
            });
            db.Deliveries.Add(new Delivery
            {
                Id = Guid.NewGuid(), DeviceId = odinKey.DeviceId, Kind = DeliveryKind.Config, TargetId = bundle.Id, VersionId = Guid.NewGuid(),
                CreatedUtc = f.Time.GetUtcNow(), NotesJson = """{"manifest":[],"missingLocalKeys":["Paths/gamedirs/1/path"]}""",
            });
            await db.SaveChangesAsync();
            return 0;
        });

        var setup = await Get<SetupChecklist>(web, $"/v1/web/devices/{odinKey.DeviceId}/setup");

        var eden = setup.Steps.Single(s => s is { Kind: SetupStepKind.InstallEmulator, EmulatorId: "eden" });
        Assert.False(eden.Done);
        Assert.True(setup.Steps.Single(s => s is { Kind: SetupStepKind.InstallEmulator, EmulatorId: "retroarch" }).Done);
        var cores = setup.Steps.Single(s => s.Kind == SetupStepKind.InstallCores);
        Assert.Equal("retroarch", cores.EmulatorId);
        Assert.Equal("melonds", cores.Detail);
        Assert.False(cores.Done);
        Assert.Contains(setup.Steps, s => s is { Kind: SetupStepKind.RestoreSaves, EmulatorId: "retroarch", Done: false });
        var grant = setup.Steps.Single(s => s.Kind == SetupStepKind.GrantFolders);
        Assert.Equal("Paths/gamedirs/1/path", grant.Detail);
        Assert.False(grant.Done);
    }

    [Fact]
    public async Task Setup_keep_own_marks_choose_config_done()
    {
        using var f = NewFactory();
        var (thor, thorKey) = await f.Register(Email, deviceName: "Thor");
        var (odin, odinKey) = await f.PairSecondDevice(thor, "Odin");
        var web = await f.WebLogin(Email);
        await Status(thor, new EmulatorStatus("eden", "dev.eden", "1", null));
        await Status(odin, new EmulatorStatus("eden", "dev.eden", "1", null));
        var files = await Fixtures.Manifest(thor, ("config/qt-config.ini", "[UI]\nx=1\n"u8.ToArray()));
        Assert.True((await thor.PostJsonAsync("/v1/configs/upload", new ConfigUploadRequest("eden", null, files))).IsSuccessStatusCode);
        var url = $"/v1/web/devices/{odinKey.DeviceId}/setup";

        var before = await Get<SetupChecklist>(web, url);
        Assert.Contains(before.Steps, s => s is { Kind: SetupStepKind.ChooseConfig, EmulatorId: "eden", Done: false });

        Assert.Equal(HttpStatusCode.NoContent, (await web.PostAsync($"{url}/keep-own/eden", null)).StatusCode);

        var after = await Get<SetupChecklist>(web, url);
        Assert.Contains(after.Steps, s => s is { Kind: SetupStepKind.ChooseConfig, EmulatorId: "eden", Done: true });
        Assert.Equal(HttpStatusCode.BadRequest, (await web.PostAsync($"{url}/keep-own/nope", null)).StatusCode);
        _ = thorKey;
    }

    private static string[] TempUploads(ServerFactory f)
    {
        var dir = Path.Combine(f.DataDirectory, "tmp");
        return Directory.Exists(dir) ? Directory.GetFiles(dir, "upload-*") : [];
    }

    [Fact]
    public async Task Web_upload_over_total_cap_is_413_and_leaves_no_temp_files()
    {
        using var f = NewFactory();
        f.Settings["EmuSync:MaxBlobBytes"] = "100";
        f.Settings["EmuSync:MaxUploadBytes"] = "25";
        await f.Register(Email);
        var web = await f.WebLogin(Email);

        var r = await web.PostAsync("/v1/web/saves/upload", Form("G1", ("a", "0123456789"), ("b", "0123456789"), ("c", "0123456789")));

        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, r.StatusCode);
        Assert.Empty(TempUploads(f));
        Assert.Equal(HttpStatusCode.OK, (await web.PostAsync("/v1/web/saves/upload", Form("G1", ("a", "0123456789"), ("b", "0123456789")))).StatusCode);
        Assert.Empty(TempUploads(f));
    }

    [Fact]
    public async Task Startup_sweeps_stale_upload_temp_files()
    {
        using var f = NewFactory();
        var dir = Directory.CreateDirectory(Path.Combine(f.DataDirectory, "tmp")).FullName;
        var stale = Path.Combine(dir, "upload-stale");
        var fresh = Path.Combine(dir, "upload-fresh");
        File.WriteAllText(stale, "x");
        File.WriteAllText(fresh, "x");
        File.SetLastWriteTimeUtc(stale, DateTime.UtcNow.AddHours(-2));

        f.CreateClient(); // builds the host

        Assert.False(File.Exists(stale));
        Assert.True(File.Exists(fresh));
    }

    [Fact]
    public async Task Export_skips_missing_blobs_and_lists_them()
    {
        using var f = NewFactory();
        var (odin, _) = await f.Register(Email);
        var web = await f.WebLogin(Email);
        await Save(odin, "keep", null, "G1");
        await Save(odin, "lost", null, "G2");
        var lostHash = Fixtures.Sha256("lost"u8.ToArray());
        File.Delete(Directory.GetFiles(f.DataDirectory, lostHash, SearchOption.AllDirectories).Single());

        var r = await web.GetAsync("/v1/export.zip");

        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        using var zip = await ReadZip(r);
        Assert.Equal(["MISSING.txt", "eden/G1/save/default/data.dat"], zip.Entries.Select(e => e.FullName).Order(StringComparer.Ordinal));
        using var reader = new StreamReader(zip.GetEntry("MISSING.txt")!.Open());
        Assert.Equal($"eden/G2/save/default/data.dat  {lostHash}\n", await reader.ReadToEndAsync());
    }

    [Fact]
    public async Task Second_user_sees_nothing_of_the_first()
    {
        using var f = NewFactory();
        var (odin, odinKey) = await f.Register(Email, deviceName: "Odin");
        await Status(odin, new EmulatorStatus("eden", "dev.eden", "1", null));
        await odin.PutAsJsonAsync("/v1/devices/me/installs/eden", new InstallsRequest([new InstalledGame("G1", "Game", null)]));
        var v = await Save(odin, "secret", null, "G1");
        await f.Register("b@example.com", deviceName: "Bdev");
        var b = await f.WebLogin("b@example.com");

        var home = await Get<HomeResponse>(b, "/v1/web/home");
        Assert.Empty(home.Continue);
        Assert.Equal(["Bdev"], home.Devices.Select(d => d.Name));
        Assert.DoesNotContain(home.Recent, a => a.GameKey == "G1");
        Assert.Empty(await Get<GameRow[]>(b, "/v1/web/games"));
        Assert.Equal(HttpStatusCode.NotFound, (await b.GetAsync("/v1/web/games/eden/G1")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await b.GetAsync($"/v1/web/devices/{odinKey.DeviceId}")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await b.GetAsync($"/v1/web/devices/{odinKey.DeviceId}/setup")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await b.PostAsync($"/v1/web/devices/{odinKey.DeviceId}/setup/keep-own/eden", null)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await b.GetAsync($"/v1/saves/versions/{v.VersionId}/download")).StatusCode);
        using var zip = await ReadZip(await b.GetAsync("/v1/export.zip"));
        Assert.Empty(zip.Entries);
        Assert.Empty((await Get<ConfigOverview>(b, "/v1/web/configs")).Emulators);
    }

    [Fact]
    public async Task Revoked_device_is_excluded_everywhere()
    {
        using var f = NewFactory();
        var (thor, thorKey) = await f.Register(Email, deviceName: "Thor");
        var (odin, odinKey) = await f.PairSecondDevice(thor, "Odin");
        var web = await f.WebLogin(Email);
        await Status(thor, new EmulatorStatus("retroarch", "com.retroarch", "1", ["mgba"]));
        await Status(odin, new EmulatorStatus("eden", "dev.eden", "1", null));
        await thor.PutAsJsonAsync("/v1/devices/me/installs/retroarch", new InstallsRequest([new InstalledGame("G1", null, null)]));
        await odin.PutAsJsonAsync("/v1/devices/me/installs/eden", new InstallsRequest([new InstalledGame("E1", null, null)]));
        var files = await Fixtures.Manifest(odin, ("config/qt-config.ini", "[UI]\nx=1\n"u8.ToArray()));
        Assert.True((await odin.PostJsonAsync("/v1/configs/upload", new ConfigUploadRequest("eden", null, files))).IsSuccessStatusCode);
        var (third, thirdKey) = await f.PairSecondDevice(thor, "Third");
        await Status(third, new EmulatorStatus("eden", "dev.eden", "1", null));

        Assert.Equal(HttpStatusCode.NoContent, (await web.DeleteAsync($"/v1/devices/{odinKey.DeviceId}")).StatusCode);

        var home = await Get<HomeResponse>(web, "/v1/web/home");
        Assert.DoesNotContain(home.Devices, d => d.Id == odinKey.DeviceId);
        var games = await Get<GameRow[]>(web, "/v1/web/games");
        Assert.DoesNotContain(games, g => g.InstalledOn.Contains(odinKey.DeviceId));
        Assert.DoesNotContain(games, g => g.GameKey == "E1");
        Assert.Equal(HttpStatusCode.NotFound, (await web.GetAsync($"/v1/web/devices/{odinKey.DeviceId}")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await web.GetAsync($"/v1/web/devices/{odinKey.DeviceId}/setup")).StatusCode);
        var overview = await Get<ConfigOverview>(web, "/v1/web/configs");
        Assert.DoesNotContain(overview.Emulators.SelectMany(e => e.Solo), s => s.Device.Id == odinKey.DeviceId);
        // Third's checklist no longer asks for Odin's emulator or its config versions.
        var setup = await Get<SetupChecklist>(web, $"/v1/web/devices/{thirdKey.DeviceId}/setup");
        Assert.DoesNotContain(setup.Steps, s => s.Kind == SetupStepKind.ChooseConfig);
        Assert.Contains(setup.Steps, s => s is { Kind: SetupStepKind.InstallEmulator, EmulatorId: "retroarch" });
        _ = thorKey;
    }
}
