using System.Net;
using System.Net.Http.Json;
using EmuSync.Protocol;
using EmuSync.Server.Tests.Infrastructure;

namespace EmuSync.Server.Tests;

public class AccountTests
{
    private static async Task AssertError(HttpResponseMessage r, HttpStatusCode status, string code)
    {
        Assert.Equal(status, r.StatusCode);
        Assert.Equal(code, (await r.ReadAsync<ErrorResponse>()).Code);
    }

    [Fact]
    public async Task First_account_can_register_when_registration_closed_and_is_admin()
    {
        using var factory = new ServerFactory();
        var (device, key) = await factory.Register("Admin@Example.com");

        var account = await (await device.GetAsync("/v1/account")).ReadAsync<AccountInfo>();
        Assert.Equal(key.UserId, account.Id);
        Assert.Equal("admin@example.com", account.Email);
        Assert.True(account.IsAdmin);
    }

    [Fact]
    public async Task Second_account_rejected_when_registration_closed()
    {
        using var factory = new ServerFactory();
        await factory.Register("a@example.com");
        var r = await factory.Client().PostAsJsonAsync("/v1/auth/register",
            new RegisterRequest("b@example.com", TestApi.Password, "d", "android"));
        await AssertError(r, HttpStatusCode.Forbidden, "registration_closed");
    }

    [Fact]
    public async Task Duplicate_email_is_409_and_weak_password_is_400()
    {
        using var factory = new ServerFactory();
        factory.Settings["EmuSync:AllowRegistration"] = "true";
        await factory.Register("a@example.com");
        var dup = await factory.Client().PostAsJsonAsync("/v1/auth/register",
            new RegisterRequest(" A@example.com ", TestApi.Password, "d", "android"));
        await AssertError(dup, HttpStatusCode.Conflict, "email_taken");
        var weak = await factory.Client().PostAsJsonAsync("/v1/auth/register",
            new RegisterRequest("c@example.com", "short", "d", "android"));
        await AssertError(weak, HttpStatusCode.BadRequest, "weak_password");
    }

    [Fact]
    public async Task Login_wrong_password_is_401_invalid_credentials()
    {
        using var factory = new ServerFactory();
        await factory.Register("a@example.com");
        var r = await factory.Client().PostAsJsonAsync("/v1/auth/login",
            new LoginRequest("a@example.com", "wrong password", "d", "android"));
        await AssertError(r, HttpStatusCode.Unauthorized, "invalid_credentials");

        var ok = await factory.Client().PostAsJsonAsync("/v1/auth/login",
            new LoginRequest("A@example.com", TestApi.Password, "second", "android"));
        ok.EnsureSuccessStatusCode();
        Assert.NotEqual(default, (await ok.ReadAsync<DeviceKeyResponse>()).DeviceId);
    }

    [Fact]
    public async Task Unauthenticated_is_401_unauthorized()
    {
        using var factory = new ServerFactory();
        await AssertError(await factory.Client().GetAsync("/v1/account"), HttpStatusCode.Unauthorized, "unauthorized");
    }

    [Fact]
    public async Task Device_key_authenticates_and_updates_LastSeenUtc()
    {
        using var factory = new ServerFactory();
        var (device, key) = await factory.Register("a@example.com");
        factory.Time.Advance(TimeSpan.FromMinutes(5));

        var me = await (await device.GetAsync("/v1/devices/me")).ReadAsync<DeviceSummary>();
        Assert.Equal(key.DeviceId, me.Id);
        Assert.True(me.IsCurrent);
        Assert.Equal(factory.Time.GetUtcNow(), me.LastSeenUtc);
    }

    [Fact]
    public async Task Web_session_cookie_authenticates()
    {
        using var factory = new ServerFactory();
        await factory.Register("a@example.com");
        var web = await factory.WebLogin("a@example.com");

        var account = await (await web.GetAsync("/v1/account")).ReadAsync<AccountInfo>();
        Assert.Equal("a@example.com", account.Email);
        await AssertError(await web.GetAsync("/v1/devices/me"), HttpStatusCode.NotFound, "no_device");

        (await web.DeleteAsync("/v1/web/session")).EnsureSuccessStatusCode();
        await AssertError(await web.GetAsync("/v1/account"), HttpStatusCode.Unauthorized, "unauthorized");
    }

    [Fact]
    public async Task Web_session_cookie_is_httponly_strict()
    {
        using var factory = new ServerFactory();
        await factory.Register("a@example.com");
        var r = await factory.Client().PostAsJsonAsync("/v1/web/session", new WebSessionRequest("a@example.com", TestApi.Password, null));
        var cookie = Assert.Single(r.Headers.GetValues("Set-Cookie"));
        Assert.StartsWith("emusync_session=", cookie);
        Assert.Contains("httponly", cookie, StringComparison.OrdinalIgnoreCase);
        Assert.Contains("samesite=strict", cookie, StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public async Task Change_password_works()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var bad = await device.PostAsJsonAsync("/v1/account/password", new ChangePasswordRequest("nope nope nope", "new password 1"));
        Assert.False(bad.IsSuccessStatusCode);
        (await device.PostAsJsonAsync("/v1/account/password", new ChangePasswordRequest(TestApi.Password, "new password 1"))).EnsureSuccessStatusCode();
        await factory.WebLogin("a@example.com", "new password 1");
    }

    [Fact]
    public async Task Users_cannot_see_each_others_devices()
    {
        using var factory = new ServerFactory();
        factory.Settings["EmuSync:AllowRegistration"] = "true";
        var (a, ka) = await factory.Register("a@example.com");
        var (b, kb) = await factory.Register("b@example.com");

        var listA = await (await a.GetAsync("/v1/devices")).ReadAsync<DeviceSummary[]>();
        var listB = await (await b.GetAsync("/v1/devices")).ReadAsync<DeviceSummary[]>();
        Assert.Equal(ka.DeviceId, Assert.Single(listA).Id);
        Assert.DoesNotContain(listB, d => d.Id == ka.DeviceId);
        Assert.Equal(kb.DeviceId, Assert.Single(listB).Id);
    }

    [Fact]
    public async Task Auth_endpoints_are_rate_limited()
    {
        using var factory = new ServerFactory();
        factory.Settings["EmuSync:AuthRequestsPerMinute"] = "3";
        var codes = new List<HttpStatusCode>();
        for (var i = 0; i < 5; i++)
            codes.Add((await factory.Client().PostAsJsonAsync("/v1/auth/login",
                new LoginRequest("x@example.com", "whatever pass", "d", "android"))).StatusCode);
        Assert.Equal(HttpStatusCode.TooManyRequests, codes[^1]);
        Assert.Equal(HttpStatusCode.Unauthorized, codes[0]);
    }
}
