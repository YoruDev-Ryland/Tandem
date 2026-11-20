using System.Security.Cryptography;
using System.Text.RegularExpressions;
using EmuSync.Server.Data;
using EmuSync.Server.Http;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace EmuSync.Server.Blobs;

public interface IBlobStore
{
    /// <summary>Streams content to disk while hashing; throws hash_mismatch (400) if it differs. Returns the size.</summary>
    Task<long> PutAsync(Guid userId, string expectedHash, Stream content, CancellationToken ct);
    Task<Stream?> OpenAsync(Guid userId, string hash, CancellationToken ct);
    Task<bool> ExistsAsync(Guid userId, string hash, CancellationToken ct);
    Task DeleteAsync(Guid userId, string hash, CancellationToken ct);
}

public static partial class BlobHash
{
    [GeneratedRegex("^[0-9a-f]{64}$")]
    private static partial Regex Pattern();

    public static bool IsValid(string? hash) => hash is not null && Pattern().IsMatch(hash);

    public static void Require(string? hash)
    {
        if (!IsValid(hash)) throw new ApiException(400, "bad_hash", "Hash must be 64 lowercase hex characters.");
    }
}

public sealed class BlobStore(IOptions<ServerOptions> options) : IBlobStore
{
    private string PathFor(Guid userId, string hash)
    {
        BlobHash.Require(hash);
        return Path.Combine(options.Value.DataDirectory, "blobs", userId.ToString("N"), hash[..2], hash[2..4], hash);
    }

    public async Task<long> PutAsync(Guid userId, string expectedHash, Stream content, CancellationToken ct)
    {
        var path = PathFor(userId, expectedHash);
        var exists = File.Exists(path);
        var tmp = $"{path}.tmp-{Guid.NewGuid():N}";
        var moved = false;
        FileStream? file = null;
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            // An existing blob is not rewritten: the body is still drained and verified.
            file = exists ? null : new FileStream(tmp, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                81920, FileOptions.Asynchronous | FileOptions.SequentialScan);

            using var hasher = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            var buffer = new byte[81920];
            long total = 0;
            int read;
            while ((read = await content.ReadAsync(buffer, ct)) > 0)
            {
                hasher.AppendData(buffer, 0, read);
                if (file is not null) await file.WriteAsync(buffer.AsMemory(0, read), ct);
                total += read;
            }

            if (file is not null) { await file.FlushAsync(ct); await file.DisposeAsync(); file = null; }
            if (!string.Equals(Convert.ToHexStringLower(hasher.GetHashAndReset()), expectedHash, StringComparison.Ordinal))
                throw new ApiException(400, "hash_mismatch", "Content does not match the hash.");

            if (!exists)
            {
                File.Move(tmp, path, overwrite: true); // same directory: atomic; same content if racing
                moved = true;
            }
            return total;
        }
        finally
        {
            if (file is not null) await file.DisposeAsync();
            if (!moved)
            {
                try { File.Delete(tmp); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            }
        }
    }

    public Task<Stream?> OpenAsync(Guid userId, string hash, CancellationToken ct)
    {
        var path = PathFor(userId, hash);
        Stream? stream = File.Exists(path)
            ? new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, 81920,
                FileOptions.Asynchronous | FileOptions.SequentialScan)
            : null;
        return Task.FromResult(stream);
    }

    public Task<bool> ExistsAsync(Guid userId, string hash, CancellationToken ct) =>
        Task.FromResult(File.Exists(PathFor(userId, hash)));

    public Task DeleteAsync(Guid userId, string hash, CancellationToken ct)
    {
        File.Delete(PathFor(userId, hash));
        return Task.CompletedTask;
    }
}

public sealed class BlobService(EmuSyncDbContext db, TimeProvider time)
{
    public async Task RecordAsync(Guid userId, string hash, long size, CancellationToken ct = default)
    {
        if (await db.UserBlobs.AnyAsync(b => b.UserId == userId && b.Hash == hash, ct)) return;
        db.UserBlobs.Add(new UserBlob { UserId = userId, Hash = hash, Size = size, CreatedUtc = time.GetUtcNow() });
        try { await db.SaveChangesAsync(ct); }
        catch (DbUpdateException)
        {
            // Duplicate-key race: another request recorded the same blob first.
            db.ChangeTracker.Clear();
            if (!await db.UserBlobs.AnyAsync(b => b.UserId == userId && b.Hash == hash, ct)) throw;
        }
    }

    public async Task<List<string>> MissingAsync(Guid userId, IEnumerable<string> hashes, CancellationToken ct = default)
    {
        var wanted = hashes.Distinct().ToList();
        var have = await db.UserBlobs.Where(b => b.UserId == userId && wanted.Contains(b.Hash))
            .Select(b => b.Hash).ToListAsync(ct);
        return wanted.Except(have).ToList();
    }

    public async Task RequireAllAsync(Guid userId, IEnumerable<string> hashes, CancellationToken ct = default)
    {
        var missing = await MissingAsync(userId, hashes, ct);
        if (missing.Count > 0)
            throw new ApiException(400, "missing_blobs", "Missing blobs: " + string.Join(",", missing));
    }

    /// <summary>A manifest's sizes must match the stored blobs, or the same content would hash differently.</summary>
    public async Task RequireSizesAsync(Guid userId, IReadOnlyList<EmuSync.Protocol.ManifestEntry> manifest, CancellationToken ct = default)
    {
        var hashes = manifest.Select(e => e.Hash).Distinct().ToList();
        var sizes = await db.UserBlobs.Where(b => b.UserId == userId && hashes.Contains(b.Hash))
            .ToDictionaryAsync(b => b.Hash, b => b.Size, ct);
        foreach (var e in manifest)
        {
            if (!sizes.TryGetValue(e.Hash, out var size))
                throw new ApiException(400, "missing_blobs", "Missing blobs: " + e.Hash);
            if (size != e.Size)
                throw new ApiException(400, "bad_manifest", $"Size of '{e.Path}' does not match its blob.");
        }
    }

    public Task<bool> HasAsync(Guid userId, string hash, CancellationToken ct = default) =>
        db.UserBlobs.AnyAsync(b => b.UserId == userId && b.Hash == hash, ct);
}
