using Microsoft.EntityFrameworkCore;

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
        if (db.Database.IsSqlite()) db.Database.ExecuteSqlRaw("PRAGMA journal_mode=WAL");
        db.Database.Migrate();
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
