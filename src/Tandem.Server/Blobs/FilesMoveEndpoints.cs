using Tandem.Protocol;
using Tandem.Server.Auth;

namespace Tandem.Server.Blobs;

/// <summary>Admin routes for moving the stored files: check a folder, start, follow, cancel, and remove the old copy.</summary>
public static class FilesMoveEndpoints
{
    public static IEndpointRouteBuilder MapFilesMoveEndpoints(this IEndpointRouteBuilder app)
    {
        var storage = app.MapGroup("/v1/admin/storage").RequireAuthorization(AuthSetup.AdminPolicy);

        storage.MapPost("/move/check", (MovePathRequest body, FilesMover mover) => mover.Check(body.Path));

        storage.MapPost("/move", (MovePathRequest body, FilesMover mover) =>
            mover.TryStart(body.Path, out var check)
                ? Results.Accepted()
                : Results.Json(new { code = "bad_path", message = "The stored files can't move to that folder.", check },
                    statusCode: StatusCodes.Status400BadRequest));

        storage.MapGet("/move", (FilesMover mover) => mover.Status);

        storage.MapPost("/move/cancel", (FilesMover mover) =>
        {
            mover.Cancel();
            return Results.NoContent();
        });

        storage.MapPost("/move/remove-old", async (FilesMover mover, CancellationToken ct) =>
        {
            await mover.RemoveOldAsync(ct);
            return Results.NoContent();
        });
        return app;
    }
}
