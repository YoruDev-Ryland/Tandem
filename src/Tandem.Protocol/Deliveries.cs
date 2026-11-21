namespace Tandem.Protocol;

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

public sealed record ActivityItem(long Id, DateTimeOffset Utc, string Kind, string Message, Guid? DeviceId, string? DeviceName,
    string? EmulatorId, string? GameKey);

/// <param name="NextBefore">Pass as <c>before</c> to fetch the next (older) page, or null when there is no more.</param>
public sealed record ActivityPage(IReadOnlyList<ActivityItem> Items, long? NextBefore);
