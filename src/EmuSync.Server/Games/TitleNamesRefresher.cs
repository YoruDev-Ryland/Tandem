using EmuSync.Server.Data;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace EmuSync.Server.Games;

/// <summary>Fills names that are missing or just the raw title id from the current title list.</summary>
public sealed class TitleNamesBackfill(IServiceScopeFactory scopes, TitleNamesHolder holder, ILogger<TitleNamesBackfill> log)
{
    public async Task RunAsync(CancellationToken ct)
    {
        if (holder.Current is null) return;
        using var scope = scopes.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>();
        var names = scope.ServiceProvider.GetRequiredService<GameNames>();
        var emulators = GameNames.SwitchEmulators.ToList();
        var changed = 0;
        foreach (var item in await db.SaveItems.Where(i => emulators.Contains(i.EmulatorId)).ToListAsync(ct))
        {
            if (!GameNames.NeedsName(item.DisplayName, item.GameKey)) continue;
            if (names.Resolve(item.EmulatorId, item.GameKey, null) is { } name) { item.DisplayName = name; changed++; }
        }
        foreach (var r in await db.InstallRecords.Where(r => emulators.Contains(r.EmulatorId)).ToListAsync(ct))
        {
            if (!GameNames.NeedsName(r.DisplayName, r.GameKey)) continue;
            if (names.Resolve(r.EmulatorId, r.GameKey, null) is { } name) { r.DisplayName = name; changed++; }
        }
        if (changed > 0)
        {
            await db.SaveChangesAsync(ct);
            log.LogInformation("Named {Count} Switch games from the title list", changed);
        }
    }
}

/// <summary>
/// Loads the cached title list at start, and downloads a fresh one when the cache is missing or older than
/// <see cref="ServerOptions.TitleNamesRefreshDays"/>. A failed download keeps the old list and retries later.
/// </summary>
public sealed class TitleNamesRefresher(IOptions<ServerOptions> options, TitleNamesHolder holder, TitleNamesBackfill backfill,
    IHttpClientFactory http, TimeProvider time, ILogger<TitleNamesRefresher> log) : BackgroundService
{
    public const string ClientName = "titles";
    private static readonly TimeSpan RetryAfterFailure = TimeSpan.FromHours(6);

    protected override async Task ExecuteAsync(CancellationToken stop)
    {
        var o = options.Value;
        if (!o.EnableTitleNames || string.IsNullOrWhiteSpace(o.TitleNamesUrl)) return;
        var cache = Path.Combine(o.DataDirectory, "titles.json");
        var maxAge = TimeSpan.FromDays(Math.Max(1, o.TitleNamesRefreshDays));

        if (File.Exists(cache))
        {
            try
            {
                holder.Current = TitleNames.Load(cache);
                await backfill.RunAsync(stop);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                log.LogWarning(e, "Couldn't read the cached title list; downloading a new one");
            }
        }

        while (!stop.IsCancellationRequested)
        {
            var age = File.Exists(cache) && holder.Current is not null
                ? time.GetUtcNow() - File.GetLastWriteTimeUtc(cache)
                : TimeSpan.MaxValue;
            var wait = age >= maxAge ? TimeSpan.Zero : maxAge - age;
            if (wait > TimeSpan.Zero) await Task.Delay(wait, time, stop);

            try
            {
                await DownloadAsync(o.TitleNamesUrl!, cache, stop);
                await backfill.RunAsync(stop);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                log.LogWarning("Couldn't download the Switch title list from {Url}: {Error}", o.TitleNamesUrl, e.Message);
                await Task.Delay(RetryAfterFailure, time, stop);
            }
        }
    }

    private async Task DownloadAsync(string url, string cache, CancellationToken ct)
    {
        var tmp = cache + ".download";
        try
        {
            using (var client = http.CreateClient(ClientName))
            using (var response = await client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct))
            {
                response.EnsureSuccessStatusCode();
                await using var file = File.Create(tmp);
                await response.Content.CopyToAsync(file, ct);
            }
            TitleNames list;
            await using (var f = File.OpenRead(tmp)) list = TitleNames.Parse(f);
            if (list.Count == 0) throw new InvalidDataException("the list had no titles");
            list.Save(cache);
            holder.Current = list;
            log.LogInformation("Loaded {Count} Switch titles", list.Count);
        }
        finally
        {
            File.Delete(tmp);
        }
    }
}
