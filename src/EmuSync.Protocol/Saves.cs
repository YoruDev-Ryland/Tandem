namespace EmuSync.Protocol;

/// <summary>One file of a save/state/config snapshot. Path is relative, '/'-separated.</summary>
public sealed record ManifestEntry(string Path, string Hash, long Size);

public enum SaveUploadStatus { Accepted, Unchanged, Conflict, Superseded }

/// <param name="Core">For Kind=State it is part of the item identity; for Kind=Save it is only recorded on the version.</param>
/// <param name="BaseVersionId">The last version this device saw for the item, or null if it never synced it.</param>
public sealed record SaveUploadRequest(string EmulatorId, ItemKind Kind, string GameKey, string Slot, string Core,
    Guid? BaseVersionId, IReadOnlyList<ManifestEntry> Manifest, string? DisplayName);

/// <param name="VersionId">The new (or matching) version. For Unchanged it is the head.</param>
public sealed record SaveUploadResponse(SaveUploadStatus Status, Guid ItemId, Guid VersionId, Guid HeadVersionId);

public enum ResolveChoice { KeepVersion, KeepBoth }

public sealed record ResolveConflictRequest(ResolveChoice Choice, Guid? KeepVersionId);

public enum FirstSyncChoice { UseDevice, UseServer }

/// <param name="ItemIds">Only these items; null means every open first-sync conflict of the device.</param>
public sealed record BulkFirstSyncRequest(Guid DeviceId, FirstSyncChoice Choice, IReadOnlyList<Guid>? ItemIds);

/// <param name="DeviceId">The device the restore was requested for (recorded for the activity log).</param>
public sealed record RestoreRequest(Guid DeviceId);

/// <param name="VersionId">The new head: a copy of the chosen version.</param>
public sealed record RestoreResponse(Guid ItemId, Guid VersionId);
