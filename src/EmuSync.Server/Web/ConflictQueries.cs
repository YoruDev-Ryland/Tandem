using EmuSync.Protocol;
using EmuSync.Server.Data;
using EmuSync.Server.Saves;
using Microsoft.EntityFrameworkCore;

namespace EmuSync.Server.Web;

/// <summary>The open save conflicts of a user, for the web app's conflict list.</summary>
public sealed class ConflictQueries(EmuSyncDbContext db)
{
    /// <summary>One row per save item with an open conflict version: FirstSync before Concurrent, then by game name.</summary>
    public async Task<ConflictRow[]> ListAsync(Guid userId, CancellationToken ct)
    {
        var userItems = db.SaveItems.Where(i => i.UserId == userId).Select(i => i.Id);
        var open = await db.SaveVersions.AsNoTracking()
            .Where(v => userItems.Contains(v.ItemId) && v.ConflictReason != null && !v.Resolved)
            .ToListAsync(ct);
        if (open.Count == 0) return [];

        var itemIds = open.Select(v => v.ItemId).Distinct().ToList();
        var items = await db.SaveItems.AsNoTracking().Where(i => itemIds.Contains(i.Id)).ToDictionaryAsync(i => i.Id, ct);
        var headIds = items.Values.Where(i => i.HeadVersionId != null).Select(i => i.HeadVersionId!.Value).ToList();
        var heads = await db.SaveVersions.AsNoTracking().Where(v => headIds.Contains(v.Id)).ToDictionaryAsync(v => v.Id, ct);
        var names = await db.Devices.AsNoTracking().Where(d => d.UserId == userId).ToDictionaryAsync(d => d.Id, d => d.Name, ct);

        ConflictSide Side(SaveVersion v, bool isHead) => new(v.Id, v.DeviceId,
            v.DeviceId is Guid d && names.TryGetValue(d, out var n) ? n : null, v.ReceivedUtc, v.TotalSize,
            Manifests.FromJson(v.ManifestJson).Count, v.ManifestHash, isHead);

        return open.GroupBy(v => v.ItemId).Select(g =>
            {
                var item = items[g.Key];
                var head = item.HeadVersionId is Guid h && heads.TryGetValue(h, out var hv) && g.All(v => v.Id != h) ? hv : null;
                var sides = (head is null ? [] : new[] { Side(head, true) })
                    .Concat(g.OrderBy(v => v.ReceivedUtc).ThenBy(v => v.Seq).Select(v => Side(v, v.Id == item.HeadVersionId)))
                    .ToArray();
                var reason = g.Any(v => v.ConflictReason == ConflictReason.Concurrent) ? ConflictReason.Concurrent : ConflictReason.FirstSync;
                return new ConflictRow(item.Id, item.EmulatorId, item.GameKey, item.DisplayName, item.Kind,
                    item.Slot == "" ? null : item.Slot, item.Core == "" ? null : item.Core, reason, sides);
            })
            .OrderBy(r => r.Reason != ConflictReason.FirstSync)
            .ThenBy(r => r.DisplayName ?? r.GameKey, StringComparer.OrdinalIgnoreCase)
            .ThenBy(r => r.EmulatorId, StringComparer.Ordinal)
            .ThenBy(r => r.ItemId)
            .ToArray();
    }
}
