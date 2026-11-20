using EmuSync.Protocol;
using Microsoft.Extensions.Options;

namespace EmuSync.Server.Http;

public static class ServerInfoEndpoints
{
    public static IEndpointRouteBuilder MapServerInfoEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/healthz", () => Results.Text("ok"));
        app.MapGet("/v1/server-info", (IOptions<ServerOptions> options) =>
            new ServerInfo(options.Value.ServerName, Protocol.Protocol.Version, options.Value.AllowRegistration));
        return app;
    }
}
