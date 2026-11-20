using System.Data;
using System.Globalization;
using System.Reflection;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;
using EmuSync.Server.Data;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata;

namespace EmuSync.Server.Backup;

/// <summary>A backup or restore failed for a reason worth showing as is (no secrets in the message).</summary>
public sealed class BackupException(string message, Exception? inner = null) : Exception(message, inner);

/// <summary>
/// The backup file: <c>emusync-backup-&lt;yyyyMMdd-HHmmss&gt;.tar.gz</c> (or <c>….tar.gz.enc</c>, see
/// <see cref="BackupCrypto"/>), a gzip'd PAX tar holding, in this order:
/// <list type="bullet">
/// <item><c>manifest.json</c>: <see cref="BackupManifest"/> (format version 1, server version, time, row counts).</item>
/// <item><c>db/&lt;table&gt;.jsonl</c>: every table, one JSON object per row (entity property names, enums as names,
/// times as ISO 8601), in foreign-key-safe order. Provider-independent: a SQLite backup restores into Postgres and back.</item>
/// <item><c>blobs/&lt;userId&gt;/&lt;hash&gt;</c>: the file of every UserBlob row (userId as 32 hex digits).</item>
/// </list>
/// </summary>
public static partial class BackupFormat
{
    public const int FormatVersion = 1;
    public const string Prefix = "emusync-backup-";
    public const string ManifestEntry = "manifest.json";
    public const string DbDir = "db/";
    public const string BlobDir = "blobs/";

    [GeneratedRegex(@"\Aemusync-backup-[0-9]{8}-[0-9]{6}\.tar\.gz(\.enc)?\z", RegexOptions.CultureInvariant)] // ASCII digits; \z, not "$"
    private static partial Regex NamePattern();

    /// <summary>Only names of this exact shape are ever listed, pruned or deleted at a target.</summary>
    public static bool IsBackupName(string? name) => name is not null && NamePattern().IsMatch(name);

    public static string NameFor(DateTimeOffset utc, bool encrypted) =>
        Prefix + utc.UtcDateTime.ToString("yyyyMMdd-HHmmss", CultureInfo.InvariantCulture) + (encrypted ? ".tar.gz.enc" : ".tar.gz");

    public static void RequireName(string name)
    {
        if (!IsBackupName(name)) throw new ArgumentException($"'{name}' is not a backup file name.", nameof(name));
    }

    public static readonly JsonSerializerOptions ManifestJson = new(JsonSerializerDefaults.Web) { WriteIndented = true };

    /// <summary>Rows: entity property names as declared, enums by name, unknown properties refused (a backup from a
    /// newer server with columns this one lacks fails loudly instead of losing them).</summary>
    public static readonly JsonSerializerOptions RowJson = new()
    {
        Converters = { new JsonStringEnumConverter() },
        UnmappedMemberHandling = JsonUnmappedMemberHandling.Disallow,
    };

    public static string ServerVersion { get; } =
        typeof(BackupFormat).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "unknown";
}

/// <param name="Tables">Row count per table file name.</param>
public sealed record BackupManifest(int Format, string ServerVersion, DateTimeOffset CreatedUtc, string Provider,
    Dictionary<string, long> Tables, long Blobs, long BlobBytes);

/// <summary>The logical, provider-independent export and import of every table.</summary>
public static class BackupDatabase
{
    private const int ImportBatch = 500;

    public sealed record TableFile(string Table, string Path, long Rows);

    public sealed record Export(List<TableFile> Tables, List<(Guid UserId, string Hash, long Size)> Blobs, string Provider);

    /// <summary>Every entity type in foreign-key-safe order (principals first; ties by table name).</summary>
    public static List<IEntityType> OrderedEntityTypes(IModel model)
    {
        var types = model.GetEntityTypes().Where(t => !t.IsOwned() && t.GetTableName() is not null)
            .OrderBy(t => t.GetTableName(), StringComparer.Ordinal).ToList();
        var done = new HashSet<IEntityType>();
        var ordered = new List<IEntityType>();
        while (ordered.Count < types.Count)
        {
            var next = types.FirstOrDefault(t => !done.Contains(t) && t.GetForeignKeys()
                .All(fk => fk.PrincipalEntityType == t || done.Contains(fk.PrincipalEntityType)))
                ?? throw new InvalidOperationException("The model has a foreign-key cycle; backup order is undefined.");
            done.Add(next);
            ordered.Add(next);
        }
        return ordered;
    }

    public static string FileName(IEntityType type) => BackupFormat.DbDir + type.GetTableName() + ".jsonl";

    /// <summary>
    /// Writes every table to <paramref name="dir"/> as JSON lines inside one read transaction (a snapshot:
    /// REPEATABLE READ on Postgres, a deferred read transaction on SQLite), and lists the blobs those rows hold.
    /// Rows written by uploads after the snapshot started are simply not in it.
    /// </summary>
    public static async Task<Export> ExportAsync(EmuSyncDbContext db, string dir, Func<string, string, string>? redactSetting,
        CancellationToken ct)
    {
        var provider = db.Database.IsNpgsql() ? "postgres" : "sqlite";
        await db.Database.OpenConnectionAsync(ct);
        try
        {
            await using var tx = await BeginSnapshotAsync(db, ct);
            var tables = new List<TableFile>();
            foreach (var type in OrderedEntityTypes(db.Model))
            {
                var path = Path.Combine(dir, type.GetTableName() + ".jsonl");
                var rows = await (Task<long>)ExportTableMethod.MakeGenericMethod(type.ClrType)
                    .Invoke(null, [db, path, redactSetting, ct])!;
                tables.Add(new TableFile(type.GetTableName()!, path, rows));
            }
            var blobs = (await db.UserBlobs.AsNoTracking().OrderBy(b => b.UserId).ThenBy(b => b.Hash)
                    .Select(b => new { b.UserId, b.Hash, b.Size }).ToListAsync(ct))
                .Select(b => (b.UserId, b.Hash, b.Size)).ToList();
            await tx.CommitAsync(ct);
            return new Export(tables, blobs, provider);
        }
        finally
        {
            await db.Database.CloseConnectionAsync();
        }
    }

    private static async Task<Microsoft.EntityFrameworkCore.Storage.IDbContextTransaction> BeginSnapshotAsync(EmuSyncDbContext db,
        CancellationToken ct)
    {
        if (db.Database.IsNpgsql())
            return await db.Database.BeginTransactionAsync(IsolationLevel.RepeatableRead, ct);
        // SQLite: a deferred transaction takes no write lock; its first read pins the snapshot until commit.
        var connection = (SqliteConnection)db.Database.GetDbConnection();
        var tx = connection.BeginTransaction(deferred: true);
        return await db.Database.UseTransactionAsync(tx, ct)
               ?? throw new InvalidOperationException("Could not start the backup's read transaction.");
    }

    private static readonly MethodInfo ExportTableMethod =
        typeof(BackupDatabase).GetMethod(nameof(ExportTableAsync), BindingFlags.NonPublic | BindingFlags.Static)!;

    private static async Task<long> ExportTableAsync<T>(EmuSyncDbContext db, string path, Func<string, string, string>? redactSetting,
        CancellationToken ct) where T : class
    {
        await using var file = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None, 81920, FileOptions.Asynchronous);
        await using var writer = new StreamWriter(file, new UTF8Encoding(false));
        long rows = 0;
        await foreach (var row in db.Set<T>().AsNoTracking().AsAsyncEnumerable().WithCancellation(ct))
        {
            if (row is ServerSetting s && redactSetting is not null) s.Value = redactSetting(s.Key, s.Value);
            await writer.WriteAsync(JsonSerializer.Serialize(row, BackupFormat.RowJson));
            await writer.WriteAsync('\n');
            rows++;
        }
        return rows;
    }

    private static readonly MethodInfo ImportTableMethod =
        typeof(BackupDatabase).GetMethod(nameof(ImportTableAsync), BindingFlags.NonPublic | BindingFlags.Static)!;

    /// <summary>Inserts the rows of one table file (the caller holds the transaction). Returns the row count.</summary>
    public static Task<long> ImportAsync(EmuSyncDbContext db, IEntityType type, Stream? jsonl, CancellationToken ct) =>
        jsonl is null
            ? Task.FromResult(0L)
            : (Task<long>)ImportTableMethod.MakeGenericMethod(type.ClrType).Invoke(null, [db, type.GetTableName(), jsonl, ct])!;

    private static async Task<long> ImportTableAsync<T>(EmuSyncDbContext db, string table, Stream jsonl, CancellationToken ct) where T : class
    {
        using var reader = new StreamReader(jsonl, new UTF8Encoding(false), detectEncodingFromByteOrderMarks: false, leaveOpen: true);
        long rows = 0;
        var pending = 0;
        while (await reader.ReadLineAsync(ct) is { } line)
        {
            if (line.Length == 0) continue;
            T row;
            try
            {
                row = JsonSerializer.Deserialize<T>(line, BackupFormat.RowJson)
                      ?? throw new JsonException("The row is null.");
            }
            catch (JsonException ex)
            {
                throw new BackupException($"Row {rows + 1} of table {table} could not be read: {ex.Message}", ex);
            }
            db.Set<T>().Add(row);
            rows++;
            if (++pending == ImportBatch)
            {
                await db.SaveChangesAsync(ct);
                db.ChangeTracker.Clear();
                pending = 0;
            }
        }
        if (pending > 0)
        {
            await db.SaveChangesAsync(ct);
            db.ChangeTracker.Clear();
        }
        return rows;
    }

    /// <summary>Postgres identity columns do not move when explicit ids are inserted: set each past the restored rows.</summary>
    public static async Task ResetIdentitiesAsync(EmuSyncDbContext db, CancellationToken ct)
    {
        if (!db.Database.IsNpgsql()) return;
        foreach (var type in db.Model.GetEntityTypes())
        {
            var key = type.FindPrimaryKey();
            if (key is not { Properties.Count: 1 }) continue;
            var property = key.Properties[0];
            if (property.ValueGenerated != ValueGenerated.OnAdd || property.ClrType != typeof(long) && property.ClrType != typeof(int)) continue;
            var table = type.GetTableName()!;
            var column = property.GetColumnName(StoreObjectIdentifier.Table(table, type.GetSchema()))!;
            var quotedTable = Quote(table);
            var quotedColumn = Quote(column);
            // setval(seq, max, true) when rows exist (next is max+1); setval(seq, 1, false) when empty (next is 1).
            // Identifiers come from the EF model, never from input.
            var sql = $"SELECT setval(pg_get_serial_sequence('{quotedTable.Replace("'", "''")}', '{column.Replace("'", "''")}'), " +
                      $"COALESCE(MAX({quotedColumn}), 1), MAX({quotedColumn}) IS NOT NULL) FROM {quotedTable}";
            await db.Database.ExecuteSqlRawAsync(sql, ct);
        }
    }

    private static string Quote(string identifier) => "\"" + identifier.Replace("\"", "\"\"") + "\"";
}

/// <summary>Counts the bytes passing through (read or written).</summary>
internal sealed class CountingStream(Stream inner) : Stream
{
    public long Count { get; private set; }
    public override bool CanRead => inner.CanRead;
    public override bool CanSeek => false;
    public override bool CanWrite => inner.CanWrite;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Flush() => inner.Flush();
    public override Task FlushAsync(CancellationToken ct) => inner.FlushAsync(ct);

    public override int Read(byte[] buffer, int offset, int count)
    {
        var n = inner.Read(buffer, offset, count);
        Count += n;
        return n;
    }

    public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default)
    {
        var n = await inner.ReadAsync(buffer, ct);
        Count += n;
        return n;
    }

    public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
        ReadAsync(buffer.AsMemory(offset, count), ct).AsTask();

    public override void Write(byte[] buffer, int offset, int count)
    {
        inner.Write(buffer, offset, count);
        Count += count;
    }

    public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken ct = default)
    {
        await inner.WriteAsync(buffer, ct);
        Count += buffer.Length;
    }

    public override Task WriteAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
        WriteAsync(buffer.AsMemory(offset, count), ct).AsTask();
}

/// <summary>Reads <paramref name="prefix"/> and then the rest of <paramref name="rest"/> (for sniffing a file's first bytes).</summary>
internal sealed class PrefixedStream(byte[] prefix, Stream rest) : Stream
{
    private int _pos;
    public override bool CanRead => true;
    public override bool CanSeek => false;
    public override bool CanWrite => false;
    public override long Length => throw new NotSupportedException();
    public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
    public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
    public override void SetLength(long value) => throw new NotSupportedException();
    public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    public override void Flush() { }

    public override int Read(byte[] buffer, int offset, int count) => Read(buffer.AsSpan(offset, count));

    public override int Read(Span<byte> buffer)
    {
        if (_pos < prefix.Length)
        {
            var n = Math.Min(buffer.Length, prefix.Length - _pos);
            prefix.AsSpan(_pos, n).CopyTo(buffer);
            _pos += n;
            return n;
        }
        return rest.Read(buffer);
    }

    public override Task<int> ReadAsync(byte[] buffer, int offset, int count, CancellationToken ct) =>
        ReadAsync(buffer.AsMemory(offset, count), ct).AsTask();

    public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default) =>
        _pos < prefix.Length ? ValueTask.FromResult(Read(buffer.Span)) : rest.ReadAsync(buffer, ct);
}
