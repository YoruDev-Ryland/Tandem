namespace EmuSync.Protocol;

/// <summary>How many versions to keep per save/state item, config bundle and vault item (each 1-1000).</summary>
public sealed record RetentionSettingsDto(int SaveVersions, int ConfigVersions, int VaultVersions);

/// <summary>Bytes of unique blobs. <see cref="EmulatorId"/> is "" for vault items not tied to an emulator;
/// <see cref="GameKey"/> and <see cref="DisplayName"/> are set only on game rows.</summary>
public sealed record StorageRow(string EmulatorId, string? GameKey, string? DisplayName, long Bytes);

public sealed record StorageUsage(long TotalBytes, IReadOnlyList<StorageRow> ByEmulator, IReadOnlyList<StorageRow> TopGames);
