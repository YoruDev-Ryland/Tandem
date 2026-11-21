using System.Net;
using System.Net.Http.Json;
using Tandem.Protocol;
using Tandem.Server.Tests.Infrastructure;

namespace Tandem.Server.Tests;

public class PairingTests
{
    private const string Alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static async Task AssertError(HttpResponseMessage r, HttpStatusCode status, string code)
    {
        Assert.Equal(status, r.StatusCode);
        Assert.Equal(code, (await r.ReadAsync<ErrorResponse>()).Code);
    }

    private static async Task<PairingCodeResponse> NewCode(HttpClient device)
    {
        var r = await device.PostAsync("/v1/pairing/codes", null);
        r.EnsureSuccessStatusCode();
        return await r.ReadAsync<PairingCodeResponse>();
    }

    private static Task<HttpResponseMessage> Redeem(ServerFactory f, string code) =>
        f.Client().PostAsJsonAsync("/v1/pairing/redeem", new RedeemPairingRequest(code, "Handheld", "android"));

    [Fact]
    public async Task Code_redeems_once_for_new_device_on_same_user()
    {
        using var factory = new ServerFactory();
        factory.Settings["Tandem:PublicUrl"] = "https://sync.example.com";
        var (device, key) = await factory.Register("a@example.com");
        var code = await NewCode(device);

        Assert.Equal(8, code.Code.Length);
        Assert.All(code.Code, c => Assert.Contains(c, Alphabet));
        Assert.Equal($"tandem://pair?server=https://sync.example.com&code={code.Code}", code.QrPayload);
        Assert.Equal(factory.Time.GetUtcNow().AddMinutes(5), code.ExpiresUtc);

        var response = await Redeem(factory, code.Code);
        response.EnsureSuccessStatusCode();
        var paired = await response.ReadAsync<DeviceKeyResponse>();
        Assert.Equal(key.UserId, paired.UserId);
        Assert.NotEqual(key.DeviceId, paired.DeviceId);

        var other = factory.Client();
        other.DefaultRequestHeaders.Authorization = new("Bearer", paired.DeviceKey);
        Assert.Equal(HttpStatusCode.OK, (await other.GetAsync("/v1/account")).StatusCode);
    }

    [Fact]
    public async Task Second_redeem_fails_410_code_used()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var code = await NewCode(device);
        (await Redeem(factory, code.Code)).EnsureSuccessStatusCode();
        await AssertError(await Redeem(factory, code.Code), HttpStatusCode.Gone, "code_used");
    }

    [Fact]
    public async Task Code_expires_after_5_minutes()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var code = await NewCode(device);
        factory.Time.Advance(TimeSpan.FromMinutes(5) + TimeSpan.FromSeconds(1));
        await AssertError(await Redeem(factory, code.Code), HttpStatusCode.Gone, "code_expired");
    }

    [Fact]
    public async Task Unknown_code_is_404()
    {
        using var factory = new ServerFactory();
        await AssertError(await Redeem(factory, "ZZZZZZZZ"), HttpStatusCode.NotFound, "code_not_found");
    }

    [Fact]
    public async Task Codes_are_case_insensitive_and_ignore_dashes()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var code = await NewCode(device);
        var typed = (code.Code[..4] + "-" + code.Code[4..]).ToLowerInvariant();
        (await Redeem(factory, typed)).EnsureSuccessStatusCode();
    }

    [Fact]
    public async Task Concurrent_redeems_yield_exactly_one_success()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var code = await NewCode(device);
        var results = await Task.WhenAll(Enumerable.Range(0, 8).Select(_ => Redeem(factory, code.Code)));
        Assert.Equal(1, results.Count(r => r.IsSuccessStatusCode));
        Assert.All(results.Where(r => !r.IsSuccessStatusCode), r => Assert.Equal(HttpStatusCode.Gone, r.StatusCode));
    }

    [Fact]
    public async Task Creating_a_code_requires_auth()
    {
        using var factory = new ServerFactory();
        Assert.Equal(HttpStatusCode.Unauthorized, (await factory.Client().PostAsync("/v1/pairing/codes", null)).StatusCode);
    }

    [Fact]
    public async Task Web_session_accepts_pairing_code()
    {
        using var factory = new ServerFactory();
        var (device, key) = await factory.Register("a@example.com");
        var code = await NewCode(device);

        var web = factory.CreateClient(new() { HandleCookies = true });
        web.DefaultRequestHeaders.Add(Protocol.Protocol.Header, Protocol.Protocol.Version.ToString());
        var response = await web.PostAsJsonAsync("/v1/web/session", new WebSessionRequest(null, null, code.Code));
        response.EnsureSuccessStatusCode();
        Assert.Equal(key.UserId, (await response.ReadAsync<AccountInfo>()).Id);
        Assert.Equal(HttpStatusCode.OK, (await web.GetAsync("/v1/account")).StatusCode);

        var devices = await (await device.GetAsync("/v1/devices")).ReadAsync<List<DeviceSummary>>();
        Assert.DoesNotContain(devices, d => d.Name == "Web browser"); // M-6: browser sign-in creates no device

        await AssertError(await web.PostAsJsonAsync("/v1/web/session", new WebSessionRequest(null, null, code.Code)),
            HttpStatusCode.Gone, "code_used");
    }
}
