using System.Net;
using System.Net.Http.Json;
using System.Text;
using EmuSync.Protocol;
using EmuSync.Server.Data;
using EmuSync.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Testcontainers.PostgreSql;

namespace EmuSync.Server.Tests;

public class VaultTests
{
    private const string Email = "a@example.com";

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
        return new Setup(f, await f.WebLogin(Email), odin, odinKey.DeviceId, thor, thorKey.DeviceId);
    }

    private static Task<List<ManifestEntry>> Files(HttpClient device, params (string path, string text)[] files) =>
        Fixtures.Manifest(device, files.Select(x => (x.path, Encoding.UTF8.GetBytes(x.text))).ToArray());

    private static Task<HttpResponseMessage> UploadRaw(HttpClient c, string category, string? emulator, string name,
        IReadOnlyList<ManifestEntry> manifest) =>
        c.PostJsonAsync("/v1/vault/upload", new VaultUploadRequest(category, emulator, name, manifest));

    private static async Task<VaultUploadResponse> Upload(HttpClient c, string category, string? emulator, string name,
        IReadOnlyList<ManifestEntry> manifest)
    {
        var r = await UploadRaw(c, category, emulator, name, manifest);
        Assert.True(r.StatusCode == HttpStatusCode.OK, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        return await r.ReadAsync<VaultUploadResponse>();
    }

    private static async Task<List<VaultItemDto>> List(HttpClient c) =>
        await (await c.GetAsync("/v1/vault")).ReadAsync<List<VaultItemDto>>();

    private static Task<HttpResponseMessage> Send(HttpClient web, Guid item, Guid device) =>
        web.PostJsonAsync($"/v1/vault/{item}/send", new SendVaultRequest(device));

    private static async Task<List<DeliveryDto>> VaultDeliveries(HttpClient device)
    {
        var r = await device.GetAsync("/v1/deliveries");
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        return (await r.ReadAsync<DeliveryList>()).Items.Where(d => d.Kind == DeliveryKind.Vault).ToList();
    }

    private static Task<HttpResponseMessage> Ack(HttpClient device, Guid id, DeliveryResult result = DeliveryResult.Applied) =>
        device.PostJsonAsync($"/v1/deliveries/{id}/ack", new AckRequest(result, null));

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

    [Fact]
    public async Task Upload_same_name_creates_new_version()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var first = await Upload(s.Odin, "keys", "eden", "prod.keys", await Files(s.Odin, ("prod.keys", "k1")));
        Assert.False(first.Unchanged);

        // A web session can upload too (blobs come from a device).
        var second = await Upload(s.Web, "keys", "eden", "prod.keys", await Files(s.Odin, ("prod.keys", "k2 longer")));

        Assert.Equal(first.ItemId, second.ItemId);
        Assert.NotEqual(first.VersionId, second.VersionId);
        Assert.False(second.Unchanged);
        var item = Assert.Single(await List(s.Web));
        Assert.Equal(second.VersionId, item.HeadVersionId);
        Assert.Equal(9, item.TotalSize);
        Assert.Equal(("keys", "eden", "prod.keys"), (item.Category, item.EmulatorId, item.Name));
        var versions = await InDb(f, db => db.VaultVersions.AsNoTracking().Where(v => v.ItemId == first.ItemId).OrderBy(v => v.Seq).ToListAsync());
        Assert.Equal(new[] { 1, 2 }, versions.Select(v => v.Seq));
        Assert.Equal(s.OdinId, versions[0].DeviceId);
        Assert.Null(versions[1].DeviceId);

        // Another emulator or category with the same name is a different item.
        var other = await Upload(s.Odin, "keys", null, "prod.keys", await Files(s.Odin, ("prod.keys", "k1")));
        Assert.NotEqual(first.ItemId, other.ItemId);
        Assert.Equal(2, (await List(s.Web)).Count);
        var kinds = (await (await s.Web.GetAsync("/v1/activity")).ReadAsync<ActivityPage>()).Items.Select(i => i.Kind);
        Assert.Contains("vault_uploaded", kinds);
    }

    [Fact]
    public async Task Identical_upload_is_unchanged()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var files = await Files(s.Odin, ("scph5501.bin", "bios"));
        var first = await Upload(s.Odin, "bios", "retroarch", "PS1 US", files);

        var again = await Upload(s.Thor, "bios", "retroarch", "PS1 US", files);

        Assert.True(again.Unchanged);
        Assert.Equal(first.VersionId, again.VersionId);
        Assert.Equal(first.ItemId, again.ItemId);
        Assert.Equal(1, await InDb(f, db => db.VaultVersions.CountAsync()));
    }

    [Fact]
    public async Task Upload_validates_input()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var files = await Files(s.Odin, ("a.bin", "x"));
        await AssertError(await UploadRaw(s.Odin, "roms", null, "n", files), HttpStatusCode.BadRequest, "bad_category");
        foreach (var name in new[] { "", "a/b", "a\\b", "..", "x..y", "a\0b", new string('n', 201) })
            await AssertError(await UploadRaw(s.Odin, "bios", null, name, files), HttpStatusCode.BadRequest, "bad_name");
        Assert.Equal(HttpStatusCode.OK, (await UploadRaw(s.Odin, "bios", null, new string('n', 200), files)).StatusCode);
        await AssertError(await UploadRaw(s.Odin, "bios", "mame", "n", files), HttpStatusCode.BadRequest, "unknown_emulator");
        await AssertError(await UploadRaw(s.Odin, "bios", null, "n", []), HttpStatusCode.BadRequest, "bad_manifest");
        var missing = new[] { new ManifestEntry("z.bin", new string('a', 64), 3) };
        await AssertError(await UploadRaw(s.Odin, "bios", null, "n", missing), HttpStatusCode.BadRequest, "missing_blobs");
        Assert.Equal(HttpStatusCode.Unauthorized, (await f.Client().GetAsync("/v1/vault")).StatusCode);
    }

    [Fact]
    public async Task Send_creates_delivery_only_for_that_device()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var up = await Upload(s.Odin, "firmware", "azahar", "fw", await Files(s.Odin, ("fw/a.bin", "aaa"), ("fw/b.bin", "bb")));
        // Uploading never delivers anything.
        Assert.Empty(await VaultDeliveries(s.Odin));
        Assert.Empty(await VaultDeliveries(s.Thor));

        var send = await Send(s.Web, up.ItemId, s.ThorId);

        Assert.Equal(HttpStatusCode.OK, send.StatusCode);
        Assert.Empty(await VaultDeliveries(s.Odin));
        var d = Assert.Single(await VaultDeliveries(s.Thor));
        Assert.Equal((up.ItemId, up.VersionId), (d.TargetId, d.VersionId));
        Assert.Equal(["fw/a.bin", "fw/b.bin"], d.Manifest.Select(e => e.Path));
        Assert.Null(d.GameKey);
        Assert.Null(d.KnownVersionId);
        Assert.Null(d.MissingLocalKeys);

        // Sending again supersedes the un-acked one, for the (new) current head.
        var up2 = await Upload(s.Odin, "firmware", "azahar", "fw", await Files(s.Odin, ("fw/a.bin", "aaa2")));
        var resend = await (await Send(s.Web, up.ItemId, s.ThorId)).ReadAsync<SendVaultResponse>();
        var d2 = Assert.Single(await VaultDeliveries(s.Thor));
        Assert.Equal(resend.DeliveryId, d2.Id);
        Assert.Equal(up2.VersionId, d2.VersionId);
        var rows = await InDb(f, db => db.Deliveries.AsNoTracking().Where(x => x.Kind == DeliveryKind.Vault).ToListAsync());
        Assert.Equal(2, rows.Count);
        Assert.True(rows.Single(r => r.Id == d.Id).Superseded);

        // Save/config reconciles leave vault deliveries alone (listing reconciles for the device).
        await Upload(s.Thor, "bios", null, "other", await Files(s.Thor, ("o.bin", "o")));
        Assert.Single(await VaultDeliveries(s.Thor));
        Assert.False((await InDb(f, db => db.Deliveries.AsNoTracking().SingleAsync(x => x.Id == d2.Id))).Superseded);

        await AssertError(await Send(s.Web, Guid.NewGuid(), s.ThorId), HttpStatusCode.NotFound, "vault_item_not_found");
        await AssertError(await Send(s.Web, up.ItemId, Guid.NewGuid()), HttpStatusCode.NotFound, "device_not_found");
    }

    [Fact]
    public async Task Holders_reflect_acked_deliveries()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var v1 = await Upload(s.Odin, "keys", "eden", "prod.keys", await Files(s.Odin, ("prod.keys", "k1")));
        Assert.Empty(Assert.Single(await List(s.Web)).Holders);

        await Send(s.Web, v1.ItemId, s.ThorId);
        Assert.Empty(Assert.Single(await List(s.Web)).Holders); // not acked yet
        var d = Assert.Single(await VaultDeliveries(s.Thor));
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(s.Thor, d.Id)).StatusCode);

        var holder = Assert.Single(Assert.Single(await List(s.Web)).Holders);
        Assert.Equal((s.ThorId, "Thor", true), (holder.DeviceId, holder.DeviceName, holder.Current));
        Assert.Empty(await VaultDeliveries(s.Thor));

        // A new head makes the holder stale, and a failed delivery changes nothing.
        var v2 = await Upload(s.Odin, "keys", "eden", "prod.keys", await Files(s.Odin, ("prod.keys", "k2")));
        Assert.False(Assert.Single(Assert.Single(await List(s.Web)).Holders).Current);
        await Send(s.Web, v1.ItemId, s.OdinId);
        var od = Assert.Single(await VaultDeliveries(s.Odin));
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(s.Odin, od.Id, DeliveryResult.Failed)).StatusCode);
        Assert.Single(Assert.Single(await List(s.Web)).Holders);
        await Send(s.Web, v1.ItemId, s.OdinId);
        od = Assert.Single(await VaultDeliveries(s.Odin));
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(s.Odin, od.Id)).StatusCode);
        var holders = Assert.Single(await List(s.Web)).Holders.ToDictionary(h => h.DeviceId);
        Assert.True(holders[s.OdinId].Current);
        Assert.False(holders[s.ThorId].Current);

        // Re-sending to Thor and acking brings it current; the activity log records the placement.
        await Send(s.Web, v1.ItemId, s.ThorId);
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(s.Thor, Assert.Single(await VaultDeliveries(s.Thor)).Id)).StatusCode);
        Assert.All(Assert.Single(await List(s.Web)).Holders, h => Assert.True(h.Current));
        Assert.Equal(v2.VersionId, Assert.Single(await List(s.Web)).HeadVersionId);
        var state = await InDb(f, db => db.DeviceVaultStates.AsNoTracking().ToListAsync());
        Assert.Equal(2, state.Count);
        var kinds = (await (await s.Web.GetAsync("/v1/activity")).ReadAsync<ActivityPage>()).Items.Select(i => i.Kind).ToList();
        Assert.Equal(3, kinds.Count(k => k == "vault_placed"));
        Assert.DoesNotContain("delivered", kinds);
    }

    [Fact]
    public async Task Late_ack_of_superseded_vault_delivery_records_holding_without_going_backwards()
    {
        using var f = NewFactory();
        var s = await TwoDevices(f);
        var v1 = await Upload(s.Odin, "bios", null, "b", await Files(s.Odin, ("b.bin", "1")));
        await Send(s.Web, v1.ItemId, s.ThorId);
        var old = Assert.Single(await VaultDeliveries(s.Thor));
        var v2 = await Upload(s.Odin, "bios", null, "b", await Files(s.Odin, ("b.bin", "22")));
        await Send(s.Web, v1.ItemId, s.ThorId);
        var current = Assert.Single(await VaultDeliveries(s.Thor));
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(s.Thor, current.Id)).StatusCode);

        // The device applied the old one late: it must not be recorded as holding the older version.
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(s.Thor, old.Id)).StatusCode);

        Assert.True(Assert.Single(Assert.Single(await List(s.Web)).Holders).Current);
        Assert.Equal(v2.VersionId, current.VersionId);
    }

    [DockerFact]
    [Trait("Category", "Postgres")]
    public async Task Vault_on_postgres()
    {
        await using var container = new PostgreSqlBuilder("postgres:17-alpine").Build();
        await container.StartAsync();
        using var f = NewFactory();
        f.Settings["EmuSync:DatabaseProvider"] = "postgres";
        f.Settings["EmuSync:DatabaseConnection"] = container.GetConnectionString();
        var s = await TwoDevices(f);
        var up = await Upload(s.Odin, "keys", "eden", "prod.keys", await Files(s.Odin, ("prod.keys", "k1")));
        Assert.True((await Upload(s.Odin, "keys", "eden", "prod.keys", await Files(s.Odin, ("prod.keys", "k1")))).Unchanged);
        await Send(s.Web, up.ItemId, s.ThorId);
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(s.Thor, Assert.Single(await VaultDeliveries(s.Thor)).Id)).StatusCode);
        Assert.True(Assert.Single(Assert.Single(await List(s.Web)).Holders).Current);
    }
}
