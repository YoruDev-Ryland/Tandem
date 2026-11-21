using System.Net;
using System.Net.Http.Json;
using Tandem.Protocol;
using Tandem.Server.Cli;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Data.Sqlite;

namespace Tandem.Server.Tests;

public class AdminCliTests
{
    private static async Task<(int Code, string Output, string Error)> Cli(ServerFactory f, params string[] args)
    {
        f.Client(); // builds the host (migrates the database)
        var output = new StringWriter();
        var error = new StringWriter();
        var code = await AdminCli.RunAsync(args, f.Services, output, error, CancellationToken.None);
        return (code, output.ToString(), error.ToString());
    }

    private static string PasswordIn(string output) =>
        output.Split('\n').Select(l => l.Trim()).Single(l => l.StartsWith("Password: ", StringComparison.Ordinal))["Password: ".Length..];

    private static async Task<HttpResponseMessage> Login(ServerFactory f, string email, string password) =>
        await f.Client().PostAsJsonAsync("/v1/auth/login", new LoginRequest(email, password, "cli-test", "android"));

    [Fact]
    public async Task Cli_create_user_prints_password_and_user_can_login()
    {
        using var f = new ServerFactory();
        var (code, output, error) = await Cli(f, "create-user", "Me@Example.com", "--admin");
        Assert.Equal(0, code);
        Assert.Equal("", error);
        var password = PasswordIn(output);
        Assert.Equal(HttpStatusCode.OK, (await Login(f, "me@example.com", password)).StatusCode);

        var web = await f.WebLogin("me@example.com", password);
        Assert.Equal(HttpStatusCode.OK, (await web.GetAsync("/v1/admin/users")).StatusCode); // --admin took effect

        var (dupCode, _, dupError) = await Cli(f, "create-user", "me@example.com");
        Assert.Equal(1, dupCode);
        Assert.Contains("already exists", dupError);
    }

    [Fact]
    public async Task Cli_create_user_on_an_empty_server_makes_an_admin()
    {
        using var f = new ServerFactory();
        var first = PasswordIn((await Cli(f, "create-user", "first@example.com")).Output);
        var second = PasswordIn((await Cli(f, "create-user", "second@example.com")).Output);

        var a = await (await (await f.WebLogin("first@example.com", first)).GetAsync("/v1/account")).ReadAsync<AccountInfo>();
        var b = await (await (await f.WebLogin("second@example.com", second)).GetAsync("/v1/account")).ReadAsync<AccountInfo>();
        Assert.True(a.IsAdmin);   // the first account is always admin
        Assert.False(b.IsAdmin);
    }

    [Fact]
    public async Task Cli_reset_password_changes_it()
    {
        using var f = new ServerFactory();
        var created = PasswordIn((await Cli(f, "create-user", "me@example.com")).Output);
        var (code, output, _) = await Cli(f, "reset-password", "me@example.com");
        Assert.Equal(0, code);
        var fresh = PasswordIn(output);
        Assert.NotEqual(created, fresh);
        Assert.Equal(HttpStatusCode.Unauthorized, (await Login(f, "me@example.com", created)).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await Login(f, "me@example.com", fresh)).StatusCode);

        var (missing, _, error) = await Cli(f, "reset-password", "nobody@example.com");
        Assert.Equal(1, missing);
        Assert.NotEqual("", error);
    }

    [Fact]
    public async Task Cli_unknown_command_exits_1()
    {
        using var f = new ServerFactory();
        var (code, _, error) = await Cli(f, "frobnicate");
        Assert.Equal(1, code);
        Assert.Contains("frobnicate", error);
    }

    [Fact]
    public async Task Cli_list_users_and_revoke_device()
    {
        using var f = new ServerFactory();
        var (_, key) = await f.Register("a@example.com");
        var (code, output, _) = await Cli(f, "list-users");
        Assert.Equal(0, code);
        Assert.Contains("a@example.com", output);
        Assert.Contains("admin", output);

        var (revoke, _, _) = await Cli(f, "revoke-device", key.DeviceId.ToString());
        Assert.Equal(0, revoke);
        var after = f.Client();
        after.DefaultRequestHeaders.Authorization = new("Bearer", key.DeviceKey);
        Assert.Equal(HttpStatusCode.Unauthorized, (await after.GetAsync("/v1/devices")).StatusCode);

        Assert.Equal(1, (await Cli(f, "revoke-device", Guid.NewGuid().ToString())).Code);
        Assert.Equal(1, (await Cli(f, "revoke-device", "not-a-guid")).Code);
    }

    [Fact]
    public void Command_dispatch_accepts_admin_verb_or_bare_known_command_only()
    {
        Assert.Equal(["list-users"], AdminCli.CommandArguments(["admin", "list-users"])!);
        Assert.Equal(["create-user", "a@b", "--admin"], AdminCli.CommandArguments(["create-user", "a@b", "--admin"])!);
        Assert.Null(AdminCli.CommandArguments(["--urls", "http://*:80"]));
        Assert.Null(AdminCli.CommandArguments(["frobnicate"]));
        Assert.Null(AdminCli.CommandArguments([]));
    }

    [Fact]
    public async Task Cli_unexpected_exception_prints_message_not_stack_trace()
    {
        var output = new StringWriter();
        var error = new StringWriter();
        var services = new ServiceCollection().BuildServiceProvider(); // no AccountService: the command throws InvalidOperationException
        var code = await AdminCli.RunAsync(["list-users"], services, output, error, CancellationToken.None);
        Assert.Equal(1, code);
        Assert.StartsWith("list-users: ", error.ToString());
        Assert.DoesNotContain("   at ", error.ToString());
    }

    [Fact]
    public async Task Cli_cancellation_propagates()
    {
        using var f = new ServerFactory();
        using var cancelled = new CancellationTokenSource();
        await cancelled.CancelAsync();
        f.Client();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            AdminCli.RunAsync(["list-users"], f.Services, TextWriter.Null, TextWriter.Null, cancelled.Token));
    }

    [Fact]
    public void Plain_env_aliases_never_override_explicit_settings()
    {
        var env = new Dictionary<string, string?> { ["DATA_DIR"] = "/from-alias", ["SERVER_NAME"] = "Alias", ["PUBLIC_URL"] = "https://alias", ["TITLE_NAMES"] = "false" };
        var config = new ConfigurationManager();
        config.AddInMemoryCollection(new Dictionary<string, string?> { ["Tandem:DataDirectory"] = "/explicit" }); // stands in for Tandem__DataDir, UseSetting
        config.AddCommandLine(["--Tandem:ServerName=FromCommandLine"]);
        EnvironmentAliases.Add(config, name => env.GetValueOrDefault(name));
        Assert.Equal("/explicit", config["Tandem:DataDirectory"]);
        Assert.Equal("FromCommandLine", config["Tandem:ServerName"]);
        Assert.Equal("https://alias", config["Tandem:PublicUrl"]); // unset elsewhere: the alias applies
        Assert.Equal("false", config["Tandem:EnableTitleNames"]);
    }

    [Fact]
    public async Task Sqlite_database_runs_in_wal_mode()
    {
        using var f = new ServerFactory();
        f.Client();
        await using var connection = new SqliteConnection($"Data Source={Path.Combine(f.DataDirectory, "tandem.db")}");
        await connection.OpenAsync();
        await using var command = connection.CreateCommand();
        command.CommandText = "PRAGMA journal_mode";
        Assert.Equal("wal", (string?)await command.ExecuteScalarAsync());
    }
}
