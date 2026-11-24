using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;
using Microsoft.Net.Http.Headers;
using Tandem.Server.Auth;
using Tandem.Server.Data;
using Tandem.Server.Games;

namespace Tandem.Server.Art;

/// <summary>
/// <c>GET /v1/web/art/{emulatorId}/{*gameKey}</c>, for a device key or a web session like the rest of /v1/web:
/// 200 with the image; 202 art_pending while a fetch is queued or running; 404 no_art when the sources have nothing or
/// art is off; 404 art_unavailable (cached for the rest of the hour at most) when a source could not be reached; 404 not_found when the user has neither a save item for the game nor a device that reports it installed. Art is only looked up for the user's own
/// games, and the request never waits on an outbound call.
/// </summary>
public static class ArtEndpoints
{
    public const string ImageCache = "private, max-age=604800";
    public const long MissCacheSeconds = 86400;
    public const string MissCache = "private, max-age=86400";

    public static IEndpointRouteBuilder MapArtEndpoints(this IEndpointRouteBuilder app)
    {
        // gameKey may contain '/', so it is a catch-all; an encoded slash stays literal and is turned back, as for
        // /v1/web/games/{emulatorId}/{*gameKey}.
        var g = app.MapGroup("/v1/web").RequireAuthorization();
        g.MapGet("/art/{emulatorId}/{*gameKey}", GetAsync);
        g.MapGet("/spine/{emulatorId}/{*gameKey}", GetSpineAsync);
        return app;
    }

    private static async Task<IResult> GetAsync(HttpContext http, TandemDbContext db, ArtStore store, ArtFetcher fetcher,
        GameNames names, IOptions<ServerOptions> options, TimeProvider time, string emulatorId, string gameKey, CancellationToken ct)
    {
        var key = gameKey.Replace("%2F", "/", StringComparison.OrdinalIgnoreCase);
        var userId = http.User.UserId();
        var items = await db.SaveItems.AsNoTracking()
            .Where(i => i.UserId == userId && i.EmulatorId == emulatorId && i.GameKey == key)
            .Select(i => new { i.Kind, i.Slot, i.Core, i.DisplayName, i.HeadVersionId })
            .ToListAsync(ct);
        // A game counts as the user's when it has a save item or one of the user's devices reports it installed.
        var installs = await (from r in db.InstallRecords.AsNoTracking()
                              join d in db.Devices.AsNoTracking() on r.DeviceId equals d.Id
                              where d.UserId == userId && d.RevokedUtc == null && r.EmulatorId == emulatorId && r.GameKey == key
                              orderby r.SeenUtc descending
                              select new { r.DisplayName, r.System }).ToListAsync(ct);
        if (items.Count == 0 && installs.Count == 0) return Error(http, StatusCodes.Status404NotFound, "not_found", "No such game.", "no-store");
        if (!options.Value.EnableArt) return Error(http, StatusCodes.Status404NotFound, "no_art", "Cover art is turned off.", MissCache);

        switch (store.TryGet(emulatorId, key))
        {
            case ArtLookup.Image image:
                byte[] bytes;
                try { bytes = await File.ReadAllBytesAsync(image.Path, ct); }
                catch (Exception e) when (e is FileNotFoundException or DirectoryNotFoundException) { break; } // evicted meanwhile
                http.Response.Headers.CacheControl = ImageCache;
                return Results.Bytes(bytes, image.ContentType, entityTag: new EntityTagHeaderValue(image.ETag));
            case ArtLookup.Miss miss:
                // Cached no longer than the miss lasts. A transient miss (a source was down) has its own code, so a client
                // doesn't remember it as "no art", and lasts an hour at most.
                var seconds = (long)Math.Ceiling(Math.Clamp((miss.Until - time.GetUtcNow()).TotalSeconds, 1, MissCacheSeconds));
                var cache = $"private, max-age={seconds}";
                return miss.Transient
                    ? Error(http, StatusCodes.Status404NotFound, "art_unavailable", "The cover art sources could not be reached; try again later.", cache)
                    : Error(http, StatusCodes.Status404NotFound, "no_art", "No cover art was found for this game.", cache);
        }

        var display = items.Select(i => i.DisplayName).Concat(installs.Select(r => r.DisplayName)).FirstOrDefault(n => !string.IsNullOrWhiteSpace(n))
                      ?? names.Resolve(emulatorId, key, null);
        var heads = items.Where(i => i.HeadVersionId != null).Select(i => i.HeadVersionId!.Value).ToList();
        var core = await db.SaveVersions.AsNoTracking().Where(v => heads.Contains(v.Id) && v.Core != "").Select(v => v.Core).FirstOrDefaultAsync(ct)
                   ?? items.Select(i => i.Core).FirstOrDefault(c => c != "");
        fetcher.Enqueue(new ArtRequest(emulatorId, key, display, core, installs.Select(r => r.System).FirstOrDefault(x => x is not null)));
        http.Response.Headers.RetryAfter = "3";
        return Error(http, StatusCodes.Status202Accepted, "art_pending", "Cover art is being fetched.", "no-store");
    }

    /// <summary>
    /// <c>GET /v1/web/spine/{emulatorId}/{*gameKey}</c>: the game's real case spine, cut from a GameTDB scan. The same
    /// contract as /art, except 202 is spine_pending and a game without a spine (or with art off) is 404 no_spine.
    /// </summary>
    private static async Task<IResult> GetSpineAsync(HttpContext http, TandemDbContext db, ArtStore store, ArtFetcher fetcher,
        IOptions<ServerOptions> options, TimeProvider time, string emulatorId, string gameKey, CancellationToken ct)
    {
        var key = gameKey.Replace("%2F", "/", StringComparison.OrdinalIgnoreCase);
        var userId = http.User.UserId();
        var hasSave = await db.SaveItems.AsNoTracking().AnyAsync(i => i.UserId == userId && i.EmulatorId == emulatorId && i.GameKey == key, ct);
        var installs = await (from r in db.InstallRecords.AsNoTracking()
                              join d in db.Devices.AsNoTracking() on r.DeviceId equals d.Id
                              where d.UserId == userId && d.RevokedUtc == null && r.EmulatorId == emulatorId && r.GameKey == key
                              orderby r.SeenUtc descending
                              select new { r.System, r.GameId }).ToListAsync(ct);
        if (!hasSave && installs.Count == 0) return Error(http, StatusCodes.Status404NotFound, "not_found", "No such game.", "no-store");

        var reported = installs.Select(r => r.System).FirstOrDefault(s => s is not null);
        var gameId = installs.Select(r => r.GameId).FirstOrDefault(s => s is not null);
        var system = ArtSystems.Resolve(emulatorId, key, null, reported);
        if (!SpineEligibility.Has(emulatorId, key, system, gameId, options.Value.EnableArt))
            return Error(http, StatusCodes.Status404NotFound, "no_spine", "This game has no case spine.", MissCache);

        switch (store.TryGet(emulatorId, ArtStore.KeyFor(ArtKind.Spine, key)))
        {
            case ArtLookup.Image image:
                byte[] bytes;
                try { bytes = await File.ReadAllBytesAsync(image.Path, ct); }
                catch (Exception e) when (e is FileNotFoundException or DirectoryNotFoundException) { break; }
                http.Response.Headers.CacheControl = ImageCache;
                return Results.Bytes(bytes, image.ContentType, entityTag: new EntityTagHeaderValue(image.ETag));
            case ArtLookup.Miss miss:
                var seconds = (long)Math.Ceiling(Math.Clamp((miss.Until - time.GetUtcNow()).TotalSeconds, 1, MissCacheSeconds));
                var cache = $"private, max-age={seconds}";
                return miss.Transient
                    ? Error(http, StatusCodes.Status404NotFound, "art_unavailable", "The cover art sources could not be reached; try again later.", cache)
                    : Error(http, StatusCodes.Status404NotFound, "no_spine", "No case spine was found for this game.", cache);
        }

        fetcher.Enqueue(new ArtRequest(emulatorId, key, null, null, reported, ArtKind.Spine, gameId));
        http.Response.Headers.RetryAfter = "3";
        return Error(http, StatusCodes.Status202Accepted, "spine_pending", "The case spine is being fetched.", "no-store");
    }

    /// <summary>The usual error body (code, message), plus "error" with the same code, as the art contract names it.</summary>
    private static IResult Error(HttpContext http, int status, string code, string message, string cacheControl)
    {
        http.Response.Headers.CacheControl = cacheControl;
        return Results.Json(new ArtError(code, message, code), statusCode: status);
    }

    private sealed record ArtError(string Code, string Message, string Error);
}
