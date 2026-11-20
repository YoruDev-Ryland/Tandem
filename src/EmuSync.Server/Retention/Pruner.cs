using System.Globalization;
using System.Text.Json;
using EmuSync.Protocol;
using EmuSync.Server.Blobs;
using EmuSync.Server.Data;
using EmuSync.Server.Deliveries;
using EmuSync.Server.Events;
using EmuSync.Server.Saves;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace EmuSync.Server.Retention;

public sealed record PruneResult(int Versions, int Blobs, long Bytes)
{
    public static readonly PruneResult None = new(0, 0, 0);
    public PruneResult Add(PruneResult o) => new(Versions + o.Versions, Blobs + o.Blobs, Bytes + o.Bytes);
}

/// <summary>
/// Retention and blob cleanup, per user, under that user's lock (every upload's read-decide-write holds it too).
/// <para>A version is kept when ANY of these holds: it is among the newest N by Seq of its item/bundle (N from
/// <see cref="RetentionSettings"/>); it is a head; it is pinned or has a variant label; it has a ConflictReason
/// (resolved or not: the record of a user decision, and what re-uploads are matched against); an un-acked,
/// non-superseded delivery points at it; or it is any device's known, applied or vault-held version. Bases of kept
/// versions are not kept for their own sake: BaseVersionId is never read back by the server (an upload only compares
/// it with the head), and keeping it transitively would keep every version.</para>
/// <para>Then blob GC: a blob is deleted only when no remaining version manifest (save, config, vault), raw config
/// upload, or expanded Config delivery manifest (un-acked, or created in the last 7 days) references it AND it was
/// last uploaded or confirmed present more than an hour ago (blobs are sent before their manifest). Version rows are
/// deleted first, then blob rows (committed), and only then the files; a failed file delete is logged and skipped.</para>
/// </summary>
public sealed class Pruner(IServiceScopeFactory scopes, UserLocks locks, IOptions<ServerOptions> options, TimeProvider time,
    ILogger<Pruner> logger) : BackgroundService
{
    public static readonly TimeSpan BlobGrace = TimeSpan.FromHours(1);
    public static readonly TimeSpan ExpandedConfigKeep = TimeSpan.FromDays(7);
    private const int Chunk = 500;
    private static readonly JsonSerializerOptions NotesJson = new(JsonSerializerDefaults.Web);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        if (!options.Value.EnablePruning) return;
        var interval = TimeSpan.FromMinutes(Math.Max(1, options.Value.PruneIntervalMinutes));
        while (!stoppingToken.IsCancellationRequested)
        {
            try { await RunOnceAsync(stoppingToken); }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { return; }
            catch (Exception ex) { logger.LogError(ex, "Pruning run failed"); }

            try { await Task.Delay(interval, time, stoppingToken); }
            catch (OperationCanceledException) { return; }
        }
    }

    /// <summary>Prunes every user once. Runs regardless of EnablePruning (that only gates the background loop).
    /// A failure for one user is logged and the others still run.</summary>
    public async Task<PruneResult> RunOnceAsync(CancellationToken ct)
    {
        List<Guid> users;
        using (var scope = scopes.CreateScope())
            users = await scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>().Users.AsNoTracking()
                .Select(u => u.Id).ToListAsync(ct);

        var total = PruneResult.None;
        foreach (var userId in users)
        {
            try { total = total.Add(await PruneUserAsync(userId, ct)); }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception ex) { logger.LogError(ex, "Pruning failed for user {UserId}", userId); }
        }
        return total;
    }

    public async Task<PruneResult> PruneUserAsync(Guid userId, CancellationToken ct)
    {
        using var scope = scopes.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>();
        var store = scope.ServiceProvider.GetRequiredService<IBlobStore>();

        PruneResult result;
        using (await locks.Acquire(userId, ct))
        {
            var settings = await RetentionSettings.LoadAsync(db, userId, ct);
            var versions = await PruneVersionsAsync(db, userId, settings, ct);
            var (blobs, bytes) = await CollectBlobsAsync(db, store, userId, ct);
            result = new PruneResult(versions, blobs, bytes);
        }

        if (result != PruneResult.None)
        {
            logger.LogInformation("Pruned {Versions} versions and {Blobs} blobs ({Bytes} bytes) for user {UserId}",
                result.Versions, result.Blobs, result.Bytes, userId);
            await scope.ServiceProvider.GetRequiredService<IActivityLog>().WriteAsync(userId, "pruned", Message(result),
                data: new { versions = result.Versions, blobs = result.Blobs, bytes = result.Bytes });
        }
        return result;
    }

    private static string Message(PruneResult r) =>
        string.Create(CultureInfo.InvariantCulture,
            $"Pruned {r.Versions} old version{(r.Versions == 1 ? "" : "s")}, freed {r.Bytes / (1024.0 * 1024.0):0.0} MB");

    private sealed record Candidate(Guid Id, Guid OwnerId, int Seq, bool Flagged);

    /// <summary>Deletes the versions no keep rule covers. Caller holds the user's lock.</summary>
    private static async Task<int> PruneVersionsAsync(EmuSyncDbContext db, Guid userId, RetentionSettingsDto settings, CancellationToken ct)
    {
        var saveItems = db.SaveItems.Where(i => i.UserId == userId);
        var bundles = db.ConfigBundles.Where(b => b.UserId == userId);
        var vaultItems = db.VaultItems.Where(i => i.UserId == userId);
        var devices = db.Devices.Where(d => d.UserId == userId).Select(d => d.Id);

        // Version ids are Guids, unique across the three tables, so one protected set serves all of them.
        var protectedIds = new HashSet<Guid>();
        protectedIds.UnionWith(await saveItems.Where(i => i.HeadVersionId != null).Select(i => i.HeadVersionId!.Value).ToListAsync(ct));
        protectedIds.UnionWith(await bundles.Where(b => b.HeadVersionId != null).Select(b => b.HeadVersionId!.Value).ToListAsync(ct));
        protectedIds.UnionWith(await vaultItems.Where(i => i.HeadVersionId != null).Select(i => i.HeadVersionId!.Value).ToListAsync(ct));
        protectedIds.UnionWith(await db.DeviceItemStates.Where(s => saveItems.Any(i => i.Id == s.ItemId)).Select(s => s.KnownVersionId).ToListAsync(ct));
        protectedIds.UnionWith(await db.DeviceConfigs.Where(c => devices.Contains(c.DeviceId) && c.AppliedVersionId != null)
            .Select(c => c.AppliedVersionId!.Value).ToListAsync(ct));
        protectedIds.UnionWith(await db.DeviceVaultStates.Where(s => devices.Contains(s.DeviceId)).Select(s => s.VersionId).ToListAsync(ct));
        protectedIds.UnionWith(await db.Deliveries.Where(d => devices.Contains(d.DeviceId) && d.AckedUtc == null && !d.Superseded)
            .Select(d => d.VersionId).ToListAsync(ct));

        var saveDoomed = Doomed(await db.SaveVersions.AsNoTracking().Where(v => saveItems.Any(i => i.Id == v.ItemId))
            .Select(v => new Candidate(v.Id, v.ItemId, v.Seq, v.Pinned || v.Variant != null || v.ConflictReason != null))
            .ToListAsync(ct), settings.SaveVersions, protectedIds);
        var configDoomed = Doomed(await db.ConfigVersions.AsNoTracking().Where(v => bundles.Any(b => b.Id == v.BundleId))
            .Select(v => new Candidate(v.Id, v.BundleId, v.Seq, v.Pinned || v.ConflictReason != null))
            .ToListAsync(ct), settings.ConfigVersions, protectedIds);
        var vaultDoomed = Doomed(await db.VaultVersions.AsNoTracking().Where(v => vaultItems.Any(i => i.Id == v.ItemId))
            .Select(v => new Candidate(v.Id, v.ItemId, v.Seq, false))
            .ToListAsync(ct), settings.VaultVersions, protectedIds);

        return await DeleteVersionsAsync(db, userId, saveDoomed, configDoomed, vaultDoomed, ct);
    }

    /// <summary>Deletes the given versions of the user in one transaction and returns how many went. Defence in depth:
    /// the predicate re-checks the flags, so a version pinned, labelled or turned into a conflict after candidate
    /// selection is never deleted.</summary>
    internal static async Task<int> DeleteVersionsAsync(EmuSyncDbContext db, Guid userId, IReadOnlyList<Guid> saves,
        IReadOnlyList<Guid> configs, IReadOnlyList<Guid> vaults, CancellationToken ct)
    {
        if (saves.Count + configs.Count + vaults.Count == 0) return 0;
        var saveItems = db.SaveItems.Where(i => i.UserId == userId);
        var bundles = db.ConfigBundles.Where(b => b.UserId == userId);
        var vaultItems = db.VaultItems.Where(i => i.UserId == userId);
        var count = 0;
        await using var tx = await db.Database.BeginTransactionAsync(ct);
        foreach (var chunk in saves.Chunk(Chunk))
            count += await db.SaveVersions.Where(v => chunk.Contains(v.Id) && saveItems.Any(i => i.Id == v.ItemId)
                                                      && !v.Pinned && v.Variant == null && v.ConflictReason == null)
                .ExecuteDeleteAsync(ct);
        foreach (var chunk in configs.Chunk(Chunk))
            count += await db.ConfigVersions.Where(v => chunk.Contains(v.Id) && bundles.Any(b => b.Id == v.BundleId)
                                                        && !v.Pinned && v.ConflictReason == null)
                .ExecuteDeleteAsync(ct);
        foreach (var chunk in vaults.Chunk(Chunk))
            count += await db.VaultVersions.Where(v => chunk.Contains(v.Id) && vaultItems.Any(i => i.Id == v.ItemId)).ExecuteDeleteAsync(ct);
        await tx.CommitAsync(ct);
        return count;
    }

    /// <summary>Per owner: everything past the newest <paramref name="keep"/> by Seq that is neither flagged nor protected.</summary>
    private static List<Guid> Doomed(List<Candidate> rows, int keep, HashSet<Guid> protectedIds) =>
        rows.GroupBy(r => r.OwnerId)
            .SelectMany(g => g.OrderByDescending(r => r.Seq).Skip(Math.Max(1, keep)))
            .Where(r => !r.Flagged && !protectedIds.Contains(r.Id))
            .Select(r => r.Id).ToList();

    /// <summary>Deletes unreferenced blobs past the grace period: rows (committed) first, then files. Caller holds the
    /// user's lock. Any manifest that cannot be read aborts the GC for this user (throws) rather than under-count.</summary>
    private async Task<(int Blobs, long Bytes)> CollectBlobsAsync(EmuSyncDbContext db, IBlobStore store, Guid userId, CancellationToken ct)
    {
        var cutoff = time.GetUtcNow() - BlobGrace;
        var candidates = await db.UserBlobs.AsNoTracking().Where(b => b.UserId == userId && b.CreatedUtc < cutoff)
            .Select(b => new { b.Hash, b.Size }).ToListAsync(ct);
        if (candidates.Count == 0) return (0, 0);

        var referenced = await ReferencedAsync(db, userId, ct);
        var doomed = candidates.Where(b => !referenced.Contains(b.Hash)).ToList();
        if (doomed.Count == 0) return (0, 0);

        await using (var tx = await db.Database.BeginTransactionAsync(ct))
        {
            foreach (var chunk in doomed.Select(b => b.Hash).Chunk(Chunk))
                await db.UserBlobs.Where(b => b.UserId == userId && chunk.Contains(b.Hash) && b.CreatedUtc < cutoff)
                    .ExecuteDeleteAsync(ct);
            await tx.CommitAsync(ct);
        }

        // A file goes only when its row is gone for good (the delete re-checked the cutoff, so a row recorded again
        // in between survives, and so does its file).
        var survivors = new HashSet<string>(StringComparer.Ordinal);
        foreach (var chunk in doomed.Select(b => b.Hash).Chunk(Chunk))
            survivors.UnionWith(await db.UserBlobs.AsNoTracking().Where(b => b.UserId == userId && chunk.Contains(b.Hash))
                .Select(b => b.Hash).ToListAsync(ct));
        var gone = doomed.Where(b => !survivors.Contains(b.Hash)).ToList();

        // Still under the lock, so no upload confirms one of these hashes between the row and the file delete.
        foreach (var b in gone)
        {
            try { await store.DeleteAsync(userId, b.Hash, ct); }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception ex) { logger.LogWarning(ex, "Could not delete blob file {Hash} of user {UserId}", b.Hash, userId); }
        }
        return (gone.Count, gone.Sum(b => b.Size));
    }

    private async Task<HashSet<string>> ReferencedAsync(EmuSyncDbContext db, Guid userId, CancellationToken ct)
    {
        var referenced = new HashSet<string>(StringComparer.Ordinal);
        void Add(IEnumerable<ManifestEntry>? entries)
        {
            if (entries is null) return;
            foreach (var e in entries) referenced.Add(e.Hash);
        }

        var devices = db.Devices.Where(d => d.UserId == userId).Select(d => d.Id);
        await foreach (var json in db.SaveVersions.AsNoTracking()
                           .Where(v => db.SaveItems.Any(i => i.Id == v.ItemId && i.UserId == userId)).Select(v => v.ManifestJson)
                           .AsAsyncEnumerable().WithCancellation(ct))
            Add(Manifests.FromJson(json));
        await foreach (var json in db.ConfigVersions.AsNoTracking()
                           .Where(v => db.ConfigBundles.Any(b => b.Id == v.BundleId && b.UserId == userId)).Select(v => v.ManifestJson)
                           .AsAsyncEnumerable().WithCancellation(ct))
            Add(Manifests.FromJson(json));
        await foreach (var json in db.VaultVersions.AsNoTracking()
                           .Where(v => db.VaultItems.Any(i => i.Id == v.ItemId && i.UserId == userId)).Select(v => v.ManifestJson)
                           .AsAsyncEnumerable().WithCancellation(ct))
            Add(Manifests.FromJson(json));
        await foreach (var json in db.RawConfigUploads.AsNoTracking().Where(r => devices.Contains(r.DeviceId)).Select(r => r.ManifestJson)
                           .AsAsyncEnumerable().WithCancellation(ct))
            Add(Manifests.FromJson(json));

        // Expanded Config deliveries: what a device is (or was recently) told to write, which may be blobs no version holds.
        var keepAfter = time.GetUtcNow() - ExpandedConfigKeep;
        await foreach (var notes in db.Deliveries.AsNoTracking()
                           .Where(d => d.Kind == DeliveryKind.Config && devices.Contains(d.DeviceId) && d.NotesJson != null
                                       && (d.AckedUtc == null || d.CreatedUtc > keepAfter))
                           .Select(d => d.NotesJson!).AsAsyncEnumerable().WithCancellation(ct))
            Add(JsonSerializer.Deserialize<DeliveryService.ConfigNotes>(notes, NotesJson)?.Manifest);
        return referenced;
    }
}
