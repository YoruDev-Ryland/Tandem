using Microsoft.EntityFrameworkCore;

namespace EmuSync.Server.Data;

public static class DataSetup
{
    public const string SqliteFileName = "emusync.db";

    /// <summary>Registers <see cref="EmuSyncDbContext"/> backed by the configured provider.</summary>
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
            services.AddDbContext<EmuSyncDbContext, PostgresEmuSyncDbContext>(o => o.UseNpgsql(connection));
        }
        else
        {
            var dir = Directory.CreateDirectory(options.DataDirectory).FullName;
            var path = string.IsNullOrWhiteSpace(options.DatabaseConnection)
                ? Path.Combine(dir, SqliteFileName)
                : options.DatabaseConnection;
            services.AddDbContext<EmuSyncDbContext, SqliteEmuSyncDbContext>(o => o.UseSqlite($"Data Source={path}"));
        }
        return services;
    }

    /// <summary>Applies the pending migrations of the active provider.</summary>
    public static void MigrateDatabase(this IServiceProvider services)
    {
        using var scope = services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>();
        // WAL (persistent in the database file): a backup's long export transaction then does not block writers, nor
        // writers the export.
        if (db.Database.IsSqlite()) db.Database.ExecuteSqlRaw("PRAGMA journal_mode=WAL");
        db.Database.Migrate();
    }

    private static bool IsPostgres(ServerOptions o) =>
        string.Equals(o.DatabaseProvider?.Trim(), "postgres", StringComparison.OrdinalIgnoreCase);
}

/// <summary>Design-time factories for `dotnet ef`; they never touch a real database.</summary>
public sealed class SqliteDesignTimeFactory : Microsoft.EntityFrameworkCore.Design.IDesignTimeDbContextFactory<SqliteEmuSyncDbContext>
{
    public SqliteEmuSyncDbContext CreateDbContext(string[] args) =>
        new(new DbContextOptionsBuilder<SqliteEmuSyncDbContext>().UseSqlite("Data Source=design.db").Options);
}

public sealed class PostgresDesignTimeFactory : Microsoft.EntityFrameworkCore.Design.IDesignTimeDbContextFactory<PostgresEmuSyncDbContext>
{
    public PostgresEmuSyncDbContext CreateDbContext(string[] args) =>
        new(new DbContextOptionsBuilder<PostgresEmuSyncDbContext>().UseNpgsql("Host=localhost;Database=design").Options);
}
