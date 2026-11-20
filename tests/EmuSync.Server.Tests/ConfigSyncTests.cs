using System.Net;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using EmuSync.Protocol;
using EmuSync.Server.Data;
using EmuSync.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Testcontainers.PostgreSql;

namespace EmuSync.Server.Tests;

public partial class ConfigSyncTests
{
    private const string Email = "a@example.com";
    private const string Ini = "config/qt-config.ini";
    private const string GameDirKey = "UI/Paths\\gamedirs\\1\\path";
    private const string OdinLink = "content://com.android.externalstorage.documents/tree/1111-ODIN%3AROMs";
    private const string ThorLink = "content://com.android.externalstorage.documents/tree/2222-THOR%3AROMs";

    private static ServerFactory NewFactory()
    {
        var factory = new ServerFactory();
        factory.Settings["EmuSync:AllowRegistration"] = "true";
        return factory;
    }

    private sealed record Setup(ServerFactory F, HttpClient Web, HttpClient Odin, Guid OdinId, HttpClient Thor, Guid ThorId);

    /// <summary>Odin registers, Thor pairs; each reports its own roots.</summary>
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

    private static string EdenIni(string gameDir, string resolution = "1", string? screenshots = null) =>
        "[UI]\n" + (gameDir.Length > 0 ? $"Paths\\gamedirs\\1\\path={gameDir}\n" : "") + "Paths\\gamedirs\\size=1\n" +
        (screenshots is null ? "" : $"Paths\\screenshotPath={screenshots}\n") +
        $"[Renderer]\nresolution_setup={resolution}\n";

    private static Task<HttpResponseMessage> UploadRaw(HttpClient device, IReadOnlyList<ManifestEntry> files, Guid? baseId,
        string emulator = "eden") =>
        device.PostJsonAsync("/v1/configs/upload", new ConfigUploadRequest(emulator, baseId, files));

    private static async Task<ConfigUploadResponse> Upload(HttpClient device, IReadOnlyList<ManifestEntry> files, Guid? baseId)
    {
        var r = await UploadRaw(device, files, baseId);
        Assert.True(r.StatusCode == HttpStatusCode.OK, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        return await r.ReadAsync<ConfigUploadResponse>();
    }

    private static async Task<SyncGroupDto> CreateGroup(HttpClient web, Guid seed, string name = "Eden handhelds")
    {
        var r = await web.PostJsonAsync("/v1/configs/groups", new CreateGroupRequest("eden", name, seed));
        Assert.True(r.StatusCode == HttpStatusCode.OK, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        return await r.ReadAsync<SyncGroupDto>();
    }

    private static Task<HttpResponseMessage> JoinRaw(HttpClient web, Guid groupId, Guid device) =>
        web.PostJsonAsync($"/v1/configs/groups/{groupId}/members", new JoinGroupRequest(device));

    private static async Task Join(HttpClient web, Guid groupId, Guid device)
    {
        var r = await JoinRaw(web, groupId, device);
        Assert.True(r.IsSuccessStatusCode, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
    }

    private static async Task<IReadOnlyList<DeliveryDto>> Deliveries(HttpClient device)
    {
        var r = await device.GetAsync("/v1/deliveries");
        Assert.True(r.StatusCode == HttpStatusCode.OK, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        return (await r.ReadAsync<DeliveryList>()).Items.Where(d => d.Kind == DeliveryKind.Config).ToList();
    }

    private static Task<HttpResponseMessage> Ack(HttpClient device, Guid id, DeliveryResult result = DeliveryResult.Applied) =>
        device.PostJsonAsync($"/v1/deliveries/{id}/ack", new AckRequest(result, null));

    private static async Task AckOnly(HttpClient device)
    {
        var d = Assert.Single(await Deliveries(device));
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(device, d.Id)).StatusCode);
    }

    private static async Task<string> BlobText(HttpClient client, string hash)
    {
        var r = await client.GetAsync($"/v1/blobs/{hash}");
        Assert.True(r.IsSuccessStatusCode, $"{r.StatusCode}");
        return Encoding.UTF8.GetString(await r.Content.ReadAsByteArrayAsync());
    }

    private static Task<string> FileText(HttpClient client, IReadOnlyList<ManifestEntry> manifest, string path) =>
        BlobText(client, manifest.Single(e => e.Path == path).Hash);

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

    private static Task<DeviceConfig> Config(ServerFactory f, Guid deviceId) =>
        InDb(f, db => db.DeviceConfigs.AsNoTracking().SingleAsync(c => c.DeviceId == deviceId && c.EmulatorId == "eden"));

    private static Task<ConfigBundle> Bundle(ServerFactory f, Guid id) =>
        InDb(f, db => db.ConfigBundles.AsNoTracking().SingleAsync(b => b.Id == id));

    private static Task<ConfigVersion> Version(ServerFactory f, Guid id) =>
        InDb(f, db => db.ConfigVersions.AsNoTracking().SingleAsync(v => v.Id == id));

    private static async Task<List<Delivery>> OpenConfigRows(ServerFactory f) =>
        await InDb(f, db => db.Deliveries.AsNoTracking().Where(d => d.Kind == DeliveryKind.Config && d.AckedUtc == null).ToListAsync());

    /// <summary>Both devices upload solo configs, Odin seeds a group, Thor joins and applies the group head.</summary>
    private static async Task<(SyncGroupDto Group, ConfigUploadResponse OdinSolo, ConfigUploadResponse ThorSolo)> Linked(Setup s,
        string odinIni, string thorIni)
    {
        var odinSolo = await Upload(s.Odin, await Files(s.Odin, (Ini, odinIni)), null);
        var thorSolo = await Upload(s.Thor, await Files(s.Thor, (Ini, thorIni)), null);
        var group = await CreateGroup(s.Web, s.OdinId);
        await Join(s.Web, group.Id, s.ThorId);
        await AckOnly(s.Thor);
        Assert.Empty(await Deliveries(s.Odin));
        return (group, odinSolo, thorSolo);
    }

    [Fact]
    public async Task Solo_upload_creates_device_bundle_and_no_deliveries()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var files = await Files(s.Thor, (Ini, EdenIni(ThorLink)));

        var up = await Upload(s.Thor, files, null);

        Assert.Equal(SaveUploadStatus.Accepted, up.Status);
        var bundle = await Bundle(f, up.BundleId);
        Assert.Equal(s.ThorId, bundle.OwnerDeviceId);
        Assert.Null(bundle.GroupId);
        Assert.Equal(up.VersionId, bundle.HeadVersionId);
        var version = await Version(f, up.VersionId);
        Assert.Equal(1, version.Seq);
        Assert.Equal(ConfigSource.Device, version.Source);
        Assert.Equal(s.ThorId, version.DeviceId);
        var config = await Config(f, s.ThorId);
        Assert.Equal(up.BundleId, config.BundleId);
        Assert.Equal(up.VersionId, config.AppliedVersionId);
        var raw = Assert.Single(await InDb(f, db => db.RawConfigUploads.AsNoTracking().ToListAsync()));
        Assert.Equal(s.ThorId, raw.DeviceId);
        Assert.Equal(files, JsonSerializer.Deserialize<List<ManifestEntry>>(raw.ManifestJson, TestApi.Json));
        Assert.Empty(await Deliveries(s.Thor));
        Assert.Empty(await Deliveries(s.Odin));
        Assert.Empty(await OpenConfigRows(f));

        // The same files again: unchanged, and only the latest raw upload is kept.
        var again = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink))), up.VersionId);
        Assert.Equal(SaveUploadStatus.Unchanged, again.Status);
        Assert.Equal(up.VersionId, again.VersionId);
        Assert.Single(await InDb(f, db => db.RawConfigUploads.AsNoTracking().ToListAsync()));
    }

    [Fact]
    public async Task Group_change_on_one_member_delivers_to_other_members()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (loki, lokiKey) = await f.PairSecondDevice(s.Odin, "Loki");
        await Upload(loki, await Files(loki, (Ini, EdenIni(""))), null);
        var (group, _, _) = await Linked(s, EdenIni(OdinLink), EdenIni(ThorLink));
        var listed = Assert.Single((await (await s.Web.GetAsync("/v1/configs/groups")).ReadAsync<SyncGroupList>()).Items);
        Assert.Equal(new[] { s.OdinId, s.ThorId }.Order(), listed.MemberDeviceIds.Order());
        Assert.Equal(new[] { s.OdinId }, group.MemberDeviceIds);

        var change = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "3"))), group.HeadVersionId);

        Assert.Equal(SaveUploadStatus.Accepted, change.Status);
        Assert.Equal(group.BundleId, change.BundleId);
        Assert.Empty(await Deliveries(s.Thor));
        Assert.Empty(await Deliveries(loki));
        var d = Assert.Single(await Deliveries(s.Odin));
        Assert.Equal(DeliveryKind.Config, d.Kind);
        Assert.Equal(group.BundleId, d.TargetId);
        Assert.Equal(change.VersionId, d.VersionId);
        Assert.Equal(group.HeadVersionId, d.KnownVersionId);
        Assert.Equal("eden", d.EmulatorId);
        Assert.Null(d.GameKey);
        Assert.Null(d.ItemKind);
        Assert.Null(d.Slot);
        Assert.Null(d.Core);
        Assert.Empty(d.MissingLocalKeys!);
        Assert.Contains("resolution_setup=3", await FileText(s.Odin, d.Manifest, Ini));

        Assert.Equal(HttpStatusCode.NoContent, (await Ack(s.Odin, d.Id)).StatusCode);
        Assert.Equal(change.VersionId, (await Config(f, s.OdinId)).AppliedVersionId);
        Assert.Empty(await Deliveries(s.Odin));
        Assert.Equal(lokiKey.DeviceId, (await Bundle(f, (await Config(f, lokiKey.DeviceId)).BundleId)).OwnerDeviceId);
    }

    [Fact]
    public async Task Rom_folder_content_uri_kept_per_device()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (group, _, _) = await Linked(s, EdenIni(OdinLink), EdenIni(ThorLink));

        var change = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "2"))), group.HeadVersionId);

        var canonical = await FileText(s.Web, Manifest(await Version(f, change.VersionId)), Ini);
        Assert.Contains("Paths\\gamedirs\\1\\path={LOCAL}\n", canonical);
        Assert.DoesNotContain("THOR", canonical);
        var d = Assert.Single(await Deliveries(s.Odin));
        var expanded = await FileText(s.Odin, d.Manifest, Ini);
        Assert.Equal(EdenIni(OdinLink, resolution: "2"), expanded);
    }

    [Fact]
    public async Task Path_under_internal_root_is_rewritten_for_target()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (group, _, _) = await Linked(s, EdenIni(OdinLink, screenshots: "/storage/emulated/0/Pictures/Eden"),
            EdenIni(ThorLink, screenshots: "/storage/emulated/10/Pictures/Eden"));

        var change = await Upload(s.Thor, await Files(s.Thor,
            (Ini, EdenIni(ThorLink, resolution: "2", screenshots: "/storage/emulated/10/Pictures/Eden2"))), group.HeadVersionId);

        var canonical = await FileText(s.Web, Manifest(await Version(f, change.VersionId)), Ini);
        Assert.Contains("Paths\\screenshotPath={INTERNAL}/Pictures/Eden2\n", canonical);
        var d = Assert.Single(await Deliveries(s.Odin));
        Assert.Equal(EdenIni(OdinLink, resolution: "2", screenshots: "/storage/emulated/0/Pictures/Eden2"),
            await FileText(s.Odin, d.Manifest, Ini));
    }

    [Fact]
    public async Task Missing_local_value_is_empty_and_reported()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink))), null);
        await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(""))), null); // Thor never set a game folder
        var group = await CreateGroup(s.Web, s.OdinId);

        await Join(s.Web, group.Id, s.ThorId);

        var d = Assert.Single(await Deliveries(s.Thor));
        Assert.Equal(new[] { $"{Ini}#{GameDirKey}" }, d.MissingLocalKeys);
        Assert.Contains("Paths\\gamedirs\\1\\path=\n", await FileText(s.Thor, d.Manifest, Ini));
        var row = Assert.Single(await OpenConfigRows(f));
        using var notes = JsonDocument.Parse(row.NotesJson!);
        Assert.Equal($"{Ini}#{GameDirKey}", notes.RootElement.GetProperty("missingLocalKeys")[0].GetString());
        Assert.Equal(d.Manifest.Count, notes.RootElement.GetProperty("manifest").GetArrayLength());
    }

    [Fact]
    public async Task Reupload_of_missing_local_value_keeps_other_members_values()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink))), null);
        await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(""))), null);
        var group = await CreateGroup(s.Web, s.OdinId);
        await Join(s.Web, group.Id, s.ThorId);
        var d = Assert.Single(await Deliveries(s.Thor));
        await Ack(s.Thor, d.Id);

        // Thor's emulator writes back what it was given (the empty game folder) plus a real change.
        var applied = (await FileText(s.Thor, d.Manifest, Ini)).Replace("resolution_setup=1", "resolution_setup=4");
        var change = await Upload(s.Thor, await Files(s.Thor, (Ini, applied)), d.VersionId);

        Assert.Equal(SaveUploadStatus.Accepted, change.Status);
        var odin = Assert.Single(await Deliveries(s.Odin));
        Assert.Equal(EdenIni(OdinLink, resolution: "4"), await FileText(s.Odin, odin.Manifest, Ini));
    }

    [Fact]
    public async Task RetroArch_quoted_values_keep_their_quotes()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        const string cfg = "config/retroarch.cfg";
        static string Ra(string sd, string link, string scale) =>
            $"rgui_browser_directory = \"{sd}/ROMs\"\ncontent_directory = \"{link}\"\nvideo_scale = \"{scale}\"\n";
        await Upload(s.Odin, await Files(s.Odin, (cfg, Ra("/storage/1111-ODIN", OdinLink, "1"))), null);
        await Upload(s.Thor, await Files(s.Thor, (cfg, Ra("/storage/2222-THOR", ThorLink, "3"))), null);
        var group = await CreateGroup(s.Web, s.ThorId);

        await Join(s.Web, group.Id, s.OdinId);

        Assert.Equal("rgui_browser_directory = \"{SD}/ROMs\"\ncontent_directory = \"{LOCAL}\"\nvideo_scale = \"3\"\n",
            await FileText(s.Web, Manifest(await Version(f, group.HeadVersionId!.Value)), cfg));
        var d = Assert.Single(await Deliveries(s.Odin));
        Assert.Equal(Ra("/storage/1111-ODIN", OdinLink, "3"), await FileText(s.Odin, d.Manifest, cfg));
    }

    [Fact]
    public async Task Join_group_preserves_previous_solo_version()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var thorSolo = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "9"))), null);
        await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink))), null);
        var group = await CreateGroup(s.Web, s.OdinId);

        await Join(s.Web, group.Id, s.ThorId);

        var solo = await Bundle(f, thorSolo.BundleId);
        Assert.Equal(s.ThorId, solo.OwnerDeviceId);
        Assert.Equal(thorSolo.VersionId, solo.HeadVersionId);
        Assert.Contains("resolution_setup=9", await FileText(s.Web, Manifest(await Version(f, thorSolo.VersionId)), Ini));
        var config = await Config(f, s.ThorId);
        Assert.Equal(group.BundleId, config.BundleId);
        Assert.Equal(thorSolo.VersionId, config.AppliedVersionId);
        var d = Assert.Single(await Deliveries(s.Thor));
        Assert.Equal(group.HeadVersionId, d.VersionId);
        Assert.Equal(thorSolo.VersionId, d.KnownVersionId);
        Assert.Equal(EdenIni(ThorLink), await FileText(s.Thor, d.Manifest, Ini));

        var groupVersion = await Version(f, group.HeadVersionId!.Value);
        Assert.Equal(ConfigSource.Join, groupVersion.Source);
        Assert.Equal(1, groupVersion.Seq);
        Assert.Equal(group.BundleId, (await Config(f, s.OdinId)).BundleId);
        Assert.Equal(group.HeadVersionId, (await Config(f, s.OdinId)).AppliedVersionId);
        Assert.Empty(await Deliveries(s.Odin));

        await AssertError(await JoinRaw(s.Web, Guid.NewGuid(), s.ThorId), HttpStatusCode.NotFound, "group_not_found");
        var other = await CreateGroupRaw(s.Web, s.ThorId);
        await AssertError(other, HttpStatusCode.Conflict, "already_in_group");
    }

    private static Task<HttpResponseMessage> CreateGroupRaw(HttpClient web, Guid seed, string emulator = "eden") =>
        web.PostJsonAsync("/v1/configs/groups", new CreateGroupRequest(emulator, "Another", seed));

    [Fact]
    public async Task Leave_group_keeps_files_and_creates_solo_bundle()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (group, _, thorSolo) = await Linked(s, EdenIni(OdinLink), EdenIni(ThorLink));

        var r = await s.Web.DeleteAsync($"/v1/configs/groups/{group.Id}/members/{s.ThorId}");

        Assert.Equal(HttpStatusCode.NoContent, r.StatusCode);
        var config = await Config(f, s.ThorId);
        Assert.NotEqual(group.BundleId, config.BundleId);
        Assert.NotEqual(thorSolo.BundleId, config.BundleId);
        var solo = await Bundle(f, config.BundleId);
        Assert.Equal(s.ThorId, solo.OwnerDeviceId);
        Assert.Null(solo.GroupId);
        Assert.Equal(solo.HeadVersionId, config.AppliedVersionId);
        var seeded = await Version(f, solo.HeadVersionId!.Value);
        Assert.Equal(ConfigSource.Copy, seeded.Source);
        Assert.Equal((await Version(f, group.HeadVersionId!.Value)).ManifestHash, seeded.ManifestHash);
        Assert.Empty(await Deliveries(s.Thor));
        var groups = await (await s.Web.GetAsync("/v1/configs/groups")).ReadAsync<SyncGroupList>();
        Assert.Equal(new[] { s.OdinId }, Assert.Single(groups.Items).MemberDeviceIds);

        await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink, resolution: "5"))), group.HeadVersionId);
        Assert.Empty(await Deliveries(s.Thor));
        await AssertError(await s.Web.DeleteAsync($"/v1/configs/groups/{group.Id}/members/{s.ThorId}"),
            HttpStatusCode.NotFound, "not_member");
    }

    [Fact]
    public async Task Delete_group_only_when_empty()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (group, _, _) = await Linked(s, EdenIni(OdinLink), EdenIni(ThorLink));

        await AssertError(await s.Web.DeleteAsync($"/v1/configs/groups/{group.Id}"), HttpStatusCode.Conflict, "group_not_empty");
        (await s.Web.DeleteAsync($"/v1/configs/groups/{group.Id}/members/{s.ThorId}")).EnsureSuccessStatusCode();
        (await s.Web.DeleteAsync($"/v1/configs/groups/{group.Id}/members/{s.OdinId}")).EnsureSuccessStatusCode();

        Assert.Equal(HttpStatusCode.NoContent, (await s.Web.DeleteAsync($"/v1/configs/groups/{group.Id}")).StatusCode);
        Assert.Empty(await InDb(f, db => db.SyncGroups.ToListAsync()));
        Assert.False(await InDb(f, db => db.ConfigBundles.AnyAsync(b => b.Id == group.BundleId)));
        Assert.False(await InDb(f, db => db.ConfigVersions.AnyAsync(v => v.BundleId == group.BundleId)));
        Assert.Empty(await Deliveries(s.Odin));
        Assert.Empty(await Deliveries(s.Thor));
    }

    [Fact]
    public async Task Apply_Copy_does_not_link()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var thor = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "7"))), null);
        var odin = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink))), null);

        var r = await s.Web.PostJsonAsync("/v1/configs/apply", new ApplyConfigRequest(s.OdinId, "eden", thor.VersionId, ApplyMode.Copy));

        Assert.True(r.StatusCode == HttpStatusCode.OK, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        var applied = await r.ReadAsync<ApplyConfigResponse>();
        Assert.Equal(odin.BundleId, applied.BundleId);
        Assert.Null(applied.GroupId);
        Assert.Empty(await InDb(f, db => db.SyncGroups.ToListAsync()));
        var copy = await Version(f, applied.HeadVersionId);
        Assert.Equal(ConfigSource.Copy, copy.Source);
        Assert.Equal(odin.VersionId, copy.BaseVersionId);
        Assert.Equal(2, copy.Seq);
        Assert.Equal((await Version(f, thor.VersionId)).ManifestHash, copy.ManifestHash);
        Assert.Equal(odin.BundleId, (await Config(f, s.OdinId)).BundleId);
        var d = Assert.Single(await Deliveries(s.Odin));
        Assert.Equal(EdenIni(OdinLink, resolution: "7"), await FileText(s.Odin, d.Manifest, Ini));
        await Ack(s.Odin, d.Id);

        await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "8"))), thor.VersionId);
        Assert.Empty(await Deliveries(s.Odin));
        Assert.Empty(await Deliveries(s.Thor));
    }

    [Fact]
    public async Task Apply_Copy_to_target_without_config_is_rejected()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var thor = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink))), null);

        var r = await s.Web.PostJsonAsync("/v1/configs/apply", new ApplyConfigRequest(s.OdinId, "eden", thor.VersionId, ApplyMode.Copy));

        Assert.Equal(HttpStatusCode.BadRequest, r.StatusCode);
        var error = await r.ReadAsync<ErrorResponse>();
        Assert.Equal("no_config", error.Code);
        Assert.Equal("Open eden once on that device so its config is backed up first.", error.Message);
        Assert.False(await InDb(f, db => db.DeviceConfigs.AnyAsync(c => c.DeviceId == s.OdinId)));
        Assert.False(await InDb(f, db => db.ConfigBundles.AnyAsync(b => b.OwnerDeviceId == s.OdinId)));
        Assert.Empty(await Deliveries(s.Odin));
    }

    [Fact]
    public async Task Path_under_SD_delivered_to_device_without_SD_uses_own_value_or_reports_missing()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (loki, lokiKey) = await f.PairSecondDevice(s.Odin, "Loki");
        var (heim, heimKey) = await f.PairSecondDevice(s.Odin, "Heim");
        foreach (var device in new[] { loki, heim }) // no {SD} root on either
        {
            var r = await device.PutAsJsonAsync("/v1/devices/me/status", new DeviceStatusRequest("android", "1.0", true,
                [new EmulatorStatus("eden", "dev.eden", "0.1", null)],
                new Dictionary<string, string> { ["{INTERNAL}"] = "/storage/emulated/0" }));
            r.EnsureSuccessStatusCode();
        }
        await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, screenshots: "/storage/2222-THOR/Shots"))), null);
        await Upload(loki, await Files(loki, (Ini, EdenIni("", screenshots: "/storage/emulated/0/LokiShots"))), null);
        await Upload(heim, await Files(heim, (Ini, EdenIni(""))), null);
        var group = await CreateGroup(s.Web, s.ThorId);
        Assert.Contains("Paths\\screenshotPath={SD}/Shots\n",
            await FileText(s.Web, Manifest(await Version(f, group.HeadVersionId!.Value)), Ini));

        await Join(s.Web, group.Id, lokiKey.DeviceId);
        await Join(s.Web, group.Id, heimKey.DeviceId);

        var toLoki = Assert.Single(await Deliveries(loki));
        Assert.Contains("Paths\\screenshotPath=/storage/emulated/0/LokiShots\n", await FileText(loki, toLoki.Manifest, Ini));
        Assert.Equal(new[] { $"{Ini}#{GameDirKey}" }, toLoki.MissingLocalKeys);
        var toHeim = Assert.Single(await Deliveries(heim));
        Assert.Contains("Paths\\screenshotPath=\n", await FileText(heim, toHeim.Manifest, Ini));
        Assert.Equal(new[] { $"{Ini}#{GameDirKey}", $"{Ini}#UI/Paths\\screenshotPath" }.Order(), toHeim.MissingLocalKeys!.Order());
    }

    [Fact]
    public async Task Apply_Linked_from_solo_device_creates_group()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var thor = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "6"))), null);
        await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink))), null);

        var r = await s.Web.PostJsonAsync("/v1/configs/apply", new ApplyConfigRequest(s.OdinId, "eden", thor.VersionId, ApplyMode.Linked));

        Assert.True(r.StatusCode == HttpStatusCode.OK, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        var applied = await r.ReadAsync<ApplyConfigResponse>();
        var group = Assert.Single(await InDb(f, db => db.SyncGroups.AsNoTracking().ToListAsync()));
        Assert.Equal(group.Id, applied.GroupId);
        Assert.Equal("eden group", group.Name);
        Assert.Equal(group.BundleId, applied.BundleId);
        Assert.Equal(group.BundleId, (await Config(f, s.ThorId)).BundleId);
        Assert.Equal(group.BundleId, (await Config(f, s.OdinId)).BundleId);
        Assert.Empty(await Deliveries(s.Thor));
        var d = Assert.Single(await Deliveries(s.Odin));
        Assert.Equal(applied.HeadVersionId, d.VersionId);
        Assert.Equal(EdenIni(OdinLink, resolution: "6"), await FileText(s.Odin, d.Manifest, Ini));

        // A second Linked apply onto a group version joins the existing group.
        var (loki, lokiKey) = await f.PairSecondDevice(s.Odin, "Loki");
        await Upload(loki, await Files(loki, (Ini, EdenIni(""))), null);
        var again = await (await s.Web.PostJsonAsync("/v1/configs/apply",
            new ApplyConfigRequest(lokiKey.DeviceId, "eden", applied.HeadVersionId, ApplyMode.Linked))).ReadAsync<ApplyConfigResponse>();
        Assert.Equal(group.Id, again.GroupId);
        Assert.Single(await InDb(f, db => db.SyncGroups.ToListAsync()));
        Assert.Single(await Deliveries(loki));
    }

    [Fact]
    public async Task Apply_validates_source_and_emulator()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var thor = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink))), null);
        var (other, _) = await f.Register("b@example.com");
        var stranger = await Upload(other, await Files(other, (Ini, EdenIni(""))), null);

        await AssertError(await s.Web.PostJsonAsync("/v1/configs/apply",
            new ApplyConfigRequest(s.OdinId, "dolphin", thor.VersionId, ApplyMode.Copy)), HttpStatusCode.BadRequest, "wrong_emulator");
        await AssertError(await s.Web.PostJsonAsync("/v1/configs/apply",
            new ApplyConfigRequest(s.OdinId, "nope", thor.VersionId, ApplyMode.Copy)), HttpStatusCode.BadRequest, "unknown_emulator");
        await AssertError(await s.Web.PostJsonAsync("/v1/configs/apply",
            new ApplyConfigRequest(s.OdinId, "eden", stranger.VersionId, ApplyMode.Copy)), HttpStatusCode.NotFound, "version_not_found");
        await AssertError(await s.Web.PostJsonAsync("/v1/configs/apply",
            new ApplyConfigRequest(s.OdinId, "eden", thor.VersionId, ApplyMode.Linked)), HttpStatusCode.BadRequest, "no_config");
    }

    [Fact]
    public async Task Opaque_file_passes_through_byte_identical()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        byte[] opaque = [.. Encoding.UTF8.GetBytes("path=/storage/emulated/10/x\n"), 0xFF, 0xFE, 0x00, 0x01];
        var badIni = (byte[])[.. Encoding.UTF8.GetBytes("[UI]\nscreenshotPath=/storage/emulated/10/x\n"), 0xC3];
        await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink))), null);
        var thorFiles = await Fixtures.Manifest(s.Thor, ("config/input/pad.bin", opaque), ("config/broken.ini", badIni),
            (Ini, Encoding.UTF8.GetBytes(EdenIni(ThorLink))));
        var thor = await Upload(s.Thor, thorFiles, null);
        var group = await CreateGroup(s.Web, s.ThorId);

        await Join(s.Web, group.Id, s.OdinId);

        var canonical = Manifest(await Version(f, thor.VersionId));
        var d = Assert.Single(await Deliveries(s.Odin));
        foreach (var path in new[] { "config/input/pad.bin", "config/broken.ini" })
        {
            var uploaded = thorFiles.Single(e => e.Path == path);
            Assert.Equal(uploaded, canonical.Single(e => e.Path == path));
            Assert.Equal(uploaded, d.Manifest.Single(e => e.Path == path));
        }
        var bytes = await (await s.Odin.GetAsync($"/v1/blobs/{d.Manifest.Single(e => e.Path == "config/input/pad.bin").Hash}"))
            .Content.ReadAsByteArrayAsync();
        Assert.Equal(opaque, bytes);
    }

    [Fact]
    public async Task Concurrent_group_edits_conflict_and_hold_deliveries()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (group, _, _) = await Linked(s, EdenIni(OdinLink), EdenIni(ThorLink));
        var thorChange = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "2"))), group.HeadVersionId);
        Assert.Single(await Deliveries(s.Odin));

        // Odin changed its settings before applying Thor's change.
        var odinChange = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink, resolution: "3"))), group.HeadVersionId);

        Assert.Equal(SaveUploadStatus.Conflict, odinChange.Status);
        var bundle = await Bundle(f, group.BundleId);
        Assert.True(bundle.OpenConflict);
        Assert.Equal(thorChange.VersionId, bundle.HeadVersionId);
        Assert.Equal(ConflictReason.Concurrent, (await Version(f, odinChange.VersionId)).ConflictReason);
        Assert.Empty(await Deliveries(s.Odin));
        Assert.Empty(await Deliveries(s.Thor));
        Assert.Empty(await OpenConfigRows(f));
        // A retry of the same upload gives the same answer and no new version.
        var retry = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink, resolution: "3"))), group.HeadVersionId);
        Assert.Equal(odinChange.VersionId, retry.VersionId);

        await AssertError(await s.Web.PostJsonAsync($"/v1/configs/bundles/{group.BundleId}/conflicts/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepBoth, odinChange.VersionId)), HttpStatusCode.BadRequest, "not_supported");
        var resolve = await s.Web.PostJsonAsync($"/v1/configs/bundles/{group.BundleId}/conflicts/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepVersion, odinChange.VersionId));

        Assert.Equal(HttpStatusCode.NoContent, resolve.StatusCode);
        bundle = await Bundle(f, group.BundleId);
        Assert.False(bundle.OpenConflict);
        Assert.Equal(odinChange.VersionId, bundle.HeadVersionId);
        Assert.True((await Version(f, odinChange.VersionId)).Resolved);
        Assert.Equal(odinChange.VersionId, (await Config(f, s.OdinId)).AppliedVersionId);
        Assert.Empty(await Deliveries(s.Odin));
        var d = Assert.Single(await Deliveries(s.Thor));
        Assert.Equal(odinChange.VersionId, d.VersionId);
        Assert.Equal(EdenIni(ThorLink, resolution: "3"), await FileText(s.Thor, d.Manifest, Ini));
        await AssertError(await s.Web.PostJsonAsync($"/v1/configs/bundles/{group.BundleId}/conflicts/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepVersion, odinChange.VersionId)), HttpStatusCode.Conflict, "no_conflict");
    }

    [Fact]
    public async Task Config_upload_identical_to_resolved_version_is_Superseded()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (group, _, _) = await Linked(s, EdenIni(OdinLink), EdenIni(ThorLink));
        var thorChange = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "2"))), group.HeadVersionId);
        var odinChange = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink, resolution: "3"))), group.HeadVersionId);
        Assert.Equal(SaveUploadStatus.Conflict, odinChange.Status);
        (await s.Web.PostJsonAsync($"/v1/configs/bundles/{group.BundleId}/conflicts/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepVersion, thorChange.VersionId))).EnsureSuccessStatusCode();
        var versions = await InDb(f, db => db.ConfigVersions.CountAsync(v => v.BundleId == group.BundleId));

        // Odin, about to apply Thor's head, first uploads its local (resolved, losing) copy on the old base.
        var again = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink, resolution: "3"))), group.HeadVersionId);

        Assert.Equal(SaveUploadStatus.Superseded, again.Status);
        Assert.Equal(odinChange.VersionId, again.VersionId);
        Assert.Equal(versions, await InDb(f, db => db.ConfigVersions.CountAsync(v => v.BundleId == group.BundleId)));
        var bundle = await Bundle(f, group.BundleId);
        Assert.False(bundle.OpenConflict);
        Assert.Equal(thorChange.VersionId, bundle.HeadVersionId);
        Assert.Equal(odinChange.VersionId, (await Config(f, s.OdinId)).AppliedVersionId);
        var d = Assert.Single(await Deliveries(s.Odin));
        Assert.Equal(thorChange.VersionId, d.VersionId);
        Assert.Empty(await Deliveries(s.Thor));

        Assert.Equal(HttpStatusCode.NoContent, (await Ack(s.Odin, d.Id)).StatusCode);
        Assert.Equal(thorChange.VersionId, (await Config(f, s.OdinId)).AppliedVersionId);
        Assert.Empty(await Deliveries(s.Odin));
        Assert.Empty(await OpenConfigRows(f));
    }

    [Fact]
    public async Task First_sync_upload_against_existing_head_conflicts()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink))), null);
        await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink))), null);
        var group = await CreateGroup(s.Web, s.OdinId);
        await Join(s.Web, group.Id, s.ThorId);
        // Thor has not applied the group head yet (the server does not record it as holding the head) and has no
        // base: a first-sync conflict. (A device the server records as holding the head is treated as based on it.)
        Assert.NotEqual(group.HeadVersionId, (await Config(f, s.ThorId)).AppliedVersionId);

        var r = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "4"))), null);

        Assert.Equal(SaveUploadStatus.Conflict, r.Status);
        Assert.Equal(ConflictReason.FirstSync, (await Version(f, r.VersionId)).ConflictReason);
        Assert.Equal(group.HeadVersionId, (await Bundle(f, group.BundleId)).HeadVersionId);
    }

    // Device-realistic follow-ups (I-2): the device uploads with the version id it actually holds.

    [Fact]
    public async Task Seed_device_change_after_group_creation_is_Accepted()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (group, odinSolo, _) = await Linked(s, EdenIni(OdinLink), EdenIni(ThorLink));

        // Odin's local base is still its solo version id: nobody told it about the group's first version.
        var change = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink, resolution: "3"))), odinSolo.VersionId);

        Assert.Equal(SaveUploadStatus.Accepted, change.Status);
        Assert.Equal(group.BundleId, change.BundleId);
        var bundle = await Bundle(f, group.BundleId);
        Assert.False(bundle.OpenConflict);
        Assert.Equal(change.VersionId, bundle.HeadVersionId);
        Assert.Null((await Version(f, change.VersionId)).ConflictReason);
        Assert.Equal(group.HeadVersionId, (await Version(f, change.VersionId)).BaseVersionId);
        Assert.Equal(change.VersionId, Assert.Single(await Deliveries(s.Thor)).VersionId);
    }

    [Fact]
    public async Task Leaver_change_on_new_solo_bundle_is_Accepted()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (group, _, _) = await Linked(s, EdenIni(OdinLink), EdenIni(ThorLink)); // Thor applied the group head
        (await s.Web.DeleteAsync($"/v1/configs/groups/{group.Id}/members/{s.ThorId}")).EnsureSuccessStatusCode();
        var solo = (await Config(f, s.ThorId)).BundleId;

        var change = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "4"))), group.HeadVersionId);

        Assert.Equal(SaveUploadStatus.Accepted, change.Status);
        Assert.Equal(solo, change.BundleId);
        var bundle = await Bundle(f, solo);
        Assert.False(bundle.OpenConflict);
        Assert.Equal(change.VersionId, bundle.HeadVersionId);
    }

    [Fact]
    public async Task Config_resolve_winner_next_change_on_its_real_base_is_Accepted()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (group, odinSolo, _) = await Linked(s, EdenIni(OdinLink), EdenIni(ThorLink));
        var thorChange = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "2"))), group.HeadVersionId);
        // Odin changed locally before applying Thor's change; its base is its solo version id.
        var odinChange = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink, resolution: "3"))), odinSolo.VersionId);
        Assert.Equal(SaveUploadStatus.Conflict, odinChange.Status);
        Assert.Equal(thorChange.VersionId, (await Bundle(f, group.BundleId)).HeadVersionId);
        (await s.Web.PostJsonAsync($"/v1/configs/bundles/{group.BundleId}/conflicts/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepVersion, odinChange.VersionId))).EnsureSuccessStatusCode();

        var next = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink, resolution: "5"))), odinSolo.VersionId);

        Assert.Equal(SaveUploadStatus.Accepted, next.Status);
        var bundle = await Bundle(f, group.BundleId);
        Assert.False(bundle.OpenConflict);
        Assert.Equal(next.VersionId, bundle.HeadVersionId);
    }

    [Fact]
    public async Task Config_device_whose_applied_is_not_head_uploading_old_base_still_conflicts()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (group, odinSolo, _) = await Linked(s, EdenIni(OdinLink), EdenIni(ThorLink));
        await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "2"))), group.HeadVersionId);
        Assert.Equal(group.HeadVersionId, (await Config(f, s.OdinId)).AppliedVersionId); // Odin has not applied Thor's change

        var odinChange = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink, resolution: "3"))), odinSolo.VersionId);

        Assert.Equal(SaveUploadStatus.Conflict, odinChange.Status);
        Assert.Equal(ConflictReason.Concurrent, (await Version(f, odinChange.VersionId)).ConflictReason);
        Assert.True((await Bundle(f, group.BundleId)).OpenConflict);
    }

    [Fact]
    public async Task Late_applied_config_ack_records_applied()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink))), null);
        await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink))), null);
        var group = await CreateGroup(s.Web, s.OdinId);
        await Join(s.Web, group.Id, s.ThorId);
        var first = Assert.Single(await Deliveries(s.Thor));
        var v2 = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink, resolution: "2"))), group.HeadVersionId);
        var second = Assert.Single(await Deliveries(s.Thor));
        Assert.Equal(v2.VersionId, second.VersionId);

        // Thor applied the first delivery before it was superseded; its ack arrives late.
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(s.Thor, first.Id)).StatusCode);

        Assert.Equal(group.HeadVersionId, (await Config(f, s.ThorId)).AppliedVersionId);
        var still = Assert.Single(await Deliveries(s.Thor));
        Assert.Equal(second.Id, still.Id);
        Assert.Equal(group.HeadVersionId, still.KnownVersionId);

        Assert.Equal(HttpStatusCode.NoContent, (await Ack(s.Thor, second.Id)).StatusCode);
        Assert.Equal(v2.VersionId, (await Config(f, s.ThorId)).AppliedVersionId);
        // A repeated late ack of the older version never moves applied backwards.
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(s.Thor, first.Id)).StatusCode);
        Assert.Equal(v2.VersionId, (await Config(f, s.ThorId)).AppliedVersionId);
        Assert.Empty(await Deliveries(s.Thor));
    }

    [Fact]
    public async Task Revoked_member_gets_no_config_delivery()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (group, _, _) = await Linked(s, EdenIni(OdinLink), EdenIni(ThorLink));
        (await s.Web.DeleteAsync($"/v1/devices/{s.ThorId}")).EnsureSuccessStatusCode();

        await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink, resolution: "2"))), group.HeadVersionId);

        Assert.Empty(await OpenConfigRows(f));
        var groups = await (await s.Web.GetAsync("/v1/configs/groups")).ReadAsync<SyncGroupList>();
        Assert.Equal(new[] { s.OdinId }, Assert.Single(groups.Items).MemberDeviceIds);
    }

    [Fact]
    public async Task Upload_validation()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var files = await Files(s.Odin, (Ini, EdenIni(OdinLink)));

        await AssertError(await UploadRaw(s.Odin, files, null, "nope"), HttpStatusCode.BadRequest, "unknown_emulator");
        await AssertError(await UploadRaw(s.Web, files, null), HttpStatusCode.BadRequest, "no_device");
        await AssertError(await UploadRaw(s.Odin, [new ManifestEntry(Ini, new string('a', 64), 3)], null),
            HttpStatusCode.BadRequest, "missing_blobs");
        await AssertError(await UploadRaw(s.Odin, [], null), HttpStatusCode.BadRequest, "bad_manifest");
        await AssertError(await UploadRaw(s.Odin, [files[0] with { Path = "../x.ini" }], null), HttpStatusCode.BadRequest, "bad_manifest");
        await AssertError(await CreateGroupRaw(s.Web, s.ThorId), HttpStatusCode.BadRequest, "no_config");
        await AssertError(await CreateGroupRaw(s.Web, s.OdinId, "nope"), HttpStatusCode.BadRequest, "unknown_emulator");
    }

    [Fact]
    public async Task Config_changes_notify_configs_topic_and_log_activity()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        await Linked(s, EdenIni(OdinLink), EdenIni(ThorLink));

        var kinds = await InDb(f, db => db.ActivityEvents.AsNoTracking().Select(a => a.Kind).ToListAsync());

        Assert.Contains("config_uploaded", kinds);
        Assert.Contains("group_changed", kinds);
    }

    [Fact]
    public async Task Config_open_conflict_content_reupload_is_not_Superseded()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var (group, _, _) = await Linked(s, EdenIni(OdinLink), EdenIni(ThorLink));
        var thorChange = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "2"))), group.HeadVersionId);
        var odinChange = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink, resolution: "3"))), group.HeadVersionId);
        Assert.Equal(SaveUploadStatus.Conflict, odinChange.Status);
        // Pinned or not, an open conflict is not a settled copy.
        await InDb(f, async db =>
        {
            (await db.ConfigVersions.SingleAsync(v => v.Id == odinChange.VersionId)).Pinned = true;
            await db.SaveChangesAsync();
            return 0;
        });
        var versions = await InDb(f, db => db.ConfigVersions.CountAsync(v => v.BundleId == group.BundleId));

        var again = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink, resolution: "3"))), group.HeadVersionId);

        Assert.Equal(SaveUploadStatus.Conflict, again.Status);
        Assert.Equal(odinChange.VersionId, again.VersionId);
        Assert.Equal(versions, await InDb(f, db => db.ConfigVersions.CountAsync(v => v.BundleId == group.BundleId)));
        var bundle = await Bundle(f, group.BundleId);
        Assert.True(bundle.OpenConflict);
        Assert.Equal(thorChange.VersionId, bundle.HeadVersionId);
    }

    [DockerFact]
    [Trait("Category", "Postgres")]
    public async Task Config_sync_on_postgres()
    {
        await using var container = new PostgreSqlBuilder("postgres:17-alpine").Build();
        await container.StartAsync();
        using var f = NewFactory();
        f.Settings["EmuSync:DatabaseProvider"] = "postgres";
        f.Settings["EmuSync:DatabaseConnection"] = container.GetConnectionString();
        var s = await TwoDevices(f);
        var (group, _, _) = await Linked(s, EdenIni(OdinLink), EdenIni(ThorLink));

        var change = await Upload(s.Thor, await Files(s.Thor, (Ini, EdenIni(ThorLink, resolution: "2"))), group.HeadVersionId);
        var d = Assert.Single(await Deliveries(s.Odin));
        Assert.Equal(EdenIni(OdinLink, resolution: "2"), await FileText(s.Odin, d.Manifest, Ini));
        var conflict = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink, resolution: "3"))), group.HeadVersionId);
        Assert.Equal(SaveUploadStatus.Conflict, conflict.Status);
        (await s.Web.PostJsonAsync($"/v1/configs/bundles/{group.BundleId}/conflicts/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepVersion, change.VersionId))).EnsureSuccessStatusCode();
        Assert.True((await Version(f, conflict.VersionId)).Resolved);
        Assert.Single(await Deliveries(s.Odin));
        var again = await Upload(s.Odin, await Files(s.Odin, (Ini, EdenIni(OdinLink, resolution: "3"))), group.HeadVersionId);
        Assert.Equal(SaveUploadStatus.Superseded, again.Status);
        Assert.Equal(conflict.VersionId, again.VersionId);
        Assert.Single(await Deliveries(s.Odin));
        (await s.Web.DeleteAsync($"/v1/configs/groups/{group.Id}/members/{s.ThorId}")).EnsureSuccessStatusCode();
        (await s.Web.DeleteAsync($"/v1/configs/groups/{group.Id}/members/{s.OdinId}")).EnsureSuccessStatusCode();
        Assert.Equal(HttpStatusCode.NoContent, (await s.Web.DeleteAsync($"/v1/configs/groups/{group.Id}")).StatusCode);
    }

    private static List<ManifestEntry> Manifest(ConfigVersion v) =>
        JsonSerializer.Deserialize<List<ManifestEntry>>(v.ManifestJson, TestApi.Json)!;
}
