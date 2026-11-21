namespace Tandem.Protocol;

/// <param name="Category">bios | keys | firmware | sysdata | system</param>
/// <param name="EmulatorId">Optional; when given it must be a known emulator.</param>
/// <param name="Name">1-200 characters, never a path ('/', '\', '..' and NUL are refused).</param>
public sealed record VaultUploadRequest(string Category, string? EmulatorId, string Name, IReadOnlyList<ManifestEntry> Manifest);

public sealed record VaultUploadResponse(Guid ItemId, Guid VersionId, bool Unchanged);

/// <param name="Current">The device's last applied vault delivery for the item is the head.</param>
public sealed record VaultHolder(Guid DeviceId, string DeviceName, bool Current);

public sealed record VaultItemDto(Guid Id, string Category, string? EmulatorId, string Name, Guid HeadVersionId, long TotalSize,
    IReadOnlyList<VaultHolder> Holders);

public sealed record SendVaultRequest(Guid DeviceId);

/// <param name="DeliveryId">The new Vault delivery of the head (an earlier un-acked one for the device is superseded).</param>
public sealed record SendVaultResponse(Guid DeliveryId, Guid VersionId);
