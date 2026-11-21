using System.Reflection;
using Tandem.Protocol;
using Tandem.Server.Accounts;
using Microsoft.Extensions.Options;

namespace Tandem.Server.Http;

public static class ServerInfoEndpoints
{
    /// <summary>The server's InformationalVersion without the "+commit" suffix the SDK appends.</summary>
    public static string Version { get; } = StripBuild(
        typeof(ServerInfoEndpoints).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion);

    public static string StripBuild(string? informational) =>
        string.IsNullOrWhiteSpace(informational) ? "unknown" : informational.Split('+', 2)[0];

    public static IEndpointRouteBuilder MapServerInfoEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/healthz", (StorageMonitor storage) => storage.IsLow
            ? Results.Text("degraded: storage low", statusCode: StatusCodes.Status503ServiceUnavailable)
            : Results.Text("ok"));
        app.MapGet("/v1/server-info", async (IOptions<ServerOptions> options, AccountService accounts, CancellationToken ct) =>
            new ServerInfo(options.Value.ServerName, Protocol.Protocol.Version, await accounts.IsRegistrationOpen(ct), Version));
        return app;
    }
}
