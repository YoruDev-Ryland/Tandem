using System.Net;
using System.Net.Http.Json;
using EmuSync.Protocol;
using EmuSync.Server.Tests.Infrastructure;

namespace EmuSync.Server.Tests;

public class AdminTests
{
    [Fact]
    public async Task Admin_setting_AllowRegistration_overrides_env()
    {
        using var factory = new ServerFactory();
        var (admin, _) = await factory.Register("admin@example.com");

        var closed = await factory.Client().PostAsJsonAsync("/v1/auth/register",
            new RegisterRequest("b@example.com", TestApi.Password, "d", "android"));
        Assert.Equal(HttpStatusCode.Forbidden, closed.StatusCode);

        (await admin.PutAsJsonAsync("/v1/admin/settings", new AdminSettings(true))).EnsureSuccessStatusCode();
        Assert.True((await (await admin.GetAsync("/v1/admin/settings")).ReadAsync<AdminSettings>()).AllowRegistration);
        await factory.Register("b@example.com");

        (await admin.PutAsJsonAsync("/v1/admin/settings", new AdminSettings(false))).EnsureSuccessStatusCode();
        var again = await factory.Client().PostAsJsonAsync("/v1/auth/register",
            new RegisterRequest("c@example.com", TestApi.Password, "d", "android"));
        Assert.Equal(HttpStatusCode.Forbidden, again.StatusCode);
    }

    [Fact]
    public async Task Non_admin_gets_403_on_admin_routes()
    {
        using var factory = new ServerFactory();
        factory.Settings["EmuSync:AllowRegistration"] = "true";
        await factory.Register("admin@example.com");
        var (user, _) = await factory.Register("user@example.com");

        foreach (var r in new[] { await user.GetAsync("/v1/admin/users"), await user.GetAsync("/v1/admin/settings") })
        {
            Assert.Equal(HttpStatusCode.Forbidden, r.StatusCode);
            Assert.Equal("forbidden", (await r.ReadAsync<ErrorResponse>()).Code);
        }
    }

    [Fact]
    public async Task Admin_creates_lists_and_deletes_users()
    {
        using var factory = new ServerFactory();
        var (admin, adminKey) = await factory.Register("admin@example.com");

        var created = await (await admin.PostAsJsonAsync("/v1/admin/users", new CreateUserRequest("New@Example.com", false)))
            .ReadAsync<CreatedUserResponse>();
        Assert.Equal("new@example.com", created.Email);
        Assert.Equal(16, created.Password.Length);
        await factory.WebLogin("new@example.com", created.Password);

        var dup = await admin.PostAsJsonAsync("/v1/admin/users", new CreateUserRequest("new@example.com", false));
        Assert.Equal(HttpStatusCode.Conflict, dup.StatusCode);

        var users = await (await admin.GetAsync("/v1/admin/users")).ReadAsync<UserInfo[]>();
        Assert.Equal(2, users.Length);
        Assert.Equal(1, users.Single(u => u.Id == adminKey.UserId).DeviceCount);
        var other = users.Single(u => u.Email == "new@example.com");

        var self = await admin.DeleteAsync($"/v1/admin/users/{adminKey.UserId}");
        Assert.Equal(HttpStatusCode.BadRequest, self.StatusCode);

        Assert.Equal(HttpStatusCode.NoContent, (await admin.DeleteAsync($"/v1/admin/users/{other.Id}")).StatusCode);
        Assert.Single(await (await admin.GetAsync("/v1/admin/users")).ReadAsync<UserInfo[]>());
        Assert.Equal(HttpStatusCode.NotFound, (await admin.DeleteAsync($"/v1/admin/users/{other.Id}")).StatusCode);
    }
}
