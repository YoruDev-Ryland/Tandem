using Tandem.Protocol;
using Tandem.Server.Accounts;
using Microsoft.Extensions.Options;

namespace Tandem.Server.Http;

public static class ServerInfoEndpoints
{
    public static IEndpointRouteBuilder MapServerInfoEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/healthz", () => Results.Text("ok"));
        app.MapGet("/v1/server-info", async (IOptions<ServerOptions> options, AccountService accounts, CancellationToken ct) =>
            new ServerInfo(options.Value.ServerName, Protocol.Protocol.Version, await accounts.IsRegistrationOpen(ct)));
        return app;
    }
}
