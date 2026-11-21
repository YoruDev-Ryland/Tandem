namespace Tandem.Protocol;

/// <summary>One file of a save/state/config snapshot. Path is relative, '/'-separated.</summary>
public sealed record ManifestEntry(string Path, string Hash, long Size);

/// <summary>
/// Accepted: a new version (head, or a new open conflict via Conflict). Unchanged: identical to the head.
/// Conflict: a new (or retried) open conflict version. Superseded: the upload was not based on the head but its
/// content equals a version the server already settled (a resolved conflict, or a variant or pinned non-head
/// version of the same item or bundle). No version is created, the device is recorded as holding that version and
/// is then sent the head. A still-open conflict version never matches.
/// </summary>
public enum SaveUploadStatus { Accepted, Unchanged, Conflict, Superseded }

/// <param name="Core">For Kind=State it is part of the item identity; for Kind=Save it is only recorded on the version.</param>
/// <param name="BaseVersionId">The last version this device saw for the item, or null if it never synced it.</param>
/// <param name="Fresh">True when the device has forgotten what it knew of this item (it switched the emulator's
/// variant, or signed in again), so its files may not descend from anything the server recorded for it: the server
/// then takes BaseVersionId as sent, never the version it last recorded this device as holding.</param>
public sealed record SaveUploadRequest(string EmulatorId, ItemKind Kind, string GameKey, string Slot, string Core,
    Guid? BaseVersionId, IReadOnlyList<ManifestEntry> Manifest, string? DisplayName, bool? Fresh = null);

/// <param name="VersionId">The new (or matching) version. For Unchanged it is the head. For Superseded it is the
/// existing settled version the content matched (no version is created); the device is then sent HeadVersionId.
/// A resolved conflict, or a variant or pinned non-head version, can be matched; an open conflict never is.</param>
public sealed record SaveUploadResponse(SaveUploadStatus Status, Guid ItemId, Guid VersionId, Guid HeadVersionId);

public enum ResolveChoice { KeepVersion, KeepBoth }

/// <param name="VariantName">KeepBoth only: the label for the kept-aside version(s); null means "From {DeviceName}".</param>
public sealed record ResolveConflictRequest(ResolveChoice Choice, Guid? KeepVersionId, string? VariantName = null);

public enum FirstSyncChoice { UseDevice, UseServer }

/// <param name="ItemIds">Only these items; null means every open first-sync conflict of the device.</param>
public sealed record BulkFirstSyncRequest(Guid DeviceId, FirstSyncChoice Choice, IReadOnlyList<Guid>? ItemIds);

/// <param name="DeviceId">The device the restore was requested for (recorded for the activity log).</param>
public sealed record RestoreRequest(Guid DeviceId);

/// <param name="VersionId">The new head: a copy of the chosen version.</param>
public sealed record RestoreResponse(Guid ItemId, Guid VersionId);
