using System.Net;
using System.Net.Http.Json;
using Tandem.Protocol;
using Tandem.Server.Auth;
using Tandem.Server.Blobs;
using Tandem.Server.Data;
using Tandem.Server.Http;
using Tandem.Server.Web;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.AspNetCore.Builder;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Tandem.Server.Tests.Http;

/// <summary>F-07, F-08, F-10, F-17, F-26: durability, login limits, proxies, first-run admin, headers, touch throttling.</summary>
public class HardeningTests
{
    private static Task<HttpResponseMessage> Login(ServerFactory f, string email, string password) =>
        f.Client().PostAsJsonAsync("/v1/auth/login", new LoginRequest(email, password, "d", "android"));

    [Fact]
    public async Task Five_failed_sign_ins_for_one_account_make_it_wait_with_doubling_delays()
    {
        using var f = new ServerFactory();
        await f.Register("a@example.com");
        for (var i = 0; i < LoginThrottle.FreeFailures; i++)
            Assert.Equal(HttpStatusCode.Unauthorized, (await Login(f, "a@example.com", "wrong password")).StatusCode);

        var blocked = await Login(f, "a@example.com", TestApi.Password); // even the right password waits
        Assert.Equal(HttpStatusCode.TooManyRequests, blocked.StatusCode);
        Assert.Equal("too_many_attempts", (await blocked.ReadAsync<ErrorResponse>()).Code);
        var web = await f.Client().PostAsJsonAsync("/v1/web/session", new WebSessionRequest("A@example.com", TestApi.Password, null));
        Assert.Equal(HttpStatusCode.TooManyRequests, web.StatusCode);

        f.Time.Advance(LoginThrottle.FirstDelay);
        Assert.Equal(HttpStatusCode.Unauthorized, (await Login(f, "a@example.com", "wrong again")).StatusCode);
        f.Time.Advance(LoginThrottle.FirstDelay); // the next wait is twice as long
        Assert.Equal(HttpStatusCode.TooManyRequests, (await Login(f, "a@example.com", TestApi.Password)).StatusCode);
        f.Time.Advance(LoginThrottle.FirstDelay);
        Assert.Equal(HttpStatusCode.OK, (await Login(f, "a@example.com", TestApi.Password)).StatusCode);

        // A success clears the count.
        Assert.Equal(HttpStatusCode.Unauthorized, (await Login(f, "a@example.com", "wrong")).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Login(f, "a@example.com", TestApi.Password)).StatusCode);
    }

    [Fact]
    public async Task Unknown_emails_are_limited_the_same_way()
    {
        using var f = new ServerFactory();
        for (var i = 0; i < LoginThrottle.FreeFailures; i++) await Login(f, "nobody@example.com", "x");
        Assert.Equal(HttpStatusCode.TooManyRequests, (await Login(f, "nobody@example.com", "x")).StatusCode);
    }

    private static async Task<HttpStatusCode> LoginAs(ServerFactory f, string from, string email, string password)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, "/v1/auth/login")
        {
            Content = JsonContent.Create(new LoginRequest(email, password, "d", "android")),
        };
        request.Headers.Add("X-Forwarded-For", from);
        return (await f.Client().SendAsync(request)).StatusCode;
    }

    [Fact]
    public async Task Failures_from_another_address_never_block_the_right_password_from_mine()
    {
        using var f = new ServerFactory();
        f.Settings["Tandem:KnownProxies"] = "172.18.0.0/16";
        f.ConfigureExtraServices = s => s.AddSingleton<Microsoft.AspNetCore.Hosting.IStartupFilter>(new RemoteAddress("172.18.0.3"));
        await f.Register("a@example.com");
        for (var i = 0; i < LoginThrottle.FreeFailures + 1; i++)
            await LoginAs(f, "203.0.113.66", "a@example.com", "guess");
        Assert.Equal(HttpStatusCode.TooManyRequests, await LoginAs(f, "203.0.113.66", "a@example.com", TestApi.Password));
        Assert.Equal(HttpStatusCode.OK, await LoginAs(f, "198.51.100.7", "a@example.com", TestApi.Password));
        // An attacker rotating within one IPv6 /64 stays in one bucket.
        for (var i = 0; i < LoginThrottle.FreeFailures; i++)
            await LoginAs(f, $"2001:db8:1:2::{i + 1}", "a@example.com", "guess");
        Assert.Equal(HttpStatusCode.TooManyRequests, await LoginAs(f, "2001:db8:1:2::99", "a@example.com", "guess"));
    }

    [Fact]
    public async Task The_wait_is_capped_at_five_minutes()
    {
        using var f = new ServerFactory();
        await f.Register("a@example.com");
        Assert.Equal(TimeSpan.FromMinutes(5), LoginThrottle.MaxDelay);
        for (var i = 0; i < 40; i++)
        {
            await Login(f, "a@example.com", "wrong password");
            f.Time.Advance(LoginThrottle.MaxDelay);
        }
        await Login(f, "a@example.com", "wrong password");
        f.Time.Advance(LoginThrottle.MaxDelay);
        Assert.Equal(HttpStatusCode.OK, (await Login(f, "a@example.com", TestApi.Password)).StatusCode);
    }

    [Fact]
    public async Task A_password_reset_clears_the_wait()
    {
        using var f = new ServerFactory();
        f.Settings["Tandem:AllowRegistration"] = "true";
        await f.Register("admin@example.com");
        var (_, user) = await f.Register("user@example.com");
        for (var i = 0; i < LoginThrottle.FreeFailures; i++) await Login(f, "user@example.com", "wrong password");
        Assert.Equal(HttpStatusCode.TooManyRequests, (await Login(f, "user@example.com", TestApi.Password)).StatusCode);

        // The admin web reset.
        var admin = await f.WebLogin("admin@example.com");
        var reset = await admin.PostAsync($"/v1/admin/users/{user.UserId}/reset-password", null);
        var password = (await reset.ReadAsync<ResetPasswordResponse>()).Password;
        Assert.Equal(HttpStatusCode.OK, (await Login(f, "user@example.com", password)).StatusCode);

        // The CLI reset (same service call, by email).
        for (var i = 0; i < LoginThrottle.FreeFailures; i++) await Login(f, "user@example.com", "wrong password");
        Assert.Equal(HttpStatusCode.TooManyRequests, (await Login(f, "user@example.com", password)).StatusCode);
        using (var scope = f.Services.CreateScope())
            await scope.ServiceProvider.GetRequiredService<Tandem.Server.Accounts.AccountService>()
                .ResetPassword("user@example.com", "a brand new password", CancellationToken.None);
        Assert.Equal(HttpStatusCode.OK, (await Login(f, "user@example.com", "a brand new password")).StatusCode);
    }

    [Theory]
    [InlineData("203.0.113.7", "203.0.113.7")]
    [InlineData("::ffff:203.0.113.7", "203.0.113.7")]
    [InlineData("2001:db8:1:2:aaaa:bbbb:cccc:dddd", "2001:db8:1:2::/64")]
    [InlineData("2001:db8:1:2::1", "2001:db8:1:2::/64")]
    public void Rate_limit_buckets_group_ipv6_by_slash_64(string address, string key) =>
        Assert.Equal(key, AuthSetup.PartitionKey(IPAddress.Parse(address)));

    [Fact]
    public void Known_proxies_parse_addresses_and_networks_and_reject_junk()
    {
        var parsed = KnownProxies.Parse(" 172.18.0.0/16, 10.0.0.5 ,fd00::/8,::1");
        Assert.Equal(["172.18.0.0/16", "10.0.0.5/32", "fd00::/8", "::1/128"], parsed.Select(n => n.ToString()));
        Assert.Empty(KnownProxies.Parse(null));
        Assert.Throws<InvalidOperationException>(() => KnownProxies.Parse("proxy.local"));
    }

    private static async Task<HttpStatusCode> LoginFrom(ServerFactory f, string forwardedFor)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, "/v1/auth/login")
        {
            Content = JsonContent.Create(new LoginRequest($"{Guid.NewGuid():N}@example.com", "whatever pass", "d", "android")),
        };
        request.Headers.Add("X-Forwarded-For", forwardedFor);
        return (await f.Client().SendAsync(request)).StatusCode;
    }

    /// <summary>Gives every test-server request this remote address (TestServer has none), ahead of the app's pipeline.</summary>
    private sealed class RemoteAddress(string address) : Microsoft.AspNetCore.Hosting.IStartupFilter
    {
        public Action<Microsoft.AspNetCore.Builder.IApplicationBuilder> Configure(Action<Microsoft.AspNetCore.Builder.IApplicationBuilder> next) =>
            app =>
            {
                app.Use((context, n) => { context.Connection.RemoteIpAddress = IPAddress.Parse(address); return n(context); });
                next(app);
            };
    }

    private static ServerFactory ProxyFactory(string remote)
    {
        var f = new ServerFactory();
        f.Settings["Tandem:KnownProxies"] = "172.18.0.0/16, 10.0.0.5";
        f.Settings["Tandem:AuthRequestsPerMinute"] = "2";
        f.ConfigureExtraServices = s => s.AddSingleton<Microsoft.AspNetCore.Hosting.IStartupFilter>(new RemoteAddress(remote));
        return f;
    }

    [Fact]
    public async Task A_known_proxy_forwards_the_client_address()
    {
        using var f = ProxyFactory("172.18.0.3");
        Assert.Equal(HttpStatusCode.Unauthorized, await LoginFrom(f, "203.0.113.1"));
        Assert.Equal(HttpStatusCode.Unauthorized, await LoginFrom(f, "203.0.113.1"));
        Assert.Equal(HttpStatusCode.TooManyRequests, await LoginFrom(f, "203.0.113.1"));
        Assert.Equal(HttpStatusCode.Unauthorized, await LoginFrom(f, "203.0.113.2")); // its own bucket
    }

    [Fact]
    public async Task A_chain_of_known_proxies_forwards_the_client_address()
    {
        // Cloudflare (10.0.0.5 here) in front of Traefik (172.18.0.3): "client, cloudflare-edge".
        using var f = ProxyFactory("172.18.0.3");
        Assert.Equal(HttpStatusCode.Unauthorized, await LoginFrom(f, "203.0.113.1, 10.0.0.5"));
        Assert.Equal(HttpStatusCode.Unauthorized, await LoginFrom(f, "203.0.113.1, 10.0.0.5"));
        Assert.Equal(HttpStatusCode.TooManyRequests, await LoginFrom(f, "203.0.113.1, 10.0.0.5"));
        Assert.Equal(HttpStatusCode.Unauthorized, await LoginFrom(f, "203.0.113.2, 10.0.0.5")); // its own bucket, not the edge's
    }

    [Fact]
    public async Task A_client_cannot_spoof_an_address_past_the_first_unknown_hop()
    {
        using var f = ProxyFactory("172.18.0.3");
        // The client (198.51.100.7, not a known proxy) made up the first entry: its own address is the one counted.
        Assert.Equal(HttpStatusCode.Unauthorized, await LoginFrom(f, "203.0.113.1, 198.51.100.7"));
        Assert.Equal(HttpStatusCode.Unauthorized, await LoginFrom(f, "203.0.113.2, 198.51.100.7"));
        Assert.Equal(HttpStatusCode.TooManyRequests, await LoginFrom(f, "203.0.113.3, 198.51.100.7"));
    }

    [Fact]
    public async Task Any_other_sender_cannot_spoof_the_client_address()
    {
        using var f = ProxyFactory("192.168.1.50"); // a LAN host, not the proxy
        Assert.Equal(HttpStatusCode.Unauthorized, await LoginFrom(f, "203.0.113.1"));
        Assert.Equal(HttpStatusCode.Unauthorized, await LoginFrom(f, "203.0.113.2"));
        Assert.Equal(HttpStatusCode.TooManyRequests, await LoginFrom(f, "203.0.113.3")); // one bucket: its own address
    }

    [Fact]
    public async Task With_admin_email_set_the_first_account_must_use_it()
    {
        using var f = new ServerFactory();
        f.Settings["Tandem:AdminEmail"] = "Owner@Example.com";
        f.Settings["Tandem:AllowRegistration"] = "true";
        var other = await f.Client().PostAsJsonAsync("/v1/auth/register", new RegisterRequest("stranger@example.com", TestApi.Password, "d", "android"));
        Assert.Equal(HttpStatusCode.Forbidden, other.StatusCode);
        Assert.Equal("admin_email_required", (await other.ReadAsync<ErrorResponse>()).Code);

        await f.Register("owner@example.com");
        await f.Register("stranger@example.com"); // once the admin exists, the usual rules apply
    }

    [Fact]
    public async Task Every_response_carries_the_security_headers()
    {
        using var f = new ServerFactory();
        foreach (var path in new[] { "/healthz", "/v1/server-info", "/" })
        {
            var r = await f.CreateClient().GetAsync(path);
            var csp = string.Join("", r.Headers.GetValues("Content-Security-Policy"));
            Assert.Contains("default-src 'self'", csp);
            Assert.Contains("img-src 'self' data: blob:;", csp);
            Assert.Contains("font-src 'self' data:", csp); // Vite inlines small font subsets as data: URLs
            Assert.Contains("frame-ancestors 'none'", csp);
            Assert.Equal("nosniff", r.Headers.GetValues("X-Content-Type-Options").Single());
            Assert.Equal("no-referrer", r.Headers.GetValues("Referrer-Policy").Single());
        }
    }

    [Fact]
    public void The_policy_allows_the_inline_theme_script_by_its_hash_only()
    {
        const string html = "<html><head><script>\n  var t = 1;\n</script><script type=\"module\" src=\"/a.js\"></script></head></html>";
        var hashes = SecurityHeaders.InlineScriptHashes(html);
        var expected = Convert.ToBase64String(System.Security.Cryptography.SHA256.HashData("\n  var t = 1;\n"u8.ToArray()));
        Assert.Equal([expected], hashes);
        Assert.Contains($"script-src 'self' 'sha256-{expected}';", SecurityHeaders.Policy(hashes));
        Assert.DoesNotContain("unsafe-inline';", SecurityHeaders.Policy(hashes).Split("style-src")[0]);
    }

    [Fact]
    public async Task Last_seen_is_written_at_most_once_a_minute()
    {
        using var f = new ServerFactory();
        var (device, key) = await f.Register("a@example.com");
        async Task<DateTimeOffset?> Seen()
        {
            using var scope = f.Services.CreateScope();
            return await scope.ServiceProvider.GetRequiredService<TandemDbContext>().Devices.Where(d => d.Id == key.DeviceId)
                .Select(d => d.LastSeenUtc).SingleAsync();
        }
        await device.GetAsync("/v1/devices");
        var first = await Seen();
        f.Time.Advance(TimeSpan.FromSeconds(30));
        await device.GetAsync("/v1/devices");
        Assert.Equal(first, await Seen());
        f.Time.Advance(TimeSpan.FromSeconds(31));
        await device.GetAsync("/v1/devices");
        Assert.Equal(f.Time.GetUtcNow(), await Seen());
    }

    [Fact]
    public void A_directory_can_be_fsynced()
    {
        var dir = Directory.CreateTempSubdirectory("tandem-fsync-").FullName;
        try { Assert.Equal(OperatingSystem.IsLinux() || OperatingSystem.IsMacOS(), Durability.SyncDirectory(dir)); }
        finally { Directory.Delete(dir); }
        Assert.False(Durability.SyncDirectory(Path.Combine(dir, "gone")));
    }
}
