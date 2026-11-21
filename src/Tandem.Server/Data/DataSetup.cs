using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;

namespace Tandem.Server.Data;

public static class DataSetup
{
    public const string SqliteFileName = "tandem.db";
    private const string LegacySqliteFileName = "emusync.db";

    /// <summary>
    /// Renames a database left by the project's old name (EmuSync), with its -wal/-shm/-journal files, unless a
    /// Tandem database already exists.
    /// </summary>
    public static void MoveLegacySqlite(string dir)
    {
        var legacy = Path.Combine(dir, LegacySqliteFileName);
        var current = Path.Combine(dir, SqliteFileName);
        if (!File.Exists(legacy) || File.Exists(current)) return;
        foreach (var suffix in new[] { "-wal", "-shm", "-journal" })
            if (File.Exists(legacy + suffix)) File.Move(legacy + suffix, current + suffix, overwrite: true);
        File.Move(legacy, current);
    }

    /// <summary>Registers <see cref="TandemDbContext"/> backed by the configured provider.</summary>
    public static IServiceCollection AddDatabase(this IServiceCollection services, ServerOptions options)
    {
        var provider = options.DatabaseProvider?.Trim() ?? "";
        if (provider.Length > 0 && !provider.Equals("sqlite", StringComparison.OrdinalIgnoreCase)
            && !provider.Equals("postgres", StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException(
                $"DATABASE_PROVIDER '{provider}' is not supported; use sqlite (the default) or postgres.");
        if (IsPostgres(options))
        {
            var connection = options.DatabaseConnection;
            if (string.IsNullOrWhiteSpace(connection))
                throw new InvalidOperationException("DATABASE_CONNECTION is required when DATABASE_PROVIDER is postgres.");
            services.AddDbContext<TandemDbContext, PostgresTandemDbContext>(o => o.UseNpgsql(connection));
        }
        else
        {
            var dir = Directory.CreateDirectory(options.DataDirectory).FullName;
            if (string.IsNullOrWhiteSpace(options.DatabaseConnection)) MoveLegacySqlite(dir);
            var path = string.IsNullOrWhiteSpace(options.DatabaseConnection)
                ? Path.Combine(dir, SqliteFileName)
                : options.DatabaseConnection;
            services.AddDbContext<TandemDbContext, SqliteTandemDbContext>(o => o.UseSqlite($"Data Source={path}"));
        }
        return services;
    }

    /// <summary>Applies the pending migrations of the active provider.</summary>
    public static void MigrateDatabase(this IServiceProvider services)
    {
        using var scope = services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        // WAL (persistent in the database file): a backup's long export transaction then does not block writers, nor
        // writers the export.
        if (db.Database.IsSqlite())
        {
            db.Database.ExecuteSqlRaw("PRAGMA journal_mode=WAL");
            TrySnapshotBeforeMigrations(db, scope.ServiceProvider.GetRequiredService<ILoggerFactory>().CreateLogger("Tandem.Server.Data.DataSetup"));
        }
        db.Database.Migrate();
    }

    public const int SnapshotsKept = 3;

    /// <summary>
    /// <see cref="SnapshotBeforeMigrations"/>, but a snapshot that can't be made (too little free space, the disk
    /// filling up during the copy, any I/O error) is logged as a critical warning and the server starts and migrates
    /// anyway: on a nearly full volume, a crash loop would keep it from ever reaching its "storage low" mode. Returns
    /// the snapshot's path, or null.
    /// </summary>
    public static string? TrySnapshotBeforeMigrations(TandemDbContext db, ILogger logger)
    {
        string? file = null;
        try
        {
            var source = db.Database.IsSqlite()
                ? new Microsoft.Data.Sqlite.SqliteConnectionStringBuilder(db.Database.GetConnectionString()).DataSource
                : null;
            if (!string.IsNullOrEmpty(source) && !source.Equals(":memory:", StringComparison.OrdinalIgnoreCase)) file = Path.GetFullPath(source);
            if (file is not null && File.Exists(file) && db.Database.GetPendingMigrations().Any())
            {
                var need = new FileInfo(file).Length;
                var free = FreeBytes(Path.GetDirectoryName(file)!);
                if (free is long f && f < need + MinSnapshotHeadroom)
                {
                    logger.LogCritical("Migrating the database without a snapshot: it needs about {Need} MB free beside {File}, " +
                                       "and only {Free} MB are. Free some space and keep a backup before the next upgrade.",
                        (need + MinSnapshotHeadroom) / 1_048_576, file, f / 1_048_576);
                    return null;
                }
            }
            return SnapshotBeforeMigrations(db);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or Microsoft.Data.Sqlite.SqliteException)
        {
            logger.LogCritical(ex, "Migrating the database without a snapshot: the copy before migrating failed ({Error}). " +
                                   "Keep a backup before the next upgrade.", ex.Message);
            return null;
        }
    }

    /// <summary>Free space a snapshot must leave beside the database, so the copy itself never fills the disk.</summary>
    private const long MinSnapshotHeadroom = 64L * 1024 * 1024;

    private static long? FreeBytes(string dir)
    {
        try { return new DriveInfo(dir).AvailableFreeSpace; }
        catch (Exception ex) when (ex is IOException or ArgumentException or UnauthorizedAccessException) { return null; }
    }

    /// <summary>
    /// SQLite only: before pending migrations change an existing database, copies it with <c>VACUUM INTO</c> to
    /// <c>tandem.db.pre-&lt;last applied migration id&gt;</c> beside it (a consistent copy even in WAL mode), and keeps the
    /// newest <see cref="SnapshotsKept"/> such copies. Nothing happens for a new database or when nothing is pending.
    /// Returns the snapshot's path, or null.
    /// </summary>
    public static string? SnapshotBeforeMigrations(TandemDbContext db)
    {
        if (!db.Database.IsSqlite()) return null;
        var applied = db.Database.GetAppliedMigrations().ToList();
        if (applied.Count == 0 || !db.Database.GetPendingMigrations().Any()) return null;
        var source = new Microsoft.Data.Sqlite.SqliteConnectionStringBuilder(db.Database.GetConnectionString()).DataSource;
        if (string.IsNullOrEmpty(source) || source.Equals(":memory:", StringComparison.OrdinalIgnoreCase)) return null;

        var file = Path.GetFullPath(source);
        var snapshot = $"{file}.pre-{applied[^1]}";
        if (!File.Exists(snapshot))
        {
            var sql = "VACUUM INTO '" + snapshot.Replace("'", "''", StringComparison.Ordinal) + "'";
            try
            {
                db.Database.ExecuteSqlRaw(sql);
            }
            catch
            {
                // A copy cut short (SQLITE_FULL) must not pass for a snapshot later.
                try { if (File.Exists(snapshot)) File.Delete(snapshot); } catch (IOException) { }
                throw;
            }
        }
        // Migration ids start with their timestamp, so the names sort oldest to newest.
        var dir = Path.GetDirectoryName(file)!;
        foreach (var old in Directory.EnumerateFiles(dir, Path.GetFileName(file) + ".pre-*")
                     .OrderByDescending(Path.GetFileName, StringComparer.Ordinal).Skip(SnapshotsKept))
            File.Delete(old);
        return snapshot;
    }

    private static bool IsPostgres(ServerOptions o) =>
        string.Equals(o.DatabaseProvider?.Trim(), "postgres", StringComparison.OrdinalIgnoreCase);
}

/// <summary>Design-time factories for `dotnet ef`; they never touch a real database.</summary>
public sealed class SqliteDesignTimeFactory : Microsoft.EntityFrameworkCore.Design.IDesignTimeDbContextFactory<SqliteTandemDbContext>
{
    public SqliteTandemDbContext CreateDbContext(string[] args) =>
        new(new DbContextOptionsBuilder<SqliteTandemDbContext>().UseSqlite("Data Source=design.db").Options);
}

public sealed class PostgresDesignTimeFactory : Microsoft.EntityFrameworkCore.Design.IDesignTimeDbContextFactory<PostgresTandemDbContext>
{
    public PostgresTandemDbContext CreateDbContext(string[] args) =>
        new(new DbContextOptionsBuilder<PostgresTandemDbContext>().UseNpgsql("Host=localhost;Database=design").Options);
}
