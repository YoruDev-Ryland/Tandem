using System.Threading.Channels;
using Tandem.Protocol;
using Tandem.Server.Data;
using Tandem.Server.Events;
using Microsoft.EntityFrameworkCore;

namespace Tandem.Server.Notify;

/// <summary>
/// Turns domain events into notifications. Event handlers only enqueue (a bounded channel; a full queue drops the
/// notification) so an upload or ack is never slowed or failed by a notification target; a background consumer builds
/// the message, de-duplicates it and calls <see cref="INotifier"/>.
/// <para>Every <see cref="CheckInterval"/> (when EnableNotifyChecks) <see cref="RunOnceAsync"/> notifies once per off
/// period for devices whose Shizuku has been off for <see cref="ShizukuGrace"/>; "notified" is stored on the device
/// (<c>ShizukuOffNotifiedUtc</c>), so a restart does not repeat it. Backup failures go to every admin.</para>
/// <para>De-duplication is in memory, per (user, event, target item), within <see cref="DedupeWindow"/>.</para>
/// </summary>
public sealed class NotifyTriggers(IDomainEvents events, IServiceScopeFactory scopes, INotifier notifier, TimeProvider time,
    Microsoft.Extensions.Options.IOptions<ServerOptions> options, Http.StorageMonitor storage, ILogger<NotifyTriggers> logger) : BackgroundService
{
    public static readonly TimeSpan CheckInterval = TimeSpan.FromMinutes(5);
    public static readonly TimeSpan ShizukuGrace = TimeSpan.FromMinutes(30);
    public static readonly TimeSpan DedupeWindow = TimeSpan.FromMinutes(10);
    public const int QueueCapacity = 1000;
    private const int MaxDetail = 200;

    private sealed record ShizukuOff(Guid UserId, Guid DeviceId, string DeviceName);
    private sealed record Silent(Guid UserId, Guid DeviceId, string DeviceName, int Hours);
    private sealed record StorageLow(long FreeBytes, long MinFreeBytes);

    /// <summary>The <see cref="ServerSetting"/> that marks the current low-storage episode as notified.</summary>
    public const string StorageLowNotifiedKey = "notify.storageLowNotified";

    private readonly Channel<object> _queue = Channel.CreateBounded<object>(
        new BoundedChannelOptions(Math.Max(1, options.Value.NotifyQueueCapacity)) { FullMode = BoundedChannelFullMode.Wait, SingleReader = true });
    private readonly object _gate = new();
    private readonly Dictionary<(Guid User, string Event, string Item), DateTimeOffset> _recent = [];
    private int _pending;

    public override Task StartAsync(CancellationToken cancellationToken)
    {
        events.Subscribe<ConflictOpened>((e, _) => Enqueue(e));
        events.Subscribe<ConfigConflictOpened>((e, _) => Enqueue(e));
        events.Subscribe<DeliveryFailed>((e, _) => Enqueue(e));
        events.Subscribe<BackupFinished>((e, _) => e.Ok ? Task.CompletedTask : Enqueue(e));
        return base.StartAsync(cancellationToken);
    }

    private Task Enqueue(object work)
    {
        Interlocked.Increment(ref _pending);
        if (!_queue.Writer.TryWrite(work))
        {
            Interlocked.Decrement(ref _pending);
            logger.LogWarning("Notification queue is full; dropped a {Type} notification", work.GetType().Name);
        }
        return Task.CompletedTask;
    }

    protected override Task ExecuteAsync(CancellationToken stoppingToken) =>
        Task.WhenAll(ConsumeAsync(stoppingToken), options.Value.EnableNotifyChecks ? CheckLoopAsync(stoppingToken) : Task.CompletedTask);

    private async Task ConsumeAsync(CancellationToken ct)
    {
        try
        {
            await foreach (var work in _queue.Reader.ReadAllAsync(ct))
            {
                try { await HandleAsync(work, ct); }
                catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
                catch (Exception ex) { logger.LogError(ex, "Handling a {Type} notification failed", work.GetType().Name); }
                finally { Interlocked.Decrement(ref _pending); }
            }
        }
        catch (OperationCanceledException) { }
    }

    private async Task CheckLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try { await RunOnceAsync(ct); }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
            catch (Exception ex) { logger.LogError(ex, "Shizuku check failed"); }
            try { await CheckSilentDevicesAsync(ct); }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
            catch (Exception ex) { logger.LogError(ex, "Silent device check failed"); }
            try { await CheckStorageAsync(ct); }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { return; }
            catch (Exception ex) { logger.LogError(ex, "Storage check failed"); }

            try { await Task.Delay(CheckInterval, time, ct); }
            catch (OperationCanceledException) { return; }
        }
    }

    /// <summary>Waits until every queued notification has been handled (for tests and shutdown).</summary>
    public async Task FlushAsync(TimeSpan? timeout = null)
    {
        var until = Environment.TickCount64 + (long)(timeout ?? TimeSpan.FromSeconds(10)).TotalMilliseconds;
        while (Volatile.Read(ref _pending) > 0)
        {
            if (Environment.TickCount64 > until) throw new TimeoutException("Notifications were not delivered in time.");
            await Task.Delay(5);
        }
    }

    /// <summary>One Shizuku check (the loop calls this every 5 minutes; runs regardless of EnableNotifyChecks). Queues a
    /// notification for each device off for 30+ minutes that has not been notified for this off period, and records
    /// that. Returns how many were queued.</summary>
    public async Task<int> RunOnceAsync(CancellationToken ct)
    {
        using var scope = scopes.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        var cutoff = time.GetUtcNow() - ShizukuGrace;
        // Filtered in memory: DateTimeOffset comparisons do not translate on every provider.
        var due = (await db.Devices.Where(d => d.ShizukuOk == false && d.RevokedUtc == null && d.ShizukuOffNotifiedUtc == null
                                               && d.ShizukuOffSinceUtc != null).ToListAsync(ct))
            .Where(d => d.ShizukuOffSinceUtc <= cutoff).ToList();
        if (due.Count == 0) return 0;

        var now = time.GetUtcNow();
        foreach (var d in due) d.ShizukuOffNotifiedUtc = now;
        await db.SaveChangesAsync(ct);
        foreach (var d in due) await Enqueue(new ShizukuOff(d.UserId, d.Id, d.Name));
        return due.Count;
    }

    /// <summary>The active devices (not revoked, not a legacy browser device) last seen (or, never seen, created) more
    /// than <see cref="ServerOptions.SilentDeviceHours"/> ago.</summary>
    public static async Task<List<Device>> SilentDevicesAsync(TandemDbContext db, Guid? userId, int hours, DateTimeOffset now,
        CancellationToken ct, bool tracked = false)
    {
        var cutoff = now - TimeSpan.FromHours(Math.Max(1, hours));
        var query = db.Devices.Where(d => d.RevokedUtc == null && d.Platform != Devices.DeviceService.LegacyWebPlatform);
        if (userId is { } u) query = query.Where(d => d.UserId == u);
        if (!tracked) query = query.AsNoTracking();
        // Filtered in memory: DateTimeOffset comparisons do not translate on every provider.
        return (await query.ToListAsync(ct)).Where(d => (d.LastSeenUtc ?? d.CreatedUtc) <= cutoff).ToList();
    }

    /// <summary>One silent-device check (the loop runs it every 5 minutes). Notifies once per silence: the time is kept in
    /// <c>Device.SilentNotifiedUtc</c>, cleared once the device is seen again. Returns how many were queued.</summary>
    public async Task<int> CheckSilentDevicesAsync(CancellationToken ct)
    {
        using var scope = scopes.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        var now = time.GetUtcNow();
        var hours = Math.Max(1, options.Value.SilentDeviceHours);

        // Seen since the notification: that silence is over, so a later one notifies again.
        foreach (var d in (await db.Devices.Where(d => d.SilentNotifiedUtc != null).ToListAsync(ct))
                 .Where(d => d.LastSeenUtc > d.SilentNotifiedUtc))
            d.SilentNotifiedUtc = null;
        await db.SaveChangesAsync(ct);

        var due = (await SilentDevicesAsync(db, null, hours, now, ct, tracked: true)).Where(d => d.SilentNotifiedUtc == null).ToList();
        if (due.Count == 0) return 0;
        foreach (var d in due) d.SilentNotifiedUtc = now;
        await db.SaveChangesAsync(ct);
        foreach (var d in due) await Enqueue(new Silent(d.UserId, d.Id, d.Name, hours));
        return due.Count;
    }

    /// <summary>One storage check: while the data directory is low on space, notifies the admins once per episode (kept
    /// in a server setting, so a restart does not repeat it); once there is space again the episode ends. Returns
    /// whether a notification was queued.</summary>
    public async Task<bool> CheckStorageAsync(CancellationToken ct)
    {
        using var scope = scopes.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        var row = await db.ServerSettings.FirstOrDefaultAsync(s => s.Key == StorageLowNotifiedKey, ct);
        if (!storage.IsLow)
        {
            if (row is not null)
            {
                db.ServerSettings.Remove(row);
                await db.SaveChangesAsync(ct);
            }
            return false;
        }
        if (row is not null) return false;
        db.ServerSettings.Add(new ServerSetting { Key = StorageLowNotifiedKey, Value = time.GetUtcNow().ToString("O") });
        await db.SaveChangesAsync(ct);
        await Enqueue(new StorageLow(storage.FreeBytes ?? 0, options.Value.MinFreeBytes));
        return true;
    }

    private static string Megabytes(long bytes) =>
        string.Create(System.Globalization.CultureInfo.InvariantCulture, $"{bytes / (1024.0 * 1024.0):0} MB");

    private async Task HandleAsync(object work, CancellationToken ct)
    {
        using var scope = scopes.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        switch (work)
        {
            case ConflictOpened e:
            {
                var item = await db.SaveItems.AsNoTracking().FirstOrDefaultAsync(i => i.Id == e.ItemId, ct);
                if (item is null) return;
                var device = await DeviceName(db, await db.SaveVersions.AsNoTracking().Where(v => v.Id == e.VersionId)
                    .Select(v => v.DeviceId).FirstOrDefaultAsync(ct), ct);
                var what = item.Kind == ItemKind.State ? "save state" : "save";
                await SendAsync(e.UserId, NotifyEventNames.Conflict, e.ItemId.ToString(), new NotifyMessage("Tandem: sync conflict",
                    $"{Game(item)} ({Emulator(item.EmulatorId)}): {device} uploaded a {what} that conflicts with the version on the server. "
                    + "Open Tandem to choose which to keep.", NotifyEventNames.Conflict), ct);
                break;
            }
            case ConfigConflictOpened e:
            {
                var device = await DeviceName(db, e.DeviceId, ct);
                await SendAsync(e.UserId, NotifyEventNames.Conflict, e.BundleId.ToString(), new NotifyMessage("Tandem: config conflict",
                    $"{Emulator(e.EmulatorId)} config: {device} uploaded a config that conflicts with the version on the server. "
                    + "Open Tandem to choose which to keep.", NotifyEventNames.Conflict), ct);
                break;
            }
            case DeliveryFailed e:
            {
                var delivery = await db.Deliveries.AsNoTracking().FirstOrDefaultAsync(d => d.Id == e.DeliveryId, ct);
                if (delivery is null) return;
                var device = await DeviceName(db, e.DeviceId, ct);
                var subject = await Subject(db, delivery, ct);
                var detail = string.IsNullOrWhiteSpace(e.Detail) ? "" : $" Reason: {Truncate(e.Detail.Trim())}";
                await SendAsync(e.UserId, NotifyEventNames.FailedSync, delivery.TargetId.ToString(), new NotifyMessage("Tandem: sync failed",
                    $"{device} could not apply {subject}.{detail}", NotifyEventNames.FailedSync), ct);
                break;
            }
            case ShizukuOff e:
                await SendAsync(e.UserId, NotifyEventNames.ShizukuOff, e.DeviceId.ToString(), new NotifyMessage("Tandem: Shizuku is off",
                    $"Shizuku has been off on {e.DeviceName} for over {(int)ShizukuGrace.TotalMinutes} minutes. "
                    + "Syncing there is paused until Shizuku is running again.", NotifyEventNames.ShizukuOff), ct);
                break;
            case Silent e:
                await SendAsync(e.UserId, NotifyEventNames.SilentDevice, e.DeviceId.ToString(), new NotifyMessage("Tandem: device silent",
                    $"{e.DeviceName} has not been in touch for over {e.Hours} hours, so its saves are not syncing. "
                    + "Check that it is on, online and that the Tandem app is running.", NotifyEventNames.SilentDevice), ct);
                break;
            case StorageLow e:
            {
                var admins = await db.Users.AsNoTracking().Where(u => u.IsAdmin).Select(u => u.Id).ToListAsync(ct);
                var message = new NotifyMessage("Tandem: storage low",
                    $"The server has {Megabytes(e.FreeBytes)} free in its data directory (the minimum is {Megabytes(e.MinFreeBytes)}). "
                    + "Uploads are paused until space is freed.", NotifyEventNames.StorageLow);
                foreach (var admin in admins) await SendAsync(admin, NotifyEventNames.StorageLow, "storage", message, ct);
                break;
            }
            case BackupFinished e:
            {
                var admins = await db.Users.AsNoTracking().Where(u => u.IsAdmin).Select(u => u.Id).ToListAsync(ct);
                var message = new NotifyMessage("Tandem: backup failed",
                    string.IsNullOrWhiteSpace(e.Error) ? "The scheduled backup failed." : $"The backup failed: {e.Error}", NotifyEventNames.BackupFailed);
                foreach (var admin in admins) await SendAsync(admin, NotifyEventNames.BackupFailed, "backup", message, ct);
                break;
            }
        }
    }

    private async Task SendAsync(Guid userId, string eventName, string item, NotifyMessage message, CancellationToken ct)
    {
        if (!Claim(userId, eventName, item)) return;
        await notifier.SendAsync(userId, message, ct);
    }

    /// <summary>True (and remembers it) unless the same (user, event, item) was sent within the window.</summary>
    private bool Claim(Guid userId, string eventName, string item)
    {
        var now = time.GetUtcNow();
        lock (_gate)
        {
            if (_recent.Count > 1000)
                foreach (var k in _recent.Where(p => now - p.Value >= DedupeWindow).Select(p => p.Key).ToList()) _recent.Remove(k);
            var key = (userId, eventName, item);
            if (_recent.TryGetValue(key, out var at) && now - at < DedupeWindow) return false;
            _recent[key] = now;
            return true;
        }
    }

    private static async Task<string> DeviceName(TandemDbContext db, Guid? deviceId, CancellationToken ct) =>
        deviceId is null ? "A device"
            : await db.Devices.AsNoTracking().Where(d => d.Id == deviceId).Select(d => d.Name).FirstOrDefaultAsync(ct) ?? "A device";

    private static string Game(SaveItem item) => string.IsNullOrWhiteSpace(item.DisplayName) ? item.GameKey : item.DisplayName;

    private static async Task<string> Subject(TandemDbContext db, Delivery delivery, CancellationToken ct)
    {
        switch (delivery.Kind)
        {
            case DeliveryKind.Save:
                var item = await db.SaveItems.AsNoTracking().FirstOrDefaultAsync(i => i.Id == delivery.TargetId, ct);
                return item is null ? "a save" : $"the save for {Game(item)} ({Emulator(item.EmulatorId)})";
            case DeliveryKind.Config:
                var emulator = await db.ConfigBundles.AsNoTracking().Where(b => b.Id == delivery.TargetId).Select(b => b.EmulatorId).FirstOrDefaultAsync(ct);
                return string.IsNullOrEmpty(emulator) ? "a config" : $"the {Emulator(emulator)} config";
            default:
                var name = await db.VaultItems.AsNoTracking().Where(v => v.Id == delivery.TargetId).Select(v => v.Name).FirstOrDefaultAsync(ct);
                return string.IsNullOrEmpty(name) ? "a vault file" : $"the vault file {name}";
        }
    }

    private static string Truncate(string s) => s.Length <= MaxDetail ? s : s[..MaxDetail] + "...";

    /// <summary>The emulator's display name (the id for an unknown one).</summary>
    public static string Emulator(string id) => id switch
    {
        "eden" => "Eden",
        "retroarch" => "RetroArch",
        "azahar" => "Azahar",
        "ppsspp" => "PPSSPP",
        "vita3k" => "Vita3K",
        "dolphin" => "Dolphin",
        "melonds" => "melonDS",
        _ => id,
    };
}
