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
    /// <summary>v3: Switch, 3DS and GameCube/Wii.</summary>
    public const string CacheFileName = "titles-v3.json";
    public const string PreviousCacheFileName = "titles-v2.json";

    /// <summary>Best effort: a cache file that cannot be deleted is only wasted space.</summary>
    public void DeleteCaches(string dataDirectory, params string[] names)
    {
        foreach (var name in names)
        {
            try { File.Delete(Path.Combine(dataDirectory, name)); }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException) { log.LogDebug(e, "Could not delete {File}", name); }
        }
    }
    /// <summary>A title list bigger than this is refused (titledb's US file is about 150 MB).</summary>
    public const long MaxDownloadBytes = 300L * 1024 * 1024;
    private static readonly TimeSpan RetryAfterFailure = TimeSpan.FromHours(6);

    protected override async Task ExecuteAsync(CancellationToken stop)
    {
        var o = options.Value;
        DeleteCaches(o.DataDirectory, "titles.json"); // v1 (Switch only) is never read again (F-23)
        if (!o.EnableTitleNames || string.IsNullOrWhiteSpace(o.TitleNamesUrl))
        {
            DeleteCaches(o.DataDirectory, PreviousCacheFileName, CacheFileName);
            await backfill.RunAsync(stop);   // still cleans tags and prefixes from stored names
            return;
        }
        var cache = Path.Combine(o.DataDirectory, CacheFileName);
        var maxAge = TimeSpan.FromDays(Math.Max(1, o.TitleNamesRefreshDays));

        // Until the first v3 download, the v2 list (Switch and 3DS) still names those; it goes once v3 is saved.
        var previous = Path.Combine(o.DataDirectory, PreviousCacheFileName);
        if (!File.Exists(cache) && File.Exists(previous))
        {
            try { holder.Current = TitleNames.Load(previous); }
            catch (Exception e) when (e is not OperationCanceledException) { log.LogDebug(e, "Couldn't read the previous title list"); }
        }

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
                await DownloadAsync(o, cache, stop);
                await backfill.RunAsync(stop);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                log.LogWarning("Couldn't download the title lists: {Error}", e.Message);
                await Task.Delay(RetryAfterFailure, time, stop);
            }
        }
    }

    private async Task DownloadAsync(ServerOptions o, string cache, CancellationToken ct)
    {
        var lists = new List<TitleNames> { await FetchAsync(o.TitleNamesUrl!, TitleNames.Parse, cache, ct) };
        foreach (var url in (o.ThreeDsTitleNamesUrls ?? "").Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
            lists.Add(await FetchAsync(url, TitleNames.ParseThreeDs, cache, ct));
        if (!string.IsNullOrWhiteSpace(o.DiscTitleNamesUrl))
            lists.Add(await FetchAsync(o.DiscTitleNamesUrl, TitleNames.ParseGameTdb, cache, ct));
        var merged = TitleNames.Merge([.. lists]);
        if (merged.Count == 0) throw new InvalidDataException("the lists had no titles");
        merged.Save(cache);
        holder.Current = merged;
        DeleteCaches(o.DataDirectory, PreviousCacheFileName);
        log.LogInformation("Loaded {Count} titles", merged.Count);
    }

    /// <summary>Copies a title list download, refusing one over <paramref name="max"/> bytes (by its Content-Length, or
    /// while streaming) and one that is plainly not a list (an image, video or audio type) (F-22).</summary>
    internal static async Task CopyCappedAsync(HttpContent content, Stream target, long max, CancellationToken ct)
    {
        var tooBig = $"the list is larger than {max / (1024 * 1024)} MB";
        if (content.Headers.ContentLength > max) throw new InvalidDataException(tooBig);
        var type = content.Headers.ContentType?.MediaType ?? "";
        if (type.StartsWith("image/", StringComparison.OrdinalIgnoreCase) || type.StartsWith("video/", StringComparison.OrdinalIgnoreCase)
            || type.StartsWith("audio/", StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException($"the list came back as {type}");
        await using var body = await content.ReadAsStreamAsync(ct);
        var buffer = new byte[81920];
        long total = 0;
        int read;
        while ((read = await body.ReadAsync(buffer, ct)) > 0)
        {
            total += read;
            if (total > max) throw new InvalidDataException(tooBig);
            await target.WriteAsync(buffer.AsMemory(0, read), ct);
        }
    }

    /// <summary>Downloads [url] to a temporary file beside [cache] and parses it there, so memory holds only the result.</summary>
    private async Task<TitleNames> FetchAsync(string url, Func<Stream, TitleNames> parse, string cache, CancellationToken ct)
    {
        var tmp = cache + ".download";
        try
        {
            using (var client = http.CreateClient(ClientName))
            using (var response = await client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, ct))
            {
                response.EnsureSuccessStatusCode();
                await using var file = File.Create(tmp);
                await CopyCappedAsync(response.Content, file, MaxDownloadBytes, ct);
            }
            await using var f = File.OpenRead(tmp);
            return parse(f);
        }
        finally
        {
            File.Delete(tmp);
        }
    }
}
