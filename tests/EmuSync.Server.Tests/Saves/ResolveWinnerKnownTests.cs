using System.Net;
using System.Net.Http.Json;
using System.Text;
using EmuSync.Protocol;
using EmuSync.Server.Data;
using EmuSync.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace EmuSync.Server.Tests.Saves;

public class ResolveWinnerKnownTests
{
    private const string Game = "0100E66006406000";

    private static ServerFactory NewFactory()
    {
        var factory = new ServerFactory();
        factory.Settings["EmuSync:AllowRegistration"] = "true";
        return factory;
    }

    private static SaveUploadRequest Req(IReadOnlyList<ManifestEntry> manifest, Guid? baseId) =>
        new("eden", ItemKind.Save, Game, "", "", baseId, manifest, null);

    private static Task<List<ManifestEntry>> Files(HttpClient device, string content) =>
        Fixtures.Manifest(device, ("data.dat", Encoding.UTF8.GetBytes(content)));

    private static async Task<SaveUploadResponse> Upload(HttpClient device, SaveUploadRequest request)
    {
        var response = await device.PostJsonAsync("/v1/saves/upload", request);
        Assert.True(response.StatusCode == HttpStatusCode.OK, $"{response.StatusCode}: {await response.Content.ReadAsStringAsync()}");
        return await response.ReadAsync<SaveUploadResponse>();
    }

    private static async Task Install(HttpClient device)
    {
        var response = await device.PutAsJsonAsync("/v1/devices/me/installs/eden",
            new InstallsRequest([new InstalledGame(Game, null, null)]));
        Assert.True(response.IsSuccessStatusCode, $"{response.StatusCode}: {await response.Content.ReadAsStringAsync()}");
    }

    // Deliveries are hidden while an item has an open conflict, so the pending one is fetched up front.
    private static async Task<Guid> PendingDelivery(HttpClient device)
    {
        var response = await device.GetAsync("/v1/deliveries");
        return Assert.Single((await response.ReadAsync<DeliveryList>()).Items).Id;
    }

    private static async Task Ack(HttpClient device, Guid deliveryId) =>
        (await device.PostJsonAsync($"/v1/deliveries/{deliveryId}/ack", new AckRequest(DeliveryResult.Applied, null)))
            .EnsureSuccessStatusCode();

    private static async Task<Guid?> Known(ServerFactory f, Guid deviceId, Guid itemId)
    {
        using var scope = f.Services.CreateScope();
        return await scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>().DeviceItemStates
            .Where(s => s.DeviceId == deviceId && s.ItemId == itemId)
            .Select(s => (Guid?)s.KnownVersionId).SingleOrDefaultAsync();
    }

    private static async Task<Guid?> Head(ServerFactory f, Guid itemId)
    {
        using var scope = f.Services.CreateScope();
        return (await scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>().SaveItems.AsNoTracking()
            .SingleAsync(i => i.Id == itemId)).HeadVersionId;
    }

    private static Task<HttpResponseMessage> KeepVersion(HttpClient client, Guid itemId, Guid keep) =>
        client.PostJsonAsync($"/v1/conflicts/{itemId}/resolve", new ResolveConflictRequest(ResolveChoice.KeepVersion, keep));

    [Fact]
    public async Task Resolve_UploaderAppliedHeadSinceUpload_KnownUnchanged_NextUploadConflicts()
    {
        using var factory = NewFactory();
        var (a, aKey) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        await Install(a);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var h = await Upload(b, Req(await Files(b, "h"), v1.VersionId));
        var headDelivery = await PendingDelivery(a);
        var c = await Upload(a, Req(await Files(a, "c"), v1.VersionId));
        Assert.Equal(SaveUploadStatus.Conflict, c.Status);

        await Ack(a, headDelivery); // A now knows H, although its conflict upload C is still open
        Assert.Equal(h.VersionId, await Known(factory, aKey.DeviceId, v1.ItemId));

        (await KeepVersion(a, v1.ItemId, c.VersionId)).EnsureSuccessStatusCode();

        Assert.Equal(h.VersionId, await Known(factory, aKey.DeviceId, v1.ItemId));
        Assert.Equal(c.VersionId, await Head(factory, v1.ItemId));

        // A's content is H, not C: an upload based on H must not silently replace the resolved winner.
        var next = await Upload(a, Req(await Files(a, "next"), h.VersionId));
        Assert.Equal(SaveUploadStatus.Conflict, next.Status);
        Assert.Equal(c.VersionId, await Head(factory, v1.ItemId));
    }

    [Fact]
    public async Task Resolve_UploaderStillOnBase_KnownSetToWinner()
    {
        using var factory = NewFactory();
        var (a, aKey) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        await Upload(b, Req(await Files(b, "h"), v1.VersionId));
        var c = await Upload(a, Req(await Files(a, "c"), v1.VersionId));
        Assert.Equal(SaveUploadStatus.Conflict, c.Status);
        Assert.Equal(v1.VersionId, await Known(factory, aKey.DeviceId, v1.ItemId));

        (await KeepVersion(a, v1.ItemId, c.VersionId)).EnsureSuccessStatusCode();

        Assert.Equal(c.VersionId, await Known(factory, aKey.DeviceId, v1.ItemId));
        var next = await Upload(a, Req(await Files(a, "next"), c.VersionId));
        Assert.Equal(SaveUploadStatus.Accepted, next.Status);
    }

    [Fact]
    public async Task KeepVersion_DisplacedHead_IsPinnedAndLabelled_SoRetentionKeepsIt()
    {
        using var factory = NewFactory();
        var (a, _) = await factory.Register("a@example.com");
        var (b, _) = await factory.PairSecondDevice(a, "B");
        await Install(a);
        var v1 = await Upload(a, Req(await Files(a, "v1"), null));
        var h = await Upload(b, Req(await Files(b, "h"), v1.VersionId));
        var c = await Upload(a, Req(await Files(a, "c"), v1.VersionId));
        Assert.Equal(SaveUploadStatus.Conflict, c.Status);

        (await KeepVersion(a, v1.ItemId, c.VersionId)).EnsureSuccessStatusCode();

        using var scope = factory.Services.CreateScope();
        var displaced = await scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>().SaveVersions.AsNoTracking()
            .SingleAsync(v => v.Id == h.VersionId);
        Assert.True(displaced.Pinned);
        Assert.Equal("From B", displaced.Variant);
    }
}
