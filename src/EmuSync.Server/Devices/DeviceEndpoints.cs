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

        devices.MapGet("", async (HttpContext http, EmuSyncDbContext db, CancellationToken ct) =>
        {
            var userId = http.User.UserId();
            var current = http.User.DeviceId();
            return await db.Devices.AsNoTracking()
                .Where(d => d.UserId == userId && d.RevokedUtc == null)
                .OrderBy(d => d.CreatedUtc)
                .Select(d => new DeviceSummary(d.Id, d.Name, d.Platform, d.LastSeenUtc, d.ShizukuOk, d.Id == current))
                .ToArrayAsync(ct);
        });

        devices.MapGet("/me", async (HttpContext http, EmuSyncDbContext db, CancellationToken ct) =>
        {
            var id = http.User.DeviceId() ?? throw new ApiException(404, "no_device", "This session is not tied to a device.");
            return await db.Devices.AsNoTracking().Where(d => d.Id == id)
                .Select(d => new DeviceSummary(d.Id, d.Name, d.Platform, d.LastSeenUtc, d.ShizukuOk, true))
                .FirstOrDefaultAsync(ct)
                ?? throw new ApiException(404, "no_device", "This session is not tied to a device.");
        });
        return app;
    }
}
