namespace EmuSync.Protocol;

/// <summary>
/// Something a device should apply. For Save deliveries <see cref="Manifest"/> is the version's manifest and
/// <see cref="MissingLocalKeys"/> is null.
/// </summary>
/// <param name="KnownVersionId">The version the device last synced for the target, or null if it never synced it.</param>
public sealed record DeliveryDto(Guid Id, DeliveryKind Kind, Guid TargetId, Guid VersionId, Guid? KnownVersionId,
    string EmulatorId, string? GameKey, ItemKind? ItemKind, string? Slot, string? Core,
    IReadOnlyList<ManifestEntry> Manifest, IReadOnlyList<string>? MissingLocalKeys);

public sealed record DeliveryList(IReadOnlyList<DeliveryDto> Items);

public sealed record AckRequest(DeliveryResult Result, string? Detail);
