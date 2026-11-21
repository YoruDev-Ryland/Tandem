using Tandem.Server.Data;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace Tandem.Server.Games;

/// <summary>Cleans stored names of ROM-site tags and fills missing or raw-id Switch names from the title list.</summary>
public sealed class TitleNamesBackfill(IServiceScopeFactory scopes, ILogger<TitleNamesBackfill> log)
{
    public async Task RunAsync(CancellationToken ct)
    {
        using var scope = scopes.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        var names = scope.ServiceProvider.GetRequiredService<GameNames>();
        var changed = 0;
        foreach (var item in await db.SaveItems.ToListAsync(ct))
            if (Improve(names, item.EmulatorId, item.GameKey, item.DisplayName) is { } name) { item.DisplayName = name; changed++; }
        foreach (var r in await db.InstallRecords.ToListAsync(ct))
            if (Improve(names, r.EmulatorId, r.GameKey, r.DisplayName) is { } name) { r.DisplayName = name; changed++; }
        if (changed > 0)
        {
            await db.SaveChangesAsync(ct);
            log.LogInformation("Improved {Count} game names", changed);
        }
    }

    /// <summary>A better name than [current] (tags cleaned, or a title for a raw id), or null to keep it.</summary>
    private static string? Improve(GameNames names, string emulatorId, string gameKey, string? current)
    {
        var better = names.Resolve(emulatorId, gameKey, current);
        return better is not null && better != current ? better : null;
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
        if (!o.EnableTitleNames || string.IsNullOrWhiteSpace(o.TitleNamesUrl))
        {
            await backfill.RunAsync(stop);   // still cleans tags from stored names
            return;
        }
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
