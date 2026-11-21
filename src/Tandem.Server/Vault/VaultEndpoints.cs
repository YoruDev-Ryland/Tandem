using Tandem.Protocol;
using Tandem.Server.Auth;

namespace Tandem.Server.Vault;

public static class VaultEndpoints
{
    public static IEndpointRouteBuilder MapVaultEndpoints(this IEndpointRouteBuilder app)
    {
        // Web session or device key; an upload from the web app has no device (its blobs came from a device earlier).
        var vault = app.MapGroup("/v1/vault").RequireAuthorization();

        vault.MapPost("/upload", (HttpContext http, VaultUploadRequest request, VaultService svc, CancellationToken ct) =>
            svc.UploadAsync(http.User.UserId(), http.User.DeviceId(), request, ct));

        vault.MapGet("", (HttpContext http, VaultService svc, CancellationToken ct) => svc.ListAsync(http.User.UserId(), ct));

        vault.MapPost("/{itemId:guid}/send", (HttpContext http, Guid itemId, SendVaultRequest request, VaultService svc,
            CancellationToken ct) => svc.SendAsync(http.User.UserId(), itemId, request, ct));
        return app;
    }
}
