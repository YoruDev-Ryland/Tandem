using System.Net;
using System.Net.Http.Json;
using EmuSync.Protocol;
using EmuSync.Server.Tests.Infrastructure;

namespace EmuSync.Server.Tests;

public class ServerInfoTests
{
    [Fact]
    public async Task Healthz_returns_ok()
    {
        using var factory = new ServerFactory();
        var response = await factory.CreateClient().GetAsync("/healthz");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.StartsWith("ok", await response.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task ServerInfo_works_without_protocol_header()
    {
        using var factory = new ServerFactory();
        var response = await factory.CreateClient().GetAsync("/v1/server-info");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var info = await response.ReadAsync<ServerInfo>();
        Assert.Equal("EmuSync", info.Name);
        Assert.Equal(1, info.Protocol);
    }

    [Fact]
    public async Task Api_without_protocol_header_is_426()
    {
        using var factory = new ServerFactory();
        var response = await factory.CreateClient().GetAsync("/v1/devices/me");

        Assert.Equal(HttpStatusCode.UpgradeRequired, response.StatusCode);
        var error = await response.ReadAsync<ErrorResponse>();
        Assert.Equal("protocol_mismatch", error.Code);
    }

    [Fact]
    public async Task ServerName_is_trimmed_to_60_chars()
    {
        using var factory = new ServerFactory();
        factory.Settings["EmuSync:ServerName"] = new string('x', 80);
        var info = await (await factory.Client().GetAsync("/v1/server-info")).ReadAsync<ServerInfo>();

        Assert.Equal(60, info.Name.Length);
    }

    private static async Task<bool> RegistrationOpen(ServerFactory f) =>
        (await (await f.Client().GetAsync("/v1/server-info")).ReadAsync<ServerInfo>()).RegistrationOpen;

    [Fact]
    public async Task RegistrationOpen_true_on_empty_server_even_when_env_closed()
    {
        using var factory = new ServerFactory();
        Assert.True(await RegistrationOpen(factory));
    }

    [Fact]
    public async Task RegistrationOpen_false_after_first_user_when_closed()
    {
        using var factory = new ServerFactory();
        await factory.Register("a@example.com");
        Assert.False(await RegistrationOpen(factory));
    }

    [Fact]
    public async Task RegistrationOpen_follows_admin_setting()
    {
        using var factory = new ServerFactory();
        await factory.Register("a@example.com");
        var admin = await factory.WebLogin("a@example.com");
        await admin.PutAsJsonAsync("/v1/admin/settings", new AdminSettings(true));
        Assert.True(await RegistrationOpen(factory));
        await admin.PutAsJsonAsync("/v1/admin/settings", new AdminSettings(false));
        Assert.False(await RegistrationOpen(factory));
    }
}
