using Tandem.Server.Data;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Migrations;

namespace Tandem.Server.Tests.Backup;

/// <summary>F-04 item 2: before pending migrations run on SQLite, the database is copied with VACUUM INTO.</summary>
public class MigrationSnapshotTests
{
    private static SqliteTandemDbContext Open(string path) =>
        new(new DbContextOptionsBuilder<SqliteTandemDbContext>().UseSqlite($"Data Source={path};Pooling=False").Options);

    [Fact]
    public void An_older_database_is_snapshotted_before_it_is_migrated_and_only_the_newest_three_stay()
    {
        var dir = Directory.CreateTempSubdirectory("tandem-snapshot-").FullName;
        try
        {
            var path = Path.Combine(dir, "tandem.db");
            string previous;
            using (var db = Open(path))
            {
                var all = db.Database.GetMigrations().ToList();
                previous = all[^2];
                db.GetService<IMigrator>().Migrate(previous);
                db.Database.ExecuteSqlRaw("INSERT INTO \"Users\" (\"Id\", \"Email\", \"PasswordHash\", \"IsAdmin\", \"CreatedUtc\") " +
                                          "VALUES ('6f9619ff-8b86-d011-b42d-00c04fc964ff', 'a@example.com', 'x', 1, 0)");
            }
            foreach (var old in new[] { "20200101000000_A", "20200102000000_B", "20200103000000_C" })
                File.WriteAllText($"{path}.pre-{old}", "old");

            string? snapshot;
            using (var db = Open(path))
            {
                snapshot = DataSetup.SnapshotBeforeMigrations(db);
                db.Database.Migrate();
                Assert.Null(DataSetup.SnapshotBeforeMigrations(db)); // nothing pending any more
            }

            Assert.Equal($"{path}.pre-{previous}", snapshot);
            using (var copy = new SqliteConnection($"Data Source={snapshot};Mode=ReadOnly;Pooling=False"))
            {
                copy.Open();
                using var cmd = copy.CreateCommand();
                cmd.CommandText = "SELECT \"MigrationId\" FROM \"__EFMigrationsHistory\" ORDER BY \"MigrationId\" DESC LIMIT 1";
                Assert.Equal(previous, (string?)cmd.ExecuteScalar());
                cmd.CommandText = "SELECT COUNT(*) FROM \"Users\"";
                Assert.Equal(1L, (long)cmd.ExecuteScalar()!);
            }
            var kept = Directory.EnumerateFiles(dir, "tandem.db.pre-*").Select(Path.GetFileName).Order(StringComparer.Ordinal).ToList();
            Assert.Equal(["tandem.db.pre-20200102000000_B", "tandem.db.pre-20200103000000_C", $"tandem.db.pre-{previous}"], kept);
        }
        finally
        {
            SqliteConnection.ClearAllPools();
            Directory.Delete(dir, true);
        }
    }

    [Fact]
    public void A_failed_snapshot_is_logged_loudly_and_the_migration_still_runs()
    {
        var dir = Directory.CreateTempSubdirectory("tandem-snapshot-").FullName;
        try
        {
            var path = Path.Combine(dir, "tandem.db");
            string previous;
            using (var db = Open(path))
            {
                previous = db.Database.GetMigrations().ToList()[^2];
                db.GetService<IMigrator>().Migrate(previous);
            }
            var logger = new ListLogger();
            using (var db = Open(path))
            {
                // A snapshot that cannot be written (a full disk, here a path that is a directory) is a warning, not a crash.
                Directory.CreateDirectory($"{path}.pre-{previous}");
                Assert.Null(DataSetup.TrySnapshotBeforeMigrations(db, logger));
                db.Database.Migrate();
                Assert.Empty(db.Database.GetPendingMigrations());
            }
            var warning = Assert.Single(logger.Entries, e => e.Level == Microsoft.Extensions.Logging.LogLevel.Critical);
            Assert.Contains("without a snapshot", warning.Message);
        }
        finally
        {
            SqliteConnection.ClearAllPools();
            Directory.Delete(dir, true);
        }
    }

    private sealed class ListLogger : Microsoft.Extensions.Logging.ILogger
    {
        public List<(Microsoft.Extensions.Logging.LogLevel Level, string Message)> Entries { get; } = [];
        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;
        public bool IsEnabled(Microsoft.Extensions.Logging.LogLevel logLevel) => true;
        public void Log<TState>(Microsoft.Extensions.Logging.LogLevel logLevel, Microsoft.Extensions.Logging.EventId eventId, TState state,
            Exception? exception, Func<TState, Exception?, string> formatter) => Entries.Add((logLevel, formatter(state, exception)));
    }

    [Fact]
    public void A_new_database_gets_no_snapshot()
    {
        using var f = new ServerFactory();
        f.Client();
        Assert.Empty(Directory.EnumerateFiles(f.DataDirectory, "tandem.db.pre-*"));
    }
}
