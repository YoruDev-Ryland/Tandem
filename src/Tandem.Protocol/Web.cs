namespace Tandem.Protocol;

// Read models for the web app. Device-facing protocol types live elsewhere; these are only returned by /v1/web/*.

public sealed record ContinueItem(Guid ItemId, string EmulatorId, string GameKey, string? DisplayName, string NewestDeviceName,
    DateTimeOffset NewestUtc);

/// <param name="ConfigConflicts">Config bundles (group or solo) with an open conflict; OpenConflicts and FirstSyncReviews
/// count saves only.</param>
/// <param name="DiskLow">The server's data directory is below its free-space minimum; uploads are refused (507).</param>
/// <param name="BackupNotConfigured">Only ever true for an admin: the scheduled off-server backup is not turned on.</param>
/// <param name="SilentDevices">Active devices not seen for the server's SilentDeviceHours (default 72).</param>
public sealed record Attention(int OpenConflicts, int FirstSyncReviews, IReadOnlyList<Guid> ShizukuOffDevices, int FailedDeliveries24h,
    int ConfigConflicts, bool DiskLow, bool BackupNotConfigured, IReadOnlyList<Guid> SilentDevices);

public sealed record HomeResponse(IReadOnlyList<ContinueItem> Continue, IReadOnlyList<DeviceSummary> Devices, Attention Attention,
    IReadOnlyList<ActivityItem> Recent);

/// <param name="Devices">Every active device of the user, Newest first, then by name.</param>
/// <param name="NewestOnDeviceName">Name of the device that uploaded the newest head (null for a web upload or no saves).</param>
public sealed record GameRow(string EmulatorId, string GameKey, string? DisplayName, IReadOnlyList<Guid> InstalledOn, Guid? SaveItemId,
    Guid? NewestOnDeviceId, DateTimeOffset? NewestUtc, int StateCount, bool OpenConflict, GameDeviceState[] Devices,
    string? NewestOnDeviceName);

/// <summary>
/// How a device stands for a game, first match wins. Differs: it uploaded an open conflict version of one of the
/// game's items, or the head such a conflict is against. Newest: it uploaded the newest head (NewestOnDeviceId).
/// Pending: a save delivery to it is not yet acknowledged. Waiting: Pending while the game's emulator runs on it.
/// HasIt: it holds the head of the game's save (or, without a save, of its newest item). NotInstalled: the game is not
/// installed on it. Behind: installed but holding an older version (or none).
/// </summary>
public enum DeviceGameState { Newest, HasIt, Pending, Waiting, Behind, NotInstalled, Differs }

public sealed record GameDeviceState(Guid DeviceId, string DeviceName, DeviceGameState State);

/// <param name="FileCount">Number of files in the version's manifest.</param>
/// <param name="EmulatorVersion">The uploading device's reported version of the emulator at upload time (a restore copy
/// keeps the original's), or null.</param>
public sealed record VersionSummary(Guid Id, int Seq, string? DeviceName, DateTimeOffset ReceivedUtc, long TotalSize, string Core,
    string? Variant, bool Pinned, ConflictReason? ConflictReason, bool IsHead, bool Resolved, string ManifestHash, int FileCount,
    string? EmulatorVersion);

/// <summary>One side of an open save conflict: the current head or an open conflict version.</summary>
public sealed record ConflictSide(Guid VersionId, Guid? DeviceId, string? DeviceName, DateTimeOffset ReceivedUtc, long TotalSize,
    int FileCount, string ManifestHash, bool IsHead);

/// <summary>A save item with an open conflict. Reason is Concurrent if any open version is concurrent, else FirstSync.
/// Slot and Core are null when empty. Sides: the head first, then the open versions oldest first.</summary>
public sealed record ConflictRow(Guid ItemId, string EmulatorId, string GameKey, string? DisplayName, ItemKind Kind, string? Slot,
    string? Core, ConflictReason Reason, ConflictSide[] Sides);

public sealed record SaveItemDetail(Guid ItemId, ItemKind Kind, string Slot, string Core, Guid? HeadVersionId, bool OpenConflict,
    IReadOnlyList<VersionSummary> Versions);

public sealed record GameDetail(GameRow Row, IReadOnlyList<SaveItemDetail> Items);

public sealed record EmulatorRow(string EmulatorId, string Package, string? Version, IReadOnlyList<string>? Cores);

public sealed record DeviceConfigRow(string EmulatorId, Guid BundleId, Guid? GroupId, string? GroupName, Guid? AppliedVersionId,
    Guid? HeadVersionId, bool UpToDate, bool OpenConflict);

public sealed record InstalledCount(string EmulatorId, int Games);

public sealed record DeviceDetail(DeviceSummary Summary, IReadOnlyList<EmulatorRow> Emulators, IReadOnlyDictionary<string, string> Roots,
    IReadOnlyList<DeviceConfigRow> Configs, int PendingDeliveries, long StorageBytes, IReadOnlyList<InstalledCount> Installs);

public sealed record DeviceRef(Guid Id, string Name);

public sealed record GroupRow(Guid Id, string Name, IReadOnlyList<DeviceRef> Members, Guid? HeadVersionId, bool OpenConflict);

public sealed record SoloRow(DeviceRef Device, Guid BundleId, Guid? HeadVersionId, bool OpenConflict);

public sealed record EmulatorConfigRow(string EmulatorId, IReadOnlyList<GroupRow> Groups, IReadOnlyList<SoloRow> Solo);

public sealed record ConfigOverview(IReadOnlyList<EmulatorConfigRow> Emulators);

public enum SetupStepKind { InstallEmulator, InstallCores, GrantFolders, PlaceVault, RestoreSaves, ChooseConfig }

public sealed record SetupStep(SetupStepKind Kind, string EmulatorId, string Label, bool Done, string? Detail);

public sealed record SetupChecklist(IReadOnlyList<SetupStep> Steps);
