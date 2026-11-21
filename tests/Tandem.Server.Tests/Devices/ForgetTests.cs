using System.Net;
using Tandem.Protocol;
using Tandem.Server.Data;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Tandem.Server.Tests.Devices;

/// <summary>
/// POST /v1/devices/me/forget: a device that forgot what it held for some emulators (a variant switch, a new sign-in)
/// has the server drop its record of that too, so no later upload is taken as based on the head and the heads are
/// delivered again.
/// </summary>
public class ForgetTests
{
    private const string Game = "0100E66006406000";
    private const string Other = "0100AAAA00000000";

    private static ServerFactory NewFactory()
    {
        var factory = new ServerFactory();
        factory.Settings["Tandem:AllowRegistration"] = "true";
        return factory;
    }

    private static Task<HttpResponseMessage> Forget(HttpClient device, params string[] ids) =>
        device.PostJsonAsync("/v1/devices/me/forget", new ForgetRequest(ids));

    private static async Task ForgetOk(HttpClient device, params string[] ids)
    {
        var r = await Forget(device, ids);
        Assert.True(r.StatusCode == HttpStatusCode.NoContent, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
    }

    private static async Task<T> InDb<T>(ServerFactory f, Func<TandemDbContext, Task<T>> query)
    {
        using var scope = f.Services.CreateScope();
        return await query(scope.ServiceProvider.GetRequiredService<TandemDbContext>());
    }

    private static Task<Guid?> Known(ServerFactory f, Guid deviceId, Guid itemId) =>
        InDb(f, db => db.DeviceItemStates.Where(s => s.DeviceId == deviceId && s.ItemId == itemId)
            .Select(s => (Guid?)s.KnownVersionId).SingleOrDefaultAsync());

    [Fact]
    public async Task After_forget_a_different_upload_without_a_base_conflicts_instead_of_fast_forwarding()
    {
        using var f = NewFactory();
        var (a, _) = await f.Register("a@example.com");
        var v1 = await a.UploadSave(Game, "newest", null);

        await ForgetOk(a, "eden");
        var up = await a.UploadSave(Game, "older, from the other variant", null);   // no Fresh flag

        Assert.Equal(SaveUploadStatus.Conflict, up.Status);
        Assert.Equal(ConflictReason.FirstSync,
            await InDb(f, db => db.SaveVersions.Where(v => v.Id == up.VersionId).Select(v => v.ConflictReason).SingleAsync()));
        Assert.Equal(v1.VersionId, await InDb(f, db => db.SaveItems.Where(i => i.Id == v1.ItemId).Select(i => i.HeadVersionId).SingleAsync()));
    }

    [Fact]
    public async Task After_forget_identical_content_is_unchanged()
    {
        using var f = NewFactory();
        var (a, _) = await f.Register("a@example.com");
        var v1 = await a.UploadSave(Game, "newest", null);

        await ForgetOk(a, "eden");
        var up = await a.UploadSave(Game, "newest", null);

        Assert.Equal(SaveUploadStatus.Unchanged, up.Status);
        Assert.Equal(v1.VersionId, up.VersionId);
    }

    [Fact]
    public async Task Forget_queues_the_head_of_an_installed_game_the_device_was_recorded_as_holding()
    {
        using var f = NewFactory();
        var (a, aKey) = await f.Register("a@example.com");
        await a.Install(Game);
        var v1 = await a.UploadSave(Game, "newest", null);
        Assert.Empty(await a.PendingDeliveries());   // the server thinks the device holds the head

        await ForgetOk(a, "eden");

        // Queued by the forget itself, not only by the next delivery poll.
        var queued = await InDb(f, db => db.Deliveries.Where(d => d.DeviceId == aKey.DeviceId && d.AckedUtc == null).ToListAsync());
        var d = Assert.Single(queued);
        Assert.Equal(v1.ItemId, d.TargetId);
        Assert.Equal(v1.VersionId, d.VersionId);
    }

    [Fact]
    public async Task Forget_touches_only_this_device_and_the_named_emulators()
    {
        using var f = NewFactory();
        var (a, aKey) = await f.Register("a@example.com");
        var (b, bKey) = await f.PairSecondDevice(a, "B");
        var eden = await a.UploadSave(Game, "a", null);
        var edenB = await b.UploadSave(Game, "b", eden.VersionId);
        await a.UploadSave(Game, "b", edenB.VersionId);   // a now knows B's head too
        var dolphin = await a.UploadSave(Other, "d", null, emulatorId: "dolphin");

        await ForgetOk(a, "eden", "not-an-emulator");   // unknown ids are ignored

        Assert.Null(await Known(f, aKey.DeviceId, eden.ItemId));
        Assert.Equal(edenB.VersionId, await Known(f, bKey.DeviceId, eden.ItemId));
        Assert.Equal(dolphin.VersionId, await Known(f, aKey.DeviceId, dolphin.ItemId));
    }

    [Fact]
    public async Task Forget_needs_a_device_and_at_most_fifty_ids()
    {
        using var f = NewFactory();
        var (a, _) = await f.Register("a@example.com");
        var tooMany = Enumerable.Repeat("eden", ForgetRequest.MaxEmulators + 1).ToArray();
        Assert.Equal(HttpStatusCode.BadRequest, (await Forget(a, tooMany)).StatusCode);
        var web = await f.WebLogin("a@example.com");
        Assert.Equal(HttpStatusCode.BadRequest, (await Forget(web, "eden")).StatusCode);
    }
}
