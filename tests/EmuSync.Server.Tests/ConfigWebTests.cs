using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using EmuSync.Protocol;
using EmuSync.Server.Configs;
using EmuSync.Server.Data;
using EmuSync.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace EmuSync.Server.Tests;

public class ConfigWebTests
{
    private const string Email = "a@example.com";
    private const string Ini = "config/qt-config.ini";
    private const string OdinLink = "content://com.android.externalstorage.documents/tree/1111-ODIN%3AROMs";
    private const string ThorLink = "content://com.android.externalstorage.documents/tree/2222-THOR%3AROMs";

    private static ServerFactory NewFactory()
    {
        var factory = new ServerFactory();
        factory.Settings["EmuSync:AllowRegistration"] = "true";
        return factory;
    }

    private sealed record Setup(ServerFactory F, HttpClient Web, HttpClient Odin, Guid OdinId, HttpClient Thor, Guid ThorId);

    private static async Task<Setup> TwoDevices(ServerFactory f)
    {
        var (odin, odinKey) = await f.Register(Email, deviceName: "Odin");
        var (thor, thorKey) = await f.PairSecondDevice(odin, "Thor");
        await SetRoots(odin, "/storage/emulated/0", "/storage/1111-ODIN");
        await SetRoots(thor, "/storage/emulated/10", "/storage/2222-THOR");
        return new Setup(f, await f.WebLogin(Email), odin, odinKey.DeviceId, thor, thorKey.DeviceId);
    }

    private static async Task SetRoots(HttpClient device, string internalRoot, string sd)
    {
        var r = await device.PutAsJsonAsync("/v1/devices/me/status", new DeviceStatusRequest("android", "1.0", true,
            [new EmulatorStatus("eden", "dev.eden", "0.1", null)],
            new Dictionary<string, string> { ["{INTERNAL}"] = internalRoot, ["{SD}"] = sd }));
        Assert.True(r.IsSuccessStatusCode, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
    }

    private static Task<List<ManifestEntry>> Files(HttpClient device, params (string path, string text)[] files) =>
        Fixtures.Manifest(device, files.Select(x => (x.path, Encoding.UTF8.GetBytes(x.text))).ToArray());

    private static string EdenIni(string gameDir, string resolution = "1") =>
        "[UI]\n" + (gameDir.Length > 0 ? $"Paths\\gamedirs\\1\\path={gameDir}\n" : "") + "Paths\\gamedirs\\size=1\n" +
        $"[Renderer]\nresolution_setup={resolution}\n";

    private static async Task<ConfigUploadResponse> Upload(HttpClient device, IReadOnlyList<ManifestEntry> files, Guid? baseId)
    {
        var r = await device.PostJsonAsync("/v1/configs/upload", new ConfigUploadRequest("eden", baseId, files));
        Assert.True(r.StatusCode == HttpStatusCode.OK, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        return await r.ReadAsync<ConfigUploadResponse>();
    }

    private static async Task<IReadOnlyList<DeliveryDto>> Deliveries(HttpClient device)
    {
        var r = await device.GetAsync("/v1/deliveries");
        Assert.True(r.StatusCode == HttpStatusCode.OK);
        return (await r.ReadAsync<DeliveryList>()).Items.Where(d => d.Kind == DeliveryKind.Config).ToList();
    }

    private static async Task<string> BlobText(HttpClient client, string hash)
    {
        var r = await client.GetAsync($"/v1/blobs/{hash}");
        Assert.True(r.IsSuccessStatusCode, $"{r.StatusCode}");
        return Encoding.UTF8.GetString(await r.Content.ReadAsByteArrayAsync());
    }

    private static async Task AssertError(HttpResponseMessage r, HttpStatusCode status, string code)
    {
        Assert.Equal(status, r.StatusCode);
        Assert.Equal(code, (await r.ReadAsync<ErrorResponse>()).Code);
    }

    private static async Task<T> InDb<T>(ServerFactory factory, Func<EmuSyncDbContext, Task<T>> query)
    {
        using var scope = factory.Services.CreateScope();
        return await query(scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>());
    }

    private static async Task<ConfigDiffResponse> Diff(HttpClient web, Guid a, Guid b, Guid? device = null)
    {
        var r = await web.GetAsync($"/v1/configs/diff?a={a}&b={b}" + (device is null ? "" : $"&device={device}"));
        Assert.True(r.StatusCode == HttpStatusCode.OK, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        return await r.ReadAsync<ConfigDiffResponse>();
    }

    private static string Numbered(int count, int? changed = null) =>
        string.Concat(Enumerable.Range(1, count).Select(i => (i == changed ? "CHANGED" : $"line {i}") + "\n"));

    private static Task<HttpResponseMessage> Edit(HttpClient web, Guid bundleId, string path, Guid baseId, string text, Guid? device) =>
        web.PutAsJsonAsync($"/v1/configs/bundles/{bundleId}/files/{path}" + (device is null ? "" : $"?device={device}"),
            new EditConfigFileRequest(baseId, text), TestApi.Json);

    /// <summary>Group of Odin and Thor, both holding the group head (contents: content:// links stay per device).</summary>
    private static async Task<(SyncGroupDto Group, Guid Head)> Group(Setup s)
    {
        await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink))), null);
        await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink))), null);
        var g = await (await s.Web.PostJsonAsync("/v1/configs/groups", new CreateGroupRequest("eden", "Eden", s.OdinId)))
            .ReadAsync<SyncGroupDto>();
        (await s.Web.PostJsonAsync($"/v1/configs/groups/{g.Id}/members", new JoinGroupRequest(s.ThorId))).EnsureSuccessStatusCode();
        var d = Assert.Single(await Deliveries(s.Thor));
        (await s.Thor.PostJsonAsync($"/v1/deliveries/{d.Id}/ack", new AckRequest(DeliveryResult.Applied, null))).EnsureSuccessStatusCode();
        Assert.Empty(await Deliveries(s.Odin));
        return (g, g.HeadVersionId!.Value);
    }

    [Fact]
    public void Myers_diff_unit_cases()
    {
        Assert.Empty(ConfigDiff.Hunks("a\nb\n", "a\nb\n"));
        var h = Assert.Single(ConfigDiff.Hunks("", "x\ny\n"));
        Assert.Equal([new DiffLine('+', "x"), new DiffLine('+', "y")], h.Lines);
        Assert.Equal((1, 1), (h.OldStart, h.NewStart));
        // Line terminators are ignored when comparing lines; CRLF-only change has no hunks.
        Assert.Empty(ConfigDiff.Hunks("a\r\nb\r\n", "a\nb\n"));
        // Two far-apart edits give two hunks, close ones merge.
        var far = ConfigDiff.Hunks(Numbered(30), Numbered(30, 2).Replace("line 29", "X29"));
        Assert.Equal(2, far.Count);
        var near = ConfigDiff.Hunks(Numbered(30), Numbered(30, 5).Replace("line 9", "X9"));
        Assert.Single(near);
    }

    [Fact]
    public void Myers_diff_reconstructs_both_sides_of_small_random_files()
    {
        var rng = new Random(42);
        for (var i = 0; i < 500; i++)
        {
            string[] Make() => Enumerable.Range(0, rng.Next(0, 7)).Select(_ => ((char)('a' + rng.Next(3))).ToString()).ToArray();
            string[] a = Make(), b = Make();
            var hunks = ConfigDiff.Hunks(string.Concat(a.Select(l => l + "\n")), string.Concat(b.Select(l => l + "\n")));
            if (a.SequenceEqual(b)) { Assert.Empty(hunks); continue; }
            var h = Assert.Single(hunks); // at most 6 lines a side: always one hunk covering everything
            Assert.Equal(a, h.Lines.Where(l => l.Op != '+').Select(l => l.Text));
            Assert.Equal(b, h.Lines.Where(l => l.Op != '-').Select(l => l.Text));
            Assert.Equal((1, 1), (h.OldStart, h.NewStart));
        }
    }

    [Fact]
    public void Diff_large_input_falls_back_to_whole_file_hunk()
    {
        var old = string.Concat(Enumerable.Range(0, 30000).Select(i => $"old {i}\n"));
        var @new = string.Concat(Enumerable.Range(0, 30000).Select(i => $"new {i}\n"));

        var hunk = Assert.Single(ConfigDiff.Hunks(old, @new));

        Assert.Equal((1, 1), (hunk.OldStart, hunk.NewStart));
        Assert.Equal(60000, hunk.Lines.Count);
        Assert.All(hunk.Lines.Take(30000), l => Assert.Equal('-', l.Op));
        Assert.All(hunk.Lines.Skip(30000), l => Assert.Equal('+', l.Op));
    }

    [Fact]
    public void Diff_memory_is_bounded()
    {
        // 10k lines, every 10th line replaced: D = 2000 edits.
        string Make(bool changed) => string.Concat(Enumerable.Range(0, 10000)
            .Select(i => (changed && i % 10 == 0 ? $"changed {i}" : $"line {i}") + "\n"));
        var old = Make(false);
        var @new = Make(true);
        GC.Collect();
        var before = GC.GetAllocatedBytesForCurrentThread();

        var hunks = ConfigDiff.Hunks(old, @new);

        var allocated = GC.GetAllocatedBytesForCurrentThread() - before;
        Assert.True(allocated < 200_000_000, $"allocated {allocated} bytes");
        Assert.NotEmpty(hunks);
        Assert.Equal(1000, hunks.SelectMany(h => h.Lines).Count(l => l.Op == '-'));
    }

    [Fact]
    public async Task Diff_reports_changed_lines_with_context()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var v1 = await Upload(s.Odin, await Files(s.Odin, ("notes/a.txt", Numbered(20))), null);
        var v2 = await Upload(s.Odin, await Files(s.Odin, ("notes/a.txt", Numbered(20, 10))), v1.VersionId);

        var diff = await Diff(s.Web, v1.VersionId, v2.VersionId);

        var file = Assert.Single(diff.Files);
        Assert.Equal(("notes/a.txt", FileDiffStatus.Changed), (file.Path, file.Status));
        var hunk = Assert.Single(file.Hunks);
        Assert.Equal((7, 7), (hunk.OldStart, hunk.NewStart));
        Assert.Equal(
            ["  line 7", "  line 8", "  line 9", "- line 10", "+ CHANGED", "  line 11", "  line 12", "  line 13"],
            hunk.Lines.Select(l => $"{l.Op} {l.Text}"));
    }

    [Fact]
    public async Task Diff_added_and_removed_files()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var v1 = await Upload(s.Odin, await Files(s.Odin, ("a.txt", "one\ntwo\n"), ("b.txt", "same\n"), ("bin.dat", "\u0001")), null);
        var bin = new byte[] { 0xFF, 0xFE, 0x00, 0x80 };
        var bin2 = new byte[] { 0xFF, 0xFE, 0x00, 0x81 };
        var m1 = await Fixtures.Manifest(s.Odin, ("a.txt", Encoding.UTF8.GetBytes("one\ntwo\n")), ("b.txt", Encoding.UTF8.GetBytes("same\n")), ("bin.dat", bin));
        var m2 = await Fixtures.Manifest(s.Odin, ("b.txt", Encoding.UTF8.GetBytes("same\n")), ("c.txt", Encoding.UTF8.GetBytes("new\n")), ("bin.dat", bin2));
        var a = await Upload(s.Odin, m1, v1.VersionId);
        var b = await Upload(s.Odin, m2, a.VersionId);

        var diff = await Diff(s.Web, a.VersionId, b.VersionId);

        var byPath = diff.Files.ToDictionary(x => x.Path);
        Assert.Equal(FileDiffStatus.Removed, byPath["a.txt"].Status);
        Assert.Equal(["- one", "- two"], Assert.Single(byPath["a.txt"].Hunks).Lines.Select(l => $"{l.Op} {l.Text}"));
        Assert.Equal(FileDiffStatus.Added, byPath["c.txt"].Status);
        Assert.Equal(["+ new"], Assert.Single(byPath["c.txt"].Hunks).Lines.Select(l => $"{l.Op} {l.Text}"));
        Assert.Equal(FileDiffStatus.Same, byPath["b.txt"].Status);
        Assert.Empty(byPath["b.txt"].Hunks);
        Assert.Equal(FileDiffStatus.Changed, byPath["bin.dat"].Status);
        Assert.Empty(byPath["bin.dat"].Hunks);
        Assert.Equal(new[] { "a.txt", "b.txt", "bin.dat", "c.txt" }, diff.Files.Select(x => x.Path));
    }

    [Fact]
    public async Task Diff_for_device_uses_expanded_paths()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var v1 = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni("/storage/1111-ODIN/ROMs/a", "1"))), null);
        var v2 = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni("/storage/1111-ODIN/ROMs/a", "2"))), v1.VersionId);

        var canonical = Assert.Single((await Diff(s.Web, v1.VersionId, v2.VersionId)).Files).Hunks.Single();
        var forThor = Assert.Single((await Diff(s.Web, v1.VersionId, v2.VersionId, s.ThorId)).Files).Hunks.Single();
        var forOdin = Assert.Single((await Diff(s.Web, v1.VersionId, v2.VersionId, s.OdinId)).Files).Hunks.Single();

        Assert.Contains(canonical.Lines, l => l.Op == ' ' && l.Text == "Paths\\gamedirs\\1\\path={SD}/ROMs/a");
        Assert.Contains(forThor.Lines, l => l.Op == ' ' && l.Text == "Paths\\gamedirs\\1\\path=/storage/2222-THOR/ROMs/a");
        Assert.Contains(forOdin.Lines, l => l.Op == ' ' && l.Text == "Paths\\gamedirs\\1\\path=/storage/1111-ODIN/ROMs/a");
        Assert.Contains(forThor.Lines, l => l is { Op: '+', Text: "resolution_setup=2" });

        var text = await s.Web.GetAsync($"/v1/configs/versions/{v2.VersionId}/files/{Ini}?device={s.ThorId}");
        Assert.Contains("path=/storage/2222-THOR/ROMs/a", await text.Content.ReadAsStringAsync());
        var plain = await s.Web.GetAsync($"/v1/configs/versions/{v2.VersionId}/files/{Ini}");
        Assert.Contains("path={SD}/ROMs/a", await plain.Content.ReadAsStringAsync());
        await AssertError(await s.Web.GetAsync($"/v1/configs/versions/{v2.VersionId}/files/nope.ini"), HttpStatusCode.NotFound, "file_not_found");
        await AssertError(await s.Web.GetAsync($"/v1/configs/versions/{Guid.NewGuid()}/files/{Ini}"), HttpStatusCode.NotFound, "version_not_found");
    }

    [Fact]
    public async Task Web_edit_creates_version_and_delivers_to_group()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (group, head) = await Group(s);
        var thorView = await (await s.Web.GetAsync($"/v1/configs/versions/{head}/files/{Ini}?device={s.ThorId}")).Content.ReadAsStringAsync();
        Assert.Contains(ThorLink, thorView);
        var edited = thorView.Replace("resolution_setup=1", "resolution_setup=5") + "";

        var r = await Edit(s.Web, group.BundleId, Ini, head, edited, s.ThorId);

        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        var up = await r.ReadAsync<ConfigUploadResponse>();
        Assert.Equal(SaveUploadStatus.Accepted, up.Status);
        Assert.Equal(group.BundleId, up.BundleId);
        var version = await InDb(f, db => db.ConfigVersions.AsNoTracking().SingleAsync(v => v.Id == up.VersionId));
        Assert.Equal(ConfigSource.WebEdit, version.Source);
        Assert.Null(version.DeviceId);
        Assert.Equal(head, version.BaseVersionId);
        Assert.Equal(up.VersionId, (await InDb(f, db => db.ConfigBundles.AsNoTracking().SingleAsync(b => b.Id == group.BundleId))).HeadVersionId);
        var canon = JsonSerializer.Deserialize<List<ManifestEntry>>(version.ManifestJson, TestApi.Json)!;
        var canonText = await BlobText(s.Web, canon.Single(e => e.Path == Ini).Hash);
        Assert.Contains("{LOCAL}", canonText);
        Assert.Contains("resolution_setup=5", canonText);

        // Every member is sent it, including the device whose view was edited; each keeps its own local value.
        var odin = Assert.Single(await Deliveries(s.Odin));
        var thor = Assert.Single(await Deliveries(s.Thor));
        Assert.Equal(up.VersionId, odin.VersionId);
        Assert.Equal(up.VersionId, thor.VersionId);
        var odinText = await BlobText(s.Odin, odin.Manifest.Single(e => e.Path == Ini).Hash);
        Assert.Contains(OdinLink, odinText);
        Assert.Contains("resolution_setup=5", odinText);
        Assert.Contains(ThorLink, await BlobText(s.Thor, thor.Manifest.Single(e => e.Path == Ini).Hash));

        // Same text again: nothing changes.
        var again = await (await Edit(s.Web, group.BundleId, Ini, up.VersionId, edited, s.ThorId)).ReadAsync<ConfigUploadResponse>();
        Assert.Equal(SaveUploadStatus.Unchanged, again.Status);
        Assert.Equal(up.VersionId, again.VersionId);

        var versions = await (await s.Web.GetAsync($"/v1/configs/bundles/{group.BundleId}/versions")).ReadAsync<List<ConfigVersionSummary>>();
        Assert.Equal(new[] { 2, 1 }, versions.Select(v => v.Seq));
        Assert.Equal(ConfigSource.WebEdit, versions[0].Source);
        Assert.Null(versions[0].DeviceName);
        Assert.Equal("Odin", versions[^1].DeviceName);
        var kinds = (await (await s.Web.GetAsync("/v1/activity")).ReadAsync<ActivityPage>()).Items.Select(i => i.Kind);
        Assert.Contains("config_uploaded", kinds);
    }

    [Fact]
    public async Task Web_edit_validates_device_and_size()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (group, head) = await Group(s);
        await AssertError(await Edit(s.Web, group.BundleId, Ini, head, "x", null), HttpStatusCode.BadRequest, "device_required");
        var (loki, lokiKey) = await f.PairSecondDevice(s.Odin, "Loki");
        await AssertError(await Edit(s.Web, group.BundleId, Ini, head, "x", lokiKey.DeviceId), HttpStatusCode.BadRequest, "not_member");
        await AssertError(await Edit(s.Web, group.BundleId, "missing.ini", head, "x", s.OdinId), HttpStatusCode.NotFound, "file_not_found");
        await AssertError(await Edit(s.Web, group.BundleId, Ini, head, new string('x', 4 * 1024 * 1024 + 1), s.OdinId),
            HttpStatusCode.BadRequest, "too_large");
        await AssertError(await Edit(s.Web, Guid.NewGuid(), Ini, head, "x", s.OdinId), HttpStatusCode.NotFound, "bundle_not_found");

        // A solo bundle defaults to its owner.
        var solo = await Upload(loki, await Files(loki, (Ini, EdenIni(""))), null);
        var ok = await Edit(s.Web, solo.BundleId, Ini, solo.VersionId, EdenIni("", "9"), null);
        Assert.Equal(HttpStatusCode.OK, ok.StatusCode);
        await AssertError(await Edit(s.Web, solo.BundleId, Ini, solo.VersionId, "y", s.OdinId), HttpStatusCode.BadRequest, "not_member");
    }

    [Fact]
    public async Task Web_edit_on_stale_base_is_409()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (group, head) = await Group(s);
        var change = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, "3"))), head);
        Assert.Equal(SaveUploadStatus.Accepted, change.Status);

        var r = await Edit(s.Web, group.BundleId, Ini, head, EdenIni(ThorLink, "7"), s.ThorId);

        await AssertError(r, HttpStatusCode.Conflict, "stale_base");
        Assert.Equal(change.VersionId, (await InDb(f, db => db.ConfigBundles.AsNoTracking().SingleAsync(b => b.Id == group.BundleId))).HeadVersionId);
    }
}
