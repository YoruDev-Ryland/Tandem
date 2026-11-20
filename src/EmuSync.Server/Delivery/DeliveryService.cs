using EmuSync.Protocol;
using EmuSync.Server.Data;
using EmuSync.Server.Events;
using EmuSync.Server.Http;
using EmuSync.Server.Saves;
using Microsoft.EntityFrameworkCore;
using DeliveryRow = EmuSync.Server.Data.Delivery;

namespace EmuSync.Server.Deliveries;

/// <summary>
/// Decides what each device should receive. Every recompute reconciles from the database (so running it again
/// changes nothing): a device gets the head of an item when it is not revoked, has the game installed, the item has
/// no open conflict, and the device does not already know the head. Runs under the user's lock so it never
/// interleaves with an upload's read-decide-write.
/// </summary>
public sealed class DeliveryService(EmuSyncDbContext db, UserLocks locks, IDomainEvents events, IEventHub hub,
    TimeProvider time, ILogger<DeliveryService> logger)
{
    /// <summary>After a Failed ack, the same device+target gets no new delivery for this long.</summary>
    public static readonly TimeSpan FailedBackoff = TimeSpan.FromMinutes(10);

    /// <summary>After a SkippedLocalChanged ack, the device's upload should settle it; until then (or this long) no
    /// new delivery for that device+target, so a skip never turns into a push/skip loop.</summary>
    public static readonly TimeSpan SkippedBackoff = TimeSpan.FromMinutes(2);

    public const int MaxDetail = 2000;

    public async Task RecomputeForItemAsync(Guid itemId, CancellationToken ct)
    {
        var userId = await db.SaveItems.Where(i => i.Id == itemId).Select(i => (Guid?)i.UserId).FirstOrDefaultAsync(ct);
        if (userId is null) return;
        List<Guid> notify;
        using (await locks.Acquire(userId.Value, ct))
            notify = await ReconcileAsync(userId.Value, itemId, null, ct);
        Notify(notify);
    }

    public async Task RecomputeForDeviceAsync(Guid deviceId, CancellationToken ct)
    {
        var userId = await db.Devices.Where(d => d.Id == deviceId).Select(d => (Guid?)d.UserId).FirstOrDefaultAsync(ct);
        if (userId is null) return;
        List<Guid> notify;
        using (await locks.Acquire(userId.Value, ct))
            notify = await ReconcileAsync(userId.Value, null, deviceId, ct);
        Notify(notify);
    }

    /// <summary>The device's un-acked deliveries, oldest first. Reconciles first, so an expired Failed backoff
    /// (or a missed event) is picked up on the next poll.</summary>
    public async Task<DeliveryList> ListAsync(Guid userId, Guid deviceId, CancellationToken ct)
    {
        using (await locks.Acquire(userId, ct))
            await ReconcileAsync(userId, null, deviceId, ct); // no push: the device is reading the list right now

        var pending = await db.Deliveries.AsNoTracking()
            .Where(d => d.DeviceId == deviceId && d.AckedUtc == null)
            .OrderBy(d => d.CreatedUtc).ThenBy(d => d.Id)
            .ToListAsync(ct);
        // Task 11 adds Config (and vault) deliveries; until then only saves/states are produced.
        var saveTargets = pending.Where(d => d.Kind == DeliveryKind.Save).Select(d => d.TargetId).Distinct().ToList();
        var versionIds = pending.Where(d => d.Kind == DeliveryKind.Save).Select(d => d.VersionId).Distinct().ToList();
        var items = await db.SaveItems.AsNoTracking().Where(i => saveTargets.Contains(i.Id) && i.UserId == userId)
            .ToDictionaryAsync(i => i.Id, ct);
        var versions = await db.SaveVersions.AsNoTracking().Where(v => versionIds.Contains(v.Id))
            .ToDictionaryAsync(v => v.Id, ct);
        var known = await db.DeviceItemStates.AsNoTracking()
            .Where(s => s.DeviceId == deviceId && saveTargets.Contains(s.ItemId))
            .ToDictionaryAsync(s => s.ItemId, s => s.KnownVersionId, ct);

        var result = new List<DeliveryDto>();
        foreach (var d in pending)
        {
            if (d.Kind != DeliveryKind.Save
                || !items.TryGetValue(d.TargetId, out var item)
                || !versions.TryGetValue(d.VersionId, out var version))
                continue;
            result.Add(new DeliveryDto(d.Id, d.Kind, d.TargetId, d.VersionId,
                known.TryGetValue(item.Id, out var k) ? k : null,
                item.EmulatorId, item.GameKey, item.Kind, item.Slot, version.Core,
                Manifests.FromJson(version.ManifestJson), null));
        }
        return new DeliveryList(result);
    }

    public async Task AckAsync(Guid userId, Guid deviceId, Guid deliveryId, AckRequest request, CancellationToken ct)
    {
        if (request is null || !Enum.IsDefined(request.Result))
            throw new ApiException(400, "bad_request", "Unknown result.");
        // Truncate rather than reject: a device must never be stuck retrying its own failure report.
        var detail = request.Detail is { Length: > MaxDetail } ? request.Detail[..MaxDetail] : request.Detail;

        List<Guid> notify = [];
        var wasSuperseded = false;
        using (await locks.Acquire(userId, ct))
        {
            var delivery = await db.Deliveries.FirstOrDefaultAsync(d => d.Id == deliveryId && d.DeviceId == deviceId, ct)
                ?? throw new ApiException(404, "delivery_not_found", "Delivery not found.");
            wasSuperseded = delivery.Superseded;
            if (delivery.Superseded)
            {
                // A late ack of a delivery a newer version replaced. Applied is still true (the device holds that
                // version), so record it; anything else changes nothing. Either way the device is told 204.
                if (request.Result != DeliveryResult.Applied) return;
                await MarkAppliedAsync(delivery, ct);
                await db.SaveChangesAsync(ct);
            }
            else
            {
                if (delivery.AckedUtc is not null)
                    throw new ApiException(409, "already_acked", "This delivery was already acknowledged.");
                delivery.AckedUtc = time.GetUtcNow();
                delivery.Result = request.Result;
                if (request.Result == DeliveryResult.Applied)
                    await MarkAppliedAsync(delivery, ct);
                await db.SaveChangesAsync(ct);
            }

            if (delivery.Kind == DeliveryKind.Save)
                notify = await ReconcileAsync(userId, delivery.TargetId, null, ct);
            notify.Remove(deviceId); // the acking device is talking to us; never push it from its own ack
        }
        Notify(notify);

        if (request.Result == DeliveryResult.Failed && !wasSuperseded)
        {
            logger.LogWarning("Delivery {DeliveryId} failed on device {DeviceId}: {Detail}", deliveryId, deviceId, detail);
            await events.Raise(new DeliveryFailed(userId, deviceId, deliveryId, detail), ct);
        }
    }

    private async Task MarkAppliedAsync(DeliveryRow delivery, CancellationToken ct)
    {
        switch (delivery.Kind)
        {
            case DeliveryKind.Save:
                if (!await db.SaveItems.AnyAsync(i => i.Id == delivery.TargetId, ct)) return;
                var state = await db.DeviceItemStates.FirstOrDefaultAsync(s => s.DeviceId == delivery.DeviceId && s.ItemId == delivery.TargetId, ct);
                if (state is null)
                {
                    db.DeviceItemStates.Add(new DeviceItemState { DeviceId = delivery.DeviceId, ItemId = delivery.TargetId, KnownVersionId = delivery.VersionId });
                }
                else if (state.KnownVersionId != delivery.VersionId)
                {
                    // A late ack must not move known backwards: if the device already knows a later version (e.g. it
                    // uploaded one after applying this), that is what it holds.
                    var seqs = await db.SaveVersions.Where(v => v.Id == state.KnownVersionId || v.Id == delivery.VersionId)
                        .ToDictionaryAsync(v => v.Id, v => v.Seq, ct);
                    if (!seqs.TryGetValue(state.KnownVersionId, out var knownSeq)
                        || (seqs.TryGetValue(delivery.VersionId, out var deliveredSeq) && deliveredSeq > knownSeq))
                        state.KnownVersionId = delivery.VersionId;
                }
                break;
            case DeliveryKind.Config:
                var config = await db.DeviceConfigs.FirstOrDefaultAsync(c => c.DeviceId == delivery.DeviceId && c.BundleId == delivery.TargetId, ct);
                if (config is not null) config.AppliedVersionId = delivery.VersionId;
                break;
        }
    }

    /// <summary>
    /// Reconciles Save deliveries for one item (all devices) or one device (all items). Caller holds the user lock.
    /// Returns the devices that got a new delivery.
    /// </summary>
    private async Task<List<Guid>> ReconcileAsync(Guid userId, Guid? itemId, Guid? deviceId, CancellationToken ct)
    {
        var items = db.SaveItems.Where(i => i.UserId == userId && i.HeadVersionId != null && !i.OpenConflict);
        if (itemId is Guid onlyItem) items = items.Where(i => i.Id == onlyItem);
        var devices = db.Devices.Where(d => d.UserId == userId && d.RevokedUtc == null);
        if (deviceId is Guid onlyDevice) devices = devices.Where(d => d.Id == onlyDevice);

        var wanted = await (
                from i in items
                from d in devices
                where db.InstallRecords.Any(r => r.DeviceId == d.Id && r.EmulatorId == i.EmulatorId && r.GameKey == i.GameKey)
                      && !db.DeviceItemStates.Any(s => s.DeviceId == d.Id && s.ItemId == i.Id && s.KnownVersionId == i.HeadVersionId)
                select new { DeviceId = d.Id, ItemId = i.Id, Head = i.HeadVersionId!.Value })
            .ToListAsync(ct);
        var wantedHead = wanted.ToDictionary(w => (w.DeviceId, w.ItemId), w => w.Head);

        // Every Save delivery in scope that belongs to this user: targets are the user's items.
        var userItems = db.SaveItems.Where(i => i.UserId == userId).Select(i => i.Id);
        var scope = db.Deliveries.Where(d => d.Kind == DeliveryKind.Save && userItems.Contains(d.TargetId));
        if (itemId is Guid targetFilter) scope = scope.Where(d => d.TargetId == targetFilter);
        if (deviceId is Guid deviceFilter) scope = scope.Where(d => d.DeviceId == deviceFilter);

        var now = time.GetUtcNow();
        var cutoff = now - (FailedBackoff > SkippedBackoff ? FailedBackoff : SkippedBackoff);
        var pending = await scope.Where(d => d.AckedUtc == null).OrderBy(d => d.CreatedUtc).ThenBy(d => d.Id).ToListAsync(ct);
        // Backoff looks at the device's own latest real ack; server-side supersedes don't count.
        var recentAcks = await scope.Where(d => !d.Superseded && d.AckedUtc != null && d.AckedUtc > cutoff)
            .Select(d => new { d.DeviceId, d.TargetId, AckedUtc = d.AckedUtc!.Value, d.Result }).ToListAsync(ct);
        var backingOff = recentAcks
            .GroupBy(a => (a.DeviceId, a.TargetId))
            .Where(g => g.MaxBy(a => a.AckedUtc) is { } last && last.Result switch
            {
                DeliveryResult.Failed => last.AckedUtc > now - FailedBackoff,
                DeliveryResult.SkippedLocalChanged => last.AckedUtc > now - SkippedBackoff,
                _ => false,
            })
            .Select(g => g.Key).ToHashSet();

        var covered = new HashSet<(Guid, Guid)>();
        foreach (var d in pending)
        {
            var key = (d.DeviceId, d.TargetId);
            if (wantedHead.TryGetValue(key, out var head) && d.VersionId == head && covered.Add(key)) continue;
            // Superseded, held by a conflict, no longer installed, or already known: soft-close, so a late ack of
            // it can still be honoured.
            d.Superseded = true;
            d.AckedUtc = now;
            d.Result = null;
        }

        var notify = new List<Guid>();
        foreach (var ((device, item), head) in wantedHead)
        {
            if (covered.Contains((device, item)) || backingOff.Contains((device, item))) continue;
            db.Deliveries.Add(new DeliveryRow
            {
                Id = Guid.NewGuid(), DeviceId = device, Kind = DeliveryKind.Save, TargetId = item, VersionId = head, CreatedUtc = now,
            });
            notify.Add(device);
        }

        await db.SaveChangesAsync(ct);
        return notify.Distinct().ToList();
    }

    private void Notify(List<Guid> devices)
    {
        foreach (var device in devices)
            hub.NotifyDevice(device);
    }
}
