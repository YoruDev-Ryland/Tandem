using System.Text.Json;
using EmuSync.Server.Data;
using Microsoft.EntityFrameworkCore;

namespace EmuSync.Server.Events;

public interface IActivityLog
{
    /// <summary>
    /// Records an activity row and nudges the user's live streams. Never throws: a logging failure must not fail the
    /// request that caused it.
    /// </summary>
    Task WriteAsync(Guid userId, string kind, string message, Guid? deviceId = null, string? emulatorId = null,
        string? gameKey = null, object? data = null);
}

public sealed class ActivityLog(EmuSyncDbContext db, IEventHub hub, TimeProvider time, ILogger<ActivityLog> logger) : IActivityLog
{
    public async Task WriteAsync(Guid userId, string kind, string message, Guid? deviceId = null, string? emulatorId = null,
        string? gameKey = null, object? data = null)
    {
        // Always runs after the action committed, so it deliberately ignores the request's cancellation.
        var row = new ActivityEvent
        {
                UserId = userId, Utc = time.GetUtcNow(), Kind = kind, DeviceId = deviceId, EmulatorId = emulatorId,
                GameKey = gameKey, Message = message.Length > 500 ? message[..500] : message,
                DataJson = data is null ? null : JsonSerializer.Serialize(data),
        };
        try
        {
            db.ActivityEvents.Add(row);
            await db.SaveChangesAsync(CancellationToken.None);
        }
        catch (Exception ex)
        {
            db.Entry(row).State = EntityState.Detached; // must not poison later SaveChanges in this scope
            logger.LogWarning(ex, "Could not write activity {Kind}", kind);
            return;
        }

        try
        {
            hub.NotifyUser(userId, "activity");
            switch (kind)
            {
                case "save_uploaded" or "delivered" or "delivery_failed":
                    hub.NotifyUser(userId, "saves");
                    break;
                case "conflict_opened" or "conflict_resolved":
                    hub.NotifyUser(userId, "conflicts");
                    hub.NotifyUser(userId, "saves");
                    break;
                case "device_paired" or "device_revoked":
                    hub.NotifyUser(userId, "devices");
                    break;
            }
        }
        catch (Exception ex)
        {
            logger.LogWarning(ex, "Could not notify for activity {Kind}", kind);
        }
    }
}
