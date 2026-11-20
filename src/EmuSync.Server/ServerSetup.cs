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
    }

    /// <summary>Builds the pipeline and maps endpoints. Each feature adds one line here.</summary>
    public static void Map(WebApplication app)
    {
        app.UseApiErrors();
        app.UseMiddleware<ProtocolVersionMiddleware>(); // before routing so unknown /v1 routes still get 426

        app.MapServerInfoEndpoints();
    }
}
