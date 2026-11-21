using System.Net;
using System.Net.Http.Json;
using System.Text;
using Tandem.Protocol;
using Tandem.Server.Tests.Infrastructure;

namespace Tandem.Server.Tests.Accounts;

public class WebSessionPairingTests
{
    private static HttpClient Browser(ServerFactory factory)
    {
        var client = factory.CreateClient(new() { HandleCookies = true });
        client.DefaultRequestHeaders.Add(Protocol.Protocol.Header, Protocol.Protocol.Version.ToString());
        return client;
    }

    private static async Task<string> NewCode(HttpClient device) =>
        (await (await device.PostAsync("/v1/pairing/codes", null)).ReadAsync<PairingCodeResponse>()).Code;

    private static Task<HttpResponseMessage> SignIn(HttpClient browser, string code) =>
        browser.PostAsJsonAsync("/v1/web/session", new WebSessionRequest(null, null, code));

    private static async Task<IReadOnlyList<DeviceSummary>> Devices(HttpClient device) =>
        await (await device.GetAsync("/v1/devices")).ReadAsync<List<DeviceSummary>>();

    [Fact]
    public async Task WebSession_ByPairingCode_CreatesNoDevice()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var before = await Devices(device);
        var code = await NewCode(device);

        using var browser = Browser(factory);
        (await SignIn(browser, code)).EnsureSuccessStatusCode();

        var after = await Devices(device);
        Assert.Equal(before.Count, after.Count);
        Assert.DoesNotContain(after, d => d.Name == "Web browser");
        Assert.Equal(HttpStatusCode.OK, (await browser.GetAsync("/v1/account")).StatusCode);
    }

    [Fact]
    public async Task WebSession_ByPairingCode_CodeIsSingleUse()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var code = await NewCode(device);

        using var first = Browser(factory);
        (await SignIn(first, code)).EnsureSuccessStatusCode();
        using var second = Browser(factory);
        var again = await SignIn(second, code);

        // Same error Redeem has always returned for a spent code.
        Assert.Equal(HttpStatusCode.Gone, again.StatusCode);
        Assert.Equal("code_used", (await again.ReadAsync<ErrorResponse>()).Code);
    }

    [Fact]
    public async Task WebSession_ByPairingCode_HasNoDeviceClaim()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var manifest = await Fixtures.Manifest(device, ("data.dat", Encoding.UTF8.GetBytes("hello")));
        using var browser = Browser(factory);
        (await SignIn(browser, await NewCode(device))).EnsureSuccessStatusCode();

        var r = await browser.PostJsonAsync("/v1/saves/upload",
            new SaveUploadRequest("eden", ItemKind.Save, "G1", "", "", null, manifest, null));

        Assert.Equal(HttpStatusCode.BadRequest, r.StatusCode);
        Assert.Equal("no_device", (await r.ReadAsync<ErrorResponse>()).Code);
    }
}
