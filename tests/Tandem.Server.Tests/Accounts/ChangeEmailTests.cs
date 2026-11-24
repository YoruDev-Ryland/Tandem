using System.Net;
using System.Net.Http.Json;
using Tandem.Protocol;
using Tandem.Server.Data;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Tandem.Server.Tests.Accounts;

public class ChangeEmailTests
{
    private static ServerFactory Open()
    {
        var f = new ServerFactory();
        f.Settings["Tandem:AllowRegistration"] = "true";
        return f;
    }

    private static Task<HttpResponseMessage> Change(HttpClient web, string newEmail, string? password = null) =>
        web.PostAsJsonAsync("/v1/account/email", new ChangeEmailRequest(newEmail, password ?? TestApi.Password));

    private static async Task Expect(HttpResponseMessage r, HttpStatusCode status, string code)
    {
        Assert.Equal(status, r.StatusCode);
        Assert.Equal(code, (await r.ReadAsync<ErrorResponse>()).Code);
    }

    [Fact]
    public async Task Change_email_with_the_right_password_updates_it_and_sign_in_uses_the_new_one()
    {
        using var f = Open();
        await f.Register("old@example.com");
        var web = await f.WebLogin("old@example.com");

        var r = await Change(web, "  New@Example.com ");
        r.EnsureSuccessStatusCode();
        Assert.Equal("new@example.com", (await r.ReadAsync<AccountInfo>()).Email);
        Assert.Equal("new@example.com", (await (await web.GetAsync("/v1/account")).ReadAsync<AccountInfo>()).Email);

        await f.WebLogin("new@example.com");
        var old = await f.Client().PostAsJsonAsync("/v1/web/session", new WebSessionRequest("old@example.com", TestApi.Password, null));
        Assert.Equal(HttpStatusCode.Unauthorized, old.StatusCode);

        using var scope = f.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        Assert.Contains(await db.ActivityEvents.ToListAsync(), e => e.Kind == "email_changed" && e.Message.Contains("new@example.com"));
    }

    [Fact]
    public async Task Wrong_password_is_403_and_counts_toward_the_login_throttle()
    {
        using var f = Open();
        await f.Register("a@example.com");
        var web = await f.WebLogin("a@example.com");

        for (var i = 0; i < Tandem.Server.Auth.LoginThrottle.FreeFailures; i++)
            await Expect(await Change(web, "b@example.com", "wrong password"), HttpStatusCode.Forbidden, "bad_password");

        await Expect(await Change(web, "b@example.com"), HttpStatusCode.TooManyRequests, "too_many_attempts");
        Assert.Equal("a@example.com", (await (await web.GetAsync("/v1/account")).ReadAsync<AccountInfo>()).Email);
    }

    [Fact]
    public async Task An_address_another_account_uses_is_409()
    {
        using var f = Open();
        await f.Register("a@example.com");
        await f.Register("b@example.com");
        var web = await f.WebLogin("a@example.com");

        await Expect(await Change(web, "B@Example.com"), HttpStatusCode.Conflict, "email_taken");
        Assert.Equal("a@example.com", (await (await web.GetAsync("/v1/account")).ReadAsync<AccountInfo>()).Email);
    }

    [Fact]
    public async Task Changing_to_the_same_address_in_another_case_is_allowed()
    {
        using var f = Open();
        await f.Register("a@example.com");
        var web = await f.WebLogin("a@example.com");

        (await Change(web, "A@Example.com")).EnsureSuccessStatusCode();
        Assert.Equal("a@example.com", (await (await web.GetAsync("/v1/account")).ReadAsync<AccountInfo>()).Email);
    }

    [Theory]
    [InlineData("")]
    [InlineData("no-at-sign")]
    [InlineData("has space@example.com")]
    public async Task Bad_address_is_400(string address)
    {
        using var f = Open();
        await f.Register("a@example.com");
        var web = await f.WebLogin("a@example.com");

        await Expect(await Change(web, address), HttpStatusCode.BadRequest, "invalid_email");
    }

    [Fact]
    public async Task Devices_stay_paired_and_other_web_sessions_are_signed_out()
    {
        using var f = Open();
        var (device, _) = await f.Register("a@example.com");
        var current = await f.WebLogin("a@example.com");
        var other = await f.WebLogin("a@example.com");

        (await Change(current, "b@example.com")).EnsureSuccessStatusCode();

        Assert.Equal(HttpStatusCode.OK, (await current.GetAsync("/v1/account")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await other.GetAsync("/v1/account")).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await device.GetAsync("/v1/account")).StatusCode);
    }

    [Fact]
    public async Task A_device_key_cannot_change_the_email()
    {
        using var f = Open();
        var (device, _) = await f.Register("a@example.com");
        await Expect(await Change(device, "b@example.com"), HttpStatusCode.Forbidden, "forbidden");
    }
}
