using EmuSync.Protocol;
using EmuSync.Server.Blobs;
using EmuSync.Server.Data;
using EmuSync.Server.Events;
using EmuSync.Server.Http;
using EmuSync.Server.Saves;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Storage;
using DeliveryRow = EmuSync.Server.Data.Delivery;

namespace EmuSync.Server.Vault;

/// <summary>
/// BIOS, keys, firmware and system files, stored once per user. An item is identified by category + emulator +
/// name; each changed upload is a new version and the newest is the head. Nothing here is synced continuously: a
/// device is given an item only by <see cref="SendAsync"/> (a Vault delivery of the head, which the delivery
/// reconciles for saves and configs never touch). Acking it Applied records the version the device holds
/// (<see cref="DeviceVaultState"/>), and a holder is current when that is the head.
/// </summary>
public sealed class VaultService(EmuSyncDbContext db, BlobService blobs, UserLocks locks, IEventHub hub, TimeProvider time,
    IActivityLog activity)
{
    public static readonly IReadOnlyList<string> Categories = ["bios", "keys", "firmware", "sysdata", "system"];
    public const int MaxName = 200;

    public async Task<VaultUploadResponse> UploadAsync(Guid userId, Guid? deviceId, VaultUploadRequest request, CancellationToken ct)
    {
        if (request is null) throw new ApiException(400, "bad_request", "A request body is required.");
        if (request.Category is null || !Categories.Contains(request.Category))
            throw new ApiException(400, "bad_category", $"Category must be one of {string.Join(", ", Categories)}.");
        RequireSafeName(request.Name);
        if (request.EmulatorId is not null && !EmulatorIds.IsKnown(request.EmulatorId))
            throw new ApiException(400, "unknown_emulator", $"Unknown emulator '{request.EmulatorId}'.");
        var manifest = Manifests.Normalize(request.Manifest);
        await blobs.RequireAllAsync(userId, manifest.Select(e => e.Hash), ct);
        await blobs.RequireSizesAsync(userId, manifest, ct);
        var hash = Manifests.Hash(manifest);

        VaultUploadResponse response;
        using (await locks.Acquire(userId, ct))
        {
            await using var tx = await BeginAsync(ct);
            var item = await FindItemForUpdateAsync(userId, request.Category, request.EmulatorId, request.Name, ct);
            var head = item?.HeadVersionId is Guid headId ? await db.VaultVersions.SingleAsync(v => v.Id == headId, ct) : null;
            if (head is not null && head.ManifestHash == hash)
            {
                response = new VaultUploadResponse(item!.Id, head.Id, true);
            }
            else
            {
                if (item is null)
                {
                    item = new VaultItem
                    {
                        Id = Guid.NewGuid(), UserId = userId, Category = request.Category, EmulatorId = request.EmulatorId,
                        Name = request.Name,
                    };
                    db.VaultItems.Add(item);
                }
                var seq = (await db.VaultVersions.Where(v => v.ItemId == item.Id).MaxAsync(v => (int?)v.Seq, ct) ?? 0) + 1;
                var version = new VaultVersion
                {
                    Id = Guid.NewGuid(), ItemId = item.Id, Seq = seq, DeviceId = deviceId, ReceivedUtc = time.GetUtcNow(),
                    ManifestJson = Manifests.ToJson(manifest), ManifestHash = hash, TotalSize = manifest.Sum(e => e.Size),
                };
                db.VaultVersions.Add(version);
                await db.SaveChangesAsync(ct); // the version row first: the head is set once it exists
                item.HeadVersionId = version.Id;
                response = new VaultUploadResponse(item.Id, version.Id, false);
            }
            await db.SaveChangesAsync(ct);
            if (tx is not null) await tx.CommitAsync(ct);
        }

        if (!response.Unchanged)
        {
            hub.NotifyUser(userId, "vault");
            var who = deviceId is Guid d
                ? await db.Devices.AsNoTracking().Where(x => x.Id == d).Select(x => x.Name).FirstOrDefaultAsync(ct) ?? "A device"
                : "The web app";
            await activity.WriteAsync(userId, "vault_uploaded", $"{who} uploaded {request.Name} to the vault", deviceId,
                request.EmulatorId, data: new { itemId = response.ItemId, versionId = response.VersionId, category = request.Category });
        }
        return response;
    }

    public async Task<List<VaultItemDto>> ListAsync(Guid userId, CancellationToken ct)
    {
        var items = await (
                from i in db.VaultItems.AsNoTracking()
                where i.UserId == userId && i.HeadVersionId != null
                join v in db.VaultVersions.AsNoTracking() on i.HeadVersionId equals v.Id
                orderby i.Category, i.Name, i.EmulatorId, i.Id
                select new { Item = i, v.TotalSize })
            .ToListAsync(ct);
        var ids = items.Select(i => i.Item.Id).ToList();
        var holders = await (
                from s in db.DeviceVaultStates.AsNoTracking()
                where ids.Contains(s.ItemId)
                join d in db.Devices.AsNoTracking() on s.DeviceId equals d.Id
                where d.UserId == userId && d.RevokedUtc == null
                orderby d.Name, d.Id
                select new { s.ItemId, s.DeviceId, d.Name, s.VersionId })
            .ToListAsync(ct);
        return items.Select(i => new VaultItemDto(i.Item.Id, i.Item.Category, i.Item.EmulatorId, i.Item.Name,
                i.Item.HeadVersionId!.Value, i.TotalSize,
                holders.Where(h => h.ItemId == i.Item.Id)
                    .Select(h => new VaultHolder(h.DeviceId, h.Name, h.VersionId == i.Item.HeadVersionId)).ToList()))
            .ToList();
    }

    /// <summary>Creates a Vault delivery of the item's head for the device, closing (Superseded) any earlier
    /// un-acked one for the same device and item.</summary>
    public async Task<SendVaultResponse> SendAsync(Guid userId, Guid itemId, SendVaultRequest request, CancellationToken ct)
    {
        if (request is null) throw new ApiException(400, "bad_request", "A device is required.");
        if (!await db.Devices.AnyAsync(d => d.Id == request.DeviceId && d.UserId == userId && d.RevokedUtc == null, ct))
            throw new ApiException(404, "device_not_found", "Device not found.");

        DeliveryRow delivery;
        using (await locks.Acquire(userId, ct))
        {
            var item = await db.VaultItems.AsNoTracking().FirstOrDefaultAsync(i => i.Id == itemId && i.UserId == userId, ct);
            if (item?.HeadVersionId is not Guid head)
                throw new ApiException(404, "vault_item_not_found", "Vault item not found.");
            var now = time.GetUtcNow();
            foreach (var old in await db.Deliveries.Where(d => d.Kind == DeliveryKind.Vault && d.TargetId == itemId
                                                              && d.DeviceId == request.DeviceId && d.AckedUtc == null).ToListAsync(ct))
            {
                old.Superseded = true;
                old.AckedUtc = now;
                old.Result = null;
            }
            delivery = new DeliveryRow
            {
                Id = Guid.NewGuid(), DeviceId = request.DeviceId, Kind = DeliveryKind.Vault, TargetId = itemId, VersionId = head,
                CreatedUtc = now,
            };
            db.Deliveries.Add(delivery);
            await db.SaveChangesAsync(ct);
        }

        hub.NotifyDevice(request.DeviceId);
        return new SendVaultResponse(delivery.Id, delivery.VersionId);
    }

    /// <summary>1-200 characters and usable as one path segment: no '/', '\', '..' or NUL.</summary>
    private static void RequireSafeName(string? name)
    {
        if (string.IsNullOrEmpty(name) || name.Length > MaxName)
            throw new ApiException(400, "bad_name", $"Name must be 1-{MaxName} characters.");
        if (name.Contains('/') || name.Contains('\\') || name.Contains("..", StringComparison.Ordinal) || name.Contains('\0') || name == ".")
            throw new ApiException(400, "bad_name", "Name must not contain '/', '\\', '..' or NUL.");
    }

    private bool IsPostgres => db is PostgresEmuSyncDbContext;

    private async Task<IDbContextTransaction?> BeginAsync(CancellationToken ct) =>
        IsPostgres ? await db.Database.BeginTransactionAsync(ct) : null;

    /// <summary>Loads the item tracked; on Postgres also takes its row lock for the transaction.</summary>
    private async Task<VaultItem?> FindItemForUpdateAsync(Guid userId, string category, string? emulatorId, string name, CancellationToken ct)
    {
        if (!IsPostgres)
            return await db.VaultItems.FirstOrDefaultAsync(i => i.UserId == userId && i.Category == category
                && i.EmulatorId == emulatorId && i.Name == name, ct);
        var rows = await db.VaultItems.FromSql($"""
            SELECT * FROM "VaultItems" WHERE "UserId" = {userId} AND "Category" = {category} AND "Name" = {name}
            AND "EmulatorId" IS NOT DISTINCT FROM {emulatorId} FOR UPDATE
            """).ToListAsync(ct);
        return rows.SingleOrDefault();
    }
}
