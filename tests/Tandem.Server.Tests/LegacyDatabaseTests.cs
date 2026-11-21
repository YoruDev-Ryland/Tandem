using Tandem.Server.Data;

namespace Tandem.Server.Tests;

/// <summary>The project was called EmuSync before; a data directory from then keeps its database.</summary>
public class LegacyDatabaseTests
{
    [Fact]
    public void An_emusync_database_and_its_journal_files_move_to_the_new_name()
    {
        var dir = Directory.CreateTempSubdirectory("tandem-legacy-").FullName;
        try
        {
            File.WriteAllText(Path.Combine(dir, "emusync.db"), "db");
            File.WriteAllText(Path.Combine(dir, "emusync.db-wal"), "wal");
            DataSetup.MoveLegacySqlite(dir);
            Assert.Equal("db", File.ReadAllText(Path.Combine(dir, "tandem.db")));
            Assert.Equal("wal", File.ReadAllText(Path.Combine(dir, "tandem.db-wal")));
            Assert.False(File.Exists(Path.Combine(dir, "emusync.db")));
        }
        finally { Directory.Delete(dir, true); }
    }

    [Fact]
    public void An_existing_tandem_database_is_never_replaced()
    {
        var dir = Directory.CreateTempSubdirectory("tandem-legacy-").FullName;
        try
        {
            File.WriteAllText(Path.Combine(dir, "emusync.db"), "old");
            File.WriteAllText(Path.Combine(dir, "tandem.db"), "new");
            DataSetup.MoveLegacySqlite(dir);
            Assert.Equal("new", File.ReadAllText(Path.Combine(dir, "tandem.db")));
            Assert.True(File.Exists(Path.Combine(dir, "emusync.db")));
        }
        finally { Directory.Delete(dir, true); }
    }
}
