using System.Net;
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
}
