using EmuSync.Protocol;
using Microsoft.EntityFrameworkCore;

namespace EmuSync.Server.Data;

/// <summary>
/// Provider-neutral model. Provider-specific subclasses (<see cref="SqliteEmuSyncDbContext"/>,
/// <see cref="PostgresEmuSyncDbContext"/>) own their migration sets.
/// </summary>
public abstract class EmuSyncDbContext(DbContextOptions options) : DbContext(options)
{
    public DbSet<User> Users => Set<User>();
    public DbSet<Device> Devices => Set<Device>();
    public DbSet<DeviceEmulator> DeviceEmulators => Set<DeviceEmulator>();
    public DbSet<DeviceRoot> DeviceRoots => Set<DeviceRoot>();
    public DbSet<InstallRecord> InstallRecords => Set<InstallRecord>();
    public DbSet<SaveItem> SaveItems => Set<SaveItem>();
    public DbSet<SaveVersion> SaveVersions => Set<SaveVersion>();
    public DbSet<DeviceItemState> DeviceItemStates => Set<DeviceItemState>();
    public DbSet<ConfigBundle> ConfigBundles => Set<ConfigBundle>();
    public DbSet<ConfigVersion> ConfigVersions => Set<ConfigVersion>();
    public DbSet<SyncGroup> SyncGroups => Set<SyncGroup>();
    public DbSet<DeviceConfig> DeviceConfigs => Set<DeviceConfig>();
    public DbSet<RawConfigUpload> RawConfigUploads => Set<RawConfigUpload>();
    public DbSet<VaultItem> VaultItems => Set<VaultItem>();
    public DbSet<VaultVersion> VaultVersions => Set<VaultVersion>();
    public DbSet<Delivery> Deliveries => Set<Delivery>();
    public DbSet<UserBlob> UserBlobs => Set<UserBlob>();
    public DbSet<PairingCode> PairingCodes => Set<PairingCode>();
    public DbSet<WebSession> WebSessions => Set<WebSession>();
    public DbSet<ActivityEvent> ActivityEvents => Set<ActivityEvent>();
    public DbSet<ServerSetting> ServerSettings => Set<ServerSetting>();
    public DbSet<UserSetting> UserSettings => Set<UserSetting>();

    protected override void ConfigureConventions(ModelConfigurationBuilder b)
    {
        b.Properties<ItemKind>().HaveConversion<string>().HaveMaxLength(16);
        b.Properties<ConflictReason>().HaveConversion<string>().HaveMaxLength(16);
        b.Properties<DeliveryKind>().HaveConversion<string>().HaveMaxLength(16);
        b.Properties<DeliveryResult>().HaveConversion<string>().HaveMaxLength(32);
        b.Properties<ConfigSource>().HaveConversion<string>().HaveMaxLength(16);
    }

    protected override void OnModelCreating(ModelBuilder m)
    {
        m.Entity<User>(e =>
        {
            // Emails are always stored lower-cased, so the unique index is effectively case-insensitive.
            e.Property(x => x.Email).HasConversion(v => v.ToLowerInvariant(), v => v);
            e.HasIndex(x => x.Email).IsUnique();
        });

        m.Entity<Device>(e =>
        {
            e.HasIndex(x => x.KeyHash).IsUnique();
            e.HasIndex(x => x.UserId);
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<DeviceEmulator>(e =>
        {
            e.HasKey(x => new { x.DeviceId, x.EmulatorId });
            e.HasOne<Device>().WithMany().HasForeignKey(x => x.DeviceId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<DeviceRoot>(e =>
        {
            e.HasKey(x => new { x.DeviceId, x.Token });
            e.HasOne<Device>().WithMany().HasForeignKey(x => x.DeviceId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<InstallRecord>(e =>
        {
            e.HasKey(x => new { x.DeviceId, x.EmulatorId, x.GameKey });
            e.HasOne<Device>().WithMany().HasForeignKey(x => x.DeviceId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<SaveItem>(e =>
        {
            e.HasIndex(x => new { x.UserId, x.EmulatorId, x.Kind, x.GameKey, x.Slot, x.Core }).IsUnique();
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<SaveVersion>(e =>
        {
            e.HasOne<Device>().WithMany().HasForeignKey(x => x.DeviceId).OnDelete(DeleteBehavior.SetNull);
            e.HasIndex(x => new { x.ItemId, x.Seq }).IsUnique();
            e.HasOne<SaveItem>().WithMany().HasForeignKey(x => x.ItemId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<DeviceItemState>(e =>
        {
            e.HasKey(x => new { x.DeviceId, x.ItemId });
            e.HasOne<Device>().WithMany().HasForeignKey(x => x.DeviceId).OnDelete(DeleteBehavior.Cascade);
            e.HasOne<SaveItem>().WithMany().HasForeignKey(x => x.ItemId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<ConfigBundle>(e =>
        {
            e.ToTable(t => t.HasCheckConstraint("CK_ConfigBundles_ExactlyOneOwner",
                "(\"OwnerDeviceId\" IS NOT NULL AND \"GroupId\" IS NULL) OR (\"OwnerDeviceId\" IS NULL AND \"GroupId\" IS NOT NULL)"));
            e.HasOne<Device>().WithMany().HasForeignKey(x => x.OwnerDeviceId).OnDelete(DeleteBehavior.Cascade);
            // Cycle with SyncGroup (a group's bundle points back at the group): NoAction on this edge.
            e.HasOne<SyncGroup>().WithMany().HasForeignKey(x => x.GroupId).OnDelete(DeleteBehavior.NoAction);
            e.HasIndex(x => new { x.UserId, x.EmulatorId });
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<ConfigVersion>(e =>
        {
            e.HasOne<Device>().WithMany().HasForeignKey(x => x.DeviceId).OnDelete(DeleteBehavior.SetNull);
            e.HasIndex(x => new { x.BundleId, x.Seq }).IsUnique();
            e.HasOne<ConfigBundle>().WithMany().HasForeignKey(x => x.BundleId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<SyncGroup>(e =>
        {
            e.HasIndex(x => new { x.UserId, x.EmulatorId });
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<DeviceConfig>(e =>
        {
            e.HasOne<ConfigBundle>().WithMany().HasForeignKey(x => x.BundleId).OnDelete(DeleteBehavior.Cascade);
            e.HasKey(x => new { x.DeviceId, x.EmulatorId });
            e.HasOne<Device>().WithMany().HasForeignKey(x => x.DeviceId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<RawConfigUpload>(e =>
            e.HasOne<Device>().WithMany().HasForeignKey(x => x.DeviceId).OnDelete(DeleteBehavior.Cascade));

        m.Entity<VaultItem>(e =>
        {
            e.HasIndex(x => new { x.UserId, x.Category });
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<VaultVersion>(e =>
        {
            e.HasOne<Device>().WithMany().HasForeignKey(x => x.DeviceId).OnDelete(DeleteBehavior.SetNull);
            e.HasIndex(x => new { x.ItemId, x.Seq }).IsUnique();
            e.HasOne<VaultItem>().WithMany().HasForeignKey(x => x.ItemId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<Delivery>(e =>
        {
            e.HasIndex(x => new { x.DeviceId, x.AckedUtc });
            e.HasOne<Device>().WithMany().HasForeignKey(x => x.DeviceId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<UserBlob>(e =>
        {
            e.HasKey(x => new { x.UserId, x.Hash });
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<PairingCode>(e =>
        {
            e.HasKey(x => x.CodeHash);
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<WebSession>(e =>
        {
            e.HasKey(x => x.TokenHash);
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<ActivityEvent>(e =>
        {
            e.Property(x => x.Id).ValueGeneratedOnAdd();
            e.HasIndex(x => new { x.UserId, x.Utc });
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });

        m.Entity<ServerSetting>(e => e.HasKey(x => x.Key));

        m.Entity<UserSetting>(e =>
        {
            e.HasKey(x => new { x.UserId, x.Key });
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Cascade);
        });
    }
}

public sealed class SqliteEmuSyncDbContext(DbContextOptions<SqliteEmuSyncDbContext> options) : EmuSyncDbContext(options)
{
    protected override void ConfigureConventions(ModelConfigurationBuilder b)
    {
        base.ConfigureConventions(b);
        // SQLite has no native timestamp type: store UTC unix milliseconds so ordering and comparison work.
        b.Properties<DateTimeOffset>().HaveConversion<UnixMillisConverter>();
        b.Properties<DateTimeOffset?>().HaveConversion<UnixMillisConverter>();
    }
}

public sealed class PostgresEmuSyncDbContext(DbContextOptions<PostgresEmuSyncDbContext> options) : EmuSyncDbContext(options);

public sealed class UnixMillisConverter : Microsoft.EntityFrameworkCore.Storage.ValueConversion.ValueConverter<DateTimeOffset, long>
{
    public UnixMillisConverter() : base(
        v => v.ToUnixTimeMilliseconds(),
        v => DateTimeOffset.FromUnixTimeMilliseconds(v))
    {
    }
}
