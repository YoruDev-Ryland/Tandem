namespace EmuSync.Protocol;

/// <param name="BaseVersionId">The last bundle version this device applied or uploaded, or null if it never synced the config.</param>
/// <param name="Files">The bundle's files, by client bundle path (e.g. <c>data/config/config.ini</c>).</param>
public sealed record ConfigUploadRequest(string EmulatorId, Guid? BaseVersionId, IReadOnlyList<ManifestEntry> Files);

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
