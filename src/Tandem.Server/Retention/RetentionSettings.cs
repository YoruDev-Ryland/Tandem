using System.Globalization;
using Tandem.Protocol;
using Tandem.Server.Art;
using Tandem.Server.Auth;
using Tandem.Server.Blobs;
using Tandem.Server.Data;
using Tandem.Server.Http;
using Tandem.Server.Saves;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace Tandem.Server.Retention;

/// <summary>Per-user retention counts, stored as <see cref="UserSetting"/> rows.</summary>
public static class RetentionSettings
{
    public const string SaveKey = "retention.saveVersions";
    public const string ConfigKey = "retention.configVersions";
    public const string VaultKey = "retention.vaultVersions";
    public const string StateAgeKey = "retention.stateMaxAgeDays";
    public const int Min = 1;
    public const int Max = 1000;
    public const int MaxStateAgeDays = 3650;

    public static readonly RetentionSettingsDto Defaults = new(30, 30, 5, 0);
    private static readonly string[] Keys = [SaveKey, ConfigKey, VaultKey, StateAgeKey];

    public static async Task<RetentionSettingsDto> LoadAsync(TandemDbContext db, Guid userId, CancellationToken ct)
    {
        var rows = await db.UserSettings.AsNoTracking()
            .Where(s => s.UserId == userId && Keys.Contains(s.Key))
            .ToDictionaryAsync(s => s.Key, s => s.Value, ct);
        // A missing or unreadable value means the default; never a smaller count than the user can have asked for.
        int Read(string key, int fallback, int min = Min, int max = Max) =>
            rows.TryGetValue(key, out var v) && int.TryParse(v, NumberStyles.None, CultureInfo.InvariantCulture, out var n)
                && n >= min && n <= max ? n : fallback;
        return new RetentionSettingsDto(Read(SaveKey, Defaults.SaveVersions), Read(ConfigKey, Defaults.ConfigVersions),
            Read(VaultKey, Defaults.VaultVersions), Read(StateAgeKey, Defaults.StateMaxAgeDays, 0, MaxStateAgeDays));
    }

    /// <summary>Validates (400 bad_value) and stores. Caller holds the user's lock, so a prune never sees half of it.</summary>
    public static async Task SaveAsync(TandemDbContext db, Guid userId, RetentionSettingsDto? value, CancellationToken ct)
    {
        if (value is null) throw new ApiException(400, "bad_request", "A request body is required.");
        foreach (var (name, n) in new[] { ("saveVersions", value.SaveVersions), ("configVersions", value.ConfigVersions), ("vaultVersions", value.VaultVersions) })
            if (n is < Min or > Max)
                throw new ApiException(400, "bad_value", $"{name} must be {Min}-{Max}.");
        if (value.StateMaxAgeDays is < 0 or > MaxStateAgeDays)
            throw new ApiException(400, "bad_value", $"stateMaxAgeDays must be 0-{MaxStateAgeDays}.");

        var existing = await db.UserSettings
            .Where(s => s.UserId == userId && Keys.Contains(s.Key))
            .ToDictionaryAsync(s => s.Key, ct);
        foreach (var (key, n) in new[] { (SaveKey, value.SaveVersions), (ConfigKey, value.ConfigVersions), (VaultKey, value.VaultVersions),
                     (StateAgeKey, value.StateMaxAgeDays) })
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

        settings.MapGet("/retention", (HttpContext http, TandemDbContext db, CancellationToken ct) =>
            RetentionSettings.LoadAsync(db, http.User.UserId(), ct));

        settings.MapPut("/retention", async (HttpContext http, TandemDbContext db, UserLocks locks, RetentionSettingsDto? body,
            CancellationToken ct) =>
        {
            var userId = http.User.UserId();
            using (await locks.Acquire(userId, ct))
                await RetentionSettings.SaveAsync(db, userId, body, ct);
            return await RetentionSettings.LoadAsync(db, userId, ct);
        });

        settings.MapGet("/storage", async (HttpContext http, TandemDbContext db, IOptions<ServerOptions> options, FilesLocation location,
            StorageMonitor monitor, IFreeSpaceProbe probe, FilesMover mover, CancellationToken ct) =>
        {
            var usage = await StorageAsync(db, http.User.UserId(), ct);
            return http.User.IsAdmin()
                ? usage with { Server = await ServerStorageAsync(db, options.Value, location, monitor, probe, mover, ct) }
                : usage;
        });

        settings.MapGet("/storage-view", async (HttpContext http, TandemDbContext db, CancellationToken ct) =>
        {
            var userId = http.User.UserId();
            var value = await db.UserSettings.AsNoTracking().Where(s => s.UserId == userId && s.Key == StorageViewKey)
                .Select(s => s.Value).FirstOrDefaultAsync(ct);
            return new StorageViewDto(value is not null && StorageViews.Contains(value) ? value : DefaultStorageView);
        });

        settings.MapPut("/storage-view", async (HttpContext http, TandemDbContext db, UserLocks locks, StorageViewDto? body, CancellationToken ct) =>
        {
            if (body?.View is not { } view || !StorageViews.Contains(view))
                throw new ApiException(400, "bad_value", $"view must be one of: {string.Join(", ", StorageViews)}.");
            var userId = http.User.UserId();
            using var _ = await locks.Acquire(userId, ct);
            var row = await db.UserSettings.FirstOrDefaultAsync(s => s.UserId == userId && s.Key == StorageViewKey, ct);
            if (row is null) db.UserSettings.Add(new UserSetting { UserId = userId, Key = StorageViewKey, Value = view });
            else row.Value = view;
            await db.SaveChangesAsync(ct);
            return new StorageViewDto(view);
        });
        return app;
    }

    public const string StorageViewKey = "storage.view";
    public const string DefaultStorageView = "map";
    private static readonly string[] StorageViews = ["map", "bars", "blocks"];

    /// <summary>All accounts' figures, for admins: stored files, the files disk, art, and the database file with its -wal.</summary>
    public static async Task<StorageServer> ServerStorageAsync(TandemDbContext db, ServerOptions options, FilesLocation location,
        StorageMonitor monitor, IFreeSpaceProbe probe, FilesMover mover, CancellationToken ct)
    {
        var filesBytes = await db.UserBlobs.AsNoTracking().SumAsync(b => (long?)b.Size, ct) ?? 0;
        var root = location.Root;
        return new StorageServer(filesBytes,
            root.Length > 0 ? probe.TotalBytes(root) : null, root.Length > 0 ? probe.FreeBytes(root) : null,
            options.MinFreeBytes, DirectoryBytes(ArtStore.DirectoryOf(options)), SqliteBytes(db), root, location.IsDefault,
            mover.Status, monitor.LowDisk);
    }

    private static long DirectoryBytes(string dir)
    {
        try
        {
            return Directory.Exists(dir)
                ? new DirectoryInfo(dir).EnumerateFiles("*", SearchOption.AllDirectories).Sum(f => f.Length)
                : 0;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return 0; }
    }

    /// <summary>The SQLite file plus its write-ahead log; null for Postgres or an in-memory database.</summary>
    private static long? SqliteBytes(TandemDbContext db)
    {
        if (!db.Database.IsSqlite()) return null;
        var source = new Microsoft.Data.Sqlite.SqliteConnectionStringBuilder(db.Database.GetConnectionString()).DataSource;
        if (string.IsNullOrEmpty(source) || source.Equals(":memory:", StringComparison.OrdinalIgnoreCase)) return null;
        var file = Path.GetFullPath(source);
        long Length(string path) { try { return File.Exists(path) ? new FileInfo(path).Length : 0; } catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return 0; } }
        return Length(file) + Length(file + "-wal");
    }

    /// <summary>
    /// Bytes of unique blobs (by hash) the user holds. Per emulator: the blobs of its save/state, config and vault
    /// versions and raw config uploads ("" = vault items not tied to an emulator). Per game: its save/state versions. Server is left null (the endpoint fills it for admins).
    /// A blob shared by two emulators or games counts once in each row, so rows can add up to more than the total.
    /// <see cref="StorageUsage.OlderBytes"/> are the unique bytes only non-head save/state versions reference (a blob a
    /// head, config, vault or lost item also holds would not be freed by retention). A game's own
    /// <see cref="StorageGame.OlderBytes"/> only subtracts its own head versions, so it can count a blob another game's
    /// head holds: the per-game values do not add up to the top-level figure.
    /// </summary>
    public static async Task<StorageUsage> StorageAsync(TandemDbContext db, Guid userId, CancellationToken ct)
    {
        var sizes = await db.UserBlobs.AsNoTracking().Where(b => b.UserId == userId).ToDictionaryAsync(b => b.Hash, b => b.Size, ct);

        var saves = await (from v in db.SaveVersions.AsNoTracking()
                join i in db.SaveItems.AsNoTracking() on v.ItemId equals i.Id
                where i.UserId == userId
                select new { i.EmulatorId, i.GameKey, i.DisplayName, i.Kind, VersionId = v.Id, IsHead = i.HeadVersionId == v.Id, v.ManifestJson }).ToListAsync(ct);
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

        var lost = await db.LostItems.AsNoTracking().Where(l => l.UserId == userId).Select(l => l.ManifestJson).ToListAsync(ct);

        // Each manifest is parsed once; every set below is fed from its hash array.
        static string[] Hashes(string json) => Manifests.FromJson(json).Select(e => e.Hash).ToArray();
        static void AddTo<TKey>(Dictionary<TKey, HashSet<string>> map, TKey key, string[] hashes) where TKey : notnull
        {
            if (!map.TryGetValue(key, out var set)) map[key] = set = new HashSet<string>(StringComparer.Ordinal);
            set.UnionWith(hashes);
        }
        static HashSet<string> NewSet() => new(StringComparer.Ordinal);

        var byEmulator = new Dictionary<string, HashSet<string>>(StringComparer.Ordinal);
        var byGame = new Dictionary<(string, string), HashSet<string>>();
        var currentByGame = new Dictionary<(string, string), HashSet<string>>();
        var versionsByGame = new Dictionary<(string, string), int>();
        var kindsByGame = new Dictionary<(string, string), SortedSet<string>>();
        var names = new Dictionary<(string, string), string>();
        var saveHashes = NewSet();
        var stateHashes = NewSet();
        var headHashes = NewSet();
        var allSaveStateHashes = NewSet();
        var configHashes = NewSet();
        var vaultHashes = NewSet();
        var lostHashes = NewSet();
        foreach (var r in saves)
        {
            var game = (r.EmulatorId, r.GameKey);
            var hashes = Hashes(r.ManifestJson);
            AddTo(byEmulator, r.EmulatorId, hashes);
            AddTo(byGame, game, hashes);
            versionsByGame[game] = versionsByGame.GetValueOrDefault(game) + 1;
            if (!kindsByGame.TryGetValue(game, out var kinds)) kindsByGame[game] = kinds = new SortedSet<string>(StringComparer.Ordinal);
            kinds.Add(r.Kind.ToString());
            if (r.DisplayName is not null) names[game] = r.DisplayName;
            allSaveStateHashes.UnionWith(hashes);
            (r.Kind == ItemKind.State ? stateHashes : saveHashes).UnionWith(hashes);
            if (r.IsHead)
            {
                AddTo(currentByGame, game, hashes);
                headHashes.UnionWith(hashes);
            }
        }
        foreach (var r in configs) { var h = Hashes(r.ManifestJson); AddTo(byEmulator, r.EmulatorId, h); configHashes.UnionWith(h); }
        foreach (var r in raw) { var h = Hashes(r.ManifestJson); AddTo(byEmulator, r.EmulatorId, h); configHashes.UnionWith(h); }
        foreach (var r in vault) { var h = Hashes(r.ManifestJson); AddTo(byEmulator, r.EmulatorId, h); vaultHashes.UnionWith(h); }
        foreach (var json in lost) lostHashes.UnionWith(Hashes(json));

        long Sum(IEnumerable<string> hashes) => hashes.Sum(h => sizes.GetValueOrDefault(h));
        var emulators = byEmulator.Select(kv => new StorageRow(kv.Key, null, null, Sum(kv.Value)))
            .OrderByDescending(r => r.Bytes).ThenBy(r => r.EmulatorId, StringComparer.Ordinal).ToList();
        var gameRows = byGame.Select(kv =>
        {
            var current = currentByGame.GetValueOrDefault(kv.Key);
            var currentBytes = current is null ? 0 : Sum(current);
            var bytes = Sum(kv.Value);
            return new StorageGame(kv.Key.Item1, kv.Key.Item2, names.GetValueOrDefault(kv.Key), bytes, currentBytes, bytes - currentBytes,
                versionsByGame[kv.Key], kindsByGame[kv.Key].ToList());
        }).OrderByDescending(g => g.Bytes).ThenBy(g => g.EmulatorId, StringComparer.Ordinal).ThenBy(g => g.GameKey, StringComparer.Ordinal).ToList();
        var topGames = gameRows.Take(TopGames).Select(g => new StorageRow(g.EmulatorId, g.GameKey, g.DisplayName, g.Bytes)).ToList();
        var kindBytes = new StorageKinds(Sum(saveHashes), Sum(stateHashes), Sum(configHashes), Sum(vaultHashes), Sum(lostHashes));
        return new StorageUsage(sizes.Values.Sum(), emulators, topGames, gameRows, kindBytes,
            Sum(allSaveStateHashes.Where(h => !headHashes.Contains(h) && !configHashes.Contains(h) && !vaultHashes.Contains(h) && !lostHashes.Contains(h))), null);
    }
}
