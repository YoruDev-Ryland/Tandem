using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Tandem.Protocol;
using Tandem.Server.Blobs;
using Tandem.Server.Http;

namespace Tandem.Server.Saves;

/// <summary>Validation, normalization and hashing of file manifests (saves, states and configs).</summary>
public static class Manifests
{
    public const int MaxEntries = 10000;
    public const int MaxPathLength = 512;

    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    /// <summary>Validates and returns the entries sorted by path (ordinal). Throws 400 bad_manifest.</summary>
    public static List<ManifestEntry> Normalize(IReadOnlyList<ManifestEntry>? manifest)
    {
        if (manifest is null || manifest.Count == 0) throw Bad("The manifest has no files.");
        if (manifest.Count > MaxEntries) throw Bad($"At most {MaxEntries} files per manifest.");
        var paths = new HashSet<string>(StringComparer.Ordinal);
        foreach (var e in manifest)
        {
            if (e is null) throw Bad("Null manifest entry.");
            ValidatePath(e.Path);
            if (!BlobHash.IsValid(e.Hash)) throw Bad($"Invalid hash for '{e.Path}'.");
            if (e.Size < 0) throw Bad($"Negative size for '{e.Path}'.");
            if (!paths.Add(e.Path)) throw Bad($"Duplicate path '{e.Path}'.");
        }
        return manifest.OrderBy(e => e.Path, StringComparer.Ordinal).ToList();
    }

    private static void ValidatePath(string? path)
    {
        if (string.IsNullOrEmpty(path) || path.Length > MaxPathLength)
            throw Bad($"Paths must be 1-{MaxPathLength} characters.");
        if (path.Contains('\\') || path.Contains('\0'))
            throw Bad("Paths must not contain '\\' or NUL.");
        if (path.StartsWith('/')) throw Bad("Paths must be relative.");
        foreach (var segment in path.Split('/'))
            if (segment is "" or "." or "..")
                throw Bad($"Invalid path '{path}'.");
    }

    /// <summary>Lowercase hex SHA-256 over "{path}\0{hash}\0{size}\n" per entry, sorted by path (ordinal).</summary>
    public static string Hash(IEnumerable<ManifestEntry> manifest)
    {
        var sb = new StringBuilder();
        foreach (var e in manifest.OrderBy(e => e.Path, StringComparer.Ordinal))
            sb.Append(e.Path).Append('\0').Append(e.Hash).Append('\0')
                .Append(e.Size.ToString(CultureInfo.InvariantCulture)).Append('\n');
        return Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(sb.ToString())));
    }

    public static string ToJson(IReadOnlyList<ManifestEntry> manifest) => JsonSerializer.Serialize(manifest, JsonOptions);

    public static List<ManifestEntry> FromJson(string json) =>
        JsonSerializer.Deserialize<List<ManifestEntry>>(json, JsonOptions) ?? [];

    private static ApiException Bad(string message) => new(400, "bad_manifest", message);
}
