using System.Net.Http.Json;
using Tandem.Protocol;
using Tandem.Server.Data;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Tandem.Server.Tests.Saves;

/// <summary>C6: each save version records the uploading device's emulator version; FileCount is stored.</summary>
public class EmulatorVersionTests
{
    private static Task Status(HttpClient device, string version) =>
        device.PutAsJsonAsync("/v1/devices/me/status", new DeviceStatusRequest("android", "1.0", true,
            [new EmulatorStatus("eden", "dev.eden.eden_emulator", version, null)], new Dictionary<string, string>()))
            .ContinueWith(t => t.Result.EnsureSuccessStatusCode());

    [Fact]
    public async Task A_version_records_the_emulator_version_reported_before_the_upload()
    {
        using var f = new ServerFactory();
        var (device, _) = await f.Register("a@example.com");
        var first = await device.UploadSave("g", "one", null); // no status yet
        await Status(device, "0.0.3");
        var second = await device.UploadSave("g", "two", first.VersionId);
        await Status(device, "0.0.4");

        var web = await f.WebLogin("a@example.com");
        var game = await web.GetOk<GameDetail>("/v1/web/games/eden/g");
        var versions = game.Items.Single().Versions.ToDictionary(v => v.Id);
        Assert.Null(versions[first.VersionId].EmulatorVersion);
        Assert.Equal("0.0.3", versions[second.VersionId].EmulatorVersion);
        Assert.All(versions.Values, v => Assert.Equal(1, v.FileCount));

        // A restore copy keeps the original's version: that build made the files.
        var restore = await web.PostJsonAsync($"/v1/saves/{second.ItemId}/versions/{first.VersionId}/restore",
            new RestoreRequest((await web.GetOk<DeviceSummary[]>("/v1/devices"))[0].Id));
        var restored = await restore.ReadAsync<RestoreResponse>();
        game = await web.GetOk<GameDetail>("/v1/web/games/eden/g");
        Assert.Null(game.Items.Single().Versions.Single(v => v.Id == restored.VersionId).EmulatorVersion);
    }

    [Fact]
    public async Task Rows_without_a_stored_file_count_still_report_it_from_the_manifest()
    {
        using var f = new ServerFactory();
        var (device, _) = await f.Register("a@example.com");
        var manifest = await Fixtures.Manifest(device, ("a.sav", "1"u8.ToArray()), ("b.sav", "2"u8.ToArray()));
        (await device.PostJsonAsync("/v1/saves/upload",
            new SaveUploadRequest("eden", ItemKind.Save, "g", "", "", null, manifest, null))).EnsureSuccessStatusCode();
        using (var scope = f.Services.CreateScope())
            await scope.ServiceProvider.GetRequiredService<TandemDbContext>().SaveVersions
                .ExecuteUpdateAsync(s => s.SetProperty(v => v.FileCount, (int?)null));

        var web = await f.WebLogin("a@example.com");
        Assert.Equal(2, (await web.GetOk<GameDetail>("/v1/web/games/eden/g")).Items.Single().Versions.Single().FileCount);
    }
}
