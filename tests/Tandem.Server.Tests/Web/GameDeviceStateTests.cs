using Tandem.Protocol;
using Tandem.Server.Tests.Infrastructure;

namespace Tandem.Server.Tests.Web;

public class GameDeviceStateTests
{
    private const string Email = "a@example.com";
    private const string Game = "0100E66006406000";

    private static ServerFactory NewFactory()
    {
        var factory = new ServerFactory();
        factory.Settings["Tandem:AllowRegistration"] = "true";
        return factory;
    }

    private static async Task<GameRow> Row(HttpClient web) =>
        Assert.Single(await web.GetOk<GameRow[]>("/v1/web/games"), r => r.GameKey == Game);

    private static (string Name, DeviceGameState State)[] States(GameRow row) =>
        row.Devices.Select(d => (d.DeviceName, d.State)).ToArray();

    [Fact]
    public async Task GameRow_DeviceStates_NewestHasItNotInstalled()
    {
        using var factory = NewFactory();
        var (odin, odinKey) = await factory.Register(Email, deviceName: "Odin");
        var (retroid, _) = await factory.PairSecondDevice(odin, "Retroid");
        await factory.PairSecondDevice(odin, "Ayn"); // never installs the game
        await odin.Install(Game);
        await retroid.Install(Game);
        await odin.UploadSave(Game, "v1", null);
        await retroid.AckApplied(Assert.Single(await retroid.PendingDeliveries()).Id);
        var web = await factory.WebLogin(Email);

        var row = await Row(web);

        Assert.Equal(odinKey.DeviceId, row.NewestOnDeviceId);
        Assert.Equal("Odin", row.NewestOnDeviceName);
        // Newest first, then by name.
        Assert.Equal([("Odin", DeviceGameState.Newest), ("Ayn", DeviceGameState.NotInstalled), ("Retroid", DeviceGameState.HasIt)], States(row));
        // The game detail carries the same row.
        var detail = await web.GetOk<GameDetail>($"/v1/web/games/eden/{Game}");
        Assert.Equal(States(row), States(detail.Row));
    }

    [Fact]
    public async Task GameRow_PendingDelivery_IsPending()
    {
        using var factory = NewFactory();
        var (odin, _) = await factory.Register(Email, deviceName: "Odin");
        var (retroid, _) = await factory.PairSecondDevice(odin, "Retroid");
        await odin.Install(Game);
        await retroid.Install(Game);
        await odin.UploadSave(Game, "v1", null);
        Assert.Single(await retroid.PendingDeliveries());
        var web = await factory.WebLogin(Email);

        Assert.Equal([("Odin", DeviceGameState.Newest), ("Retroid", DeviceGameState.Pending)], States(await Row(web)));
    }

    [Fact]
    public async Task GameRow_PendingAndEmulatorRunning_IsWaiting()
    {
        using var factory = NewFactory();
        var (odin, _) = await factory.Register(Email, deviceName: "Odin");
        var (retroid, _) = await factory.PairSecondDevice(odin, "Retroid");
        await odin.Install(Game);
        await retroid.Install(Game);
        await odin.UploadSave(Game, "v1", null);
        (await retroid.ReportStatus(["eden"])).EnsureSuccessStatusCode();
        var web = await factory.WebLogin(Email);

        Assert.Equal([("Odin", DeviceGameState.Newest), ("Retroid", DeviceGameState.Waiting)], States(await Row(web)));

        // Another emulator running does not hold the delivery back.
        (await retroid.ReportStatus(["retroarch"])).EnsureSuccessStatusCode();
        Assert.Equal(DeviceGameState.Pending, (await Row(web)).Devices.Single(d => d.DeviceName == "Retroid").State);
    }

    [Fact]
    public async Task GameRow_OpenConflict_BothDevicesDiffer()
    {
        using var factory = NewFactory();
        var (odin, _) = await factory.Register(Email, deviceName: "Odin");
        var (retroid, _) = await factory.PairSecondDevice(odin, "Retroid");
        await factory.PairSecondDevice(odin, "Ayn");
        await odin.Install(Game);
        await retroid.Install(Game);
        await odin.UploadSave(Game, "server copy", null);
        Assert.Equal(SaveUploadStatus.Conflict, (await retroid.UploadSave(Game, "device copy", null)).Status);
        var web = await factory.WebLogin(Email);

        var row = await Row(web);

        Assert.True(row.OpenConflict);
        Assert.Equal([("Ayn", DeviceGameState.NotInstalled), ("Odin", DeviceGameState.Differs), ("Retroid", DeviceGameState.Differs)],
            States(row));
    }
}
