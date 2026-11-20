using EmuSync.Protocol;

namespace EmuSync.Server.Data;

// Plain data classes. Times are UTC DateTimeOffset. Head/base version ids are plain columns (no FK) to avoid
// circular references between items and their versions.

public class User
{
    public Guid Id { get; set; }
    /// <summary>Always stored lower-cased (callers normalize).</summary>
    public string Email { get; set; } = "";
    public string PasswordHash { get; set; } = "";
    public bool IsAdmin { get; set; }
    public DateTimeOffset CreatedUtc { get; set; }
}

public class Device
{
    public Guid Id { get; set; }
    public Guid UserId { get; set; }
    public string Name { get; set; } = "";
    public string Platform { get; set; } = "";
    public string AppVersion { get; set; } = "";
    public string KeyHash { get; set; } = "";
    public DateTimeOffset CreatedUtc { get; set; }
    public DateTimeOffset? LastSeenUtc { get; set; }
    public bool? ShizukuOk { get; set; }
    public DateTimeOffset? ShizukuOffSinceUtc { get; set; }
    /// <summary>When the Shizuku-off notification for the current off period was sent; cleared with ShizukuOffSinceUtc.</summary>
    public DateTimeOffset? ShizukuOffNotifiedUtc { get; set; }
    public DateTimeOffset? RevokedUtc { get; set; }
}

public class DeviceEmulator
{
    public Guid DeviceId { get; set; }
    public string EmulatorId { get; set; } = "";
    public string Package { get; set; } = "";
    public string Version { get; set; } = "";
    /// <summary>RetroArch core names, as JSON.</summary>
    public string? CoresJson { get; set; }
}

public class DeviceRoot
{
    public Guid DeviceId { get; set; }
    public string Token { get; set; } = "";
    public string Path { get; set; } = "";
}

public class InstallRecord
{
    public Guid DeviceId { get; set; }
    public string EmulatorId { get; set; } = "";
    public string GameKey { get; set; } = "";
    public string? DisplayName { get; set; }
    public string? Crc32 { get; set; }
    public DateTimeOffset SeenUtc { get; set; }
}

public class SaveItem
{
    public Guid Id { get; set; }
    public Guid UserId { get; set; }
    public string EmulatorId { get; set; } = "";
    public ItemKind Kind { get; set; }
    public string GameKey { get; set; } = "";
    public string Slot { get; set; } = "";
    /// <summary>Always "" for saves, so the unique index covers both kinds.</summary>
    public string Core { get; set; } = "";
    public Guid? HeadVersionId { get; set; }
    public bool OpenConflict { get; set; }
    public string? DisplayName { get; set; }
}

public class SaveVersion
{
    public Guid Id { get; set; }
    public Guid ItemId { get; set; }
    public int Seq { get; set; }
    public Guid? BaseVersionId { get; set; }
    public Guid? DeviceId { get; set; }
    public DateTimeOffset ReceivedUtc { get; set; }
    public string ManifestJson { get; set; } = "";
    public string ManifestHash { get; set; } = "";
    public long TotalSize { get; set; }
    public string Core { get; set; } = "";
    public string? Variant { get; set; }
    public bool Pinned { get; set; }
    public ConflictReason? ConflictReason { get; set; }
    /// <summary>Set once a conflict version has been settled. Open conflicts: ConflictReason != null and !Resolved.</summary>
    public bool Resolved { get; set; }
}

public class DeviceItemState
{
    public Guid DeviceId { get; set; }
    public Guid ItemId { get; set; }
    public Guid KnownVersionId { get; set; }
}

public class ConfigBundle
{
    public Guid Id { get; set; }
    public Guid UserId { get; set; }
    public string EmulatorId { get; set; } = "";
    /// <summary>Exactly one of OwnerDeviceId / GroupId is set.</summary>
    public Guid? OwnerDeviceId { get; set; }
    public Guid? GroupId { get; set; }
    public Guid? HeadVersionId { get; set; }
    public bool OpenConflict { get; set; }
}

public class ConfigVersion
{
    public Guid Id { get; set; }
    public Guid BundleId { get; set; }
    public int Seq { get; set; }
    public Guid? BaseVersionId { get; set; }
    public Guid? DeviceId { get; set; }
    public ConfigSource Source { get; set; }
    public DateTimeOffset ReceivedUtc { get; set; }
    public string ManifestJson { get; set; } = "";
    public string ManifestHash { get; set; } = "";
    public long TotalSize { get; set; }
    public bool Pinned { get; set; }
    public ConflictReason? ConflictReason { get; set; }
    /// <summary>Set once a conflict version has been settled. Open conflicts: ConflictReason != null and !Resolved.</summary>
    public bool Resolved { get; set; }
}

public class SyncGroup
{
    public Guid Id { get; set; }
    public Guid UserId { get; set; }
    public string EmulatorId { get; set; } = "";
    public string Name { get; set; } = "";
    public Guid BundleId { get; set; }
}

public class DeviceConfig
{
    public Guid DeviceId { get; set; }
    public string EmulatorId { get; set; } = "";
    public Guid BundleId { get; set; }
    public Guid? AppliedVersionId { get; set; }
    public Guid? LastRawVersionId { get; set; }
}

public class RawConfigUpload
{
    public Guid Id { get; set; }
    public Guid DeviceId { get; set; }
    public string EmulatorId { get; set; } = "";
    public string ManifestJson { get; set; } = "";
    public DateTimeOffset ReceivedUtc { get; set; }
}

public class VaultItem
{
    public Guid Id { get; set; }
    public Guid UserId { get; set; }
    /// <summary>bios | keys | firmware | sysdata | system</summary>
    public string Category { get; set; } = "";
    /// <summary>"" when the item is not tied to an emulator (never null: part of the unique key).</summary>
    public string? EmulatorId { get; set; }
    public string Name { get; set; } = "";
    public Guid? HeadVersionId { get; set; }
}

public class VaultVersion
{
    public Guid Id { get; set; }
    public Guid ItemId { get; set; }
    public int Seq { get; set; }
    public Guid? DeviceId { get; set; }
    public DateTimeOffset ReceivedUtc { get; set; }
    public string ManifestJson { get; set; } = "";
    public string ManifestHash { get; set; } = "";
    public long TotalSize { get; set; }
}

/// <summary>The vault version a device last acknowledged applying (a Vault delivery acked Applied).</summary>
public class DeviceVaultState
{
    public Guid DeviceId { get; set; }
    public Guid ItemId { get; set; }
    public Guid VersionId { get; set; }
}

public class Delivery
{
    public Guid Id { get; set; }
    public Guid DeviceId { get; set; }
    public DeliveryKind Kind { get; set; }
    /// <summary>The SaveItem, ConfigBundle or VaultItem id, depending on Kind.</summary>
    public Guid TargetId { get; set; }
    public Guid VersionId { get; set; }
    public DateTimeOffset CreatedUtc { get; set; }
    public DateTimeOffset? AckedUtc { get; set; }
    public DeliveryResult? Result { get; set; }
    /// <summary>Closed by the server because a newer target version replaced it (AckedUtc set, Result null). A late
    /// ack of it is still honoured.</summary>
    public bool Superseded { get; set; }
    public string? NotesJson { get; set; }
}

public class UserBlob
{
    public Guid UserId { get; set; }
    public string Hash { get; set; } = "";
    public long Size { get; set; }
    public DateTimeOffset CreatedUtc { get; set; }
}

public class PairingCode
{
    public string CodeHash { get; set; } = "";
    public Guid UserId { get; set; }
    public DateTimeOffset ExpiresUtc { get; set; }
    public DateTimeOffset? UsedUtc { get; set; }
}

public class WebSession
{
    public string TokenHash { get; set; } = "";
    public Guid UserId { get; set; }
    public Guid? DeviceId { get; set; }
    public DateTimeOffset CreatedUtc { get; set; }
    public DateTimeOffset LastUsedUtc { get; set; }
}

public class ActivityEvent
{
    public long Id { get; set; }
    public Guid UserId { get; set; }
    public DateTimeOffset Utc { get; set; }
    public string Kind { get; set; } = "";
    public Guid? DeviceId { get; set; }
    public string? EmulatorId { get; set; }
    public string? GameKey { get; set; }
    public string Message { get; set; } = "";
    public string? DataJson { get; set; }
}

public class ServerSetting
{
    public string Key { get; set; } = "";
    public string Value { get; set; } = "";
}

public class UserSetting
{
    public Guid UserId { get; set; }
    public string Key { get; set; } = "";
    public string Value { get; set; } = "";
}

/// <summary>One off-server backup run (the history keeps the newest <c>BackupService.HistoryKeep</c>).</summary>
public class BackupRun
{
    public long Id { get; set; }
    public DateTimeOffset Utc { get; set; }
    public bool Ok { get; set; }
    public long Bytes { get; set; }
    public string? Name { get; set; }
    public string? Error { get; set; }
}
