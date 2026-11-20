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
public sealed class SaveService(EmuSyncDbContext db, BlobService blobs, UserLocks locks, IDomainEvents events, TimeProvider time)
{
    public const int MaxGameKey = 300;
    public const int MaxSlot = 100;
    public const int MaxCore = 100;
    public const int MaxDisplayName = 300;
    public const int MaxBulkItems = 10000;

    public async Task<SaveUploadResponse> UploadAsync(Guid userId, Guid deviceId, SaveUploadRequest request, CancellationToken ct)
    {
        ValidateIdentity(request);
        var manifest = Manifests.Normalize(request.Manifest);
        await blobs.RequireAllAsync(userId, manifest.Select(e => e.Hash), ct);
        await RequireSizesAsync(userId, manifest, ct);
        var manifestHash = Manifests.Hash(manifest);
        var slot = request.Slot ?? "";
        var core = request.Core ?? "";
        var itemCore = request.Kind == ItemKind.Save ? "" : core;

        SaveUploadResponse response;
        object? evt = null;
        using (await locks.Acquire(userId, ct))
        {
            var itemId = await FindOrCreateItemAsync(userId, request.EmulatorId, request.Kind, request.GameKey, slot, itemCore, ct);
            await using var tx = await BeginAsync(ct);
            var item = await LoadItemForUpdateAsync(userId, itemId, ct)
                ?? throw new InvalidOperationException("Save item vanished while locked.");
            if (request.DisplayName is not null) item.DisplayName = request.DisplayName;

            var head = item.HeadVersionId is Guid headId
                ? await db.SaveVersions.SingleAsync(v => v.Id == headId, ct)
                : null;

            if (head is not null && head.ManifestHash == manifestHash)
            {
                await SetKnownAsync(deviceId, item.Id, head.Id, ct);
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
                    await SetKnownAsync(deviceId, item.Id, version.Id, ct);
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
        return response;
    }

    public async Task ResolveAsync(Guid userId, Guid itemId, ResolveConflictRequest request, CancellationToken ct)
    {
        if (!Enum.IsDefined(request.Choice))
            throw new ApiException(400, "bad_request", "Unknown choice.");

        SaveHeadChanged evt;
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
            await db.SaveChangesAsync(ct);
            if (tx is not null) await tx.CommitAsync(ct);
            evt = new SaveHeadChanged(userId, item.Id, keep.Id);
        }

        await events.Raise(evt, ct);
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
                }
                foreach (var v in mine) v.Resolved = true;
                item.OpenConflict = open.Count > mine.Count;
                raised.Add(new SaveHeadChanged(userId, item.Id, item.HeadVersionId!.Value));
            }

            await db.SaveChangesAsync(ct);
            if (tx is not null) await tx.CommitAsync(ct);
        }

        foreach (var evt in raised) await events.Raise(evt, ct);
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

    /// <summary>A manifest's sizes must match the stored blobs, or the same content would hash differently.</summary>
    private async Task RequireSizesAsync(Guid userId, List<ManifestEntry> manifest, CancellationToken ct)
    {
        var hashes = manifest.Select(e => e.Hash).Distinct().ToList();
        var sizes = await db.UserBlobs.Where(b => b.UserId == userId && hashes.Contains(b.Hash))
            .ToDictionaryAsync(b => b.Hash, b => b.Size, ct);
        foreach (var e in manifest)
        {
            if (!sizes.TryGetValue(e.Hash, out var size))
                throw new ApiException(400, "missing_blobs", "Missing blobs: " + e.Hash);
            if (size != e.Size)
                throw new ApiException(400, "bad_manifest", $"Size of '{e.Path}' does not match its blob.");
        }
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

    private async Task SetKnownAsync(Guid deviceId, Guid itemId, Guid versionId, CancellationToken ct)
    {
        var state = await db.DeviceItemStates.FirstOrDefaultAsync(s => s.DeviceId == deviceId && s.ItemId == itemId, ct);
        if (state is null)
            db.DeviceItemStates.Add(new DeviceItemState { DeviceId = deviceId, ItemId = itemId, KnownVersionId = versionId });
        else
            state.KnownVersionId = versionId;
    }
}
