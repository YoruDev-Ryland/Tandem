namespace Tandem.Protocol;

/// <summary>How many versions to keep per save/state item, config bundle and vault item (each 1-1000).</summary>
public sealed record RetentionSettingsDto(int SaveVersions, int ConfigVersions, int VaultVersions);

/// <summary>Bytes of unique blobs. <see cref="EmulatorId"/> is "" for vault items not tied to an emulator;
/// <see cref="GameKey"/> and <see cref="DisplayName"/> are set only on game rows.</summary>
public sealed record StorageRow(string EmulatorId, string? GameKey, string? DisplayName, long Bytes);

public sealed record StorageUsage(long TotalBytes, IReadOnlyList<StorageRow> ByEmulator, IReadOnlyList<StorageRow> TopGames);

/// <summary>One notification destination. <see cref="Token"/> is write-only: it is accepted on PUT (an ntfy bearer
/// token; omit it to keep the stored token of an existing target with the same <see cref="Url"/>, send "" to clear it)
/// and is never returned. <see cref="TokenSet"/> is returned by GET instead (ignored on PUT).</summary>
public sealed record NotifyTarget(NotifyKind Kind, string Url, string? Token = null, bool TokenSet = false);

/// <summary>Which events notify. All default to true.</summary>
public sealed record NotifyEvents(bool Conflicts = true, bool FailedSyncs = true, bool ShizukuOff = true, bool BackupFailed = true);

/// <summary>Per-user notification settings (at most 10 targets; each Url must be http or https). BackupFailed only
/// matters for admins.</summary>
public sealed record NotifySettingsDto(IReadOnlyList<NotifyTarget> Targets, NotifyEvents Events);

/// <summary>The outcome of the test send for one target. <see cref="Status"/> is the HTTP status when a response arrived.</summary>
public sealed record NotifyTestResult(NotifyKind Kind, bool Ok, int? Status = null, string? Error = null);
