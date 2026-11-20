using System.Collections.Concurrent;
using System.Net;
using System.Net.Http.Json;
using System.Text;
using EmuSync.Protocol;
using EmuSync.Server.Data;
using EmuSync.Server.Deliveries;
using EmuSync.Server.Events;
using EmuSync.Server.Tests.Infrastructure;
using Microsoft.AspNetCore.TestHost;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Testcontainers.PostgreSql;
using WebApplicationFactoryOfProgram = Microsoft.AspNetCore.Mvc.Testing.WebApplicationFactory<Program>;

namespace EmuSync.Server.Tests;

public class DeliveryTests
{
    private const string Sword = "0100E66006406000";
    private const string Shield = "01008DB008C2C000";

    private static ServerFactory NewFactory()
    {
        var factory = new ServerFactory();
        factory.Settings["EmuSync:AllowRegistration"] = "true";
        return factory;
    }

    private static SaveUploadRequest Req(IReadOnlyList<ManifestEntry> manifest, Guid? baseId, string game = Sword,
        ItemKind kind = ItemKind.Save, string slot = "", string core = "") =>
        new("eden", kind, game, slot, core, baseId, manifest, null);

    private static Task<List<ManifestEntry>> Files(HttpClient device, string content) =>
        Fixtures.Manifest(device, ("data.dat", Encoding.UTF8.GetBytes(content)));

    private static async Task<SaveUploadResponse> Upload(HttpClient device, SaveUploadRequest request)
    {
        var response = await device.PostJsonAsync("/v1/saves/upload", request);
        Assert.True(response.StatusCode == HttpStatusCode.OK, $"{response.StatusCode}: {await response.Content.ReadAsStringAsync()}");
        return await response.ReadAsync<SaveUploadResponse>();
    }

    private static async Task Install(HttpClient device, params string[] games)
    {
        var response = await device.PutAsJsonAsync("/v1/devices/me/installs/eden",
            new InstallsRequest(games.Select(g => new InstalledGame(g, null, null)).ToList()));
        Assert.True(response.IsSuccessStatusCode, $"{response.StatusCode}: {await response.Content.ReadAsStringAsync()}");
    }

    private static async Task<IReadOnlyList<DeliveryDto>> Deliveries(HttpClient device)
    {
        var response = await device.GetAsync("/v1/deliveries");
        Assert.True(response.StatusCode == HttpStatusCode.OK, $"{response.StatusCode}: {await response.Content.ReadAsStringAsync()}");
        return (await response.ReadAsync<DeliveryList>()).Items;
    }

    private static Task<HttpResponseMessage> Ack(HttpClient device, Guid id, DeliveryResult result, string? detail = null) =>
        device.PostJsonAsync($"/v1/deliveries/{id}/ack", new AckRequest(result, detail));

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

    private static Task<List<Delivery>> AllDeliveries(ServerFactory f) =>
        InDb(f, db => db.Deliveries.AsNoTracking().ToListAsync());

    private static async Task<List<Delivery>> OpenRows(ServerFactory f) =>
        (await AllDeliveries(f)).Where(d => d.AckedUtc == null).ToList();

    private static Task<Guid?> Known(ServerFactory f, Guid deviceId, Guid itemId) =>
        InDb(f, db => db.DeviceItemStates.Where(s => s.DeviceId == deviceId && s.ItemId == itemId)
            .Select(s => (Guid?)s.KnownVersionId).SingleOrDefaultAsync());

    [Fact]
    public async Task Head_change_delivers_only_to_devices_with_game_installed()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (thor, thorKey) = await factory.PairSecondDevice(a, "Thor");
        var (odin, _) = await factory.PairSecondDevice(a, "Odin");
        await Install(thor, Sword);
        await Install(odin, Shield);
        var manifest = await Files(a, "v1");

        var v1 = await Upload(a, Req(manifest, null, core: "mGBA"));

        Assert.Empty(await Deliveries(odin));
        var d = Assert.Single(await Deliveries(thor));
        Assert.Equal(DeliveryKind.Save, d.Kind);
        Assert.Equal(v1.ItemId, d.TargetId);
        Assert.Equal(v1.VersionId, d.VersionId);
        Assert.Null(d.KnownVersionId);
        Assert.Equal("eden", d.EmulatorId);
        Assert.Equal(Sword, d.GameKey);
        Assert.Equal(ItemKind.Save, d.ItemKind);
        Assert.Equal("", d.Slot);
        Assert.Equal("mGBA", d.Core); // the version's core: a Save item's own Core is always ""
        Assert.Equal(manifest, d.Manifest);
        Assert.Null(d.MissingLocalKeys);
        var row = Assert.Single(await AllDeliveries(factory));
        Assert.Equal(thorKey.DeviceId, row.DeviceId);
        Assert.Equal(factory.Time.GetUtcNow(), row.CreatedUtc);
    }

    [Fact]
    public async Task Revoked_device_gets_no_delivery()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        await Install(b, Sword);
        (await a.DeleteAsync($"/v1/devices/{bKey.DeviceId}")).EnsureSuccessStatusCode();

        await Upload(a, Req(await Files(a, "v1"), null));

        Assert.Empty(await AllDeliveries(factory));
    }

    [Fact]
    public async Task Uploader_gets_no_delivery_of_its_own_version()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        await Install(a, Sword);

        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        await Upload(a, Req(await Files(a, "v2"), v1.VersionId));

        Assert.Empty(await Deliveries(a));
        Assert.Empty(await AllDeliveries(factory));
    }

    [Fact]
    public async Task Installing_game_later_creates_delivery_of_head()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        Assert.Empty(await Deliveries(b));

        await Install(b, Sword);

        var d = Assert.Single(await Deliveries(b));
        Assert.Equal(v2.VersionId, d.VersionId);
    }

    [Fact]
    public async Task Save_for_uninstalled_game_is_kept_but_not_delivered()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        await Install(b, Shield);

        var v1 = await Upload(a, Req(await Files(a, "v1"), null));

        Assert.Equal(v1.VersionId, await InDb(factory, db => db.SaveItems.Where(i => i.Id == v1.ItemId).Select(i => i.HeadVersionId).SingleAsync()));
        Assert.Empty(await Deliveries(a));
        Assert.Empty(await Deliveries(b));
        Assert.Empty(await AllDeliveries(factory));
    }

    [Fact]
    public async Task States_follow_the_same_rule()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        await Install(b, Sword);

        var s = await Upload(a, Req(await Files(a, "st"), null, kind: ItemKind.State, slot: "3", core: "c1"));

        var d = Assert.Single(await Deliveries(b));
        Assert.Equal(s.VersionId, d.VersionId);
        Assert.Equal(ItemKind.State, d.ItemKind);
        Assert.Equal("3", d.Slot);
        Assert.Equal("c1", d.Core);
    }

    [Fact]
    public async Task Open_conflict_holds_deliveries_until_resolved()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        var (c, _) = await factory.PairSecondDevice(a, "C");
        await Install(c, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        Assert.Equal(v2.VersionId, Assert.Single(await Deliveries(c)).VersionId);

        var conflict = await Upload(b, Req(await Files(b, "b"), v1.VersionId));
        Assert.Equal(SaveUploadStatus.Conflict, conflict.Status);

        Assert.Empty(await Deliveries(c));
        Assert.Empty(await OpenRows(factory));
        var closed = await AllDeliveries(factory);
        Assert.NotEmpty(closed);
        Assert.All(closed, d => Assert.True(d.Superseded));

        (await a.PostJsonAsync($"/v1/conflicts/{v1.ItemId}/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepVersion, conflict.VersionId))).EnsureSuccessStatusCode();

        Assert.Equal(conflict.VersionId, Assert.Single(await Deliveries(c)).VersionId);
    }

    [Fact]
    public async Task Resolve_delivers_head_to_losing_device_not_winner()
    {
        using var factory = NewFactory();
        var (a, aKey) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        await Install(a, Sword);
        await Install(b, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        (await Ack(b, Assert.Single(await Deliveries(b)).Id, DeliveryResult.Applied)).EnsureSuccessStatusCode();
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        var conflict = await Upload(b, Req(await Files(b, "b"), v1.VersionId));
        Assert.Equal(SaveUploadStatus.Conflict, conflict.Status);

        (await a.PostJsonAsync($"/v1/conflicts/{v1.ItemId}/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepVersion, conflict.VersionId))).EnsureSuccessStatusCode();

        Assert.Empty(await Deliveries(b));
        Assert.Equal(conflict.VersionId, await Known(factory, bKey.DeviceId, v1.ItemId));
        Assert.Equal(v2.VersionId, await Known(factory, aKey.DeviceId, v1.ItemId));
        Assert.Equal(conflict.VersionId, Assert.Single(await Deliveries(a)).VersionId);
    }

    [Fact]
    public async Task Resolve_to_an_older_version_still_delivers_it_to_its_uploader()
    {
        using var factory = NewFactory();
        var (a, aKey) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        await Install(a, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        var conflict = await Upload(b, Req(await Files(b, "b"), v1.VersionId));
        Assert.Equal(SaveUploadStatus.Conflict, conflict.Status);

        (await a.PostJsonAsync($"/v1/conflicts/{v1.ItemId}/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepVersion, v1.VersionId))).EnsureSuccessStatusCode();

        // A uploaded v2 after v1, so it no longer holds v1 and must be sent it.
        Assert.Equal(v2.VersionId, await Known(factory, aKey.DeviceId, v1.ItemId));
        Assert.Equal(v1.VersionId, Assert.Single(await Deliveries(a)).VersionId);
    }

    [Fact]
    public async Task Resolve_to_historical_version_delivers_to_device_that_moved_on()
    {
        using var factory = NewFactory();
        var (a, aKey) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        var (c, _) = await factory.PairSecondDevice(a, "C");
        await Install(a, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var v2 = await Upload(b, Req(await Files(b, "v2"), v1.VersionId));
        Assert.Equal(SaveUploadStatus.Accepted, v2.Status);
        (await Ack(a, Assert.Single(await Deliveries(a)).Id, DeliveryResult.Applied)).EnsureSuccessStatusCode();
        Assert.Equal(v2.VersionId, await Known(factory, aKey.DeviceId, v1.ItemId));
        var conflict = await Upload(c, Req(await Files(c, "c"), v1.VersionId));
        Assert.Equal(SaveUploadStatus.Conflict, conflict.Status);

        (await a.PostJsonAsync($"/v1/conflicts/{v1.ItemId}/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepVersion, v1.VersionId))).EnsureSuccessStatusCode();

        // v1 is A's latest upload, but it is history, not an open conflict: A holds v2 and must be sent v1.
        Assert.Equal(v2.VersionId, await Known(factory, aKey.DeviceId, v1.ItemId));
        var d = Assert.Single(await Deliveries(a));
        Assert.Equal(v1.VersionId, d.VersionId);

        // Applying it settles A, even though v1 is older (lower Seq) than what A knew.
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(a, d.Id, DeliveryResult.Applied)).StatusCode);
        Assert.Equal(v1.VersionId, await Known(factory, aKey.DeviceId, v1.ItemId));
        Assert.Empty(await Deliveries(a));
        Assert.Empty(await OpenRows(factory));
    }

    [Fact]
    public async Task Applied_ack_after_historical_resolve_settles()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        var (c, _) = await factory.PairSecondDevice(a, "C");
        var (d, _) = await factory.PairSecondDevice(a, "D");
        var devices = new[] { a, b, c, d };
        foreach (var device in devices) await Install(device, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        foreach (var device in new[] { b, c, d })
            (await Ack(device, Assert.Single(await Deliveries(device)).Id, DeliveryResult.Applied)).EnsureSuccessStatusCode();
        await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        foreach (var device in new[] { b, c })
            (await Ack(device, Assert.Single(await Deliveries(device)).Id, DeliveryResult.Applied)).EnsureSuccessStatusCode();
        var conflict = await Upload(d, Req(await Files(d, "d"), v1.VersionId)); // D never took v2
        Assert.Equal(SaveUploadStatus.Conflict, conflict.Status);

        (await a.PostJsonAsync($"/v1/conflicts/{v1.ItemId}/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepVersion, v1.VersionId))).EnsureSuccessStatusCode();

        foreach (var device in devices)
            foreach (var delivery in await Deliveries(device))
            {
                Assert.Equal(v1.VersionId, delivery.VersionId);
                Assert.Equal(HttpStatusCode.NoContent, (await Ack(device, delivery.Id, DeliveryResult.Applied)).StatusCode);
            }

        foreach (var device in devices) Assert.Empty(await Deliveries(device));
        Assert.Empty(await OpenRows(factory));
        var known = await InDb(factory, db => db.DeviceItemStates.Where(s => s.ItemId == v1.ItemId).Select(s => s.KnownVersionId).ToListAsync());
        Assert.Equal(4, known.Count);
        Assert.All(known, k => Assert.Equal(v1.VersionId, k));
    }

    /// <summary>The on-device scenario: B loses a KeepBoth resolve, then, before applying the head, re-uploads its
    /// content (now the pinned variant) on the old base. That is the server's own variant coming back, not an edit.</summary>
    private static async Task<(HttpClient B, Guid BId, SaveUploadResponse V2, SaveUploadResponse Conflict, SaveUploadResponse Again)>
        ReuploadAfterKeepBoth(ServerFactory factory, HttpClient a)
    {
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        await Install(a, Sword);
        await Install(b, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        (await Ack(b, Assert.Single(await Deliveries(b)).Id, DeliveryResult.Applied)).EnsureSuccessStatusCode();
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        var conflict = await Upload(b, Req(await Files(b, "b"), v1.VersionId));
        Assert.Equal(SaveUploadStatus.Conflict, conflict.Status);
        (await a.PostJsonAsync($"/v1/conflicts/{v1.ItemId}/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepBoth, v2.VersionId))).EnsureSuccessStatusCode();
        Assert.Equal(v2.VersionId, Assert.Single(await Deliveries(b)).VersionId);

        var again = await Upload(b, Req(await Files(b, "b"), v1.VersionId));
        return (b, bKey.DeviceId, v2, conflict, again);
    }

    [Fact]
    public async Task Upload_identical_to_resolved_variant_is_Superseded_and_delivers_head()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");

        var (b, bId, v2, conflict, again) = await ReuploadAfterKeepBoth(factory, a);

        Assert.Equal(SaveUploadStatus.Superseded, again.Status);
        Assert.Equal(conflict.VersionId, again.VersionId);
        Assert.Equal(v2.VersionId, again.HeadVersionId);
        Assert.Equal(conflict.VersionId, await Known(factory, bId, v2.ItemId));
        Assert.Equal(3, await InDb(factory, db => db.SaveVersions.CountAsync(v => v.ItemId == v2.ItemId))); // v1, v2, B's
        var item = await InDb(factory, db => db.SaveItems.AsNoTracking().SingleAsync(i => i.Id == v2.ItemId));
        Assert.False(item.OpenConflict);
        Assert.Equal(v2.VersionId, item.HeadVersionId);
        Assert.False(await InDb(factory, db => db.SaveVersions.AnyAsync(v => v.ItemId == v2.ItemId && v.ConflictReason != null && !v.Resolved)));
        Assert.Equal(v2.VersionId, Assert.Single(await OpenRows(factory), r => r.DeviceId == bId).VersionId);
        var d = Assert.Single(await Deliveries(b));
        Assert.Equal(v2.VersionId, d.VersionId);
        Assert.Equal(conflict.VersionId, d.KnownVersionId);
    }

    [Fact]
    public async Task Superseded_then_applied_ack_settles()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bId, v2, _, again) = await ReuploadAfterKeepBoth(factory, a);
        Assert.Equal(SaveUploadStatus.Superseded, again.Status);

        Assert.Equal(HttpStatusCode.NoContent, (await Ack(b, Assert.Single(await Deliveries(b)).Id, DeliveryResult.Applied)).StatusCode);

        Assert.Equal(v2.VersionId, await Known(factory, bId, v2.ItemId));
        Assert.Empty(await Deliveries(b));
        Assert.Empty(await Deliveries(a));
        Assert.Empty(await OpenRows(factory));
        Assert.False(await InDb(factory, db => db.SaveItems.Where(i => i.Id == v2.ItemId).Select(i => i.OpenConflict).SingleAsync()));
        // A later upload from B, now based on the head, is an ordinary accept.
        var next = await Upload(b, Req(await Files(b, "b2"), v2.VersionId));
        Assert.Equal(SaveUploadStatus.Accepted, next.Status);
    }

    [Fact]
    public async Task Upload_identical_to_plain_old_version_still_conflicts()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));

        // B holds v1's content but never synced it: plain history, not a resolved copy, so the old rules apply.
        var concurrent = await Upload(b, Req(await Files(b, "v1"), v1.VersionId));
        Assert.Equal(SaveUploadStatus.Conflict, concurrent.Status);
        Assert.NotEqual(v1.VersionId, concurrent.VersionId);
        Assert.Equal(v2.VersionId, concurrent.HeadVersionId);
        Assert.Equal(3, await InDb(factory, db => db.SaveVersions.CountAsync(v => v.ItemId == v1.ItemId)));
        Assert.True(await InDb(factory, db => db.SaveItems.Where(i => i.Id == v1.ItemId).Select(i => i.OpenConflict).SingleAsync()));
    }

    /// <summary>A has v1 then v2 (head); B synced v1, then opens a conflict with its own content "b" on base v1.</summary>
    private static async Task<(HttpClient A, HttpClient B, Guid BId, SaveUploadResponse V1, SaveUploadResponse V2, SaveUploadResponse Conflict)>
        OpenConcurrentConflict(ServerFactory factory)
    {
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        await Install(a, Sword);
        await Install(b, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        (await Ack(b, Assert.Single(await Deliveries(b)).Id, DeliveryResult.Applied)).EnsureSuccessStatusCode();
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        var conflict = await Upload(b, Req(await Files(b, "b"), v1.VersionId));
        Assert.Equal(SaveUploadStatus.Conflict, conflict.Status);
        return (a, b, bKey.DeviceId, v1, v2, conflict);
    }

    [Fact]
    public async Task KeepVersion_loser_reupload_is_Superseded()
    {
        using var factory = NewFactory();
        var (a, b, bId, v1, v2, conflict) = await OpenConcurrentConflict(factory);
        (await a.PostJsonAsync($"/v1/conflicts/{v2.ItemId}/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepVersion, v2.VersionId))).EnsureSuccessStatusCode();
        var versions = await InDb(factory, db => db.SaveVersions.CountAsync(v => v.ItemId == v2.ItemId));

        var again = await Upload(b, Req(await Files(b, "b"), v1.VersionId));

        Assert.Equal(SaveUploadStatus.Superseded, again.Status);
        Assert.Equal(conflict.VersionId, again.VersionId);
        Assert.Equal(v2.VersionId, again.HeadVersionId);
        Assert.Equal(versions, await InDb(factory, db => db.SaveVersions.CountAsync(v => v.ItemId == v2.ItemId)));
        Assert.Equal(conflict.VersionId, await Known(factory, bId, v2.ItemId));
        Assert.Equal(v2.VersionId, Assert.Single(await Deliveries(b)).VersionId);
    }

    [Fact]
    public async Task FirstSync_UseServer_loser_reupload_is_Superseded()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        await Install(a, Sword);
        await Install(b, Sword);
        var server = await Upload(a, Req(await Files(a, "server"), null));
        var mine = await Upload(b, Req(await Files(b, "device"), null));
        Assert.Equal(SaveUploadStatus.Conflict, mine.Status);
        (await a.PostJsonAsync("/v1/conflicts/first-sync/resolve",
            new BulkFirstSyncRequest(bKey.DeviceId, FirstSyncChoice.UseServer, null))).EnsureSuccessStatusCode();

        var again = await Upload(b, Req(await Files(b, "device"), null));

        Assert.Equal(SaveUploadStatus.Superseded, again.Status);
        Assert.Equal(mine.VersionId, again.VersionId);
        Assert.Equal(server.VersionId, again.HeadVersionId);
        Assert.Equal(2, await InDb(factory, db => db.SaveVersions.CountAsync(v => v.ItemId == server.ItemId)));
        Assert.False(await InDb(factory, db => db.SaveItems.Where(i => i.Id == server.ItemId).Select(i => i.OpenConflict).SingleAsync()));
        Assert.Equal(server.VersionId, Assert.Single(await Deliveries(b)).VersionId);
    }

    [Fact]
    public async Task Upload_identical_to_pinned_only_version_is_Superseded()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        await Install(a, Sword);
        await Install(b, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        (await a.PostAsync($"/v1/saves/{v1.ItemId}/pin/{v1.VersionId}", null)).EnsureSuccessStatusCode();
        Assert.True(await InDb(factory, db => db.SaveVersions.AnyAsync(v => v.Id == v1.VersionId && v.ConflictReason == null
            && !v.Resolved && v.Variant == null && v.Pinned)));

        var again = await Upload(b, Req(await Files(b, "v1"), null));

        Assert.Equal(SaveUploadStatus.Superseded, again.Status);
        Assert.Equal(v1.VersionId, again.VersionId);
        Assert.Equal(v2.VersionId, again.HeadVersionId);
        Assert.Equal(2, await InDb(factory, db => db.SaveVersions.CountAsync(v => v.ItemId == v1.ItemId)));
        Assert.Equal(v1.VersionId, await Known(factory, bKey.DeviceId, v1.ItemId));
    }

    [Fact]
    public async Task Upload_identical_to_variant_only_version_is_Superseded()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        await Install(a, Sword);
        await Install(b, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        // A labelled, unpinned, non-conflict version.
        await InDb(factory, async db =>
        {
            var v = await db.SaveVersions.SingleAsync(x => x.Id == v1.VersionId);
            v.Variant = "Old copy";
            v.Pinned = false;
            await db.SaveChangesAsync();
            return 0;
        });

        var again = await Upload(b, Req(await Files(b, "v1"), null));

        Assert.Equal(SaveUploadStatus.Superseded, again.Status);
        Assert.Equal(v1.VersionId, again.VersionId);
        Assert.Equal(v2.VersionId, again.HeadVersionId);
        Assert.Equal(2, await InDb(factory, db => db.SaveVersions.CountAsync(v => v.ItemId == v1.ItemId)));
        Assert.Equal(v1.VersionId, await Known(factory, bKey.DeviceId, v1.ItemId));
    }

    [Fact]
    public async Task Different_content_after_a_resolve_still_conflicts()
    {
        using var factory = NewFactory();
        var (a, b, _, v1, v2, _) = await OpenConcurrentConflict(factory);
        (await a.PostJsonAsync($"/v1/conflicts/{v2.ItemId}/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepVersion, v2.VersionId))).EnsureSuccessStatusCode();

        var other = await Upload(b, Req(await Files(b, "something else"), v1.VersionId));

        Assert.Equal(SaveUploadStatus.Conflict, other.Status);
        Assert.NotEqual(v1.VersionId, other.VersionId);
        Assert.Equal(v2.VersionId, other.HeadVersionId);
        Assert.True(await InDb(factory, db => db.SaveItems.Where(i => i.Id == v2.ItemId).Select(i => i.OpenConflict).SingleAsync()));
    }

    [Fact]
    public async Task Open_conflict_content_reupload_is_not_Superseded()
    {
        using var factory = NewFactory();
        var (a, b, _, v1, v2, conflict) = await OpenConcurrentConflict(factory);
        // Even pinned, an open conflict is not a settled copy.
        (await a.PostAsync($"/v1/saves/{v1.ItemId}/pin/{conflict.VersionId}", null)).EnsureSuccessStatusCode();
        var versions = await InDb(factory, db => db.SaveVersions.CountAsync(v => v.ItemId == v1.ItemId));

        var retry = await Upload(b, Req(await Files(b, "b"), v1.VersionId));
        Assert.Equal(SaveUploadStatus.Conflict, retry.Status);
        Assert.Equal(conflict.VersionId, retry.VersionId);
        Assert.Equal(versions, await InDb(factory, db => db.SaveVersions.CountAsync(v => v.ItemId == v1.ItemId)));

        // Another device arriving with the same content on an old base opens its own conflict, as before.
        var (c, _) = await factory.PairSecondDevice(a, "C");
        var other = await Upload(c, Req(await Files(c, "b"), v1.VersionId));
        Assert.Equal(SaveUploadStatus.Conflict, other.Status);
        Assert.NotEqual(conflict.VersionId, other.VersionId);
        Assert.Equal(v2.VersionId, other.HeadVersionId);
    }

    [Fact]
    public async Task Bulk_UseDevice_delivers_to_others_not_the_chosen_device()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        await Install(a, Sword);
        await Install(b, Sword);
        await Upload(a, Req(await Files(a, "server"), null));
        Assert.Single(await Deliveries(b));
        var mine = await Upload(b, Req(await Files(b, "device"), null));
        Assert.Equal(SaveUploadStatus.Conflict, mine.Status);
        Assert.Empty(await OpenRows(factory));

        (await b.PostJsonAsync("/v1/conflicts/first-sync/resolve",
            new BulkFirstSyncRequest(bKey.DeviceId, FirstSyncChoice.UseDevice, null))).EnsureSuccessStatusCode();

        Assert.Empty(await Deliveries(b));
        Assert.Equal(mine.VersionId, Assert.Single(await Deliveries(a)).VersionId);
    }

    [Fact]
    public async Task Newer_head_replaces_unacked_delivery()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        await Install(b, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var first = Assert.Single(await Deliveries(b));

        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));

        var d = Assert.Single(await Deliveries(b));
        Assert.Equal(v2.VersionId, d.VersionId);
        var old = Assert.Single(await AllDeliveries(factory), r => r.Id == first.Id);
        Assert.True(old.Superseded);
        Assert.Null(old.Result);
        Assert.Equal(factory.Time.GetUtcNow(), old.AckedUtc);
        Assert.Equal(d.Id, Assert.Single(await OpenRows(factory)).Id);
    }

    [Fact]
    public async Task Late_applied_ack_on_superseded_delivery_records_known()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        var (c, _) = await factory.PairSecondDevice(a, "C");
        await Install(b, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var first = Assert.Single(await Deliveries(b));
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));

        await AssertError(await Ack(c, first.Id, DeliveryResult.Applied), HttpStatusCode.NotFound, "delivery_not_found");
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(b, first.Id, DeliveryResult.Applied)).StatusCode);

        Assert.Equal(v1.VersionId, await Known(factory, bKey.DeviceId, v1.ItemId));
        Assert.Equal(v2.VersionId, Assert.Single(await Deliveries(b)).VersionId);
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(b, first.Id, DeliveryResult.Failed)).StatusCode);
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(b, first.Id, DeliveryResult.Applied)).StatusCode);
        var old = Assert.Single(await AllDeliveries(factory), r => r.Id == first.Id);
        Assert.True(old.Superseded);
        Assert.Null(old.Result);
        Assert.Equal(v2.VersionId, Assert.Single(await Deliveries(b)).VersionId);
        // A superseded Failed ack is not a backoff: the live v2 delivery is still there.
    }

    [Fact]
    public async Task Late_applied_ack_never_moves_known_backwards()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        await Install(b, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var first = Assert.Single(await Deliveries(b));

        // B applied v1, edited, and uploaded before its ack got through.
        var v2 = await Upload(b, Req(await Files(b, "v2"), v1.VersionId));
        Assert.Equal(SaveUploadStatus.Accepted, v2.Status);
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(b, first.Id, DeliveryResult.Applied)).StatusCode);

        Assert.Equal(v2.VersionId, await Known(factory, bKey.DeviceId, v1.ItemId));
        Assert.Empty(await Deliveries(b));
    }

    [Fact]
    public async Task Ack_Applied_updates_known_version()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        await Install(b, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var d = Assert.Single(await Deliveries(b));

        await AssertError(await Ack(a, d.Id, DeliveryResult.Applied), HttpStatusCode.NotFound, "delivery_not_found");
        var r = await Ack(b, d.Id, DeliveryResult.Applied);

        Assert.Equal(HttpStatusCode.NoContent, r.StatusCode);
        Assert.Equal(v1.VersionId, await Known(factory, bKey.DeviceId, v1.ItemId));
        Assert.Empty(await Deliveries(b));
        var row = Assert.Single(await AllDeliveries(factory));
        Assert.Equal(DeliveryResult.Applied, row.Result);
        Assert.Equal(factory.Time.GetUtcNow(), row.AckedUtc);
        await AssertError(await Ack(b, d.Id, DeliveryResult.Applied), HttpStatusCode.Conflict, "already_acked");

        // The next upload from B is based on what it applied: a plain head change, not a conflict.
        var v2 = await Upload(b, Req(await Files(b, "v2"), v1.VersionId));
        Assert.Equal(SaveUploadStatus.Accepted, v2.Status);
    }

    [Fact]
    public async Task Ack_SkippedLocalChanged_closes_and_recomputes()
    {
        using var root = NewFactory();
        var hub = new RecordingHub();
        using var factory = root.WithWebHostBuilder(b => b.ConfigureTestServices(s => s.AddSingleton<IEventHub>(hub)));
        var (a, _) = await RegisterOn(factory, "a@example.com");
        var (b, bKey) = await PairOn(factory, a);
        await Install(b, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        var d = Assert.Single(await Deliveries(b));

        // B's local copy turned out to equal the head already: the upload is Unchanged and B now knows the head.
        Assert.Equal(SaveUploadStatus.Unchanged, (await Upload(b, Req(await Files(b, "v2"), null))).Status);
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(b, d.Id, DeliveryResult.SkippedLocalChanged)).StatusCode);
        Assert.Empty(await Deliveries(b));
        var row = Assert.Single(await AllDeliveriesOf(factory), r => r.Id == d.Id);
        Assert.Equal(DeliveryResult.SkippedLocalChanged, row.Result);
        Assert.False(row.Superseded);

        // A skip that nothing settles: no immediate re-delivery and no push to the skipping device.
        root.Time.Advance(DeliveryService.SkippedBackoff);
        var v3 = await Upload(a, Req(await Files(a, "v3"), v2.VersionId));
        var d3 = Assert.Single(await Deliveries(b));
        var pushes = hub.Devices.Length;
        Assert.Equal(HttpStatusCode.NoContent, (await Ack(b, d3.Id, DeliveryResult.SkippedLocalChanged)).StatusCode);
        Assert.Empty(await Deliveries(b));
        root.Time.Advance(TimeSpan.FromSeconds(119));
        Assert.Empty(await Deliveries(b));

        root.Time.Advance(TimeSpan.FromSeconds(1));

        var again = Assert.Single(await Deliveries(b));
        Assert.NotEqual(d3.Id, again.Id);
        Assert.Equal(v3.VersionId, again.VersionId);
        Assert.Equal(pushes, hub.Devices.Length);
        Assert.DoesNotContain(bKey.DeviceId, hub.Devices[pushes..]);
    }

    [Fact]
    public async Task Failed_detail_is_truncated_not_rejected()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        var failed = new ConcurrentQueue<DeliveryFailed>();
        factory.Services.GetRequiredService<IDomainEvents>()
            .Subscribe<DeliveryFailed>((e, _) => { failed.Enqueue(e); return Task.CompletedTask; });
        await Install(b, Sword);
        await Upload(a, Req(await Files(a, "v1"), null));
        var d = Assert.Single(await Deliveries(b));

        Assert.Equal(HttpStatusCode.NoContent, (await Ack(b, d.Id, DeliveryResult.Failed, new string('x', 5000))).StatusCode);

        Assert.Equal(new string('x', 2000), Assert.Single(failed).Detail);
    }

    [Fact]
    public async Task Failed_ack_suppresses_redelivery_for_10_minutes()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        var failed = new ConcurrentQueue<DeliveryFailed>();
        factory.Services.GetRequiredService<IDomainEvents>()
            .Subscribe<DeliveryFailed>((e, _) => { failed.Enqueue(e); return Task.CompletedTask; });
        await Install(b, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var d = Assert.Single(await Deliveries(b));

        Assert.Equal(HttpStatusCode.NoContent, (await Ack(b, d.Id, DeliveryResult.Failed, "disk full")).StatusCode);

        Assert.Equal(new DeliveryFailed(bKey.UserId, bKey.DeviceId, d.Id, "disk full"), Assert.Single(failed));
        Assert.Empty(await Deliveries(b));
        Assert.Null(await Known(factory, bKey.DeviceId, v1.ItemId));
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        factory.Time.Advance(TimeSpan.FromMinutes(9));
        Assert.Empty(await Deliveries(b));

        factory.Time.Advance(TimeSpan.FromMinutes(2));

        var retry = Assert.Single(await Deliveries(b));
        Assert.Equal(v2.VersionId, retry.VersionId);
    }

    [Fact]
    public async Task Restore_makes_new_head_from_old_version_and_delivers_to_installed_devices()
    {
        using var factory = NewFactory();
        var (a, aKey) = await factory.Register("a@example.com", deviceName: "Thor");
        var (b, _) = await factory.PairSecondDevice(a, "Odin");
        var (c, _) = await factory.PairSecondDevice(a, "Loki");
        await Install(a, Sword);
        await Install(b, Sword);
        var m1 = await Files(a, "v1");
        var v1 = await Upload(a, Req(m1, null, core: "core-1"));
        (await Ack(b, Assert.Single(await Deliveries(b)).Id, DeliveryResult.Applied)).EnsureSuccessStatusCode();
        factory.Time.Advance(TimeSpan.FromHours(1));
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId, core: "core-2"));
        (await Ack(b, Assert.Single(await Deliveries(b)).Id, DeliveryResult.Applied)).EnsureSuccessStatusCode();
        var web = await factory.WebLogin("a@example.com");

        var response = await web.PostJsonAsync($"/v1/saves/{v1.ItemId}/versions/{v1.VersionId}/restore", new RestoreRequest(aKey.DeviceId));

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var restored = await response.ReadAsync<RestoreResponse>();
        Assert.Equal(v1.ItemId, restored.ItemId);
        var versions = await InDb(factory, db => db.SaveVersions.AsNoTracking().Where(v => v.ItemId == v1.ItemId).OrderBy(v => v.Seq).ToListAsync());
        Assert.Equal([1, 2, 3], versions.Select(v => v.Seq));
        var v3 = versions[2];
        Assert.Equal(restored.VersionId, v3.Id);
        Assert.Equal(v2.VersionId, v3.BaseVersionId);
        Assert.Null(v3.DeviceId);
        Assert.Equal(versions[0].ManifestHash, v3.ManifestHash);
        Assert.Equal(versions[0].ManifestJson, v3.ManifestJson);
        Assert.Equal("core-1", v3.Core);
        Assert.Null(v3.ConflictReason);
        Assert.True(versions[1].Pinned);
        Assert.Equal("Thor 2026-11-19 13:00", versions[1].Variant);
        Assert.Equal(v3.Id, await InDb(factory, db => db.SaveItems.Where(i => i.Id == v1.ItemId).Select(i => i.HeadVersionId).SingleAsync()));

        foreach (var device in new[] { a, b })
        {
            var d = Assert.Single(await Deliveries(device));
            Assert.Equal(v3.Id, d.VersionId);
            Assert.Equal(m1, d.Manifest);
            Assert.Equal("core-1", d.Core);
        }
        Assert.Empty(await Deliveries(c));
    }

    [Fact]
    public async Task Restore_keeps_an_already_pinned_head_as_is()
    {
        using var factory = NewFactory();
        var (a, aKey) = await factory.Register("a@example.com");
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        (await a.PostAsync($"/v1/saves/{v1.ItemId}/pin/{v2.VersionId}", null)).EnsureSuccessStatusCode();

        (await a.PostJsonAsync($"/v1/saves/{v1.ItemId}/versions/{v1.VersionId}/restore", new RestoreRequest(aKey.DeviceId)))
            .EnsureSuccessStatusCode();

        var old = await InDb(factory, db => db.SaveVersions.AsNoTracking().SingleAsync(v => v.Id == v2.VersionId));
        Assert.True(old.Pinned);
        Assert.Null(old.Variant);
    }

    [Fact]
    public async Task Restore_rejects_open_conflict_foreign_version_and_foreign_device()
    {
        using var factory = NewFactory();
        var (a, aKey) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        var (other, otherKey) = await factory.Register("other@example.com");
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var shield = await Upload(a, Req(await Files(a, "s"), null, game: Shield));
        var otherV = await Upload(other, Req(await Files(other, "o"), null));

        string Url(Guid item, Guid version) => $"/v1/saves/{item}/versions/{version}/restore";
        await AssertError(await a.PostJsonAsync(Url(v1.ItemId, shield.VersionId), new RestoreRequest(aKey.DeviceId)),
            HttpStatusCode.NotFound, "version_not_found");
        await AssertError(await a.PostJsonAsync(Url(otherV.ItemId, otherV.VersionId), new RestoreRequest(aKey.DeviceId)),
            HttpStatusCode.NotFound, "version_not_found");
        await AssertError(await a.PostJsonAsync(Url(v1.ItemId, v1.VersionId), new RestoreRequest(otherKey.DeviceId)),
            HttpStatusCode.NotFound, "device_not_found");

        await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        Assert.Equal(SaveUploadStatus.Conflict, (await Upload(b, Req(await Files(b, "b"), v1.VersionId))).Status);
        await AssertError(await a.PostJsonAsync(Url(v1.ItemId, v1.VersionId), new RestoreRequest(aKey.DeviceId)),
            HttpStatusCode.Conflict, "conflict_open");
        Assert.Equal(3, await InDb(factory, db => db.SaveVersions.CountAsync(v => v.ItemId == v1.ItemId)));
    }

    [Fact]
    public async Task Deliveries_require_a_device_identity()
    {
        using var factory = NewFactory();
        await factory.Register("a@example.com");
        var web = await factory.WebLogin("a@example.com");

        await AssertError(await web.GetAsync("/v1/deliveries"), HttpStatusCode.BadRequest, "no_device");
        await AssertError(await Ack(web, Guid.NewGuid(), DeliveryResult.Applied), HttpStatusCode.BadRequest, "no_device");
    }

    [Fact]
    public async Task Recompute_is_idempotent()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        await Install(b, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var before = await AllDeliveries(factory);

        using (var scope = factory.Services.CreateScope())
        {
            var svc = scope.ServiceProvider.GetRequiredService<DeliveryService>();
            await svc.RecomputeForItemAsync(v1.ItemId, CancellationToken.None);
            await svc.RecomputeForItemAsync(v1.ItemId, CancellationToken.None);
            await svc.RecomputeForDeviceAsync(bKey.DeviceId, CancellationToken.None);
        }
        await factory.Services.GetRequiredService<IDomainEvents>()
            .Raise(new SaveHeadChanged(bKey.UserId, v1.ItemId, v1.VersionId), CancellationToken.None);

        var after = await AllDeliveries(factory);
        Assert.Equal(before.Select(d => (d.Id, d.DeviceId, d.VersionId)), after.Select(d => (d.Id, d.DeviceId, d.VersionId)));
    }

    [Fact]
    public async Task New_delivery_notifies_device()
    {
        using var root = NewFactory();
        var hub = new RecordingHub();
        using var factory = root.WithWebHostBuilder(b => b.ConfigureTestServices(s => s.AddSingleton<IEventHub>(hub)));
        var (a, _) = await RegisterOn(factory, "a@example.com");
        var (b, bKey) = await PairOn(factory, a);
        await Install(b, Sword);
        Assert.Empty(hub.Devices);

        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        Assert.Equal([bKey.DeviceId], hub.Devices);

        // A recompute that creates nothing new notifies nobody.
        await Deliveries(b);
        Assert.Single(hub.Devices);
        await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        Assert.Equal([bKey.DeviceId, bKey.DeviceId], hub.Devices);
    }

    [DockerFact]
    [Trait("Category", "Postgres")]
    public async Task Deliveries_and_restore_on_postgres()
    {
        await using var container = new PostgreSqlBuilder("postgres:17-alpine").Build();
        await container.StartAsync();
        using var factory = NewFactory();
        factory.Settings["EmuSync:DatabaseProvider"] = "postgres";
        factory.Settings["EmuSync:DatabaseConnection"] = container.GetConnectionString();

        var (a, aKey) = await factory.Register("a@example.com");
        var (b, bKey) = await factory.PairSecondDevice(a, "B");
        await Install(a, Sword);
        await Install(b, Sword);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        (await Ack(b, Assert.Single(await Deliveries(b)).Id, DeliveryResult.Applied)).EnsureSuccessStatusCode();
        Assert.Equal(v1.VersionId, await Known(factory, bKey.DeviceId, v1.ItemId));
        var v2 = await Upload(a, Req(await Files(a, "v2"), v1.VersionId));
        Assert.Equal(v2.VersionId, Assert.Single(await Deliveries(b)).VersionId);

        var restored = await (await a.PostJsonAsync($"/v1/saves/{v1.ItemId}/versions/{v1.VersionId}/restore",
            new RestoreRequest(aKey.DeviceId))).ReadAsync<RestoreResponse>();

        Assert.Equal(restored.VersionId, Assert.Single(await Deliveries(b)).VersionId);
        Assert.Equal(restored.VersionId, Assert.Single(await Deliveries(a)).VersionId); // a knew v2, so it gets the restore too
    }

    private static async Task<List<Delivery>> AllDeliveriesOf(WebApplicationFactoryOfProgram factory)
    {
        using var scope = factory.Services.CreateScope();
        return await scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>().Deliveries.AsNoTracking().ToListAsync();
    }

    private static async Task<(HttpClient, DeviceKeyResponse)> RegisterOn(WebApplicationFactoryOfProgram factory, string email)
    {
        var response = await Client(factory).PostAsJsonAsync("/v1/auth/register", new RegisterRequest(email, TestApi.Password, "A", "android"));
        response.EnsureSuccessStatusCode();
        var key = await response.ReadAsync<DeviceKeyResponse>();
        return (Authed(factory, key), key);
    }

    private static async Task<(HttpClient, DeviceKeyResponse)> PairOn(WebApplicationFactoryOfProgram factory, HttpClient device)
    {
        var code = await (await device.PostAsync("/v1/pairing/codes", null)).ReadAsync<PairingCodeResponse>();
        var redeem = await Client(factory).PostAsJsonAsync("/v1/pairing/redeem", new RedeemPairingRequest(code.Code, "B", "android"));
        redeem.EnsureSuccessStatusCode();
        var key = await redeem.ReadAsync<DeviceKeyResponse>();
        return (Authed(factory, key), key);
    }

    private static HttpClient Client(WebApplicationFactoryOfProgram factory)
    {
        var client = factory.CreateClient();
        client.DefaultRequestHeaders.Add(Protocol.Protocol.Header, Protocol.Protocol.Version.ToString());
        return client;
    }

    private static HttpClient Authed(WebApplicationFactoryOfProgram factory, DeviceKeyResponse key)
    {
        var client = Client(factory);
        client.DefaultRequestHeaders.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", key.DeviceKey);
        return client;
    }

    private sealed class RecordingHub : IEventHub
    {
        private readonly ConcurrentQueue<Guid> _devices = new();

        public Guid[] Devices => [.. _devices];

        public void NotifyDevice(Guid deviceId) => _devices.Enqueue(deviceId);

        public void NotifyUser(Guid userId, string topic) { }

        public IEventSubscription Subscribe(Guid userId, Guid? deviceId, CancellationToken ct) =>
            new NoOpEventHub().Subscribe(userId, deviceId, ct);
    }
}
