using System.Globalization;
using EmuSync.Protocol;
using EmuSync.Server.Auth;
using EmuSync.Server.Data;
using EmuSync.Server.Http;
using EmuSync.Server.Saves;
using Microsoft.EntityFrameworkCore;

namespace EmuSync.Server.Retention;

/// <summary>Per-user retention counts, stored as <see cref="UserSetting"/> rows.</summary>
public static class RetentionSettings
{
    public const string SaveKey = "retention.saveVersions";
    public const string ConfigKey = "retention.configVersions";
    public const string VaultKey = "retention.vaultVersions";
    public const int Min = 1;
    public const int Max = 1000;

    public static readonly RetentionSettingsDto Defaults = new(30, 30, 5);

    public static async Task<RetentionSettingsDto> LoadAsync(EmuSyncDbContext db, Guid userId, CancellationToken ct)
    {
        var rows = await db.UserSettings.AsNoTracking()
            .Where(s => s.UserId == userId && (s.Key == SaveKey || s.Key == ConfigKey || s.Key == VaultKey))
            .ToDictionaryAsync(s => s.Key, s => s.Value, ct);
        // A missing or unreadable value means the default; never a smaller count than the user can have asked for.
        int Read(string key, int fallback) =>
            rows.TryGetValue(key, out var v) && int.TryParse(v, NumberStyles.None, CultureInfo.InvariantCulture, out var n)
                && n is >= Min and <= Max ? n : fallback;
        return new RetentionSettingsDto(Read(SaveKey, Defaults.SaveVersions), Read(ConfigKey, Defaults.ConfigVersions),
            Read(VaultKey, Defaults.VaultVersions));
    }

    /// <summary>Validates (400 bad_value) and stores. Caller holds the user's lock, so a prune never sees half of it.</summary>
    public static async Task SaveAsync(EmuSyncDbContext db, Guid userId, RetentionSettingsDto? value, CancellationToken ct)
    {
        if (value is null) throw new ApiException(400, "bad_request", "A request body is required.");
        foreach (var (name, n) in new[] { ("saveVersions", value.SaveVersions), ("configVersions", value.ConfigVersions), ("vaultVersions", value.VaultVersions) })
            if (n is < Min or > Max)
                throw new ApiException(400, "bad_value", $"{name} must be {Min}-{Max}.");

        var existing = await db.UserSettings
            .Where(s => s.UserId == userId && (s.Key == SaveKey || s.Key == ConfigKey || s.Key == VaultKey))
            .ToDictionaryAsync(s => s.Key, ct);
        foreach (var (key, n) in new[] { (SaveKey, value.SaveVersions), (ConfigKey, value.ConfigVersions), (VaultKey, value.VaultVersions) })
        {
            var text = n.ToString(CultureInfo.InvariantCulture);
            if (existing.TryGetValue(key, out var row)) row.Value = text;
            else db.UserSettings.Add(new UserSetting { UserId = userId, Key = key, Value = text });
        }
        await db.SaveChangesAsync(ct);
    }
}

public static class RetentionEndpoints
{
    public const int TopGames = 10;

    public static IEndpointRouteBuilder MapRetentionEndpoints(this IEndpointRouteBuilder app)
    {
        var settings = app.MapGroup("/v1/settings").RequireAuthorization();

        settings.MapGet("/retention", (HttpContext http, EmuSyncDbContext db, CancellationToken ct) =>
            RetentionSettings.LoadAsync(db, http.User.UserId(), ct));

        settings.MapPut("/retention", async (HttpContext http, EmuSyncDbContext db, UserLocks locks, RetentionSettingsDto? body,
            CancellationToken ct) =>
        {
            var userId = http.User.UserId();
            using (await locks.Acquire(userId, ct))
                await RetentionSettings.SaveAsync(db, userId, body, ct);
            return await RetentionSettings.LoadAsync(db, userId, ct);
        });

        settings.MapGet("/storage", (HttpContext http, EmuSyncDbContext db, CancellationToken ct) =>
            StorageAsync(db, http.User.UserId(), ct));
        return app;
    }

    /// <summary>
    /// Bytes of unique blobs (by hash) the user holds. Per emulator: the blobs of its save/state, config and vault
    /// versions and raw config uploads ("" = vault items not tied to an emulator). Per game: its save/state versions.
    /// A blob shared by two emulators or games counts once in each row, so rows can add up to more than the total.
    /// </summary>
    public static async Task<StorageUsage> StorageAsync(EmuSyncDbContext db, Guid userId, CancellationToken ct)
    {
        var sizes = await db.UserBlobs.AsNoTracking().Where(b => b.UserId == userId).ToDictionaryAsync(b => b.Hash, b => b.Size, ct);

        var saves = await (from v in db.SaveVersions.AsNoTracking()
                join i in db.SaveItems.AsNoTracking() on v.ItemId equals i.Id
                where i.UserId == userId
                select new { i.EmulatorId, i.GameKey, i.DisplayName, v.ManifestJson }).ToListAsync(ct);
        var configs = await (from v in db.ConfigVersions.AsNoTracking()
                join b in db.ConfigBundles.AsNoTracking() on v.BundleId equals b.Id
                where b.UserId == userId
                select new { b.EmulatorId, v.ManifestJson }).ToListAsync(ct);
        var raw = await (from r in db.RawConfigUploads.AsNoTracking()
                join d in db.Devices.AsNoTracking() on r.DeviceId equals d.Id
                where d.UserId == userId
                select new { r.EmulatorId, r.ManifestJson }).ToListAsync(ct);
        var vault = await (from v in db.VaultVersions.AsNoTracking()
                join i in db.VaultItems.AsNoTracking() on v.ItemId equals i.Id
                where i.UserId == userId
                select new { EmulatorId = i.EmulatorId ?? "", v.ManifestJson }).ToListAsync(ct);

        var byEmulator = new Dictionary<string, HashSet<string>>(StringComparer.Ordinal);
        void AddTo<TKey>(Dictionary<TKey, HashSet<string>> map, TKey key, string json) where TKey : notnull
        {
            if (!map.TryGetValue(key, out var set)) map[key] = set = new HashSet<string>(StringComparer.Ordinal);
            foreach (var e in Manifests.FromJson(json)) set.Add(e.Hash);
        }
        foreach (var r in saves) AddTo(byEmulator, r.EmulatorId, r.ManifestJson);
        foreach (var r in configs) AddTo(byEmulator, r.EmulatorId, r.ManifestJson);
        foreach (var r in raw) AddTo(byEmulator, r.EmulatorId, r.ManifestJson);
        foreach (var r in vault) AddTo(byEmulator, r.EmulatorId, r.ManifestJson);

        var byGame = new Dictionary<(string, string), HashSet<string>>();
        var names = new Dictionary<(string, string), string>();
        foreach (var r in saves)
        {
            AddTo(byGame, (r.EmulatorId, r.GameKey), r.ManifestJson);
            if (r.DisplayName is not null) names[(r.EmulatorId, r.GameKey)] = r.DisplayName;
        }

        long Sum(HashSet<string> hashes) => hashes.Sum(h => sizes.GetValueOrDefault(h));
        var emulators = byEmulator.Select(kv => new StorageRow(kv.Key, null, null, Sum(kv.Value)))
            .OrderByDescending(r => r.Bytes).ThenBy(r => r.EmulatorId, StringComparer.Ordinal).ToList();
        var games = byGame.Select(kv => new StorageRow(kv.Key.Item1, kv.Key.Item2, names.GetValueOrDefault(kv.Key), Sum(kv.Value)))
            .OrderByDescending(r => r.Bytes).ThenBy(r => r.EmulatorId, StringComparer.Ordinal).ThenBy(r => r.GameKey, StringComparer.Ordinal)
            .Take(TopGames).ToList();
        return new StorageUsage(sizes.Values.Sum(), emulators, games);
    }
}
