using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Options;

namespace Tandem.Server.Art;

/// <summary>What the cache knows about a game's cover.</summary>
public abstract record ArtLookup
{
    public sealed record Image(string Path, string ContentType, string ETag) : ArtLookup;
    /// <summary>No cover until [Until]. Transient when a source could not be reached (the sources are asked again
    /// within the hour), rather than having said it has nothing.</summary>
    public sealed record Miss(DateTimeOffset Until, bool Transient) : ArtLookup;
    public sealed record Unknown : ArtLookup;
}

/// <summary>
/// Covers on disk under <c>{DataDirectory}/art/</c>. A game's file is named by the lower-case hex SHA-256 of
/// <c>emulatorId + "\n" + gameKey</c>, with ".png" or ".jpg" for an image or ".miss" for a recorded miss (whose text is
/// the time after which the sources are asked again, then "transient" on a second line when a source could not be reached).
/// Each read touches the image's last-access time; when the images
/// together pass ArtCacheBytes, the least recently read go first.
/// </summary>
public sealed class ArtStore(IOptions<ServerOptions> options, TimeProvider time, ILogger<ArtStore> log)
{
    private const string TransientMark = "transient";
    private const string BasisPrefix = "basis=";
    private static readonly TimeSpan StaleTemp = TimeSpan.FromHours(1);
    private static readonly TimeSpan MissSweepEvery = TimeSpan.FromDays(1);
    private readonly object _evictGate = new();
    private DateTimeOffset _lastMissSweep = DateTimeOffset.MinValue;

    public static string DirectoryOf(ServerOptions o) => Path.GetFullPath(Path.Combine(o.DataDirectory, "art"));

    public string Directory => DirectoryOf(options.Value);

    /// <summary>The store key for a kind: the game key for a cover, a separate namespace for a spine, so a game's
    /// spine and cover never share a file, a miss or an ETag.</summary>
    /// <remarks>The spine namespace carries the cutter's version: when the cut changes, old cuts stop being found and
    /// age out of the cache like any unused image.</remarks>
    public static string KeyFor(ArtKind kind, string gameKey) => kind == ArtKind.Spine ? $"\u0001spine.v{SpineCutter.Version}\n" + gameKey : gameKey;

    public static string NameOf(string emulatorId, string gameKey) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(emulatorId + "\n" + gameKey)));

    /// <summary>The image or a live miss for the key. With [basis], a miss recorded under a different basis (or none) is
    /// <see cref="ArtLookup.Unknown"/>: what the lookup knew has changed, so it is worth making again.</summary>
    public ArtLookup TryGet(string emulatorId, string gameKey, string? basis = null)
    {
        var name = NameOf(emulatorId, gameKey);
        foreach (var (ext, type) in new[] { (".png", "image/png"), (".jpg", "image/jpeg") })
        {
            var path = Path.Combine(Directory, name + ext);
            var info = new FileInfo(path);
            if (!info.Exists) continue;
            try { File.SetLastAccessTimeUtc(path, time.GetUtcNow().UtcDateTime); }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException) { log.LogDebug(e, "Couldn't touch {File}", path); }
            return new ArtLookup.Image(path, type, $"\"{name[..16]}-{info.Length:x}-{info.LastWriteTimeUtc.Ticks:x}\"");
        }
        var miss = Path.Combine(Directory, name + ".miss");
        if (File.Exists(miss))
        {
            try
            {
                var lines = File.ReadAllLines(miss);
                if (lines.Length > 0
                    && DateTimeOffset.TryParse(lines[0].Trim(), CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var until)
                    && time.GetUtcNow() < until)
                {
                    var recorded = lines.Skip(1).Select(l => l.Trim()).FirstOrDefault(l => l.StartsWith(BasisPrefix, StringComparison.Ordinal))?[BasisPrefix.Length..];
                    if (basis is not null && recorded != basis) return new ArtLookup.Unknown();
                    return new ArtLookup.Miss(until, lines.Skip(1).Any(l => l.Trim() == TransientMark));
                }
                File.Delete(miss);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException) { log.LogDebug(e, "Couldn't read {File}", miss); }
        }
        return new ArtLookup.Unknown();
    }

    /// <summary>Stores a checked image (".png" or ".jpg"), drops any miss record and trims the cache.</summary>
    public void Save(string emulatorId, string gameKey, byte[] bytes, string extension)
    {
        System.IO.Directory.CreateDirectory(Directory);
        var name = NameOf(emulatorId, gameKey);
        var path = Path.Combine(Directory, name + extension);
        var tmp = path + ".tmp";
        File.WriteAllBytes(tmp, bytes);
        File.Move(tmp, path, overwrite: true);
        File.SetLastAccessTimeUtc(path, time.GetUtcNow().UtcDateTime);
        foreach (var other in new[] { ".png", ".jpg", ".miss" }.Where(e => e != extension))
            File.Delete(Path.Combine(Directory, name + other));
        Evict();
    }

    /// <summary>Records that the sources have nothing until [until]; [transient] when one could not be reached.</summary>
    public void RecordMiss(string emulatorId, string gameKey, DateTimeOffset until, bool transient = false, string? basis = null)
    {
        System.IO.Directory.CreateDirectory(Directory);
        var path = Path.Combine(Directory, NameOf(emulatorId, gameKey) + ".miss");
        var text = until.ToString("O", CultureInfo.InvariantCulture);
        if (transient) text += "\n" + TransientMark;
        if (basis is not null && !basis.Contains('\n')) text += "\n" + BasisPrefix + basis;
        File.WriteAllText(path, text);
    }

    /// <summary>Deletes temp files a crash left behind (older than an hour), here and under index/, and expired miss
    /// records. Run at start-up, and from <see cref="Evict"/> at most once a day.</summary>
    public void Sweep()
    {
        var now = time.GetUtcNow();
        _lastMissSweep = now;
        var dir = new DirectoryInfo(Directory);
        if (!dir.Exists) return;
        foreach (var f in dir.EnumerateFiles("*", SearchOption.AllDirectories))
        {
            try
            {
                if (f.Extension == ".tmp" && now.UtcDateTime - f.LastWriteTimeUtc > StaleTemp) f.Delete();
                else if (f.Extension == ".miss" && f.DirectoryName == dir.FullName)
                {
                    var first = File.ReadLines(f.FullName).FirstOrDefault()?.Trim();
                    if (!DateTimeOffset.TryParse(first, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var until) || until <= now)
                        f.Delete();
                }
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException) { log.LogDebug(e, "Couldn't sweep {File}", f.Name); }
        }
    }

    /// <summary>Deletes the least recently read images until the rest fit in ArtCacheBytes.</summary>
    public void Evict()
    {
        lock (_evictGate)
        {
            var dir = new DirectoryInfo(Directory);
            if (!dir.Exists) return;
            if (time.GetUtcNow() - _lastMissSweep >= MissSweepEvery) Sweep();
            var images = dir.EnumerateFiles().Where(f => f.Extension is ".png" or ".jpg").ToList();
            var total = images.Sum(f => f.Length);
            var cap = options.Value.ArtCacheBytes;
            foreach (var f in images.OrderBy(f => f.LastAccessTimeUtc).ThenBy(f => f.Name, StringComparer.Ordinal))
            {
                if (total <= cap) break;
                try
                {
                    var length = f.Length;
                    f.Delete();
                    total -= length;
                }
                catch (Exception e) when (e is IOException or UnauthorizedAccessException) { log.LogDebug(e, "Couldn't evict {File}", f.Name); }
            }
        }
    }
}
