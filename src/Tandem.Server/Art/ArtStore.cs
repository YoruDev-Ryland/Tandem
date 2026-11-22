using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using Microsoft.Extensions.Options;

namespace Tandem.Server.Art;

/// <summary>What the cache knows about a game's cover.</summary>
public abstract record ArtLookup
{
    public sealed record Image(string Path, string ContentType, string ETag) : ArtLookup;
    public sealed record Miss : ArtLookup;
    public sealed record Unknown : ArtLookup;
}

/// <summary>
/// Covers on disk under <c>{DataDirectory}/art/</c>. A game's file is named by the lower-case hex SHA-256 of
/// <c>emulatorId + "\n" + gameKey</c>, with ".png" or ".jpg" for an image or ".miss" for a recorded miss (whose text is
/// the time after which the sources are asked again). Each read touches the image's last-access time; when the images
/// together pass ArtCacheBytes, the least recently read go first.
/// </summary>
public sealed class ArtStore(IOptions<ServerOptions> options, TimeProvider time, ILogger<ArtStore> log)
{
    private readonly object _evictGate = new();

    public static string DirectoryOf(ServerOptions o) => Path.GetFullPath(Path.Combine(o.DataDirectory, "art"));

    public string Directory => DirectoryOf(options.Value);

    public static string NameOf(string emulatorId, string gameKey) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(emulatorId + "\n" + gameKey)));

    public ArtLookup TryGet(string emulatorId, string gameKey)
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
                if (DateTimeOffset.TryParse(File.ReadAllText(miss).Trim(), CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var until)
                    && time.GetUtcNow() < until)
                    return new ArtLookup.Miss();
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

    /// <summary>Records that the sources have nothing until [until].</summary>
    public void RecordMiss(string emulatorId, string gameKey, DateTimeOffset until)
    {
        System.IO.Directory.CreateDirectory(Directory);
        var path = Path.Combine(Directory, NameOf(emulatorId, gameKey) + ".miss");
        File.WriteAllText(path, until.ToString("O", CultureInfo.InvariantCulture));
    }

    /// <summary>Deletes the least recently read images until the rest fit in ArtCacheBytes.</summary>
    public void Evict()
    {
        lock (_evictGate)
        {
            var dir = new DirectoryInfo(Directory);
            if (!dir.Exists) return;
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
