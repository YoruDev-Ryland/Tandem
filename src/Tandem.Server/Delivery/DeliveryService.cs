using System.Text.Json;
using Tandem.Protocol;
using Tandem.Server.Configs;
using Tandem.Server.Data;
using Tandem.Server.Events;
using Tandem.Server.Http;
using Tandem.Server.Saves;
using Microsoft.EntityFrameworkCore;
using DeliveryRow = Tandem.Server.Data.Delivery;

namespace Tandem.Server.Deliveries;

/// <summary>
/// Decides what each device should receive. Every recompute reconciles from the database (so running it again
/// changes nothing): a device gets the head of an item when it is not revoked, has the game installed, the item has
/// no open conflict, and the device does not already know the head. Runs under the user's lock so it never
/// interleaves with an upload's read-decide-write.
/// </summary>
public sealed class DeliveryService(TandemDbContext db, UserLocks locks, IDomainEvents events, IEventHub hub,
    TimeProvider time, ILogger<DeliveryService> logger, IActivityLog activity, ConfigTransform configs, MaintenanceLock maintenance)
{
    private static readonly JsonSerializerOptions NotesJson = new(JsonSerializerDefaults.Web);

    /// <summary>A Config delivery's <see cref="DeliveryRow.NotesJson"/>: the version expanded for the target device.</summary>
    public sealed record ConfigNotes(List<ManifestEntry> Manifest, List<string> MissingLocalKeys);

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

    /// <summary>Reconciles every Config delivery of the user (bundle head, conflict or membership changed).</summary>
    public async Task RecomputeConfigsAsync(Guid userId, CancellationToken ct)
    {
        List<Guid> notify;
        using (await locks.Acquire(userId, ct))
            notify = await ReconcileConfigsAsync(userId, null, ct);
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

    /// <summary>
    /// The device forgot what it held for the emulators in <paramref name="request"/> (a variant switch, a new
    /// sign-in): drops this server's record of it too. The device's known versions of those emulators' save items are
    /// deleted and its applied config versions cleared, so neither the known-head rule of a save upload nor the
    /// applied-version rule of a config upload can take a later upload as based on the head, and the heads of its
    /// installed games and its config are delivered again. Unknown emulator ids are ignored.
    /// </summary>
    public async Task ForgetAsync(Guid userId, Guid deviceId, ForgetRequest request, CancellationToken ct)
    {
        var requested = request.EmulatorIds ?? [];
        if (requested.Count > ForgetRequest.MaxEmulators)
            throw new ApiException(400, "too_many", $"At most {ForgetRequest.MaxEmulators} emulators are allowed.");
        var ids = requested.Where(id => id is not null && EmulatorIds.IsKnown(id)).Distinct(StringComparer.Ordinal).ToList();
        if (ids.Count == 0) return;

        List<Guid> notify;
        using (await maintenance.AcquireAsync(ct))   // the order everywhere: maintenance, then the user's lock
        using (await locks.Acquire(userId, ct))
        {
            var items = db.SaveItems.Where(i => i.UserId == userId && ids.Contains(i.EmulatorId)).Select(i => i.Id);
            var states = await db.DeviceItemStates.Where(s => s.DeviceId == deviceId && items.Contains(s.ItemId)).ToListAsync(ct);
            db.DeviceItemStates.RemoveRange(states);
            var configs = await db.DeviceConfigs.Where(c => c.DeviceId == deviceId && ids.Contains(c.EmulatorId)).ToListAsync(ct);
            foreach (var c in configs) c.AppliedVersionId = null;
            await db.SaveChangesAsync(ct);
            notify = (await ReconcileAsync(userId, null, deviceId, ct)).Concat(await ReconcileConfigsAsync(userId, deviceId, ct))
                .Distinct().ToList();
        }
        Notify(notify);
    }

    /// <summary>The device's un-acked deliveries, oldest first. Reconciles first, so an expired Failed backoff
    /// (or a missed event) is picked up on the next poll.</summary>
    public async Task<DeliveryList> ListAsync(Guid userId, Guid deviceId, CancellationToken ct)
    {
        using (await locks.Acquire(userId, ct))
        {
            // No push: the device is reading the list right now.
            await ReconcileAsync(userId, null, deviceId, ct);
            await ReconcileConfigsAsync(userId, deviceId, ct);
        }

        var pending = await db.Deliveries.AsNoTracking()
            .Where(d => d.DeviceId == deviceId && d.AckedUtc == null)
            .OrderBy(d => d.CreatedUtc).ThenBy(d => d.Id)
            .ToListAsync(ct);
        var saveTargets = pending.Where(d => d.Kind == DeliveryKind.Save).Select(d => d.TargetId).Distinct().ToList();
        var versionIds = pending.Where(d => d.Kind == DeliveryKind.Save).Select(d => d.VersionId).Distinct().ToList();
        var items = await db.SaveItems.AsNoTracking().Where(i => saveTargets.Contains(i.Id) && i.UserId == userId)
            .ToDictionaryAsync(i => i.Id, ct);
        var versions = await db.SaveVersions.AsNoTracking().Where(v => versionIds.Contains(v.Id))
            .ToDictionaryAsync(v => v.Id, ct);
        var known = await db.DeviceItemStates.AsNoTracking()
            .Where(s => s.DeviceId == deviceId && saveTargets.Contains(s.ItemId))
            .ToDictionaryAsync(s => s.ItemId, s => s.KnownVersionId, ct);
        var configTargets = pending.Where(d => d.Kind == DeliveryKind.Config).Select(d => d.TargetId).Distinct().ToList();
        var bundles = await db.ConfigBundles.AsNoTracking().Where(b => configTargets.Contains(b.Id) && b.UserId == userId)
            .ToDictionaryAsync(b => b.Id, ct);
        var applied = await db.DeviceConfigs.AsNoTracking().Where(c => c.DeviceId == deviceId && configTargets.Contains(c.BundleId))
            .ToDictionaryAsync(c => c.BundleId, c => c.AppliedVersionId, ct);

        var vaultTargets = pending.Where(d => d.Kind == DeliveryKind.Vault).Select(d => d.TargetId).Distinct().ToList();
        var vaultVersionIds = pending.Where(d => d.Kind == DeliveryKind.Vault).Select(d => d.VersionId).Distinct().ToList();
        var vaultItems = await db.VaultItems.AsNoTracking().Where(i => vaultTargets.Contains(i.Id) && i.UserId == userId)
            .ToDictionaryAsync(i => i.Id, ct);
        var vaultVersions = await db.VaultVersions.AsNoTracking().Where(v => vaultVersionIds.Contains(v.Id))
            .ToDictionaryAsync(v => v.Id, v => v.ManifestJson, ct);

        var result = new List<DeliveryDto>();
        foreach (var d in pending)
        {
            if (d.Kind == DeliveryKind.Vault
                && vaultItems.TryGetValue(d.TargetId, out var vaultItem)
                && vaultVersions.TryGetValue(d.VersionId, out var vaultManifest))
            {
                // Only the item id and manifest identify a vault delivery; the emulator is "" when the item has none.
                result.Add(new DeliveryDto(d.Id, d.Kind, d.TargetId, d.VersionId, null, vaultItem.EmulatorId ?? "",
                    null, null, null, null, Manifests.FromJson(vaultManifest), null));
                continue;
            }

            if (d.Kind == DeliveryKind.Save
                && items.TryGetValue(d.TargetId, out var item)
                && versions.TryGetValue(d.VersionId, out var version))
            {
                result.Add(new DeliveryDto(d.Id, d.Kind, d.TargetId, d.VersionId,
                    known.TryGetValue(item.Id, out var k) ? k : null,
                    item.EmulatorId, item.GameKey, item.Kind, item.Slot, version.Core,
                    Manifests.FromJson(version.ManifestJson), null));
            }
            else if (d.Kind == DeliveryKind.Config
                     && bundles.TryGetValue(d.TargetId, out var bundle)
                     && d.NotesJson is not null
                     && JsonSerializer.Deserialize<ConfigNotes>(d.NotesJson, NotesJson) is { } notes)
            {
                result.Add(new DeliveryDto(d.Id, d.Kind, d.TargetId, d.VersionId,
                    applied.TryGetValue(bundle.Id, out var a) ? a : null,
                    bundle.EmulatorId, null, null, null, null, notes.Manifest, notes.MissingLocalKeys));
            }
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
        string? emulatorId = null, gameKey = null, label = null;
        DeliveryRow delivery;
        using (await locks.Acquire(userId, ct))
        {
            delivery = await db.Deliveries.FirstOrDefaultAsync(d => d.Id == deliveryId && d.DeviceId == deviceId, ct)
                ?? throw new ApiException(404, "delivery_not_found", "Delivery not found.");
            wasSuperseded = delivery.Superseded;
            if (delivery.Superseded)
            {
                // A late ack of a delivery a newer version replaced. Applied is still true (the device holds that
                // version), so record it; anything else changes nothing. Either way the device is told 204.
                if (request.Result != DeliveryResult.Applied) return;
                await MarkAppliedAsync(delivery, lateAck: true, ct);
                delivery.Detail = detail;
                await db.SaveChangesAsync(ct);
            }
            else
            {
                if (delivery.AckedUtc is not null)
                    throw new ApiException(409, "already_acked", "This delivery was already acknowledged.");
                delivery.AckedUtc = time.GetUtcNow();
                delivery.Result = request.Result;
                delivery.Detail = detail;
                if (request.Result == DeliveryResult.Applied)
                    await MarkAppliedAsync(delivery, lateAck: false, ct);
                await db.SaveChangesAsync(ct);
            }

            if (delivery.Kind == DeliveryKind.Save)
            {
                var item = await db.SaveItems.AsNoTracking().Where(i => i.Id == delivery.TargetId)
                    .Select(i => new { i.EmulatorId, i.GameKey, i.DisplayName }).FirstOrDefaultAsync(ct);
                if (item is not null) (emulatorId, gameKey, label) = (item.EmulatorId, item.GameKey, Games.GameNames.Label(item.GameKey, item.DisplayName));
            }
            else if (delivery.Kind == DeliveryKind.Vault)
            {
                var item = await db.VaultItems.AsNoTracking().Where(i => i.Id == delivery.TargetId)
                    .Select(i => new { i.EmulatorId, i.Name }).FirstOrDefaultAsync(ct);
                if (item is not null) (emulatorId, label) = (item.EmulatorId, item.Name);
            }
            else if (delivery.Kind == DeliveryKind.Config)
            {
                emulatorId = await db.ConfigBundles.AsNoTracking().Where(b => b.Id == delivery.TargetId)
                    .Select(b => b.EmulatorId).FirstOrDefaultAsync(ct);
                if (emulatorId is not null) label = $"the {Games.EmulatorNames.Display(emulatorId)} config";
            }
            label ??= delivery.Kind == DeliveryKind.Save ? "a save" : delivery.Kind.ToString().ToLowerInvariant();
            if (delivery.Kind == DeliveryKind.Save)
                notify = await ReconcileAsync(userId, delivery.TargetId, null, ct);
            else if (delivery.Kind == DeliveryKind.Config)
                notify = await ReconcileConfigsAsync(userId, null, ct);
            notify.Remove(deviceId); // the acking device is talking to us; never push it from its own ack
        }
        Notify(notify);

        if (request.Result == DeliveryResult.Applied)
        {
            var name = await db.Devices.AsNoTracking().Where(d => d.Id == deviceId).Select(d => d.Name).FirstOrDefaultAsync(ct) ?? "A device";
            if (delivery.Kind == DeliveryKind.Vault)
                await activity.WriteAsync(userId, "vault_placed", $"{name} received {label} from the vault", deviceId, emulatorId,
                    data: new { itemId = delivery.TargetId, versionId = delivery.VersionId });
            else
                await activity.WriteAsync(userId, "delivered", $"{name} received {label}", deviceId, emulatorId, gameKey);
        }

        if (request.Result == DeliveryResult.Failed && !wasSuperseded)
        {
            logger.LogWarning("Delivery {DeliveryId} failed on device {DeviceId}: {Detail}", deliveryId, deviceId, detail);
            await events.Raise(new DeliveryFailed(userId, deviceId, deliveryId, detail), ct);
            var name = await db.Devices.AsNoTracking().Where(d => d.Id == deviceId).Select(d => d.Name).FirstOrDefaultAsync(ct) ?? "A device";
            await activity.WriteAsync(userId, "delivery_failed", $"{name} failed to apply {label}", deviceId, emulatorId, gameKey,
                new { deliveryId, detail });
        }
    }

    /// <param name="lateAck">The delivery was superseded before this ack. A live delivery is the current head, so
    /// applying it always sets known (even when the head is historical, with a lower Seq than what the device knew);
    /// only a late ack is checked against moving known backwards.</param>
    private async Task MarkAppliedAsync(DeliveryRow delivery, bool lateAck, CancellationToken ct)
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
                else if (!lateAck)
                {
                    state.KnownVersionId = delivery.VersionId;
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
                // Only while the device still follows that bundle (after leaving a group it has its own copy).
                var config = await db.DeviceConfigs.FirstOrDefaultAsync(c => c.DeviceId == delivery.DeviceId && c.BundleId == delivery.TargetId, ct);
                if (config is null) return;
                if (!lateAck || config.AppliedVersionId is null)
                {
                    config.AppliedVersionId = delivery.VersionId;
                }
                else if (config.AppliedVersionId != delivery.VersionId)
                {
                    // Never backwards on a late ack: compare Seq within the bundle; an applied version from another
                    // bundle (the device's solo history before joining) is older than anything in this one.
                    var seqs = await db.ConfigVersions
                        .Where(v => v.BundleId == delivery.TargetId && (v.Id == config.AppliedVersionId || v.Id == delivery.VersionId))
                        .ToDictionaryAsync(v => v.Id, v => v.Seq, ct);
                    if (!seqs.TryGetValue(config.AppliedVersionId.Value, out var appliedSeq)
                        || (seqs.TryGetValue(delivery.VersionId, out var deliveredSeq) && deliveredSeq > appliedSeq))
                        config.AppliedVersionId = delivery.VersionId;
                }
                break;
            case DeliveryKind.Vault:
                if (!await db.VaultItems.AnyAsync(i => i.Id == delivery.TargetId, ct)) return;
                var held = await db.DeviceVaultStates.FirstOrDefaultAsync(s => s.DeviceId == delivery.DeviceId && s.ItemId == delivery.TargetId, ct);
                if (held is null)
                {
                    db.DeviceVaultStates.Add(new DeviceVaultState { DeviceId = delivery.DeviceId, ItemId = delivery.TargetId, VersionId = delivery.VersionId });
                }
                else if (!lateAck)
                {
                    held.VersionId = delivery.VersionId;
                }
                else if (held.VersionId != delivery.VersionId)
                {
                    // A late ack of a superseded delivery must not move the device back to an older version.
                    var seqs = await db.VaultVersions.Where(v => v.Id == held.VersionId || v.Id == delivery.VersionId)
                        .ToDictionaryAsync(v => v.Id, v => v.Seq, ct);
                    if (!seqs.TryGetValue(held.VersionId, out var heldSeq)
                        || (seqs.TryGetValue(delivery.VersionId, out var sentSeq) && sentSeq > heldSeq))
                        held.VersionId = delivery.VersionId;
                }
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
        var pending = await scope.Where(d => d.AckedUtc == null).OrderBy(d => d.CreatedUtc).ThenBy(d => d.Id).ToListAsync(ct);
        var backingOff = await BackingOffAsync(scope, now, ct);

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

    /// <summary>Device+target pairs whose latest real ack (server-side supersedes don't count) was a recent Failed
    /// or SkippedLocalChanged.</summary>
    private static async Task<HashSet<(Guid DeviceId, Guid TargetId)>> BackingOffAsync(IQueryable<DeliveryRow> scope,
        DateTimeOffset now, CancellationToken ct)
    {
        var cutoff = now - (FailedBackoff > SkippedBackoff ? FailedBackoff : SkippedBackoff);
        var recentAcks = await scope.Where(d => !d.Superseded && d.AckedUtc != null && d.AckedUtc > cutoff)
            .Select(d => new { d.DeviceId, d.TargetId, AckedUtc = d.AckedUtc!.Value, d.Result }).ToListAsync(ct);
        return recentAcks
            .GroupBy(a => (a.DeviceId, a.TargetId))
            .Where(g => g.MaxBy(a => a.AckedUtc) is { } last && last.Result switch
            {
                DeliveryResult.Failed => last.AckedUtc > now - FailedBackoff,
                DeliveryResult.SkippedLocalChanged => last.AckedUtc > now - SkippedBackoff,
                _ => false,
            })
            .Select(g => g.Key).ToHashSet();
    }

    /// <summary>
    /// Reconciles Config deliveries of the user (optionally one device). A device gets its bundle's head when it is
    /// not revoked, the bundle has a head and no open conflict, and the device has not applied the head. The files
    /// are expanded for the device when the delivery is created (stored in NotesJson). Caller holds the user lock.
    /// Returns the devices that got a new delivery.
    /// </summary>
    private async Task<List<Guid>> ReconcileConfigsAsync(Guid userId, Guid? deviceId, CancellationToken ct)
    {
        var devices = db.Devices.Where(d => d.UserId == userId && d.RevokedUtc == null);
        if (deviceId is Guid onlyDevice) devices = devices.Where(d => d.Id == onlyDevice);
        var wanted = await (
                from c in db.DeviceConfigs
                join d in devices on c.DeviceId equals d.Id
                join b in db.ConfigBundles on c.BundleId equals b.Id
                where b.UserId == userId && b.HeadVersionId != null && !b.OpenConflict
                      && (c.AppliedVersionId == null || c.AppliedVersionId != b.HeadVersionId)
                select new { c.DeviceId, BundleId = b.Id, Head = b.HeadVersionId!.Value, b.EmulatorId })
            .AsNoTracking().ToListAsync(ct);
        var wantedHead = wanted.ToDictionary(w => (w.DeviceId, w.BundleId));

        // Scoped by the user's devices (not bundles), so deliveries of a deleted group bundle are closed too.
        var userDevices = db.Devices.Where(d => d.UserId == userId).Select(d => d.Id);
        var scope = db.Deliveries.Where(d => d.Kind == DeliveryKind.Config && userDevices.Contains(d.DeviceId));
        if (deviceId is Guid deviceFilter) scope = scope.Where(d => d.DeviceId == deviceFilter);

        var now = time.GetUtcNow();
        var pending = await scope.AsNoTracking().Where(d => d.AckedUtc == null)
            .OrderBy(d => d.CreatedUtc).ThenBy(d => d.Id).ToListAsync(ct);
        var backingOff = await BackingOffAsync(scope, now, ct);

        var covered = new HashSet<(Guid, Guid)>();
        var stale = new List<Guid>();
        foreach (var d in pending)
        {
            var key = (d.DeviceId, d.TargetId);
            if (wantedHead.TryGetValue(key, out var w) && d.VersionId == w.Head && covered.Add(key)) continue;
            stale.Add(d.Id);
        }

        // Expand before any tracked change: storing expanded blobs saves.
        var created = new List<DeliveryRow>();
        foreach (var w in wanted)
        {
            if (covered.Contains((w.DeviceId, w.BundleId)) || backingOff.Contains((w.DeviceId, w.BundleId))) continue;
            var manifestJson = await db.ConfigVersions.AsNoTracking().Where(v => v.Id == w.Head).Select(v => v.ManifestJson).SingleAsync(ct);
            var (manifest, missing) = await configs.ExpandAsync(userId, w.DeviceId, w.EmulatorId, Manifests.FromJson(manifestJson), ct);
            created.Add(new DeliveryRow
            {
                Id = Guid.NewGuid(), DeviceId = w.DeviceId, Kind = DeliveryKind.Config, TargetId = w.BundleId, VersionId = w.Head,
                CreatedUtc = now, NotesJson = JsonSerializer.Serialize(new ConfigNotes(manifest, missing), NotesJson),
            });
        }

        if (stale.Count > 0)
        {
            // Superseded, held by a conflict, membership changed, revoked, or already applied: soft-close, so a late
            // ack of it can still be honoured.
            foreach (var d in await db.Deliveries.Where(d => stale.Contains(d.Id)).ToListAsync(ct))
            {
                d.Superseded = true;
                d.AckedUtc = now;
                d.Result = null;
            }
        }
        db.Deliveries.AddRange(created);
        await db.SaveChangesAsync(ct);
        return created.Select(d => d.DeviceId).Distinct().ToList();
    }

    private void Notify(List<Guid> devices)
    {
        foreach (var device in devices)
            hub.NotifyDevice(device);
    }
}
