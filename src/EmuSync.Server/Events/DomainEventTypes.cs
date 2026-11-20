namespace EmuSync.Server.Events;

public sealed record InstallsChanged(Guid UserId, Guid DeviceId, string EmulatorId, IReadOnlyList<string> AddedGameKeys);
