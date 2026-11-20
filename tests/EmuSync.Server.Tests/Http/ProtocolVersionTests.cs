using System.Net;
using System.Net.Http.Json;
using System.Text;
using EmuSync.Protocol;
using EmuSync.Server.Tests.Infrastructure;

namespace EmuSync.Server.Tests.Http;

public class ProtocolVersionTests
{
    private static ServerFactory NewFactory()
    {
        var factory = new ServerFactory();
        factory.Settings["EmuSync:AllowRegistration"] = "true";
        return factory;
    }

    /// <summary>A cookie-session client like a browser: no protocol header.</summary>
    private static async Task<HttpClient> BrowserClient(ServerFactory factory, string email)
    {
        var client = await factory.WebLogin(email);
        client.DefaultRequestHeaders.Remove(Protocol.Protocol.Header);
        return client;
    }

    private static async Task<Guid> UploadSave(HttpClient device)
    {
        var manifest = await Fixtures.Manifest(device, ("data.dat", Encoding.UTF8.GetBytes("hello")));
        var r = await device.PostJsonAsync("/v1/saves/upload",
            new SaveUploadRequest("eden", ItemKind.Save, "G1", "", "", null, manifest, null));
        r.EnsureSuccessStatusCode();
        return (await r.ReadAsync<SaveUploadResponse>()).VersionId;
    }

    [Fact]
    public async Task Events_WithSessionCookie_NoProtocolHeader_Streams()
    {
        using var factory = NewFactory();
        await factory.Register("a@example.com");
        using var browser = await BrowserClient(factory, "a@example.com");

        using var response = await browser.GetAsync("/v1/events", HttpCompletionOption.ResponseHeadersRead);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("text/event-stream", response.Content.Headers.ContentType?.MediaType);
    }

    [Fact]
    public async Task Download_WithSessionCookie_NoHeader_Returns200()
    {
        using var factory = NewFactory();
        var (device, _) = await factory.Register("a@example.com");
        var versionId = await UploadSave(device);
        using var browser = await BrowserClient(factory, "a@example.com");

        using var response = await browser.GetAsync($"/v1/saves/versions/{versionId}/download");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task ExportZip_WithSessionCookie_NoHeader_Returns200()
    {
        using var factory = NewFactory();
        var (device, _) = await factory.Register("a@example.com");
        await UploadSave(device);
        using var browser = await BrowserClient(factory, "a@example.com");

        using var response = await browser.GetAsync("/v1/export.zip");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }

    [Fact]
    public async Task Events_WithBearer_NoHeader_Returns426()
    {
        using var factory = NewFactory();
        var (device, _) = await factory.Register("a@example.com");
        device.DefaultRequestHeaders.Remove(Protocol.Protocol.Header);

        using var response = await device.GetAsync("/v1/events", HttpCompletionOption.ResponseHeadersRead);

        Assert.Equal(HttpStatusCode.UpgradeRequired, response.StatusCode);
    }

    [Fact]
    public async Task OtherV1Get_WithCookie_NoHeader_Returns426()
    {
        using var factory = NewFactory();
        await factory.Register("a@example.com");
        using var browser = await BrowserClient(factory, "a@example.com");

        using var response = await browser.GetAsync("/v1/web/home");

        Assert.Equal(HttpStatusCode.UpgradeRequired, response.StatusCode);
    }
}
