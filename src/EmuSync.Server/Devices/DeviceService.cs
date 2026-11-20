using System.Text.Json;
using System.Text.RegularExpressions;
using EmuSync.Protocol;
using EmuSync.Server.Data;
using EmuSync.Server.Events;
using EmuSync.Server.Http;
using Microsoft.EntityFrameworkCore;

namespace EmuSync.Server.Devices;

public sealed partial class DeviceService(EmuSyncDbContext db, TimeProvider time, IDomainEvents events, IActivityLog activity)
{
    public const int MaxGames = 20000;
    public const int MaxCores = 500;
    public const int MaxRunningEmulators = 50;
    /// <summary>A device without a status report for this long is offline: its running emulators read as empty. Other
    /// requests (which refresh LastSeenUtc) do not count.</summary>
    public static readonly TimeSpan OnlineWindow = TimeSpan.FromMinutes(2);

    /// <summary>Stored in <see cref="Device.RunningEmulatorsJson"/>: the list and the time of the status report that sent it.</summary>
    private sealed record RunningStatus(DateTimeOffset At, string[] Ids);

    /// <summary>The device's running emulators; empty when its last status report is older than <see cref="OnlineWindow"/>
    /// or Shizuku is off (the app cannot see the foreground app then). A value it cannot read (an older format, or
    /// damaged) reads as empty too, so one bad row cannot fail every page that lists devices.</summary>
    public static string[] RunningEmulators(string? json, bool? shizukuOk, DateTimeOffset now)
    {
        if (json is null || shizukuOk == false) return [];
        RunningStatus? status;
        try
        {
            status = JsonSerializer.Deserialize<RunningStatus>(json, Json);
        }
        catch (JsonException)
        {
            return [];
        }
        return status is null || now - status.At > OnlineWindow ? [] : status.Ids ?? [];
    }

    public static DeviceSummary Summary(Device d, Guid? currentDeviceId, DateTimeOffset now) =>
        new(d.Id, d.Name, d.Platform, d.LastSeenUtc, d.ShizukuOk, d.Id == currentDeviceId,
            RunningEmulators(d.RunningEmulatorsJson, d.ShizukuOk, now));

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    [GeneratedRegex(@"\A\{(INTERNAL|SD|EMU:[a-z0-9]+)\}\z")] // \z: "$" would also match before a trailing newline
    private static partial Regex RootToken();

    public Task<bool> HasInstalled(Guid deviceId, string emulatorId, string gameKey) =>
        db.InstallRecords.AnyAsync(r => r.DeviceId == deviceId && r.EmulatorId == emulatorId && r.GameKey == gameKey);

    public Task<string?> Root(Guid deviceId, string token) =>
        db.DeviceRoots.Where(r => r.DeviceId == deviceId && r.Token == token).Select(r => (string?)r.Path).FirstOrDefaultAsync();

    public async Task UpdateStatus(Guid deviceId, DeviceStatusRequest request, CancellationToken ct)
    {
        Validate(request);
        var device = await db.Devices.FirstOrDefaultAsync(d => d.Id == deviceId, ct)
            ?? throw new ApiException(404, "device_not_found", "Device not found.");
        var now = time.GetUtcNow();

        device.Platform = request.Platform;
        device.AppVersion = request.AppVersion;
        if (!request.ShizukuOk)
            device.ShizukuOffSinceUtc = device.ShizukuOk == false ? device.ShizukuOffSinceUtc ?? now : now;
        else
        {
            device.ShizukuOffSinceUtc = null;
            device.ShizukuOffNotifiedUtc = null;
        }
        device.ShizukuOk = request.ShizukuOk;
        device.LastSeenUtc = now;
        // Ids this server does not know (a newer app) are dropped, not a reason to reject the whole report.
        var running = (request.RunningEmulators ?? []).Where(id => id is not null && EmulatorIds.IsKnown(id))
            .Distinct(StringComparer.Ordinal).ToArray();
        device.RunningEmulatorsJson = running.Length > 0 ? JsonSerializer.Serialize(new RunningStatus(now, running), Json) : null;

        var emulators = request.Emulators ?? [];
        var roots = request.Roots ?? new Dictionary<string, string>();

        var oldEmus = await db.DeviceEmulators.Where(e => e.DeviceId == deviceId).ToListAsync(ct);
        db.DeviceEmulators.RemoveRange(oldEmus.Where(o => emulators.All(e => e.EmulatorId != o.EmulatorId)));
        foreach (var e in emulators)
        {
            var cores = e.Cores is null ? null : JsonSerializer.Serialize(e.Cores);
            var row = oldEmus.FirstOrDefault(o => o.EmulatorId == e.EmulatorId);
            if (row is null)
                db.DeviceEmulators.Add(new DeviceEmulator { DeviceId = deviceId, EmulatorId = e.EmulatorId, Package = e.Package, Version = e.Version, CoresJson = cores });
            else
            {
                row.Package = e.Package;
                row.Version = e.Version;
                row.CoresJson = cores;
            }
        }

        var oldRoots = await db.DeviceRoots.Where(r => r.DeviceId == deviceId).ToListAsync(ct);
        db.DeviceRoots.RemoveRange(oldRoots.Where(o => !roots.ContainsKey(o.Token)));
        foreach (var (token, path) in roots)
        {
            var row = oldRoots.FirstOrDefault(o => o.Token == token);
            if (row is null)
                db.DeviceRoots.Add(new DeviceRoot { DeviceId = deviceId, Token = token, Path = path });
            else
                row.Path = path;
        }
        await db.SaveChangesAsync(ct);
    }

    private static void Validate(DeviceStatusRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.Platform) || request.Platform.Length > 40)
            throw new ApiException(400, "bad_request", "Platform is required (max 40 characters).");
        if (request.AppVersion is null || request.AppVersion.Length > 40)
            throw new ApiException(400, "bad_request", "AppVersion is required (max 40 characters).");
        var seen = new HashSet<string>();
        foreach (var e in request.Emulators ?? [])
        {
            if (e.EmulatorId is null || !EmulatorIds.IsKnown(e.EmulatorId))
                throw new ApiException(400, "unknown_emulator", $"Unknown emulator '{e.EmulatorId}'.");
            if (!seen.Add(e.EmulatorId))
                throw new ApiException(400, "bad_request", $"Emulator '{e.EmulatorId}' listed twice.");
            if (e.Cores is { Count: > MaxCores })
                throw new ApiException(400, "too_many", $"At most {MaxCores} cores are allowed.");
            if (e.Package is null || e.Version is null)
                throw new ApiException(400, "bad_request", "Package and Version are required.");
        }
        if (request.RunningEmulators is { Length: > MaxRunningEmulators })
            throw new ApiException(400, "too_many", $"At most {MaxRunningEmulators} running emulators are allowed.");
        foreach (var (token, path) in request.Roots ?? new Dictionary<string, string>())
        {
            if (!RootToken().IsMatch(token))
                throw new ApiException(400, "bad_root", $"Invalid root token '{token}'.");
            if (token.StartsWith("{EMU:", StringComparison.Ordinal) && !EmulatorIds.IsKnown(token[5..^1]))
                throw new ApiException(400, "bad_root", $"Root token '{token}' names an unknown emulator.");
            if (string.IsNullOrEmpty(path) || !path.StartsWith('/'))
                throw new ApiException(400, "bad_root", $"Root path for '{token}' must be absolute.");
        }
    }

    public async Task<InstallsResponse> ReplaceInstalls(Guid userId, Guid deviceId, string emulatorId,
        InstallsRequest request, CancellationToken ct)
    {
        if (!EmulatorIds.IsKnown(emulatorId))
            throw new ApiException(400, "unknown_emulator", $"Unknown emulator '{emulatorId}'.");
        var games = request.Games ?? [];
        if (games.Count > MaxGames)
            throw new ApiException(400, "too_many", $"At most {MaxGames} games per request.");
        var keys = new HashSet<string>(StringComparer.Ordinal);
        foreach (var g in games)
        {
            if (string.IsNullOrEmpty(g.GameKey) || g.GameKey.Length > 300 || g.DisplayName is { Length: > 300 } || g.Crc32 is { Length: > 16 })
                throw new ApiException(400, "bad_game", "GameKey must be 1-300 characters, DisplayName at most 300.");
            if (!keys.Add(g.GameKey))
                throw new ApiException(400, "duplicate_game", $"Duplicate game '{g.GameKey}'.");
        }

        var now = time.GetUtcNow();
        var existing = await db.InstallRecords.Where(r => r.DeviceId == deviceId && r.EmulatorId == emulatorId).ToListAsync(ct);
        var existingKeys = existing.Select(r => r.GameKey).ToHashSet(StringComparer.Ordinal);
        var removed = existing.Where(r => !keys.Contains(r.GameKey)).ToList();
        var added = games.Where(g => !existingKeys.Contains(g.GameKey)).ToList();

        db.InstallRecords.RemoveRange(removed);
        var byKey = games.ToDictionary(g => g.GameKey, StringComparer.Ordinal);
        foreach (var r in existing.Where(r => keys.Contains(r.GameKey)))
        {
            var g = byKey[r.GameKey];
            r.DisplayName = g.DisplayName;
            r.Crc32 = g.Crc32;
            r.SeenUtc = now;
        }
        foreach (var g in added)
            db.InstallRecords.Add(new InstallRecord
            {
                DeviceId = deviceId, EmulatorId = emulatorId, GameKey = g.GameKey,
                DisplayName = g.DisplayName, Crc32 = g.Crc32, SeenUtc = now,
            });
        await db.SaveChangesAsync(ct);

        if (added.Count > 0)
            await events.Raise(new InstallsChanged(userId, deviceId, emulatorId, added.Select(g => g.GameKey).ToArray()), ct);
        return new InstallsResponse(added.Count, removed.Count);
    }

    public async Task<DeviceSummary> Rename(Guid userId, Guid? currentDeviceId, Guid id, string? name, CancellationToken ct)
    {
        var trimmed = name?.Trim() ?? "";
        if (trimmed.Length is < 1 or > 80)
            throw new ApiException(400, "bad_name", "Device name must be 1-80 characters.");
        var device = await Owned(userId, id, ct);
        device.Name = trimmed;
        await db.SaveChangesAsync(ct);
        return Summary(device, currentDeviceId, time.GetUtcNow());
    }

    public async Task Revoke(Guid userId, Guid id, CancellationToken ct)
    {
        var device = await Owned(userId, id, ct);
        var firstRevoke = device.RevokedUtc is null;
        device.RevokedUtc ??= time.GetUtcNow();
        await db.SaveChangesAsync(ct);
        if (firstRevoke)
            await activity.WriteAsync(userId, "device_revoked", $"{device.Name} was revoked", device.Id);
    }

    private async Task<Device> Owned(Guid userId, Guid id, CancellationToken ct) =>
        await db.Devices.FirstOrDefaultAsync(d => d.Id == id && d.UserId == userId, ct)
        ?? throw new ApiException(404, "device_not_found", "Device not found.");
}
