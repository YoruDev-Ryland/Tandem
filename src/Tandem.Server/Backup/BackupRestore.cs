using System.Formats.Tar;
using System.IO.Compression;
using System.Text.Json;
using Tandem.Server.Blobs;
using Tandem.Server.Data;
using Tandem.Server.Http;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace Tandem.Server.Backup;

public sealed record RestoreResult(int Users, long Rows, long Blobs);

/// <summary>
/// Restores a backup file into an <b>empty</b> server: no users in the database and nothing in DATA_DIR/blobs (the
/// server should not be running). Rows go in, in the archive's foreign-key-safe order, inside one transaction; every
/// blob's SHA-256 is checked against its name as it is written; row and blob counts are checked against the manifest
/// and the UserBlob rows; for an encrypted file the final chunk must authenticate. Any failure rolls the database back
/// and removes the blobs written so far, leaving the server as empty as it was.
/// </summary>
public static class BackupRestore
{
    public static async Task<RestoreResult> RestoreAsync(IServiceProvider services, Stream file, string? passphrase, CancellationToken ct)
    {
        using var scope = services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        var store = scope.ServiceProvider.GetRequiredService<IBlobStore>();
        var blobRoot = Path.Combine(scope.ServiceProvider.GetRequiredService<IOptions<ServerOptions>>().Value.DataDirectory, "blobs");

        if (await db.Users.AnyAsync(ct))
            throw new BackupException("This server already has users. Restore only into an empty database (a new data directory or database).");
        if (Directory.Exists(blobRoot) && Directory.EnumerateFileSystemEntries(blobRoot).Any())
            throw new BackupException($"The blob directory {blobRoot} is not empty. Restore only into an empty data directory.");

        var magic = new byte[BackupCrypto.Magic.Length];
        var sniffed = await file.ReadAtLeastAsync(magic, magic.Length, throwOnEndOfStream: false, ct);
        Stream body = new PrefixedStream(magic[..sniffed], file);
        if (BackupCrypto.HasMagic(magic.AsSpan(0, sniffed)))
        {
            if (string.IsNullOrEmpty(passphrase))
                throw new BackupException("This backup is encrypted: give its passphrase with --passphrase.");
            body = BackupCrypto.Decrypt(body, passphrase);
        }

        var success = false;
        try
        {
            await using var tx = await db.Database.BeginTransactionAsync(ct);
            // A fresh server may hold its own server settings or run history: the backup's replace them.
            await db.ServerSettings.ExecuteDeleteAsync(ct);
            await db.BackupRuns.ExecuteDeleteAsync(ct);
            var result = await ReadArchiveAsync(db, store, body, ct);
            await BackupDatabase.ResetIdentitiesAsync(db, ct);
            await tx.CommitAsync(ct);
            success = true;
            return result;
        }
        catch (InvalidDataException ex)
        {
            throw new BackupException($"The backup file is damaged or truncated: {ex.Message}", ex);
        }
        catch (EndOfStreamException ex)
        {
            throw new BackupException("The backup file is truncated.", ex);
        }
        finally
        {
            if (!success)
            {
                try { if (Directory.Exists(blobRoot)) Directory.Delete(blobRoot, recursive: true); }
                catch (IOException) { }
                catch (UnauthorizedAccessException) { }
            }
        }
    }

    private static async Task<RestoreResult> ReadArchiveAsync(TandemDbContext db, IBlobStore store, Stream body, CancellationToken ct)
    {
        var byTable = db.Model.GetEntityTypes().Where(t => t.GetTableName() is not null)
            .ToDictionary(t => BackupDatabase.FileName(t), t => t, StringComparer.Ordinal);
        await using var gzip = new GZipStream(body, CompressionMode.Decompress, leaveOpen: true);
        await using var tar = new TarReader(gzip, leaveOpen: true);

        BackupManifest? manifest = null;
        var rows = new Dictionary<string, long>(StringComparer.Ordinal);
        HashSet<(Guid, string)>? expectedBlobs = null;
        var restored = new HashSet<(Guid, string)>();

        while (await tar.GetNextEntryAsync(copyData: false, ct) is { } entry)
        {
            if (entry.EntryType is not (TarEntryType.RegularFile or TarEntryType.V7RegularFile))
                throw new BackupException($"Unexpected entry {entry.Name} in the backup.");
            var name = entry.Name;
            if (manifest is null)
            {
                if (name != BackupFormat.ManifestEntry) throw new BackupException("This is not an Tandem backup (no manifest.json first).");
                manifest = await ReadManifestAsync(entry.DataStream, ct);
            }
            else if (name.StartsWith(BackupFormat.DbDir, StringComparison.Ordinal))
            {
                if (expectedBlobs is not null) throw new BackupException($"Table {name} comes after the blobs; the backup is damaged.");
                if (!byTable.TryGetValue(name, out var type))
                    throw new BackupException($"The backup has table {name}, which this server version does not know. Use a newer server.");
                if (rows.ContainsKey(name)) throw new BackupException($"The backup holds {name} twice.");
                rows[name] = await BackupDatabase.ImportAsync(db, type, entry.DataStream, ct);
            }
            else if (name.StartsWith(BackupFormat.BlobDir, StringComparison.Ordinal))
            {
                expectedBlobs ??= (await db.UserBlobs.AsNoTracking().Select(b => new { b.UserId, b.Hash }).ToListAsync(ct))
                    .Select(b => (b.UserId, b.Hash)).ToHashSet();
                var (userId, hash) = ParseBlobName(name);
                if (!expectedBlobs.Contains((userId, hash))) throw new BackupException($"The backup holds blob {name}, which no row records.");
                if (!restored.Add((userId, hash))) throw new BackupException($"The backup holds blob {name} twice.");
                try
                {
                    await store.PutAsync(userId, hash, entry.DataStream ?? Stream.Null, ct);
                }
                catch (ApiException ex) when (ex.Code == "hash_mismatch")
                {
                    throw new BackupException($"Blob {name} does not match its SHA-256; the backup is damaged.", ex);
                }
            }
            else
            {
                throw new BackupException($"Unexpected entry {name} in the backup.");
            }
        }

        // Read to the very end: for an encrypted file this is where a missing final chunk shows.
        await gzip.CopyToAsync(Stream.Null, ct);
        await body.CopyToAsync(Stream.Null, ct);

        if (manifest is null) throw new BackupException("The backup is empty.");
        foreach (var (table, count) in manifest.Tables)
        {
            var file = BackupFormat.DbDir + table + ".jsonl";
            if (rows.GetValueOrDefault(file, -1) != count)
                throw new BackupException($"Table {table} should have {count} rows but the backup holds {rows.GetValueOrDefault(file)}; it is truncated or damaged.");
        }
        expectedBlobs ??= (await db.UserBlobs.AsNoTracking().Select(b => new { b.UserId, b.Hash }).ToListAsync(ct))
            .Select(b => (b.UserId, b.Hash)).ToHashSet();
        if (restored.Count != manifest.Blobs || expectedBlobs.Count != restored.Count)
            throw new BackupException(
                $"The backup should hold {manifest.Blobs} blobs ({expectedBlobs.Count} recorded) but holds {restored.Count}; it is truncated or damaged.");

        return new RestoreResult(await db.Users.CountAsync(ct), rows.Values.Sum(), restored.Count);
    }

    private static async Task<BackupManifest> ReadManifestAsync(Stream? data, CancellationToken ct)
    {
        BackupManifest? manifest;
        try
        {
            manifest = data is null ? null : await JsonSerializer.DeserializeAsync<BackupManifest>(data, BackupFormat.ManifestJson, ct);
        }
        catch (JsonException ex)
        {
            throw new BackupException("The backup's manifest.json cannot be read.", ex);
        }
        if (manifest is null) throw new BackupException("The backup's manifest.json is empty.");
        if (manifest.Format != BackupFormat.FormatVersion)
            throw new BackupException($"Backup format {manifest.Format} is not supported (this server reads format {BackupFormat.FormatVersion}).");
        manifest = manifest with { Tables = manifest.Tables ?? [] };
        return manifest;
    }

    private static (Guid UserId, string Hash) ParseBlobName(string name)
    {
        var parts = name[BackupFormat.BlobDir.Length..].Split('/');
        if (parts.Length != 2 || !Guid.TryParseExact(parts[0], "N", out var userId) || !BlobHash.IsValid(parts[1]))
            throw new BackupException($"Unexpected entry {name} in the backup.");
        return (userId, parts[1]);
    }
}
