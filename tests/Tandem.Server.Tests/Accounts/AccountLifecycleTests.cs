using System.Net;
using System.Net.Http.Json;
using Tandem.Protocol;
using Tandem.Server.Cli;
using Tandem.Server.Data;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;

namespace Tandem.Server.Tests.Accounts;

/// <summary>C2 (self-delete) and C3 (admin reset-password revokes sessions).</summary>
public class AccountLifecycleTests
{
    private static ServerFactory Open()
    {
        var f = new ServerFactory();
        f.Settings["Tandem:AllowRegistration"] = "true";
        return f;
    }

    private static HttpRequestMessage DeleteAccount(string password) =>
        new(HttpMethod.Delete, "/v1/account") { Content = JsonContent.Create(new DeleteAccountRequest(password)) };

    [Fact]
    public async Task A_user_deletes_their_own_account_with_the_password()
    {
        using var f = Open();
        await f.Register("admin@example.com");
        var (device, key) = await f.Register("user@example.com");
        await device.UploadSave("game", "data", null);
        var web = await f.WebLogin("user@example.com");

        var wrong = await web.SendAsync(DeleteAccount("not the password"));
        Assert.Equal(HttpStatusCode.Forbidden, wrong.StatusCode);
        Assert.Equal("bad_password", (await wrong.ReadAsync<ErrorResponse>()).Code);

        var ok = await web.SendAsync(DeleteAccount(TestApi.Password));
        Assert.Equal(HttpStatusCode.NoContent, ok.StatusCode);
        Assert.Contains(ok.Headers.GetValues("Set-Cookie"), c => c.StartsWith("tandem_session=;", StringComparison.Ordinal));

        Assert.Equal(HttpStatusCode.Unauthorized, (await web.GetAsync("/v1/account")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await device.GetAsync("/v1/devices")).StatusCode);
        using var scope = f.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        Assert.False(await db.Users.AnyAsync(u => u.Id == key.UserId));
        Assert.False(await db.SaveItems.AnyAsync(i => i.UserId == key.UserId));
        Assert.False(Directory.Exists(Path.Combine(f.DataDirectory, "blobs", key.UserId.ToString("N"))));
    }

    [Fact]
    public async Task The_only_admin_cannot_delete_themselves_while_others_exist_but_can_when_alone()
    {
        using var f = Open();
        await f.Register("admin@example.com");
        var (_, other) = await f.Register("user@example.com");
        var admin = await f.WebLogin("admin@example.com");

        var refused = await admin.SendAsync(DeleteAccount(TestApi.Password));
        Assert.Equal(HttpStatusCode.Conflict, refused.StatusCode);
        Assert.Equal("last_admin", (await refused.ReadAsync<ErrorResponse>()).Code);

        Assert.Equal(HttpStatusCode.NoContent, (await admin.DeleteAsync($"/v1/admin/users/{other.UserId}")).StatusCode);
        Assert.Equal(HttpStatusCode.NoContent, (await admin.SendAsync(DeleteAccount(TestApi.Password))).StatusCode);
    }

    [Fact]
    public async Task A_device_key_cannot_delete_the_account()
    {
        using var f = Open();
        var (device, _) = await f.Register("a@example.com");
        Assert.Equal(HttpStatusCode.Forbidden, (await device.SendAsync(DeleteAccount(TestApi.Password))).StatusCode);
    }

    [Fact]
    public async Task Admin_reset_password_returns_a_new_password_and_signs_out_every_session()
    {
        using var f = Open();
        await f.Register("admin@example.com");
        var (_, user) = await f.Register("user@example.com");
        var admin = await f.WebLogin("admin@example.com");
        var userWeb = await f.WebLogin("user@example.com");
        Assert.Equal(HttpStatusCode.OK, (await userWeb.GetAsync("/v1/account")).StatusCode);

        var response = await admin.PostAsync($"/v1/admin/users/{user.UserId}/reset-password", null);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var password = (await response.ReadAsync<ResetPasswordResponse>()).Password;
        Assert.True(password.Length >= 8);

        Assert.Equal(HttpStatusCode.Unauthorized, (await userWeb.GetAsync("/v1/account")).StatusCode);
        await f.WebLogin("user@example.com", password);
        Assert.Equal(HttpStatusCode.NotFound, (await admin.PostAsync($"/v1/admin/users/{Guid.NewGuid()}/reset-password", null)).StatusCode);
    }

    [Fact]
    public async Task Cli_reset_password_also_signs_out_the_web_sessions()
    {
        using var f = Open();
        await f.Register("user@example.com");
        var web = await f.WebLogin("user@example.com");
        var code = await AdminCli.RunAsync(["reset-password", "user@example.com"], f.Services, TextWriter.Null, TextWriter.Null,
            CancellationToken.None);
        Assert.Equal(0, code);
        Assert.Equal(HttpStatusCode.Unauthorized, (await web.GetAsync("/v1/account")).StatusCode);
    }
}
