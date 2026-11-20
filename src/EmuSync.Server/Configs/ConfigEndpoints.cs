using EmuSync.Protocol;
using EmuSync.Server.Auth;
using EmuSync.Server.Http;

namespace EmuSync.Server.Configs;

public static class ConfigEndpoints
{
    public static IEndpointRouteBuilder MapConfigEndpoints(this IEndpointRouteBuilder app)
    {
        // Web session or device key, like saves; uploads need a device identity.
        var configs = app.MapGroup("/v1/configs").RequireAuthorization();

        configs.MapPost("/upload", (HttpContext http, ConfigUploadRequest request, ConfigService svc, CancellationToken ct) =>
            svc.UploadAsync(http.User.UserId(), RequireDevice(http), request, ct));

        configs.MapPost("/bundles/{id:guid}/conflicts/resolve", async (HttpContext http, Guid id, ResolveConflictRequest request,
            ConfigService svc, CancellationToken ct) =>
        {
            await svc.ResolveAsync(http.User.UserId(), id, request, ct);
            return Results.NoContent();
        });

        configs.MapGet("/groups", (HttpContext http, ConfigService svc, CancellationToken ct) =>
            svc.ListGroupsAsync(http.User.UserId(), ct));

        configs.MapPost("/groups", (HttpContext http, CreateGroupRequest request, ConfigService svc, CancellationToken ct) =>
            svc.CreateGroupAsync(http.User.UserId(), request, ct));

        configs.MapPost("/groups/{id:guid}/members", (HttpContext http, Guid id, JoinGroupRequest request, ConfigService svc,
            CancellationToken ct) => svc.JoinAsync(http.User.UserId(), id, request, ct));

        configs.MapDelete("/groups/{id:guid}/members/{deviceId:guid}", async (HttpContext http, Guid id, Guid deviceId,
            ConfigService svc, CancellationToken ct) =>
        {
            await svc.LeaveAsync(http.User.UserId(), id, deviceId, ct);
            return Results.NoContent();
        });

        configs.MapDelete("/groups/{id:guid}", async (HttpContext http, Guid id, ConfigService svc, CancellationToken ct) =>
        {
            await svc.DeleteGroupAsync(http.User.UserId(), id, ct);
            return Results.NoContent();
        });

        configs.MapPost("/apply", (HttpContext http, ApplyConfigRequest request, ConfigService svc, CancellationToken ct) =>
            svc.ApplyAsync(http.User.UserId(), request, ct));
        return app;
    }

    private static Guid RequireDevice(HttpContext http) =>
        http.User.DeviceId() ?? throw new ApiException(400, "no_device", "This endpoint requires a device identity.");
}
