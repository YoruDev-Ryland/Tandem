using System.Globalization;
using EmuSync.Protocol;
using EmuSync.Server.Blobs;
using EmuSync.Server.Data;
using EmuSync.Server.Events;
using EmuSync.Server.Http;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;

namespace EmuSync.Server.Saves;

/// <summary>
/// Save/state versioning. Ordering never uses device clocks: an upload names the version it was based on and the
/// server decides head vs conflict from that alone. A conflict version is never head until resolved.
/// </summary>
public sealed class SaveService(EmuSyncDbContext db, BlobService blobs, UserLocks locks, IDomainEvents events, TimeProvider time,
    IActivityLog activity)
{
    public const int MaxGameKey = 300;
    public const int MaxSlot = 100;
    public const int MaxCore = 100;
    public const int MaxDisplayName = 300;
    public const int MaxBulkItems = 10000;

    public async Task<SaveUploadResponse> UploadAsync(Guid userId, Guid? deviceId, SaveUploadRequest request, CancellationToken ct)
    {
        ValidateIdentity(request);
        var manifest = Manifests.Normalize(request.Manifest);
        await blobs.RequireAllAsync(userId, manifest.Select(e => e.Hash), ct);
        await blobs.RequireSizesAsync(userId, manifest, ct);
        var manifestHash = Manifests.Hash(manifest);
        var slot = request.Slot ?? "";
        var core = request.Core ?? "";
        var itemCore = request.Kind == ItemKind.Save ? "" : core;

        SaveUploadResponse response;
        object? evt = null;
        string label = request.GameKey;
        using (await locks.Acquire(userId, ct))
        {
            var itemId = await FindOrCreateItemAsync(userId, request.EmulatorId, request.Kind, request.GameKey, slot, itemCore, ct);
            await using var tx = await BeginAsync(ct);
            var item = await LoadItemForUpdateAsync(userId, itemId, ct)
                ?? throw new InvalidOperationException("Save item vanished while locked.");
            if (request.DisplayName is not null) item.DisplayName = request.DisplayName;
            label = item.DisplayName ?? item.GameKey;

            var head = item.HeadVersionId is Guid headId
                ? await db.SaveVersions.SingleAsync(v => v.Id == headId, ct)
                : null;

            if (head is not null && head.ManifestHash == manifestHash)
            {
                if (deviceId is Guid d1) await SetKnownAsync(d1, item.Id, head.Id, ct);
                response = new SaveUploadResponse(SaveUploadStatus.Unchanged, item.Id, head.Id, head.Id);
            }
            else if (head is not null && request.BaseVersionId != head.Id && await db.SaveVersions
                         .Where(v => v.ItemId == item.Id && v.DeviceId == deviceId && v.ConflictReason != null && !v.Resolved
                                     && v.ManifestHash == manifestHash)
                         .OrderBy(v => v.Seq).Select(v => (Guid?)v.Id).FirstOrDefaultAsync(ct) is Guid existingConflict)
            {
                // A retry of an upload that already opened a conflict: same answer, no duplicate version. (An upload
                // based on the head is never a retry of a conflict and goes through rule 5 instead.)
                response = new SaveUploadResponse(SaveUploadStatus.Conflict, item.Id, existingConflict, head.Id);
            }
            else
            {
                var seq = (await db.SaveVersions.Where(v => v.ItemId == item.Id).MaxAsync(v => (int?)v.Seq, ct) ?? 0) + 1;
                var version = new SaveVersion
                {
                    Id = Guid.NewGuid(), ItemId = item.Id, Seq = seq, BaseVersionId = request.BaseVersionId,
                    DeviceId = deviceId, ReceivedUtc = time.GetUtcNow(), ManifestJson = Manifests.ToJson(manifest),
                    ManifestHash = manifestHash, TotalSize = manifest.Sum(e => e.Size), Core = core,
                };
                db.SaveVersions.Add(version);

                if (head is null || request.BaseVersionId == head.Id)
                {
                    item.HeadVersionId = version.Id;
                    if (deviceId is Guid d2) await SetKnownAsync(d2, item.Id, version.Id, ct);
                    response = new SaveUploadResponse(SaveUploadStatus.Accepted, item.Id, version.Id, version.Id);
                    evt = new SaveHeadChanged(userId, item.Id, version.Id);
                }
                else
                {
                    var reason = request.BaseVersionId is null ? ConflictReason.FirstSync : ConflictReason.Concurrent;
                    version.ConflictReason = reason;
                    item.OpenConflict = true;
                    response = new SaveUploadResponse(SaveUploadStatus.Conflict, item.Id, version.Id, head.Id);
                    evt = new ConflictOpened(userId, item.Id, version.Id, reason);
                }
            }

            await db.SaveChangesAsync(ct);
            if (tx is not null) await tx.CommitAsync(ct);
        }

        if (evt is not null) await events.Raise(evt, ct);
        if (response.Status != SaveUploadStatus.Unchanged)
        {
            var device = deviceId is Guid dn ? await DeviceNameAsync(dn, ct) : "The web app";
            var what = request.Kind == ItemKind.Save ? "save" : "state";
            if (response.Status == SaveUploadStatus.Accepted)
                await activity.WriteAsync(userId, "save_uploaded", $"{device} uploaded {what} for {label}", deviceId, request.EmulatorId, request.GameKey);
            else
                await activity.WriteAsync(userId, "conflict_opened", $"{device} uploaded {what} for {label}, which conflicts", deviceId, request.EmulatorId, request.GameKey,
                    new { itemId = response.ItemId, versionId = response.VersionId });
        }
        return response;
    }

    /// <summary>The identity-level lookup the web upload needs: the item's head and whether it has an open conflict.</summary>
    public async Task<(Guid? HeadVersionId, bool OpenConflict)> FindHeadAsync(Guid userId, string emulatorId, ItemKind kind,
        string gameKey, string slot, string core, CancellationToken ct)
    {
        var itemCore = kind == ItemKind.Save ? "" : core;
        var item = await db.SaveItems.AsNoTracking().FirstOrDefaultAsync(i => i.UserId == userId && i.EmulatorId == emulatorId
            && i.Kind == kind && i.GameKey == gameKey && i.Slot == slot && i.Core == itemCore, ct);
        return item is null ? (null, false) : (item.HeadVersionId, item.OpenConflict);
    }

    private async Task<string> DeviceNameAsync(Guid deviceId, CancellationToken ct) =>
        await db.Devices.AsNoTracking().Where(d => d.Id == deviceId).Select(d => d.Name).FirstOrDefaultAsync(ct) ?? "A device";

    public async Task ResolveAsync(Guid userId, Guid itemId, ResolveConflictRequest request, CancellationToken ct)
    {
        if (!Enum.IsDefined(request.Choice))
            throw new ApiException(400, "bad_request", "Unknown choice.");

        SaveHeadChanged evt;
        string emulatorId, gameKey, label;
        using (await locks.Acquire(userId, ct))
        {
            await using var tx = await BeginAsync(ct);
            var item = await LoadItemForUpdateAsync(userId, itemId, ct)
                ?? throw new ApiException(404, "item_not_found", "Save item not found.");
            var open = await OpenConflicts(item.Id).ToListAsync(ct);
            if (open.Count == 0)
                throw new ApiException(409, "no_conflict", "This item has no open conflict.");
            var keep = request.KeepVersionId is Guid keepId
                ? await db.SaveVersions.FirstOrDefaultAsync(v => v.Id == keepId && v.ItemId == item.Id, ct)
                : null;
            if (keep is null)
                throw new ApiException(400, "bad_version", "KeepVersionId must be a version of this item.");

            if (request.Choice == ResolveChoice.KeepBoth)
            {
                // Everything that does not end up as head stays as a pinned, labelled variant: the other open
                // conflict versions and, if a conflict version wins, the head it displaces.
                var losers = open.Where(v => v.Id != keep.Id).ToList();
                if (item.HeadVersionId is Guid oldHead && oldHead != keep.Id && losers.All(v => v.Id != oldHead))
                    losers.Add(await db.SaveVersions.SingleAsync(v => v.Id == oldHead, ct));
                await LabelVariantsAsync(losers, ct);
            }

            foreach (var v in open) v.Resolved = true;
            item.HeadVersionId = keep.Id;
            item.OpenConflict = false;
            // The winning device already holds the head; every other device keeps its old known version and so is
            // sent the head by the delivery recompute.
            if (open.Any(v => v.Id == keep.Id)) await SetKnownForUploaderAsync(keep, ct);
            await db.SaveChangesAsync(ct);
            if (tx is not null) await tx.CommitAsync(ct);
            evt = new SaveHeadChanged(userId, item.Id, keep.Id);
            (emulatorId, gameKey, label) = (item.EmulatorId, item.GameKey, item.DisplayName ?? item.GameKey);
        }

        await events.Raise(evt, ct);
        await activity.WriteAsync(userId, "conflict_resolved", $"Conflict resolved for {label}", null, emulatorId, gameKey);
    }

    public async Task ResolveFirstSyncAsync(Guid userId, BulkFirstSyncRequest request, CancellationToken ct)
    {
        if (!Enum.IsDefined(request.Choice))
            throw new ApiException(400, "bad_request", "Unknown choice.");
        if (request.ItemIds is { Count: > MaxBulkItems })
            throw new ApiException(400, "too_many", $"At most {MaxBulkItems} items per request.");
        if (!await db.Devices.AnyAsync(d => d.Id == request.DeviceId && d.UserId == userId, ct))
            throw new ApiException(404, "device_not_found", "Device not found.");

        var raised = new List<SaveHeadChanged>();
        var resolved = new List<Guid>();
        using (await locks.Acquire(userId, ct))
        {
            await using var tx = await BeginAsync(ct);
            var deviceId = request.DeviceId;
            var query = db.SaveVersions.Where(v => v.DeviceId == deviceId && v.ConflictReason == ConflictReason.FirstSync && !v.Resolved
                                                   && db.SaveItems.Any(i => i.Id == v.ItemId && i.UserId == userId));
            if (request.ItemIds is { } only)
            {
                var filter = only.Distinct().ToList();
                query = query.Where(v => filter.Contains(v.ItemId));
            }
            var itemIds = (await query.Select(v => v.ItemId).Distinct().ToListAsync(ct)).Order().ToList();

            foreach (var itemId in itemIds) // ordered by id so row locks are always taken in the same order
            {
                var item = await LoadItemForUpdateAsync(userId, itemId, ct)
                    ?? throw new InvalidOperationException("Save item vanished while locked.");
                var open = await OpenConflicts(item.Id).OrderBy(v => v.Seq).ToListAsync(ct);
                var mine = open.Where(v => v.DeviceId == deviceId && v.ConflictReason == ConflictReason.FirstSync).ToList();
                if (mine.Count == 0) continue;

                if (request.Choice == FirstSyncChoice.UseDevice)
                {
                    // The displaced server head stays as a pinned, labelled variant so the choice is reversible.
                    if (item.HeadVersionId is Guid oldHead && mine.All(v => v.Id != oldHead))
                        await LabelVariantsAsync([await db.SaveVersions.SingleAsync(v => v.Id == oldHead, ct)], ct);
                    item.HeadVersionId = mine[^1].Id; // the device's most recent upload
                    await SetKnownForUploaderAsync(mine[^1], ct);
                }
                foreach (var v in mine) v.Resolved = true;
                item.OpenConflict = open.Count > mine.Count;
                raised.Add(new SaveHeadChanged(userId, item.Id, item.HeadVersionId!.Value));
                resolved.Add(item.Id);
            }

            await db.SaveChangesAsync(ct);
            if (tx is not null) await tx.CommitAsync(ct);
        }

        foreach (var evt in raised) await events.Raise(evt, ct);
        if (resolved.Count > 0)
        {
            var device = await DeviceNameAsync(request.DeviceId, ct);
            var whose = request.Choice == FirstSyncChoice.UseDevice ? "device's" : "server's";
            var games = resolved.Count == 1 ? "1 game" : $"{resolved.Count} games";
            await activity.WriteAsync(userId, "conflict_resolved", $"{device} first-sync: kept {whose} saves for {games}",
                request.DeviceId, data: new { itemIds = resolved });
        }
    }

    /// <summary>
    /// Rollback: makes a copy of an earlier version the new head (Seq+1, based on the current head, no device). The
    /// displaced head is kept as a pinned, labelled variant. Delivery then sends the copy to every device with the game.
    /// </summary>
    public async Task<RestoreResponse> RestoreAsync(Guid userId, Guid itemId, Guid versionId, RestoreRequest request, CancellationToken ct)
    {
        if (request is null || !await db.Devices.AnyAsync(d => d.Id == request.DeviceId && d.UserId == userId, ct))
            throw new ApiException(404, "device_not_found", "Device not found.");

        RestoreResponse response;
        SaveHeadChanged? evt = null;
        string emulatorId, gameKey, label;
        using (await locks.Acquire(userId, ct))
        {
            await using var tx = await BeginAsync(ct);
            var item = await LoadItemForUpdateAsync(userId, itemId, ct)
                ?? throw new ApiException(404, "version_not_found", "Version not found.");
            var chosen = await db.SaveVersions.FirstOrDefaultAsync(v => v.Id == versionId && v.ItemId == item.Id, ct)
                ?? throw new ApiException(404, "version_not_found", "Version not found.");
            if (item.OpenConflict)
                throw new ApiException(409, "conflict_open", "Resolve the open conflict before restoring.");

            var head = item.HeadVersionId is Guid headId
                ? await db.SaveVersions.SingleAsync(v => v.Id == headId, ct)
                : null;
            if (head is not null && head.ManifestHash == chosen.ManifestHash)
            {
                response = new RestoreResponse(item.Id, head.Id); // the head already has this content
            }
            else
            {
                var seq = (await db.SaveVersions.Where(v => v.ItemId == item.Id).MaxAsync(v => (int?)v.Seq, ct) ?? 0) + 1;
                var copy = new SaveVersion
                {
                    Id = Guid.NewGuid(), ItemId = item.Id, Seq = seq, BaseVersionId = head?.Id, DeviceId = null,
                    ReceivedUtc = time.GetUtcNow(), ManifestJson = chosen.ManifestJson, ManifestHash = chosen.ManifestHash,
                    TotalSize = chosen.TotalSize, Core = chosen.Core,
                };
                db.SaveVersions.Add(copy);
                if (head is { Pinned: false }) await LabelVariantsAsync([head], ct);
                item.HeadVersionId = copy.Id;
                response = new RestoreResponse(item.Id, copy.Id);
                evt = new SaveHeadChanged(userId, item.Id, copy.Id);
            }
            (emulatorId, gameKey, label) = (item.EmulatorId, item.GameKey, item.DisplayName ?? item.GameKey);

            await db.SaveChangesAsync(ct);
            if (tx is not null) await tx.CommitAsync(ct);
        }

        if (evt is not null)
        {
            await events.Raise(evt, ct);
            await activity.WriteAsync(userId, "save_uploaded", $"Restored an earlier version of {label}", request.DeviceId, emulatorId, gameKey);
        }
        return response;
    }

    public async Task SetPinnedAsync(Guid userId, Guid itemId, Guid versionId, bool pinned, CancellationToken ct)
    {
        var updated = await db.SaveVersions
            .Where(v => v.Id == versionId && v.ItemId == itemId && db.SaveItems.Any(i => i.Id == itemId && i.UserId == userId))
            .ExecuteUpdateAsync(s => s.SetProperty(v => v.Pinned, pinned), ct);
        if (updated == 0) throw new ApiException(404, "version_not_found", "Version not found.");
    }

    private IQueryable<SaveVersion> OpenConflicts(Guid itemId) =>
        db.SaveVersions.Where(v => v.ItemId == itemId && v.ConflictReason != null && !v.Resolved);

    private async Task LabelVariantsAsync(List<SaveVersion> versions, CancellationToken ct)
    {
        var deviceIds = versions.Where(v => v.DeviceId is not null).Select(v => v.DeviceId!.Value).Distinct().ToList();
        var names = await db.Devices.Where(d => deviceIds.Contains(d.Id)).ToDictionaryAsync(d => d.Id, d => d.Name, ct);
        foreach (var v in versions)
        {
            var name = v.DeviceId is Guid id && names.TryGetValue(id, out var n) ? n : "Unknown device";
            v.Variant ??= $"{name} {v.ReceivedUtc.UtcDateTime.ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture)}";
            v.Pinned = true;
        }
    }

    private static void ValidateIdentity(SaveUploadRequest r)
    {
        if (r.EmulatorId is null || !EmulatorIds.IsKnown(r.EmulatorId))
            throw new ApiException(400, "unknown_emulator", $"Unknown emulator '{r.EmulatorId}'.");
        if (!Enum.IsDefined(r.Kind))
            throw new ApiException(400, "bad_request", "Unknown kind.");
        if (string.IsNullOrEmpty(r.GameKey) || r.GameKey.Length > MaxGameKey)
            throw new ApiException(400, "bad_request", $"GameKey must be 1-{MaxGameKey} characters.");
        if (r.Slot is { Length: > MaxSlot })
            throw new ApiException(400, "bad_request", $"Slot must be at most {MaxSlot} characters.");
        if (r.Core is { Length: > MaxCore })
            throw new ApiException(400, "bad_request", $"Core must be at most {MaxCore} characters.");
        if (r.DisplayName is { Length: > MaxDisplayName })
            throw new ApiException(400, "bad_request", $"DisplayName must be at most {MaxDisplayName} characters.");
    }

    private async Task<Guid> FindOrCreateItemAsync(Guid userId, string emulatorId, ItemKind kind, string gameKey,
        string slot, string core, CancellationToken ct)
    {
        Task<Guid?> Find() => db.SaveItems
            .Where(i => i.UserId == userId && i.EmulatorId == emulatorId && i.Kind == kind && i.GameKey == gameKey
                        && i.Slot == slot && i.Core == core)
            .Select(i => (Guid?)i.Id).FirstOrDefaultAsync(ct);

        if (await Find() is Guid found) return found;
        var item = new SaveItem
        {
            Id = Guid.NewGuid(), UserId = userId, EmulatorId = emulatorId, Kind = kind, GameKey = gameKey, Slot = slot, Core = core,
        };
        db.SaveItems.Add(item);
        try
        {
            await db.SaveChangesAsync(ct);
            return item.Id;
        }
        catch (DbUpdateException)
        {
            // Unique-index race (another instance created it first): use theirs.
            db.ChangeTracker.Clear();
            if (await Find() is Guid raced) return raced;
            throw;
        }
        finally
        {
            db.ChangeTracker.Clear(); // the decide step reloads the item, under the row lock on Postgres
        }
    }

    private bool IsPostgres => db is PostgresEmuSyncDbContext;

    private async Task<IDbContextTransaction?> BeginAsync(CancellationToken ct) =>
        IsPostgres ? await db.Database.BeginTransactionAsync(ct) : null;

    /// <summary>Loads the user's item tracked; on Postgres also takes its row lock for the transaction.</summary>
    private async Task<SaveItem?> LoadItemForUpdateAsync(Guid userId, Guid itemId, CancellationToken ct)
    {
        if (!IsPostgres)
            return await db.SaveItems.FirstOrDefaultAsync(i => i.Id == itemId && i.UserId == userId, ct);
        // ToListAsync (not First/Single) so EF does not wrap the raw SQL in a subquery.
        var rows = await db.SaveItems
            .FromSql($"""SELECT * FROM "SaveItems" WHERE "Id" = {itemId} AND "UserId" = {userId} FOR UPDATE""")
            .ToListAsync(ct);
        return rows.SingleOrDefault();
    }

    /// <summary>
    /// After a resolve, the device that uploaded the winning version already holds it, so it needs no delivery.
    /// Callers only pass a winner that is one of the open conflict versions being resolved (a winner picked from
    /// history says nothing about what its uploader holds now), and only when it is that device's latest upload.
    /// </summary>
    private async Task SetKnownForUploaderAsync(SaveVersion winner, CancellationToken ct)
    {
        if (winner.DeviceId is not Guid deviceId) return;
        var latest = await db.SaveVersions.Where(v => v.ItemId == winner.ItemId && v.DeviceId == deviceId)
            .OrderByDescending(v => v.Seq).Select(v => (Guid?)v.Id).FirstOrDefaultAsync(ct);
        if (latest == winner.Id) await SetKnownAsync(deviceId, winner.ItemId, winner.Id, ct);
    }

    private async Task SetKnownAsync(Guid deviceId, Guid itemId, Guid versionId, CancellationToken ct)
    {
        var state = await db.DeviceItemStates.FirstOrDefaultAsync(s => s.DeviceId == deviceId && s.ItemId == itemId, ct);
        if (state is null)
            db.DeviceItemStates.Add(new DeviceItemState { DeviceId = deviceId, ItemId = itemId, KnownVersionId = versionId });
        else
            state.KnownVersionId = versionId;
    }
}
