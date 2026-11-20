using System.Text.Json;
using System.Text.Json.Serialization;
using EmuSync.Protocol;
using EmuSync.Server.Auth;
using EmuSync.Server.Data;
using EmuSync.Server.Http;
using Microsoft.EntityFrameworkCore;

namespace EmuSync.Server.Events;

public static class EventEndpoints
{
    public const int DefaultLimit = 50;
    public const int MaxLimit = 200;

    private static readonly JsonSerializerOptions Wire = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    public static IEndpointRouteBuilder MapEventEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapGet("/v1/events", async (HttpContext http, IEventHub hub) =>
        {
            var ct = http.RequestAborted;
            await using var subscription = hub.Subscribe(http.User.UserId(), http.User.DeviceId(), ct); // registered before headers go out; disposed on any exit
            http.Response.Headers.ContentType = "text/event-stream";
            http.Response.Headers.CacheControl = "no-cache";
            http.Response.Headers["X-Accel-Buffering"] = "no";
            try
            {
                await http.Response.Body.FlushAsync(ct);
                await foreach (var evt in subscription.Events.WithCancellation(ct))
                {
                    var json = JsonSerializer.Serialize(new { type = evt.Type, topic = evt.Topic }, Wire);
                    await http.Response.WriteAsync($"event: {evt.Type}\ndata: {json}\n\n", ct);
                    await http.Response.Body.FlushAsync(ct);
                }
            }
            catch (OperationCanceledException) { }
        }).RequireAuthorization();

        app.MapGet("/v1/activity", async (HttpContext http, EmuSyncDbContext db, CancellationToken ct,
            Guid? device, string? emulator, string? game, long? before, int? limit) =>
        {
            var take = limit ?? DefaultLimit;
            if (take is < 1 or > MaxLimit)
                throw new ApiException(400, "bad_request", $"limit must be 1-{MaxLimit}.");
            var userId = http.User.UserId();
            var query = db.ActivityEvents.AsNoTracking().Where(a => a.UserId == userId);
            if (device is { } d) query = query.Where(a => a.DeviceId == d);
            if (!string.IsNullOrEmpty(emulator)) query = query.Where(a => a.EmulatorId == emulator);
            if (!string.IsNullOrEmpty(game)) query = query.Where(a => a.GameKey == game);
            if (before is { } b) query = query.Where(a => a.Id < b);

            var rows = await query.OrderByDescending(a => a.Id).Take(take + 1)
                .Select(a => new
                {
                    a.Id, a.Utc, a.Kind, a.Message, a.DeviceId, a.EmulatorId, a.GameKey,
                    DeviceName = db.Devices.Where(x => x.Id == a.DeviceId).Select(x => x.Name).FirstOrDefault(),
                })
                .ToListAsync(ct);
            var more = rows.Count > take;
            var items = rows.Take(take)
                .Select(r => new ActivityItem(r.Id, r.Utc, r.Kind, r.Message, r.DeviceId, r.DeviceName, r.EmulatorId, r.GameKey))
                .ToList();
            return new ActivityPage(items, more ? items[^1].Id : null);
        }).RequireAuthorization();
        return app;
    }
}
