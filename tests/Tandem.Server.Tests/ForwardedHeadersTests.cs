using System.Net;
using System.Net.Http.Json;
using Tandem.Protocol;
using Tandem.Server.Tests.Infrastructure;

namespace Tandem.Server.Tests;

/// <summary>TRUST_FORWARDED_HEADERS: behind a reverse proxy, the client address, scheme and host come from the
/// X-Forwarded-* headers only when the option is on.</summary>
public class ForwardedHeadersTests
{
    private const string Email = "a@example.com";

    private static ServerFactory NewFactory(bool trust, int authPerMinute = 10000)
    {
        var factory = new ServerFactory();
        factory.Settings["Tandem:TrustForwardedHeaders"] = trust ? "true" : "false";
        factory.Settings["Tandem:AuthRequestsPerMinute"] = authPerMinute.ToString(System.Globalization.CultureInfo.InvariantCulture);
        return factory;
    }

    private static async Task<HttpStatusCode> Login(ServerFactory f, string forwardedFor)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, "/v1/auth/login")
        {
            Content = JsonContent.Create(new LoginRequest("x@example.com", "whatever pass", "d", "android")),
        };
        request.Headers.Add("X-Forwarded-For", forwardedFor);
        return (await f.Client().SendAsync(request)).StatusCode;
    }

    private static async Task<HttpResponseMessage> WebSession(ServerFactory f, string? forwardedProto)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, "/v1/web/session")
        {
            Content = JsonContent.Create(new WebSessionRequest(Email, TestApi.Password, null)),
        };
        if (forwardedProto is not null) request.Headers.Add("X-Forwarded-Proto", forwardedProto);
        var r = await f.Client().SendAsync(request);
        Assert.True(r.IsSuccessStatusCode, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        return r;
    }

    private static string SessionCookie(HttpResponseMessage r) =>
        Assert.Single(r.Headers.GetValues("Set-Cookie"), c => c.StartsWith("tandem_session=", StringComparison.Ordinal));

    [Fact]
    public async Task Trusted_forwarded_for_gives_each_client_its_own_rate_partition()
    {
        using var f = NewFactory(trust: true, authPerMinute: 2);

        Assert.Equal(HttpStatusCode.Unauthorized, await Login(f, "203.0.113.1"));
        Assert.Equal(HttpStatusCode.Unauthorized, await Login(f, "203.0.113.1"));
        Assert.Equal(HttpStatusCode.TooManyRequests, await Login(f, "203.0.113.1"));

        Assert.Equal(HttpStatusCode.Unauthorized, await Login(f, "203.0.113.2"));
    }

    [Fact]
    public async Task Untrusted_forwarded_for_is_ignored_and_clients_share_a_partition()
    {
        using var f = NewFactory(trust: false, authPerMinute: 2);

        Assert.Equal(HttpStatusCode.Unauthorized, await Login(f, "203.0.113.1"));
        Assert.Equal(HttpStatusCode.Unauthorized, await Login(f, "203.0.113.1"));

        Assert.Equal(HttpStatusCode.TooManyRequests, await Login(f, "203.0.113.2"));
    }

    [Fact]
    public async Task Trusted_forwarded_proto_https_makes_the_session_cookie_secure()
    {
        using var f = NewFactory(trust: true);
        await f.Register(Email);

        Assert.Contains("secure", SessionCookie(await WebSession(f, "https")), StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("secure", SessionCookie(await WebSession(f, null)), StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task Untrusted_forwarded_proto_does_not_make_the_cookie_secure()
    {
        using var f = NewFactory(trust: false);
        await f.Register(Email);

        Assert.DoesNotContain("secure", SessionCookie(await WebSession(f, "https")), StringComparison.OrdinalIgnoreCase);
    }

    private static async Task<string> PairingQr(HttpClient device)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, "/v1/pairing/codes");
        request.Headers.Add("X-Forwarded-Proto", "https");
        request.Headers.Add("X-Forwarded-Host", "sync.example.com");
        var r = await device.SendAsync(request);
        Assert.True(r.IsSuccessStatusCode, $"{r.StatusCode}: {await r.Content.ReadAsStringAsync()}");
        return (await r.ReadAsync<PairingCodeResponse>()).QrPayload;
    }

    [Fact]
    public async Task Trusted_forwarded_proto_and_host_set_the_pairing_fallback_url()
    {
        using var f = NewFactory(trust: true);
        var (device, _) = await f.Register(Email);

        Assert.Contains("https://sync.example.com", await PairingQr(device));
    }

    [Fact]
    public async Task Untrusted_forwarded_proto_and_host_are_ignored_for_the_pairing_url()
    {
        using var f = NewFactory(trust: false);
        var (device, _) = await f.Register(Email);

        var qr = await PairingQr(device);
        Assert.DoesNotContain("sync.example.com", qr);
        Assert.Contains("http://localhost", qr);
    }
}
