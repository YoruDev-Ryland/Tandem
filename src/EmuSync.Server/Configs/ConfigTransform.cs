using System.Text;
using System.Text.RegularExpressions;
using EmuSync.Protocol;
using EmuSync.Server.Blobs;
using EmuSync.Server.Configs.Formats;
using EmuSync.Server.Data;
using EmuSync.Server.Http;
using EmuSync.Server.Saves;
using Microsoft.EntityFrameworkCore;

namespace EmuSync.Server.Configs;

/// <summary>
/// Moves config files between devices. On upload, path values are made portable (canonical form): plain paths
/// under a known root become tokens and device-local values become <see cref="Local"/>. On delivery, tokens are
/// expanded with the target's roots and every <see cref="Local"/> (or unknown token) gets the target's own value
/// from its latest raw upload. Files without a known format, too large, or not valid UTF-8 pass through untouched.
/// Rewritten files are stored as new (content-addressed) blobs, so a repeat produces the same hash.
/// </summary>
public sealed partial class ConfigTransform(EmuSyncDbContext db, IBlobStore store, BlobService blobs, TimeProvider time)
{
    public const string Local = "{LOCAL}";

    /// <summary>Larger files are treated as opaque.</summary>
    public const long MaxTextBytes = 4 * 1024 * 1024;

    private static readonly UTF8Encoding StrictUtf8 = new(encoderShouldEmitUTF8Identifier: false, throwOnInvalidBytes: true);

    [GeneratedRegex(@"^\{(?:INTERNAL|SD|EMU:[a-z0-9]+|LOCAL)\}")]
    private static partial Regex TokenStart();

    private sealed record TextFile(IConfigFormat Format, string Text, List<ConfigValue> Values);

    public async Task<Dictionary<string, string>> RootsAsync(Guid deviceId, CancellationToken ct) =>
        await db.DeviceRoots.AsNoTracking().Where(r => r.DeviceId == deviceId).ToDictionaryAsync(r => r.Token, r => r.Path, ct);

    /// <param name="head">The bundle head's canonical manifest, if any: an empty value where the head holds
    /// <see cref="Local"/> stays <see cref="Local"/> (a device that had no value for the key writes back the empty
    /// value it was given, which must not erase the other members' values).</param>
    public async Task<List<ManifestEntry>> CanonicalizeAsync(Guid userId, string emulatorId, IReadOnlyList<ManifestEntry> raw,
        IReadOnlyDictionary<string, string> roots, IReadOnlyList<ManifestEntry>? head, CancellationToken ct)
    {
        var headByPath = head?.ToDictionary(e => e.Path, StringComparer.Ordinal);
        var result = new List<ManifestEntry>(raw.Count);
        foreach (var entry in raw)
        {
            var file = await LoadAsync(userId, emulatorId, entry, ct);
            if (file is null) { result.Add(entry); continue; }

            HashSet<string>? headLocal = null;
            var changes = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var v in Unique(file.Values))
            {
                var inner = PathTokens.StripQuotes(v.Value, out var quote);
                if (inner.Length == 0)
                {
                    headLocal ??= await HeadLocalKeysAsync(userId, emulatorId, headByPath, entry.Path, ct);
                    if (headLocal.Contains(v.Key)) changes[v.Key] = PathTokens.Requote(Local, quote);
                    continue;
                }
                if (!PathTokens.IsPath(v.Value)) continue;
                var tokenized = PathTokens.Tokenize(v.Value, roots);
                changes[v.Key] = tokenized.DeviceLocal ? PathTokens.Requote(Local, quote) : tokenized.Value;
            }
            result.Add(await RewriteAsync(userId, entry, file, changes, null, ct));
        }
        return result;
    }

    /// <summary>The canonical files as the target device should write them, and the <c>path#key</c> values it has none for.</summary>
    /// <param name="preview">Read-only use (diffs): when set, rewritten files are not stored as blobs; their text is
    /// put here by hash instead. Without it the caller must hold the user's lock.</param>
    public async Task<(List<ManifestEntry> Manifest, List<string> MissingLocalKeys)> ExpandAsync(Guid userId, Guid deviceId,
        string emulatorId, IReadOnlyList<ManifestEntry> canonical, CancellationToken ct, IDictionary<string, string>? preview = null)
    {
        var roots = await RootsAsync(deviceId, ct);
        var rawJson = await db.RawConfigUploads.AsNoTracking()
            .Where(r => r.DeviceId == deviceId && r.EmulatorId == emulatorId)
            .OrderByDescending(r => r.ReceivedUtc).Select(r => r.ManifestJson).FirstOrDefaultAsync(ct);
        var rawByPath = (rawJson is null ? [] : Manifests.FromJson(rawJson)).ToDictionary(e => e.Path, StringComparer.Ordinal);

        var result = new List<ManifestEntry>(canonical.Count);
        var missing = new List<string>();
        foreach (var entry in canonical)
        {
            var file = await LoadAsync(userId, emulatorId, entry, ct);
            if (file is null) { result.Add(entry); continue; }

            Dictionary<string, string>? local = null;
            var changes = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var v in Unique(file.Values))
            {
                var inner = PathTokens.StripQuotes(v.Value, out var quote);
                if (!TokenStart().IsMatch(inner)) continue;
                var expanded = inner.StartsWith(Local, StringComparison.Ordinal) ? null : PathTokens.Expand(v.Value, roots);
                if (expanded is not null) { changes[v.Key] = expanded; continue; }

                local ??= await LocalValuesAsync(userId, emulatorId, rawByPath, entry.Path, ct);
                if (local.TryGetValue(v.Key, out var mine))
                {
                    changes[v.Key] = mine;
                }
                else
                {
                    changes[v.Key] = PathTokens.Requote("", quote);
                    missing.Add($"{entry.Path}#{v.Key}");
                }
            }
            result.Add(await RewriteAsync(userId, entry, file, changes, preview, ct));
        }
        return (result, missing);
    }

    /// <summary>Keys whose occurrences all carry the same value (first occurrence). Replace rewrites every
    /// occurrence of a key, so a key repeated with different values is left alone.</summary>
    private static IEnumerable<ConfigValue> Unique(List<ConfigValue> values) =>
        values.GroupBy(v => v.Key, StringComparer.Ordinal)
            .Where(g => g.Select(v => v.Value).Distinct(StringComparer.Ordinal).Count() == 1)
            .Select(g => g.First());

    private async Task<HashSet<string>> HeadLocalKeysAsync(Guid userId, string emulatorId,
        Dictionary<string, ManifestEntry>? headByPath, string path, CancellationToken ct)
    {
        var keys = new HashSet<string>(StringComparer.Ordinal);
        if (headByPath is null || !headByPath.TryGetValue(path, out var headEntry)) return keys;
        var file = await LoadAsync(userId, emulatorId, headEntry, ct);
        if (file is null) return keys;
        foreach (var v in Unique(file.Values))
            if (PathTokens.StripQuotes(v.Value, out _) == Local)
                keys.Add(v.Key);
        return keys;
    }

    /// <summary>The target's own non-empty values in its raw copy of the file.</summary>
    private async Task<Dictionary<string, string>> LocalValuesAsync(Guid userId, string emulatorId,
        Dictionary<string, ManifestEntry> rawByPath, string path, CancellationToken ct)
    {
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        if (!rawByPath.TryGetValue(path, out var rawEntry)) return values;
        var file = await LoadAsync(userId, emulatorId, rawEntry, ct);
        if (file is null) return values;
        foreach (var v in Unique(file.Values))
            if (PathTokens.StripQuotes(v.Value, out _).Length > 0)
                values[v.Key] = v.Value;
        return values;
    }

    /// <summary>The file as text with its values, or null when it is opaque (no known format, too large, not
    /// valid UTF-8, or nothing the adapter can read).</summary>
    private async Task<TextFile?> LoadAsync(Guid userId, string emulatorId, ManifestEntry entry, CancellationToken ct)
    {
        var format = ConfigFormats.ForPath(emulatorId, entry.Path);
        if (format is null || entry.Size > MaxTextBytes) return null;
        await using var stream = await store.OpenAsync(userId, entry.Hash, ct)
            ?? throw new InvalidOperationException($"Blob {entry.Hash} is recorded but missing from the store.");
        using var buffer = new MemoryStream();
        await stream.CopyToAsync(buffer, ct);
        string text;
        try { text = StrictUtf8.GetString(buffer.GetBuffer(), 0, (int)buffer.Length); }
        catch (DecoderFallbackException) { return null; }
        var values = format.Read(text).ToList();
        return values.Count == 0 ? null : new TextFile(format, text, values);
    }

    /// <param name="preview">When set, nothing is stored: rewritten text goes here by hash (read-only callers).</param>
    private async Task<ManifestEntry> RewriteAsync(Guid userId, ManifestEntry entry, TextFile file,
        Dictionary<string, string> changes, IDictionary<string, string>? preview, CancellationToken ct)
    {
        if (changes.Count == 0) return entry;
        var text = file.Format.Replace(file.Text, changes);
        if (text == file.Text) return entry;
        string hash;
        long size;
        if (preview is null)
        {
            (hash, size) = await PutTextAsync(userId, text, ct);
        }
        else
        {
            var bytes = Encode(text);
            (hash, size) = (Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(bytes)), bytes.Length);
            preview[hash] = text;
        }
        return entry with { Hash = hash, Size = size };
    }

    private static byte[] Encode(string text)
    {
        try { return StrictUtf8.GetBytes(text); }
        catch (EncoderFallbackException) { throw new ApiException(400, "bad_request", "The text is not valid Unicode."); }
    }

    /// <summary>Stores text (UTF-8, no BOM) as a blob of the user. Self-healing: the file is written whenever it is
    /// missing, even if the row exists, and the row is recorded whenever it is missing. Callers hold the user's lock
    /// and must not have pending changes (recording saves).</summary>
    public async Task<(string Hash, long Size)> PutTextAsync(Guid userId, string text, CancellationToken ct)
    {
        var bytes = Encode(text);
        var hash = Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(bytes));
        if (!await store.ExistsAsync(userId, hash, ct))
        {
            await using var content = new MemoryStream(bytes, writable: false);
            await store.PutAsync(userId, hash, content, ct);
        }
        if (!await blobs.HasAsync(userId, hash, ct))
            await RecordAsync(userId, hash, bytes.Length, ct);
        return (hash, bytes.Length);
    }

    /// <summary>The blob as text, or null when it is binary (not valid UTF-8) or larger than <see cref="MaxTextBytes"/>.</summary>
    public async Task<string?> ReadTextAsync(Guid userId, ManifestEntry entry, CancellationToken ct,
        IReadOnlyDictionary<string, string>? preview = null)
    {
        if (entry.Size > MaxTextBytes) return null;
        if (preview is not null && preview.TryGetValue(entry.Hash, out var previewed)) return previewed;
        await using var stream = await store.OpenAsync(userId, entry.Hash, ct)
            ?? throw new InvalidOperationException($"Blob {entry.Hash} is recorded but missing from the store.");
        using var buffer = new MemoryStream();
        await stream.CopyToAsync(buffer, ct);
        try { return StrictUtf8.GetString(buffer.GetBuffer(), 0, (int)buffer.Length); }
        catch (DecoderFallbackException) { return null; }
    }

    /// <summary>Like <see cref="BlobService.RecordAsync"/>, but a duplicate-key race detaches only its own row:
    /// callers may hold tracked entities. Callers must not have pending changes (SaveChanges flushes them).</summary>
    private async Task RecordAsync(Guid userId, string hash, long size, CancellationToken ct)
    {
        var row = new UserBlob { UserId = userId, Hash = hash, Size = size, CreatedUtc = time.GetUtcNow() };
        db.UserBlobs.Add(row);
        try { await db.SaveChangesAsync(ct); }
        catch (DbUpdateException)
        {
            db.Entry(row).State = EntityState.Detached;
            if (!await blobs.HasAsync(userId, hash, ct)) throw;
        }
    }
}
