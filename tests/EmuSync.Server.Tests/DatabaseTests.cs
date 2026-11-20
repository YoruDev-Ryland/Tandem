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

    [DockerFact]
    [Trait("Category", "Postgres")]
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
