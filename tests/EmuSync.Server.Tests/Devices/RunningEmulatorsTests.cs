using System.Net;
using System.Net.Http.Json;
using EmuSync.Protocol;
using EmuSync.Server.Data;
using EmuSync.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace EmuSync.Server.Tests.Devices;

public class RunningEmulatorsTests
{
    private const string Email = "a@example.com";

    private static ServerFactory NewFactory()
    {
        var factory = new ServerFactory();
        factory.Settings["EmuSync:AllowRegistration"] = "true";
        return factory;
    }

    private static async Task<string[]> Running(HttpClient client, Guid deviceId) =>
        (await client.GetOk<DeviceSummary[]>("/v1/devices")).Single(d => d.Id == deviceId).RunningEmulators;

    [Fact]
    public async Task RunningEmulators_ClearedWhenStatusStale()
    {
        using var factory = NewFactory();
        var (odin, _) = await factory.Register(Email, deviceName: "Odin");
        var (retroid, retroidKey) = await factory.PairSecondDevice(odin, "Retroid");
        (await retroid.ReportStatus(["eden", "retroarch"])).EnsureSuccessStatusCode();
        var web = await factory.WebLogin(Email);

        Assert.Equal(["eden", "retroarch"], await Running(web, retroidKey.DeviceId));
        Assert.Equal(["eden", "retroarch"], (await retroid.GetOk<DeviceSummary>("/v1/devices/me")).RunningEmulators);
        Assert.Equal(["eden", "retroarch"], (await web.GetOk<HomeResponse>("/v1/web/home")).Devices.Single(d => d.Id == retroidKey.DeviceId).RunningEmulators);
        Assert.Equal(["eden", "retroarch"], (await web.GetOk<DeviceDetail>($"/v1/web/devices/{retroidKey.DeviceId}")).Summary.RunningEmulators);

        factory.Time.Advance(TimeSpan.FromMinutes(3));

        Assert.Empty(await Running(web, retroidKey.DeviceId));
        Assert.Empty((await web.GetOk<DeviceDetail>($"/v1/web/devices/{retroidKey.DeviceId}")).Summary.RunningEmulators);
    }

    [Fact]
    public async Task RunningEmulators_ReplacedOnEachReport_OlderClientsMeanNone()
    {
        using var factory = NewFactory();
        var (odin, odinKey) = await factory.Register(Email, deviceName: "Odin");
        (await odin.ReportStatus(["eden"])).EnsureSuccessStatusCode();
        Assert.Equal(["eden"], await Running(odin, odinKey.DeviceId));

        (await odin.ReportStatus(["retroarch"])).EnsureSuccessStatusCode();
        Assert.Equal(["retroarch"], await Running(odin, odinKey.DeviceId));

        (await odin.ReportStatus(null)).EnsureSuccessStatusCode(); // an older client never sends the field
        Assert.Empty(await Running(odin, odinKey.DeviceId));
    }

    [Fact]
    public async Task RunningEmulators_UnknownIdsDropped_ReportAccepted()
    {
        using var factory = NewFactory();
        var (odin, odinKey) = await factory.Register(Email, deviceName: "Odin");

        (await odin.ReportStatus(["not-an-emulator", "eden"])).EnsureSuccessStatusCode();
        Assert.Equal(["eden"], await Running(odin, odinKey.DeviceId));

        (await odin.ReportStatus(["not-an-emulator"])).EnsureSuccessStatusCode();
        Assert.Empty(await Running(odin, odinKey.DeviceId));
    }

    [Fact]
    public async Task RunningEmulators_UnknownInstalledEmulator_StillRejected()
    {
        using var factory = NewFactory();
        var (odin, _) = await factory.Register(Email, deviceName: "Odin");

        var response = await odin.PutAsJsonAsync("/v1/devices/me/status", new DeviceStatusRequest("android", "1.0", true,
            [new EmulatorStatus("not-an-emulator", "pkg", "1", null)], new Dictionary<string, string>(), null));

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Equal("unknown_emulator", (await response.ReadAsync<ErrorResponse>()).Code);
    }

    [Theory]
    [InlineData("[\"eden\"]")] // a bare array, as an early build stored it
    [InlineData("not json")]
    public async Task RunningEmulators_UnreadableStoredValue_ReadsAsNone(string stored)
    {
        using var factory = NewFactory();
        var (odin, odinKey) = await factory.Register(Email, deviceName: "Odin");
        await odin.Install("G1");
        await odin.UploadSave("G1", "v1", null);
        using (var scope = factory.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>();
            var device = await db.Devices.SingleAsync(d => d.Id == odinKey.DeviceId);
            device.RunningEmulatorsJson = stored;
            await db.SaveChangesAsync();
        }
        var web = await factory.WebLogin(Email);

        Assert.Empty(await Running(web, odinKey.DeviceId));
        Assert.Empty((await web.GetOk<HomeResponse>("/v1/web/home")).Devices.Single(d => d.Id == odinKey.DeviceId).RunningEmulators);
        Assert.Single(await web.GetOk<GameRow[]>("/v1/web/games"));
    }

    [Fact]
    public async Task RunningEmulators_OtherRequestsDoNotKeepStaleStatusAlive()
    {
        using var factory = NewFactory();
        var (odin, _) = await factory.Register(Email, deviceName: "Odin");
        var (retroid, retroidKey) = await factory.PairSecondDevice(odin, "Retroid");
        await odin.Install("G1");
        await retroid.Install("G1");
        await odin.UploadSave("G1", "v1", null);
        (await retroid.ReportStatus(["eden"])).EnsureSuccessStatusCode();
        var web = await factory.WebLogin(Email);
        Assert.Equal(DeviceGameState.Waiting, await RetroidState(web));

        factory.Time.Advance(TimeSpan.FromMinutes(3));
        Assert.Single(await retroid.PendingDeliveries()); // a device-key request: the device is online, its status is stale

        Assert.Empty(await Running(web, retroidKey.DeviceId));
        Assert.Empty((await retroid.GetOk<DeviceSummary>("/v1/devices/me")).RunningEmulators);
        Assert.Equal(DeviceGameState.Pending, await RetroidState(web));
    }

    [Fact]
    public async Task RunningEmulators_EmptyWhileShizukuOff()
    {
        using var factory = NewFactory();
        var (odin, odinKey) = await factory.Register(Email, deviceName: "Odin");

        (await odin.PutAsJsonAsync("/v1/devices/me/status", new DeviceStatusRequest("android", "1.0", false, [],
            new Dictionary<string, string>(), ["eden"]))).EnsureSuccessStatusCode();

        Assert.Empty(await Running(odin, odinKey.DeviceId));
    }

    private static async Task<DeviceGameState> RetroidState(HttpClient web) =>
        (await web.GetOk<GameRow[]>("/v1/web/games")).Single(r => r.GameKey == "G1").Devices.Single(d => d.DeviceName == "Retroid").State;
}
