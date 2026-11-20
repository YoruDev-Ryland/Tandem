using EmuSync.Protocol;
using EmuSync.Server.Data;
using EmuSync.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace EmuSync.Server.Tests.Web;

public class ConflictListTests
{
    private const string Email = "a@example.com";

    private static ServerFactory NewFactory()
    {
        var factory = new ServerFactory();
        factory.Settings["EmuSync:AllowRegistration"] = "true";
        return factory;
    }

    private static async Task<SaveVersion> Version(ServerFactory f, Guid id)
    {
        using var scope = f.Services.CreateScope();
        return await scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>().SaveVersions.AsNoTracking().SingleAsync(v => v.Id == id);
    }

    [Fact]
    public async Task ConflictList_FirstSync_ReturnsBothSidesWithHashAndSize()
    {
        using var factory = NewFactory();
        var (odin, odinKey) = await factory.Register(Email, deviceName: "Odin");
        var (retroid, retroidKey) = await factory.PairSecondDevice(odin, "Retroid");
        // "Aaa" sorts first by name but is Concurrent, so it comes after the FirstSync conflict on "Zelda".
        var a1 = await odin.UploadSave("Aaa", "a-one", null);
        var aConcurrent = await retroid.UploadSave("Aaa", "a-two", Guid.NewGuid());
        var head = await odin.UploadSave("Zelda", "server copy", null);
        factory.Time.Advance(TimeSpan.FromMinutes(5));
        var mine = await retroid.UploadSave("Zelda", "device", null);
        Assert.Equal(SaveUploadStatus.Conflict, mine.Status);
        Assert.Equal(SaveUploadStatus.Conflict, aConcurrent.Status);
        var web = await factory.WebLogin(Email);

        var rows = await web.GetOk<ConflictRow[]>("/v1/web/conflicts");

        Assert.Equal(["Zelda", "Aaa"], rows.Select(r => r.GameKey));
        var row = rows[0];
        Assert.Equal(head.ItemId, row.ItemId);
        Assert.Equal("eden", row.EmulatorId);
        Assert.Equal(ItemKind.Save, row.Kind);
        Assert.Null(row.Slot);
        Assert.Null(row.Core);
        Assert.Equal(ConflictReason.FirstSync, row.Reason);
        Assert.Equal(2, row.Sides.Length);

        var server = row.Sides[0];
        var headRow = await Version(factory, head.VersionId);
        Assert.Equal(new ConflictSide(head.VersionId, odinKey.DeviceId, "Odin", headRow.ReceivedUtc, "server copy".Length, 1,
            headRow.ManifestHash, true), server);
        var device = row.Sides[1];
        var deviceRow = await Version(factory, mine.VersionId);
        Assert.Equal(new ConflictSide(mine.VersionId, retroidKey.DeviceId, "Retroid", deviceRow.ReceivedUtc, "device".Length, 1,
            deviceRow.ManifestHash, false), device);
        Assert.NotEqual(server.ManifestHash, device.ManifestHash);

        Assert.Equal(ConflictReason.Concurrent, rows[1].Reason);
        Assert.Equal([a1.VersionId, aConcurrent.VersionId], rows[1].Sides.Select(s => s.VersionId));
    }

    [Fact]
    public async Task ConflictList_Resolved_NotListed()
    {
        using var factory = NewFactory();
        var (odin, _) = await factory.Register(Email, deviceName: "Odin");
        var (retroid, _) = await factory.PairSecondDevice(odin, "Retroid");
        var head = await odin.UploadSave("Zelda", "server copy", null);
        await retroid.UploadSave("Zelda", "device", null);
        var web = await factory.WebLogin(Email);
        Assert.Single(await web.GetOk<ConflictRow[]>("/v1/web/conflicts"));

        (await web.PostJsonAsync($"/v1/conflicts/{head.ItemId}/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepVersion, head.VersionId))).EnsureSuccessStatusCode();

        Assert.Empty(await web.GetOk<ConflictRow[]>("/v1/web/conflicts"));
    }

    private static async Task<(ServerFactory Factory, HttpClient Web, SaveUploadResponse Head, SaveUploadResponse Device)> KeepBothSetup()
    {
        var factory = NewFactory();
        var (odin, _) = await factory.Register(Email, deviceName: "Odin");
        var (retroid, _) = await factory.PairSecondDevice(odin, "Retroid");
        var head = await odin.UploadSave("Zelda", "server copy", null);
        var device = await retroid.UploadSave("Zelda", "device", null);
        return (factory, await factory.WebLogin(Email), head, device);
    }

    private static async Task<VersionSummary> Summary(HttpClient web, Guid versionId) =>
        (await web.GetOk<GameDetail>("/v1/web/games/eden/Zelda")).Items.SelectMany(i => i.Versions).Single(v => v.Id == versionId);

    [Fact]
    public async Task KeepBoth_WithVariantName_LabelsVariant()
    {
        var (factory, web, head, device) = await KeepBothSetup();
        using var _ = factory;

        (await web.PostJsonAsync($"/v1/conflicts/{head.ItemId}/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepBoth, head.VersionId, "  Speedrun file  "))).EnsureSuccessStatusCode();

        var kept = await Summary(web, device.VersionId);
        Assert.Equal("Speedrun file", kept.Variant);
        Assert.True(kept.Pinned);
        Assert.True(kept.Resolved);
        Assert.Equal((await Version(factory, device.VersionId)).ManifestHash, kept.ManifestHash);
        Assert.Equal(1, kept.FileCount);
        var winner = await Summary(web, head.VersionId);
        Assert.True(winner.IsHead);
        Assert.Null(winner.Variant);
    }

    [Fact]
    public async Task KeepBoth_NoName_DefaultsFromDeviceName()
    {
        var (factory, web, head, device) = await KeepBothSetup();
        using var _ = factory;

        (await web.PostJsonAsync($"/v1/conflicts/{head.ItemId}/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepBoth, head.VersionId))).EnsureSuccessStatusCode();

        Assert.Equal("From Retroid", (await Summary(web, device.VersionId)).Variant);
    }

    [Fact]
    public async Task KeepBoth_VariantNameTooLong_Rejected()
    {
        var (factory, web, head, _) = await KeepBothSetup();
        using var _f = factory;

        var response = await web.PostJsonAsync($"/v1/conflicts/{head.ItemId}/resolve",
            new ResolveConflictRequest(ResolveChoice.KeepBoth, head.VersionId, new string('x', 81)));

        Assert.Equal(System.Net.HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Equal("bad_variant_name", (await response.ReadAsync<ErrorResponse>()).Code);
    }
}
