using System.Text.Json;
using Tandem.Protocol;
using Tandem.Server.Data;
using Tandem.Server.Deliveries;
using Tandem.Server.Devices;
using Tandem.Server.Saves;
using Tandem.Server.Http;
using Microsoft.EntityFrameworkCore;

namespace Tandem.Server.Web;

/// <summary>Read models for the web app. Everything is scoped to the user and ignores revoked devices.</summary>
public sealed class WebQueryService(TandemDbContext db, TimeProvider time, GameStateQueries gameStates, Http.StorageMonitor storage,
    Microsoft.Extensions.Options.IOptions<ServerOptions> options)
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    public const int ContinueCount = 10;
    public const int RecentCount = 10;

    /// <summary>The user's devices that sync: not revoked, and not a legacy browser device.</summary>
    private IQueryable<Device> ActiveDevices(Guid userId) => db.Devices.AsNoTracking()
        .Where(d => d.UserId == userId && d.RevokedUtc == null && d.Platform != DeviceService.LegacyWebPlatform);

    // ---- Home ----

    public async Task<HomeResponse> HomeAsync(Guid userId, Guid? currentDevice, CancellationToken ct, bool isAdmin = false)
    {
        var heads = await (
                from i in db.SaveItems.AsNoTracking()
                join v in db.SaveVersions.AsNoTracking() on i.HeadVersionId equals v.Id
                where i.UserId == userId && i.Kind == ItemKind.Save
                orderby v.ReceivedUtc descending, v.Seq descending
                select new { i.Id, i.EmulatorId, i.GameKey, i.DisplayName, v.DeviceId, v.ReceivedUtc })
            .Take(ContinueCount).ToListAsync(ct);
        var names = await DeviceNamesAsync(userId, ct);
        var cont = heads.Select(h => new ContinueItem(h.Id, h.EmulatorId, h.GameKey, h.DisplayName,
            h.DeviceId is Guid d && names.TryGetValue(d, out var n) ? n : "Restored", h.ReceivedUtc)).ToList();

        var devices = await DevicesAsync(userId, currentDevice, ct);

        var itemIds = db.SaveItems.Where(i => i.UserId == userId).Select(i => i.Id);
        var open = db.SaveVersions.Where(v => itemIds.Contains(v.ItemId) && v.ConflictReason != null && !v.Resolved);
        var concurrent = await open.CountAsync(v => v.ConflictReason == ConflictReason.Concurrent, ct);
        var firstSync = await open.Where(v => v.ConflictReason == ConflictReason.FirstSync).Select(v => v.ItemId).Distinct().CountAsync(ct);
        var shizuku = await ActiveDevices(userId).Where(d => d.ShizukuOk == false).Select(d => d.Id).ToListAsync(ct);
        var since = time.GetUtcNow().AddHours(-24);
        var userDevices = db.Devices.Where(d => d.UserId == userId).Select(d => d.Id);
        var failed = await db.Deliveries.CountAsync(d => userDevices.Contains(d.DeviceId) && d.Result == DeliveryResult.Failed && d.AckedUtc >= since, ct);
        var configConflicts = await db.ConfigBundles.CountAsync(b => b.UserId == userId && b.OpenConflict, ct);

        var recent = await db.ActivityEvents.AsNoTracking().Where(a => a.UserId == userId)
            .OrderByDescending(a => a.Id).Take(RecentCount)
            .Select(a => new ActivityItem(a.Id, a.Utc, a.Kind, a.Message, a.DeviceId,
                db.Devices.Where(x => x.Id == a.DeviceId).Select(x => x.Name).FirstOrDefault(), a.EmulatorId, a.GameKey))
            .ToListAsync(ct);
        var backupOff = isAdmin && !(await Backup.BackupSettings.LoadAsync(db, ct)).Enabled;
        var silent = (await Notify.NotifyTriggers.SilentDevicesAsync(db, userId, options.Value.SilentDeviceHours, time.GetUtcNow(), ct))
            .OrderBy(d => d.CreatedUtc).Select(d => d.Id).ToList();
        return new HomeResponse(cont, devices, new Attention(concurrent, firstSync, shizuku, failed, configConflicts, storage.IsLow,
            backupOff, silent), recent);
    }

    public async Task<List<DeviceSummary>> DevicesAsync(Guid userId, Guid? current, CancellationToken ct)
    {
        var now = time.GetUtcNow();
        return (await ActiveDevices(userId).OrderBy(d => d.CreatedUtc).ToListAsync(ct))
            .Select(d => DeviceService.Summary(d, current, now)).ToList();
    }

    private Task<Dictionary<Guid, string>> DeviceNamesAsync(Guid userId, CancellationToken ct) =>
        db.Devices.AsNoTracking().Where(d => d.UserId == userId).ToDictionaryAsync(d => d.Id, d => d.Name, ct);

    // ---- Games ----

    public async Task<List<GameRow>> GamesAsync(Guid userId, string? emulator, string? q, CancellationToken ct)
    {
        var itemQuery = db.SaveItems.AsNoTracking().Where(i => i.UserId == userId && (emulator == null || i.EmulatorId == emulator));
        var items = await itemQuery.ToListAsync(ct);
        var installs = await (from r in db.InstallRecords.AsNoTracking()
                join d in ActiveDevices(userId) on r.DeviceId equals d.Id
                where emulator == null || r.EmulatorId == emulator
                select r).ToListAsync(ct);
        var headIds = items.Where(i => i.HeadVersionId != null).Select(i => i.HeadVersionId!.Value).ToList();
        var heads = await db.SaveVersions.AsNoTracking().Where(v => headIds.Contains(v.Id))
            .Select(v => new HeadInfo(v.Id, v.DeviceId, v.ReceivedUtc)).ToDictionaryAsync(v => v.Id, ct);
        var states = await gameStates.LoadAsync(userId, itemQuery, ct);

        var keys = items.Select(i => (i.EmulatorId, i.GameKey)).Concat(installs.Select(r => (r.EmulatorId, r.GameKey))).Distinct().ToList();
        var itemsBy = items.ToLookup(i => (i.EmulatorId, i.GameKey));
        var installsBy = installs.ToLookup(r => (r.EmulatorId, r.GameKey));
        var rows = keys.Select(k => BuildRow(k.EmulatorId, k.GameKey, itemsBy[k].ToList(), installsBy[k].ToList(), heads, states)).ToList();

        if (!string.IsNullOrWhiteSpace(q))
            rows = rows.Where(r => r.GameKey.Contains(q, StringComparison.OrdinalIgnoreCase)
                                   || (r.DisplayName?.Contains(q, StringComparison.OrdinalIgnoreCase) ?? false)).ToList();
        return rows.OrderBy(r => r.EmulatorId, StringComparer.Ordinal).ThenBy(r => r.DisplayName ?? r.GameKey, StringComparer.OrdinalIgnoreCase).ToList();
    }

    private static GameRow BuildRow(string emulatorId, string gameKey, List<SaveItem> items, List<InstallRecord> installs,
        Dictionary<Guid, HeadInfo> heads, GameStates states)
    {
        var saveItem = items.Where(i => i.Kind == ItemKind.Save).OrderBy(i => i.Slot != "").ThenBy(i => i.Slot, StringComparer.Ordinal).FirstOrDefault();
        var newestItem = items.Where(i => i.HeadVersionId is Guid h && heads.ContainsKey(h))
            .OrderByDescending(i => heads[i.HeadVersionId!.Value].ReceivedUtc).FirstOrDefault();
        var newest = newestItem is null ? null : heads[newestItem.HeadVersionId!.Value];
        var display = items.Select(i => i.DisplayName).Concat(installs.OrderByDescending(r => r.SeenUtc).Select(r => r.DisplayName))
            .FirstOrDefault(n => !string.IsNullOrEmpty(n));
        var reference = saveItem?.HeadVersionId is not null ? saveItem : newestItem;
        var devices = states.For(emulatorId, items, installs, heads, reference, newest?.DeviceId);
        return new GameRow(emulatorId, gameKey, display, installs.Select(r => r.DeviceId).Distinct().ToList(),
            saveItem?.Id, newest?.DeviceId, newest?.ReceivedUtc, items.Count(i => i.Kind == ItemKind.State), items.Any(i => i.OpenConflict),
            devices, states.DeviceName(newest?.DeviceId));
    }

    public async Task<GameDetail> GameAsync(Guid userId, string emulatorId, string gameKey, CancellationToken ct)
    {
        var itemQuery = db.SaveItems.AsNoTracking().Where(i => i.UserId == userId && i.EmulatorId == emulatorId && i.GameKey == gameKey);
        var items = await itemQuery
            .OrderBy(i => i.Kind).ThenBy(i => i.Slot).ThenBy(i => i.Core).ToListAsync(ct);
        var installs = await (from r in db.InstallRecords.AsNoTracking()
                join d in ActiveDevices(userId) on r.DeviceId equals d.Id
                where r.EmulatorId == emulatorId && r.GameKey == gameKey
                select r).ToListAsync(ct);
        if (items.Count == 0 && installs.Count == 0)
            throw new ApiException(404, "game_not_found", "Game not found.");

        var ids = items.Select(i => i.Id).ToList();
        // The manifest itself is read only for rows stored before FileCount was.
        var versions = await db.SaveVersions.AsNoTracking().Where(v => ids.Contains(v.ItemId)).OrderByDescending(v => v.Seq)
            .Select(v => new
            {
                v.Id, v.ItemId, v.Seq, v.DeviceId, v.ReceivedUtc, v.TotalSize, v.Core, v.Variant, v.Pinned, v.ConflictReason,
                v.Resolved, v.ManifestHash, v.EmulatorVersion, v.FileCount,
                OldManifest = v.FileCount == null ? v.ManifestJson : null,
            }).ToListAsync(ct);
        var states = await gameStates.LoadAsync(userId, itemQuery, ct);
        var headIds = items.Where(i => i.HeadVersionId != null).Select(i => i.HeadVersionId!.Value).ToHashSet();
        var heads = versions.Where(v => headIds.Contains(v.Id)).ToDictionary(v => v.Id, v => new HeadInfo(v.Id, v.DeviceId, v.ReceivedUtc));
        var byItem = versions.ToLookup(v => v.ItemId);

        var details = items.Select(i => new SaveItemDetail(i.Id, i.Kind, i.Slot, i.Core, i.HeadVersionId, i.OpenConflict,
            byItem[i.Id].Select(v => new VersionSummary(v.Id, v.Seq,
                states.DeviceName(v.DeviceId), v.ReceivedUtc, v.TotalSize, v.Core, v.Variant,
                v.Pinned, v.ConflictReason, v.Id == i.HeadVersionId, v.Resolved, v.ManifestHash,
                v.FileCount ?? Manifests.FromJson(v.OldManifest ?? "[]").Count, v.EmulatorVersion)).ToList())).ToList();
        return new GameDetail(BuildRow(emulatorId, gameKey, items, installs, heads, states), details);
    }

    // ---- Devices ----

    public async Task<DeviceDetail> DeviceAsync(Guid userId, Guid deviceId, Guid? current, CancellationToken ct)
    {
        var device = await ActiveDevices(userId).FirstOrDefaultAsync(d => d.Id == deviceId, ct)
            ?? throw new ApiException(404, "device_not_found", "Device not found.");
        var summary = DeviceService.Summary(device, current, time.GetUtcNow());

        var emulators = (await db.DeviceEmulators.AsNoTracking().Where(e => e.DeviceId == deviceId).OrderBy(e => e.EmulatorId).ToListAsync(ct))
            .Select(e => new EmulatorRow(e.EmulatorId, e.Package, e.Version, Cores(e.CoresJson))).ToList();
        var roots = await db.DeviceRoots.AsNoTracking().Where(r => r.DeviceId == deviceId).ToDictionaryAsync(r => r.Token, r => r.Path, ct);

        var configs = await (
                from c in db.DeviceConfigs.AsNoTracking()
                where c.DeviceId == deviceId
                join b in db.ConfigBundles.AsNoTracking() on c.BundleId equals b.Id
                join g in db.SyncGroups.AsNoTracking() on b.GroupId equals g.Id into groups
                from g in groups.DefaultIfEmpty()
                orderby c.EmulatorId
                select new DeviceConfigRow(c.EmulatorId, b.Id, b.GroupId, g == null ? null : g.Name, c.AppliedVersionId, b.HeadVersionId,
                    c.AppliedVersionId != null && c.AppliedVersionId == b.HeadVersionId, b.OpenConflict))
            .ToListAsync(ct);

        var pending = await db.Deliveries.CountAsync(d => d.DeviceId == deviceId && d.AckedUtc == null, ct);
        var storage = await db.SaveVersions.Where(v => v.DeviceId == deviceId).SumAsync(v => (long?)v.TotalSize, ct) ?? 0;
        var installs = (await db.InstallRecords.AsNoTracking().Where(r => r.DeviceId == deviceId)
                .GroupBy(r => r.EmulatorId).Select(g => new { g.Key, N = g.Count() }).ToListAsync(ct))
            .OrderBy(x => x.Key, StringComparer.Ordinal).Select(x => new InstalledCount(x.Key, x.N)).ToList();
        return new DeviceDetail(summary, emulators, roots, configs, pending, storage, installs);
    }

    public static List<string>? Cores(string? json) =>
        json is null ? null : JsonSerializer.Deserialize<List<string>>(json, Json);

    // ---- Configs ----

    public async Task<ConfigOverview> ConfigsAsync(Guid userId, CancellationToken ct)
    {
        var groups = await db.SyncGroups.AsNoTracking().Where(g => g.UserId == userId).ToListAsync(ct);
        var bundles = await db.ConfigBundles.AsNoTracking().Where(b => b.UserId == userId).ToDictionaryAsync(b => b.Id, ct);
        var devices = await ActiveDevices(userId).ToDictionaryAsync(d => d.Id, d => new DeviceRef(d.Id, d.Name), ct);
        var configs = (await db.DeviceConfigs.AsNoTracking().Where(c => db.Devices.Any(d => d.Id == c.DeviceId && d.UserId == userId)).ToListAsync(ct))
            .Where(c => devices.ContainsKey(c.DeviceId) && bundles.ContainsKey(c.BundleId)).ToList();

        var rows = new Dictionary<string, (List<GroupRow> Groups, List<SoloRow> Solo)>(StringComparer.Ordinal);
        (List<GroupRow> Groups, List<SoloRow> Solo) Row(string emulator) =>
            rows.TryGetValue(emulator, out var r) ? r : rows[emulator] = ([], []);

        foreach (var g in groups.OrderBy(g => g.Name, StringComparer.OrdinalIgnoreCase))
        {
            var members = configs.Where(c => c.BundleId == g.BundleId).Select(c => devices[c.DeviceId]).OrderBy(d => d.Name).ToList();
            var bundle = bundles.GetValueOrDefault(g.BundleId);
            Row(g.EmulatorId).Groups.Add(new GroupRow(g.Id, g.Name, members, bundle?.HeadVersionId, bundle?.OpenConflict ?? false));
        }
        foreach (var c in configs.Where(c => bundles[c.BundleId].OwnerDeviceId != null).OrderBy(c => devices[c.DeviceId].Name))
            Row(c.EmulatorId).Solo.Add(new SoloRow(devices[c.DeviceId], c.BundleId, bundles[c.BundleId].HeadVersionId,
                bundles[c.BundleId].OpenConflict));

        return new ConfigOverview(rows.OrderBy(r => r.Key, StringComparer.Ordinal)
            .Select(r => new EmulatorConfigRow(r.Key, r.Value.Groups, r.Value.Solo)).ToList());
    }

    // ---- Setup checklist ----

    public static string KeepOwnKey(Guid deviceId, string emulatorId) => $"setup.keepOwn.{deviceId}.{emulatorId}";

    public async Task KeepOwnAsync(Guid userId, Guid deviceId, string emulatorId, CancellationToken ct)
    {
        if (!EmulatorIds.IsKnown(emulatorId))
            throw new ApiException(400, "unknown_emulator", $"Unknown emulator '{emulatorId}'.");
        if (!await ActiveDevices(userId).AnyAsync(d => d.Id == deviceId, ct))
            throw new ApiException(404, "device_not_found", "Device not found.");
        var key = KeepOwnKey(deviceId, emulatorId);
        var row = await db.UserSettings.FirstOrDefaultAsync(s => s.UserId == userId && s.Key == key, ct);
        if (row is null) db.UserSettings.Add(new UserSetting { UserId = userId, Key = key, Value = "1" });
        else row.Value = "1";
        try { await db.SaveChangesAsync(ct); }
        catch (DbUpdateException)
        {
            db.ChangeTracker.Clear(); // a concurrent identical request won the insert
            if (!await db.UserSettings.AnyAsync(s => s.UserId == userId && s.Key == key, ct)) throw;
        }
    }

    public async Task<SetupChecklist> SetupAsync(Guid userId, Guid deviceId, CancellationToken ct)
    {
        if (!await ActiveDevices(userId).AnyAsync(d => d.Id == deviceId, ct))
            throw new ApiException(404, "device_not_found", "Device not found.");
        var steps = new List<SetupStep>();

        var others = ActiveDevices(userId).Where(d => d.Id != deviceId).Select(d => d.Id);
        var mine = await db.DeviceEmulators.AsNoTracking().Where(e => e.DeviceId == deviceId).ToDictionaryAsync(e => e.EmulatorId, ct);
        var theirs = await db.DeviceEmulators.AsNoTracking().Where(e => others.Contains(e.DeviceId)).ToListAsync(ct);
        var theirEmulators = theirs.Select(e => e.EmulatorId).Distinct().Order(StringComparer.Ordinal).ToList();

        foreach (var emu in theirEmulators)
            steps.Add(new SetupStep(SetupStepKind.InstallEmulator, emu, $"Install {emu}", mine.ContainsKey(emu), null));

        foreach (var emu in theirEmulators.Where(mine.ContainsKey))
        {
            var wanted = theirs.Where(e => e.EmulatorId == emu).SelectMany(e => Cores(e.CoresJson) ?? []).ToList();
            if (wanted.Count == 0) continue;
            var have = (Cores(mine[emu].CoresJson) ?? []).ToHashSet(StringComparer.OrdinalIgnoreCase);
            var missing = wanted.Where(c => !have.Contains(c)).Distinct(StringComparer.OrdinalIgnoreCase).Order(StringComparer.OrdinalIgnoreCase).ToList();
            steps.Add(new SetupStep(SetupStepKind.InstallCores, emu,
                missing.Count == 0 ? $"Cores for {emu}" : $"Install {emu} cores", missing.Count == 0, missing.Count == 0 ? null : string.Join(", ", missing)));
        }

        await AddGrantFoldersAsync(steps, deviceId, ct);

        // Vault items for the device's emulators that it has not been given.
        var held = await db.DeviceVaultStates.Where(s => s.DeviceId == deviceId).Select(s => s.ItemId).ToListAsync(ct);
        var vault = await db.VaultItems.AsNoTracking().Where(v => v.UserId == userId && v.HeadVersionId != null && v.EmulatorId != null && v.EmulatorId != "")
            .ToListAsync(ct);
        foreach (var g in vault.Where(v => mine.ContainsKey(v.EmulatorId!)).GroupBy(v => v.EmulatorId!).OrderBy(g => g.Key, StringComparer.Ordinal))
        {
            var missing = g.Where(v => !held.Contains(v.Id)).Select(v => v.Name).Order(StringComparer.Ordinal).ToList();
            steps.Add(new SetupStep(SetupStepKind.PlaceVault, g.Key, $"Place {g.Key} system files", missing.Count == 0,
                missing.Count == 0 ? null : string.Join(", ", missing)));
        }

        // Saves: Done once no un-acked Save delivery remains. Listed per emulator that has saves for the user.
        var pendingTargets = await db.Deliveries.AsNoTracking().Where(d => d.DeviceId == deviceId && d.Kind == DeliveryKind.Save && d.AckedUtc == null)
            .Select(d => d.TargetId).ToListAsync(ct);
        var pendingByEmu = await db.SaveItems.AsNoTracking().Where(i => i.UserId == userId && pendingTargets.Contains(i.Id))
            .GroupBy(i => i.EmulatorId).Select(g => new { g.Key, N = g.Count() }).ToDictionaryAsync(x => x.Key, x => x.N, ct);
        var emusWithSaves = await db.SaveItems.AsNoTracking().Where(i => i.UserId == userId && i.HeadVersionId != null)
            .Select(i => i.EmulatorId).Distinct().ToListAsync(ct);
        foreach (var emu in emusWithSaves.Concat(pendingByEmu.Keys).Distinct().Where(mine.ContainsKey).Order(StringComparer.Ordinal))
        {
            var n = pendingByEmu.GetValueOrDefault(emu);
            steps.Add(new SetupStep(SetupStepKind.RestoreSaves, emu, $"Restore {emu} saves", n == 0, n == 0 ? null : $"{n} waiting"));
        }

        await AddChooseConfigAsync(steps, userId, deviceId, mine.Keys, ct);
        return new SetupChecklist(steps);
    }

    private async Task AddGrantFoldersAsync(List<SetupStep> steps, Guid deviceId, CancellationToken ct)
    {
        var rows = await (from d in db.Deliveries.AsNoTracking()
                join b in db.ConfigBundles.AsNoTracking() on d.TargetId equals b.Id
                where d.DeviceId == deviceId && d.Kind == DeliveryKind.Config && !d.Superseded
                select new { d.CreatedUtc, d.Id, d.AckedUtc, d.NotesJson, b.EmulatorId })
            .ToListAsync(ct);
        foreach (var g in rows.GroupBy(r => r.EmulatorId).OrderBy(g => g.Key, StringComparer.Ordinal))
        {
            var ordered = g.OrderByDescending(r => r.CreatedUtc).ThenByDescending(r => r.Id).ToList();
            var latest = MissingKeys(ordered[0].NotesJson);
            var latestAcked = ordered.FirstOrDefault(r => r.AckedUtc != null);
            if (latest.Count == 0 && (latestAcked is null || MissingKeys(latestAcked.NotesJson).Count == 0)) continue;
            var done = latestAcked is not null && MissingKeys(latestAcked.NotesJson).Count == 0;
            var shown = latest.Count > 0 ? latest : MissingKeys(latestAcked!.NotesJson);
            steps.Add(new SetupStep(SetupStepKind.GrantFolders, g.Key, $"Grant folder access for {g.Key}", done, string.Join(", ", shown)));
        }
    }

    private static List<string> MissingKeys(string? notesJson) =>
        notesJson is null ? [] : JsonSerializer.Deserialize<DeliveryService.ConfigNotes>(notesJson, Json)?.MissingLocalKeys ?? [];

    private async Task AddChooseConfigAsync(List<SetupStep> steps, Guid userId, Guid deviceId, IEnumerable<string> emulators, CancellationToken ct)
    {
        var own = await (from c in db.DeviceConfigs.AsNoTracking()
                where c.DeviceId == deviceId
                join b in db.ConfigBundles.AsNoTracking() on c.BundleId equals b.Id
                select new { c.EmulatorId, b.Id, b.GroupId }).ToListAsync(ct);
        var keepOwn = (await db.UserSettings.AsNoTracking().Where(s => s.UserId == userId && s.Key.StartsWith($"setup.keepOwn.{deviceId}."))
            .Select(s => s.Key).ToListAsync(ct)).Select(k => k[$"setup.keepOwn.{deviceId}.".Length..]).ToHashSet();
        foreach (var emu in emulators.Order(StringComparer.Ordinal))
        {
            var otherVersions = await (from v in db.ConfigVersions.AsNoTracking()
                    join b in db.ConfigBundles.AsNoTracking() on v.BundleId equals b.Id
                    join d in db.Devices.AsNoTracking() on v.DeviceId equals d.Id
                    where b.UserId == userId && b.EmulatorId == emu && d.Id != deviceId && d.RevokedUtc == null
                    select v.Id).AnyAsync(ct);
            if (!otherVersions) continue;
            var row = own.FirstOrDefault(o => o.EmulatorId == emu);
            var linked = row?.GroupId != null;
            var copied = row is not null && await db.ConfigVersions.AnyAsync(v => v.BundleId == row.Id && v.Source == ConfigSource.Copy, ct);
            steps.Add(new SetupStep(SetupStepKind.ChooseConfig, emu, $"Choose a {emu} config", linked || copied || keepOwn.Contains(emu), null));
        }
    }
}
