using EmuSync.Protocol;
using EmuSync.Server.Auth;
using EmuSync.Server.Data;
using EmuSync.Server.Http;
using Microsoft.EntityFrameworkCore;

namespace EmuSync.Server.Devices;

public static class DeviceEndpoints
{
    public static IEndpointRouteBuilder MapDeviceEndpoints(this IEndpointRouteBuilder app)
    {
        var devices = app.MapGroup("/v1/devices").RequireAuthorization();

        devices.MapGet("", async (HttpContext http, EmuSyncDbContext db, TimeProvider time, CancellationToken ct) =>
        {
            var userId = http.User.UserId();
            var current = http.User.DeviceId();
            var now = time.GetUtcNow();
            return (await db.Devices.AsNoTracking()
                    .Where(d => d.UserId == userId && d.RevokedUtc == null && d.Platform != DeviceService.LegacyWebPlatform)
                    .OrderBy(d => d.CreatedUtc)
                    .ToListAsync(ct))
                .Select(d => DeviceService.Summary(d, current, now)).ToArray();
        });

        devices.MapGet("/me", async (HttpContext http, EmuSyncDbContext db, TimeProvider time, CancellationToken ct) =>
        {
            var id = http.User.DeviceId() ?? throw new ApiException(404, "no_device", "This session is not tied to a device.");
            var device = await db.Devices.AsNoTracking().FirstOrDefaultAsync(d => d.Id == id, ct)
                ?? throw new ApiException(404, "no_device", "This session is not tied to a device.");
            return DeviceService.Summary(device, id, time.GetUtcNow());
        });

        devices.MapPut("/me/status", async (HttpContext http, DeviceStatusRequest request, DeviceService svc, CancellationToken ct) =>
        {
            await svc.UpdateStatus(RequireDevice(http), request, ct);
            return Results.NoContent();
        });

        devices.MapPut("/me/installs/{emulatorId}", (HttpContext http, string emulatorId, InstallsRequest request, DeviceService svc, CancellationToken ct) =>
            svc.ReplaceInstalls(http.User.UserId(), RequireDevice(http), emulatorId, request, ct));

        devices.MapPatch("/{id:guid}", (HttpContext http, Guid id, RenameDeviceRequest request, DeviceService svc, CancellationToken ct) =>
            svc.Rename(http.User.UserId(), http.User.DeviceId(), id, request.Name, ct));

        devices.MapDelete("/{id:guid}", async (HttpContext http, Guid id, DeviceService svc, CancellationToken ct) =>
        {
            await svc.Revoke(http.User.UserId(), id, ct);
            return Results.NoContent();
        });
        return app;
    }

    private static Guid RequireDevice(HttpContext http) =>
        http.User.DeviceId() ?? throw new ApiException(400, "no_device", "This endpoint requires a device identity.");
}
