using System.Diagnostics;
using System.Net;
using EmuSync.Protocol;
using EmuSync.Server.Data;
using EmuSync.Server.Tests.Infrastructure;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Testcontainers.PostgreSql;

namespace EmuSync.Server.Tests;

public class DatabaseTests
{
    [Fact]
    public async Task Sqlite_database_is_created_in_data_directory()
    {
        using var factory = new ServerFactory();
        var response = await factory.Client().GetAsync("/healthz");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.True(File.Exists(Path.Combine(factory.DataDirectory, "emusync.db")));
    }

    [Theory]
    [InlineData("mysql")]
    [InlineData("sqlserver")]
    [InlineData("sqlite3")]
    public void Unknown_database_provider_fails_startup_with_a_clear_message(string provider)
    {
        var dir = Directory.CreateTempSubdirectory("emusync-test-").FullName;
        try
        {
            var ex = Assert.Throws<InvalidOperationException>(() =>
                new ServiceCollection().AddDatabase(new ServerOptions { DatabaseProvider = provider, DataDirectory = dir }));
            Assert.Contains("DATABASE_PROVIDER", ex.Message);
            Assert.Contains("sqlite", ex.Message);
            Assert.Contains("postgres", ex.Message);
        }
        finally { Directory.Delete(dir, recursive: true); }
    }

    [Fact]
    public void Unknown_database_provider_stops_the_server()
    {
        using var factory = new ServerFactory();
        factory.Settings["EmuSync:DatabaseProvider"] = "mongodb";
        var ex = Assert.ThrowsAny<Exception>(() => factory.Client());
        Assert.Contains("DATABASE_PROVIDER 'mongodb' is not supported", ex.ToString());
    }

    [Theory]
    [InlineData("SQLite")]
    [InlineData("sqlite")]
    [InlineData("")] // unset or empty: the default
    public void Database_provider_is_case_insensitive(string provider)
    {
        var dir = Directory.CreateTempSubdirectory("emusync-test-").FullName;
        try
        {
            var services = new ServiceCollection().AddDatabase(new ServerOptions { DatabaseProvider = provider, DataDirectory = dir });
            Assert.Contains(services, s => s.ServiceType == typeof(EmuSyncDbContext));
        }
        finally { Directory.Delete(dir, recursive: true); }
    }

    [Fact]
    public async Task Missing_data_directory_is_created()
    {
        using var factory = new ServerFactory();
        var nested = Path.Combine(factory.DataDirectory, "a", "b");
        factory.Settings["EmuSync:DataDirectory"] = nested;
        await factory.Client().GetAsync("/healthz");

        Assert.True(File.Exists(Path.Combine(nested, "emusync.db")));
    }

    [Fact]
    public async Task SaveItem_identity_is_unique()
    {
        using var factory = new ServerFactory();
        await factory.Client().GetAsync("/healthz");
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>();

        var user = new User { Id = Guid.NewGuid(), Email = "a@b.c", PasswordHash = "x", CreatedUtc = factory.Time.GetUtcNow() };
        db.Users.Add(user);
        db.SaveItems.Add(NewItem(user.Id));
        db.SaveItems.Add(NewItem(user.Id));

        await Assert.ThrowsAsync<DbUpdateException>(() => db.SaveChangesAsync());
    }

    [Fact]
    public async Task Times_round_trip_and_order_on_sqlite()
    {
        using var factory = new ServerFactory();
        await factory.Client().GetAsync("/healthz");
        var t1 = DateTimeOffset.Parse("2026-01-01T00:00:00Z");
        var t2 = DateTimeOffset.Parse("2026-06-01T00:00:00Z");

        using (var scope = factory.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>();
            var user = new User { Id = Guid.NewGuid(), Email = "a@b.c", PasswordHash = "x", CreatedUtc = t1 };
            db.Users.Add(user);
            db.ActivityEvents.Add(new Data.ActivityEvent { UserId = user.Id, Utc = t2, Kind = "k", Message = "late" });
            db.ActivityEvents.Add(new Data.ActivityEvent { UserId = user.Id, Utc = t1, Kind = "k", Message = "early" });
            await db.SaveChangesAsync();
        }

        using (var scope = factory.Services.CreateScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>();
            var events = await db.ActivityEvents.Where(e => e.Utc > t1).ToListAsync();
            Assert.Single(events);
            Assert.Equal(["early", "late"], (await db.ActivityEvents.OrderBy(e => e.Utc).ToListAsync()).Select(e => e.Message));
            Assert.Equal(t2, events[0].Utc);
        }
    }

    [Fact]
    public async Task Enums_are_stored_as_strings()
    {
        using var factory = new ServerFactory();
        await factory.Client().GetAsync("/healthz");
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>();
        var user = new User { Id = Guid.NewGuid(), Email = "a@b.c", PasswordHash = "x", CreatedUtc = factory.Time.GetUtcNow() };
        db.Users.Add(user);
        db.SaveItems.Add(NewItem(user.Id));
        await db.SaveChangesAsync();

        var kind = await db.Database.SqlQueryRaw<string>("SELECT Kind AS Value FROM SaveItems").SingleAsync();
        Assert.Equal("State", kind);
    }

    private static User NewUser(string email = "a@b.c") => new()
    {
        Id = Guid.NewGuid(), Email = email, PasswordHash = "x", CreatedUtc = DateTimeOffset.Parse("2026-01-01T00:00:00Z"),
    };

    private static Device NewDevice(Guid userId) => new()
    {
        Id = Guid.NewGuid(), UserId = userId, Name = "d", Platform = "android", AppVersion = "1",
        KeyHash = Guid.NewGuid().ToString(), CreatedUtc = DateTimeOffset.Parse("2026-01-01T00:00:00Z"),
    };

    private static async Task<EmuSyncDbContext> StartAsync(ServerFactory factory, IServiceScope[] holder)
    {
        await factory.Client().GetAsync("/healthz");
        holder[0] = factory.Services.CreateScope();
        return holder[0].ServiceProvider.GetRequiredService<EmuSyncDbContext>();
    }

    [Fact]
    public async Task Email_is_stored_lower_cased_and_unique_case_insensitively()
    {
        using var factory = new ServerFactory();
        var h = new IServiceScope[1];
        var db = await StartAsync(factory, h);
        using var _ = h[0];
        db.Users.Add(NewUser("A@B.c"));
        await db.SaveChangesAsync();

        Assert.Equal("a@b.c", await db.Database.SqlQueryRaw<string>("SELECT Email AS Value FROM Users").SingleAsync());
        Assert.NotNull(await db.Users.SingleOrDefaultAsync(u => u.Email == "A@B.C"));

        db.Users.Add(NewUser("a@b.C"));
        await Assert.ThrowsAsync<DbUpdateException>(() => db.SaveChangesAsync());
    }

    [Theory]
    [InlineData(false, false)]
    [InlineData(true, true)]
    public async Task ConfigBundle_requires_exactly_one_owner(bool device, bool group)
    {
        using var factory = new ServerFactory();
        var h = new IServiceScope[1];
        var db = await StartAsync(factory, h);
        using var _ = h[0];
        var user = NewUser();
        var dev = NewDevice(user.Id);
        var grp = new SyncGroup { Id = Guid.NewGuid(), UserId = user.Id, EmulatorId = "eden", Name = "g", BundleId = Guid.NewGuid() };
        db.AddRange(user, dev, grp);
        await db.SaveChangesAsync();

        db.ConfigBundles.Add(new ConfigBundle
        {
            Id = Guid.NewGuid(), UserId = user.Id, EmulatorId = "eden",
            OwnerDeviceId = device ? dev.Id : null, GroupId = group ? grp.Id : null,
        });
        await Assert.ThrowsAsync<DbUpdateException>(() => db.SaveChangesAsync());
    }

    [Fact]
    public async Task ConfigBundle_with_one_owner_is_accepted()
    {
        using var factory = new ServerFactory();
        var h = new IServiceScope[1];
        var db = await StartAsync(factory, h);
        using var _ = h[0];
        var user = NewUser();
        var dev = NewDevice(user.Id);
        db.AddRange(user, dev);
        db.ConfigBundles.Add(new ConfigBundle { Id = Guid.NewGuid(), UserId = user.Id, EmulatorId = "eden", OwnerDeviceId = dev.Id });
        await db.SaveChangesAsync();
    }

    [Fact]
    public async Task Deleting_user_removes_all_their_rows()
    {
        using var factory = new ServerFactory();
        var h = new IServiceScope[1];
        var db = await StartAsync(factory, h);
        using var _ = h[0];
        var now = DateTimeOffset.Parse("2026-02-01T00:00:00Z");

        var user1 = Seed(db, "one@x.y", now);
        var user2 = Seed(db, "two@x.y", now);
        await db.SaveChangesAsync();

        db.Users.Remove(user1);
        await db.SaveChangesAsync();
        db.ChangeTracker.Clear();

        Assert.Equal(1, await db.Users.CountAsync());
        Assert.Equal(1, await db.Devices.CountAsync());
        Assert.Equal(1, await db.SaveItems.CountAsync());
        Assert.Equal(1, await db.SaveVersions.CountAsync());
        Assert.Equal(2, await db.ConfigBundles.CountAsync());
        Assert.Equal(1, await db.SyncGroups.CountAsync());
        Assert.Equal(1, await db.DeviceConfigs.CountAsync());
        Assert.Equal(1, await db.Deliveries.CountAsync());
        Assert.Equal(1, await db.InstallRecords.CountAsync());
        Assert.Equal(1, await db.ActivityEvents.CountAsync());
        Assert.Equal(user2.Id, (await db.Devices.SingleAsync()).UserId);
    }

    private static User Seed(EmuSyncDbContext db, string email, DateTimeOffset now)
    {
        var user = NewUser(email);
        var dev = NewDevice(user.Id);
        var item = new SaveItem { Id = Guid.NewGuid(), UserId = user.Id, EmulatorId = "eden", Kind = ItemKind.Save, GameKey = "g", Slot = "", Core = "" };
        var version = new SaveVersion { Id = Guid.NewGuid(), ItemId = item.Id, Seq = 1, DeviceId = dev.Id, ReceivedUtc = now, ManifestJson = "{}", ManifestHash = "h" };
        var bundle = new ConfigBundle { Id = Guid.NewGuid(), UserId = user.Id, EmulatorId = "eden", OwnerDeviceId = dev.Id };
        var group = new SyncGroup { Id = Guid.NewGuid(), UserId = user.Id, EmulatorId = "eden", Name = "g", BundleId = Guid.NewGuid() };
        var groupBundle = new ConfigBundle { Id = group.BundleId, UserId = user.Id, EmulatorId = "eden", GroupId = group.Id };
        db.AddRange(user, dev, item, version, group, bundle, groupBundle);
        db.AddRange(
            new DeviceConfig { DeviceId = dev.Id, EmulatorId = "eden", BundleId = bundle.Id },
            new Delivery { Id = Guid.NewGuid(), DeviceId = dev.Id, Kind = DeliveryKind.Save, TargetId = item.Id, VersionId = version.Id, CreatedUtc = now },
            new InstallRecord { DeviceId = dev.Id, EmulatorId = "eden", GameKey = "g", SeenUtc = now },
            new Data.ActivityEvent { UserId = user.Id, Utc = now, Kind = "k", Message = "m" });
        return user;
    }

    [DockerFact]    [Trait("Category", "Postgres")]
    public async Task Postgres_migrations_apply()
    {
        await using var container = new PostgreSqlBuilder("postgres:17-alpine").Build();
        await container.StartAsync();

        using var factory = new ServerFactory();
        factory.Settings["EmuSync:DatabaseProvider"] = "postgres";
        factory.Settings["EmuSync:DatabaseConnection"] = container.GetConnectionString();
        var response = await factory.Client().GetAsync("/healthz");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);

        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<EmuSyncDbContext>();
        var user = new User { Id = Guid.NewGuid(), Email = "a@b.c", PasswordHash = "x", CreatedUtc = factory.Time.GetUtcNow() };
        db.Users.Add(user);
        db.SaveItems.Add(NewItem(user.Id));
        await db.SaveChangesAsync();
        Assert.Equal(1, await db.SaveItems.CountAsync());
    }

    private static SaveItem NewItem(Guid userId) => new()
    {
        Id = Guid.NewGuid(), UserId = userId, EmulatorId = "eden", Kind = ItemKind.State,
        GameKey = "0100", Slot = "1", Core = "",
    };
}

/// <summary>[Fact] that is skipped (not failed) when the Docker daemon is unreachable.</summary>
public sealed class DockerFactAttribute : FactAttribute
{
    private static readonly Lazy<bool> Available = new(Probe);

    public DockerFactAttribute()
    {
        if (!Available.Value) Skip = "Docker is not reachable";
    }

    private static bool Probe()
    {
        try
        {
            using var p = Process.Start(new ProcessStartInfo("docker", "info")
            {
                RedirectStandardOutput = true, RedirectStandardError = true,
            })!;
            if (!p.WaitForExit(10_000)) { p.Kill(); return false; }
            return p.ExitCode == 0;
        }
        catch { return false; }
    }
}
