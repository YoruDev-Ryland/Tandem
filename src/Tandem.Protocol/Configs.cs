namespace Tandem.Protocol;

/// <param name="BaseVersionId">The last bundle version this device applied or uploaded, or null if it never synced the config.</param>
/// <param name="Files">The bundle's files, by client bundle path (e.g. <c>data/config/config.ini</c>).</param>
/// <param name="Fresh">True when the device has forgotten what it knew of this config (a variant switch or a new
/// sign-in): the server takes BaseVersionId as sent, never the version it last recorded this device as applying.</param>
public sealed record ConfigUploadRequest(string EmulatorId, Guid? BaseVersionId, IReadOnlyList<ManifestEntry> Files, bool? Fresh = null);

/// <param name="VersionId">The new (or matching) version. For Unchanged it is the head.</param>
public sealed record ConfigUploadResponse(SaveUploadStatus Status, Guid BundleId, Guid VersionId);

public sealed record CreateGroupRequest(string EmulatorId, string Name, Guid SeedDeviceId);

public sealed record JoinGroupRequest(Guid DeviceId);

/// <param name="MemberDeviceIds">Non-revoked members only.</param>
public sealed record SyncGroupDto(Guid Id, string EmulatorId, string Name, Guid BundleId, Guid? HeadVersionId,
    IReadOnlyList<Guid> MemberDeviceIds);

public sealed record SyncGroupList(IReadOnlyList<SyncGroupDto> Items);

public enum ApplyMode { Linked, Copy }

public sealed record ApplyConfigRequest(Guid TargetDeviceId, string EmulatorId, Guid SourceVersionId, ApplyMode Mode);

/// <param name="BundleId">The bundle the target now follows.</param>
/// <param name="HeadVersionId">That bundle's head, which the target is sent unless it already holds it.</param>
/// <param name="GroupId">The group the target is in after a Linked apply; null for Copy.</param>
public sealed record ApplyConfigResponse(Guid BundleId, Guid HeadVersionId, Guid? GroupId);

/// <param name="ConflictReason">Set on a version that was uploaded as a conflict (open or settled).</param>
/// <param name="Resolved">True once a conflict version's conflict was resolved.</param>
public sealed record ConfigVersionSummary(Guid Id, int Seq, ConfigSource Source, string? DeviceName, DateTimeOffset ReceivedUtc,
    long TotalSize, bool Pinned, ConflictReason? ConflictReason, bool Resolved);

public enum FileDiffStatus { Added, Removed, Changed, Same }

/// <param name="Op">' ' (context), '+' (added) or '-' (removed).</param>
public sealed record DiffLine(char Op, string Text);

/// <param name="OldStart">1-based line in the old file where the hunk starts (the line before which an insert lands).</param>
public sealed record DiffHunk(int OldStart, int NewStart, IReadOnlyList<DiffLine> Lines);

/// <param name="Hunks">Empty for Same, for binary/opaque files, and for a Changed file whose lines are equal once
/// line terminators are ignored.</param>
public sealed record FileDiff(string Path, FileDiffStatus Status, IReadOnlyList<DiffHunk> Hunks);

public sealed record ConfigDiffResponse(IReadOnlyList<FileDiff> Files);

/// <param name="BaseVersionId">The bundle head the editor started from; anything else is a 409 stale_base.</param>
/// <param name="Text">The file as the chosen device sees it (expanded), UTF-8, at most 4 MiB.</param>
public sealed record EditConfigFileRequest(Guid BaseVersionId, string Text);
