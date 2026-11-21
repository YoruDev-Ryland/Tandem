namespace Tandem.Server.Events;

public sealed record InstallsChanged(Guid UserId, Guid DeviceId, string EmulatorId, IReadOnlyList<string> AddedGameKeys);

public sealed record SaveHeadChanged(Guid UserId, Guid ItemId, Guid HeadVersionId);

public sealed record ConflictOpened(Guid UserId, Guid ItemId, Guid VersionId, Tandem.Protocol.ConflictReason Reason);

/// <summary>A config upload opened a NEW conflict on a bundle (not raised for a retry of an existing one).</summary>
public sealed record ConfigConflictOpened(Guid UserId, Guid BundleId, Guid VersionId, string EmulatorId, Guid DeviceId);

public sealed record DeliveryFailed(Guid UserId, Guid DeviceId, Guid DeliveryId, string? Detail);

/// <summary>A config bundle head, conflict, membership or applied version changed for the user.</summary>
public sealed record ConfigsChanged(Guid UserId);

/// <summary>Raised (through <see cref="IDomainEvents"/>) once at the end of every backup run, scheduled or manual.
/// <paramref name="Error"/> is set when <paramref name="Ok"/> is false, or when the run was <paramref name="Partial"/> (written,
/// but some blob files were missing): a message safe to show and send (no secrets).</summary>
public sealed record BackupFinished(bool Ok, string? Error, bool Partial = false);
