namespace EmuSync.Server.Events;

public sealed record InstallsChanged(Guid UserId, Guid DeviceId, string EmulatorId, IReadOnlyList<string> AddedGameKeys);

public sealed record SaveHeadChanged(Guid UserId, Guid ItemId, Guid HeadVersionId);

public sealed record ConflictOpened(Guid UserId, Guid ItemId, Guid VersionId, EmuSync.Protocol.ConflictReason Reason);
