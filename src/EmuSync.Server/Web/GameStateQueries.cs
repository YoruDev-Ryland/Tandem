using EmuSync.Protocol;
using EmuSync.Server.Data;
using EmuSync.Server.Devices;
using Microsoft.EntityFrameworkCore;

namespace EmuSync.Server.Web;

/// <summary>
/// Per-device state of game rows (see <see cref="DeviceGameState"/>). <see cref="LoadAsync"/> reads everything a page of
/// rows needs in a fixed number of queries; <see cref="GameStates.For"/> then computes each row in memory.
/// </summary>
public sealed class GameStateQueries(EmuSyncDbContext db, TimeProvider time)
{
    /// <param name="items">The page's save items (already scoped to the user); used as a subquery.</param>
    public async Task<GameStates> LoadAsync(Guid userId, IQueryable<SaveItem> items, CancellationToken ct)
    {
        var itemIds = items.Select(i => i.Id);
        var devices = await db.Devices.AsNoTracking().Where(d => d.UserId == userId)
            .Select(d => new DeviceInfo(d.Id, d.Name, d.CreatedUtc,
                d.RevokedUtc == null && d.Platform != DeviceService.LegacyWebPlatform, d.ShizukuOk, d.RunningEmulatorsJson))
            .ToListAsync(ct);
        var known = await db.DeviceItemStates.AsNoTracking().Where(s => itemIds.Contains(s.ItemId))
            .Select(s => new { s.DeviceId, s.ItemId, s.KnownVersionId }).ToListAsync(ct);
        var pending = await db.Deliveries.AsNoTracking()
            .Where(d => d.Kind == DeliveryKind.Save && d.AckedUtc == null && itemIds.Contains(d.TargetId))
            .Select(d => new { d.DeviceId, d.TargetId }).ToListAsync(ct);
        var conflicts = await db.SaveVersions.AsNoTracking()
            .Where(v => itemIds.Contains(v.ItemId) && v.ConflictReason != null && !v.Resolved)
            .Select(v => new { v.ItemId, v.DeviceId }).ToListAsync(ct);

        var now = time.GetUtcNow();
        return new GameStates(
            devices.ToDictionary(d => d.Id),
            devices.Where(d => d.Active).OrderBy(d => d.CreatedUtc).ToList(),
            devices.ToDictionary(d => d.Id, d => DeviceService.RunningEmulators(d.RunningJson, d.ShizukuOk, now).ToHashSet(StringComparer.Ordinal)),
            known.ToDictionary(k => (k.DeviceId, k.ItemId), k => k.KnownVersionId),
            pending.Select(p => (p.DeviceId, p.TargetId)).ToHashSet(),
            conflicts.ToLookup(c => c.ItemId, c => c.DeviceId));
    }

    /// <param name="Active">Shown in game rows: not revoked, and not a legacy browser device.</param>
    public sealed record DeviceInfo(Guid Id, string Name, DateTimeOffset CreatedUtc, bool Active, bool? ShizukuOk, string? RunningJson);
}

public sealed record HeadInfo(Guid Id, Guid? DeviceId, DateTimeOffset ReceivedUtc);

public sealed class GameStates(
    Dictionary<Guid, GameStateQueries.DeviceInfo> allDevices,
    List<GameStateQueries.DeviceInfo> activeDevices,
    Dictionary<Guid, HashSet<string>> running,
    Dictionary<(Guid Device, Guid Item), Guid> known,
    HashSet<(Guid Device, Guid Item)> pending,
    ILookup<Guid, Guid?> openConflictDevices)
{
    public string? DeviceName(Guid? id) => id is Guid d && allDevices.TryGetValue(d, out var info) ? info.Name : null;

    /// <param name="reference">The item whose head decides HasIt (the game's save, else its newest item); null when no
    /// item has a head.</param>
    public GameDeviceState[] For(string emulatorId, IReadOnlyList<SaveItem> items, IReadOnlyList<InstallRecord> installs,
        IReadOnlyDictionary<Guid, HeadInfo> heads, SaveItem? reference, Guid? newestDeviceId)
    {
        var differs = new HashSet<Guid>();
        foreach (var item in items)
        {
            var sides = openConflictDevices[item.Id].ToList();
            if (sides.Count == 0) continue;
            foreach (var d in sides) if (d is Guid id) differs.Add(id);
            if (item.HeadVersionId is Guid h && heads.TryGetValue(h, out var head) && head.DeviceId is Guid hd) differs.Add(hd);
        }
        var installed = installs.Select(r => r.DeviceId).ToHashSet();

        return activeDevices.Select(d => new GameDeviceState(d.Id, d.Name, State(d.Id)))
            .OrderBy(s => s.State != DeviceGameState.Newest)
            .ThenBy(s => s.DeviceName, StringComparer.OrdinalIgnoreCase)
            .ThenBy(s => s.DeviceId)
            .ToArray();

        DeviceGameState State(Guid device)
        {
            if (differs.Contains(device)) return DeviceGameState.Differs;
            if (device == newestDeviceId) return DeviceGameState.Newest;
            if (items.Any(i => pending.Contains((device, i.Id))))
                return running.TryGetValue(device, out var r) && r.Contains(emulatorId) ? DeviceGameState.Waiting : DeviceGameState.Pending;
            if (reference?.HeadVersionId is Guid head
                ? known.TryGetValue((device, reference.Id), out var k) && k == head
                : installed.Contains(device))
                return DeviceGameState.HasIt;
            return installed.Contains(device) ? DeviceGameState.Behind : DeviceGameState.NotInstalled;
        }
    }
}
