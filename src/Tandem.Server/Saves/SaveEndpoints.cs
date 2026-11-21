using Tandem.Protocol;
using Tandem.Server.Auth;
using Tandem.Server.Http;

namespace Tandem.Server.Saves;

public static class SaveEndpoints
{
    public static IEndpointRouteBuilder MapSaveEndpoints(this IEndpointRouteBuilder app)
    {
        var saves = app.MapGroup("/v1/saves").RequireAuthorization();

        saves.MapPost("/upload", (HttpContext http, SaveUploadRequest request, SaveService svc, CancellationToken ct) =>
            svc.UploadAsync(http.User.UserId(), RequireDevice(http), request, ct));

        saves.MapPost("/{itemId:guid}/pin/{versionId:guid}", async (HttpContext http, Guid itemId, Guid versionId, SaveService svc, CancellationToken ct) =>
        {
            await svc.SetPinnedAsync(http.User.UserId(), itemId, versionId, true, ct);
            return Results.NoContent();
        });

        saves.MapDelete("/{itemId:guid}/pin/{versionId:guid}", async (HttpContext http, Guid itemId, Guid versionId, SaveService svc, CancellationToken ct) =>
        {
            await svc.SetPinnedAsync(http.User.UserId(), itemId, versionId, false, ct);
            return Results.NoContent();
        });

        saves.MapPost("/{itemId:guid}/versions/{versionId:guid}/restore", (HttpContext http, Guid itemId, Guid versionId, RestoreRequest request, SaveService svc, CancellationToken ct) =>
            svc.RestoreAsync(http.User.UserId(), itemId, versionId, request, ct));

        // Web session or device key: simple conflicts can be settled on the handheld.
        var conflicts = app.MapGroup("/v1/conflicts").RequireAuthorization();

        conflicts.MapPost("/{itemId:guid}/resolve", async (HttpContext http, Guid itemId, ResolveConflictRequest request, SaveService svc, CancellationToken ct) =>
        {
            await svc.ResolveAsync(http.User.UserId(), itemId, request, ct);
            return Results.NoContent();
        });

        conflicts.MapPost("/first-sync/resolve", async (HttpContext http, BulkFirstSyncRequest request, SaveService svc, CancellationToken ct) =>
        {
            await svc.ResolveFirstSyncAsync(http.User.UserId(), request, ct);
            return Results.NoContent();
        });
        return app;
    }

    private static Guid RequireDevice(HttpContext http) =>
        http.User.DeviceId() ?? throw new ApiException(400, "no_device", "This endpoint requires a device identity.");
}
