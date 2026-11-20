using EmuSync.Server.Accounts;
using EmuSync.Server.Auth;
using EmuSync.Server.Data;
using EmuSync.Server.Devices;
using EmuSync.Server.Http;

namespace EmuSync.Server;

public static class ServerSetup
{
    /// <summary>Registers configuration and services. Each feature adds one line here.</summary>
    public static void Configure(WebApplicationBuilder builder)
    {
        EnvironmentAliases.Add(builder.Configuration);
        builder.Services.Configure<ServerOptions>(builder.Configuration.GetSection(ServerOptions.Section));
        builder.Services.AddSingleton(TimeProvider.System);
        builder.Services.AddDatabase(builder.Configuration.GetSection(ServerOptions.Section).Get<ServerOptions>() ?? new ServerOptions());
        builder.Services.AddEmuSyncAuth(builder.Configuration.GetSection(ServerOptions.Section).Get<ServerOptions>() ?? new ServerOptions());
        builder.Services.AddScoped<AccountService>();
    }

    /// <summary>Builds the pipeline and maps endpoints. Each feature adds one line here.</summary>
    public static void Map(WebApplication app)
    {
        app.Services.MigrateDatabase();
        app.UseApiErrors();
        app.UseMiddleware<ProtocolVersionMiddleware>(); // before routing so unknown /v1 routes still get 426

        app.UseRouting();
        app.UseAuthentication();
        app.UseRateLimiter();
        app.UseAuthorization();

        app.MapServerInfoEndpoints();
        app.MapAccountEndpoints();
        app.MapAdminEndpoints();
        app.MapDeviceEndpoints();
    }
}
