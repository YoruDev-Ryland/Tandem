using System.Net;
using System.Net.Http.Json;
using Tandem.Protocol;
using Tandem.Server.Data;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Tandem.Server.Tests.Devices;

/// <summary>
/// Before browser sign-in stopped creating devices, signing in with a pairing code made a device with platform "web".
/// Those rows are left alone, but they are not devices that sync, so no list or game state shows them.
/// </summary>
public class LegacyWebDeviceTests
{
    private const string Email = "a@example.com";

    private static async Task<Guid> AddLegacyWebDevice(HttpClient owner, ServerFactory factory)
    {
        var code = await (await owner.PostAsync("/v1/pairing/codes", null)).ReadAsync<PairingCodeResponse>();
        var redeem = await factory.Client().PostAsJsonAsync("/v1/pairing/redeem", new RedeemPairingRequest(code.Code, "Web browser", "web"));
        redeem.EnsureSuccessStatusCode();
        return (await redeem.ReadAsync<DeviceKeyResponse>()).DeviceId;
    }

    [Fact]
    public async Task LegacyWebDevice_HiddenFromDeviceListsAndGameStates_ButKept()
    {
        using var factory = new ServerFactory();
        factory.Settings["Tandem:AllowRegistration"] = "true";
        var (odin, odinKey) = await factory.Register(Email, deviceName: "Odin");
        await odin.Install("G1");
        await odin.UploadSave("G1", "v1", null);
        var webId = await AddLegacyWebDevice(odin, factory);
        var web = await factory.WebLogin(Email);

        Assert.Equal([odinKey.DeviceId], (await web.GetOk<DeviceSummary[]>("/v1/devices")).Select(d => d.Id));
        Assert.Equal([odinKey.DeviceId], (await odin.GetOk<DeviceSummary[]>("/v1/devices")).Select(d => d.Id));
        Assert.Equal([odinKey.DeviceId], (await web.GetOk<HomeResponse>("/v1/web/home")).Devices.Select(d => d.Id));
        var row = (await web.GetOk<GameRow[]>("/v1/web/games")).Single(r => r.GameKey == "G1");
        Assert.Equal(["Odin"], row.Devices.Select(d => d.DeviceName));
        var detail = await web.GetOk<GameDetail>("/v1/web/games/eden/G1");
        Assert.Equal(["Odin"], detail.Row.Devices.Select(d => d.DeviceName));
        Assert.Equal(HttpStatusCode.NotFound, (await web.GetAsync($"/v1/web/devices/{webId}")).StatusCode);

        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        var kept = await db.Devices.AsNoTracking().SingleAsync(d => d.Id == webId);
        Assert.Null(kept.RevokedUtc);
    }
}
