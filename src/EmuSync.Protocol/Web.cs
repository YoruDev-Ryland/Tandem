namespace EmuSync.Protocol;

// Read models for the web app. Device-facing protocol types live elsewhere; these are only returned by /v1/web/*.

public sealed record ContinueItem(Guid ItemId, string EmulatorId, string GameKey, string? DisplayName, string NewestDeviceName,
    DateTimeOffset NewestUtc);

public sealed record Attention(int OpenConflicts, int FirstSyncReviews, IReadOnlyList<Guid> ShizukuOffDevices, int FailedDeliveries24h);

public sealed record HomeResponse(IReadOnlyList<ContinueItem> Continue, IReadOnlyList<DeviceSummary> Devices, Attention Attention,
    IReadOnlyList<ActivityItem> Recent);

public sealed record GameRow(string EmulatorId, string GameKey, string? DisplayName, IReadOnlyList<Guid> InstalledOn, Guid? SaveItemId,
    Guid? NewestOnDeviceId, DateTimeOffset? NewestUtc, int StateCount, bool OpenConflict);

public sealed record VersionSummary(Guid Id, int Seq, string? DeviceName, DateTimeOffset ReceivedUtc, long TotalSize, string Core,
    string? Variant, bool Pinned, ConflictReason? ConflictReason, bool IsHead);

public sealed record SaveItemDetail(Guid ItemId, ItemKind Kind, string Slot, string Core, Guid? HeadVersionId, bool OpenConflict,
    IReadOnlyList<VersionSummary> Versions);

public sealed record GameDetail(GameRow Row, IReadOnlyList<SaveItemDetail> Items);

public sealed record EmulatorRow(string EmulatorId, string Package, string? Version, IReadOnlyList<string>? Cores);

public sealed record DeviceConfigRow(string EmulatorId, Guid BundleId, Guid? GroupId, string? GroupName, Guid? AppliedVersionId,
    Guid? HeadVersionId, bool UpToDate);

public sealed record InstalledCount(string EmulatorId, int Games);

public sealed record DeviceDetail(DeviceSummary Summary, IReadOnlyList<EmulatorRow> Emulators, IReadOnlyDictionary<string, string> Roots,
    IReadOnlyList<DeviceConfigRow> Configs, int PendingDeliveries, long StorageBytes, IReadOnlyList<InstalledCount> Installs);

public sealed record DeviceRef(Guid Id, string Name);

public sealed record GroupRow(Guid Id, string Name, IReadOnlyList<DeviceRef> Members, Guid? HeadVersionId);

public sealed record SoloRow(DeviceRef Device, Guid BundleId, Guid? HeadVersionId);

public sealed record EmulatorConfigRow(string EmulatorId, IReadOnlyList<GroupRow> Groups, IReadOnlyList<SoloRow> Solo);

public sealed record ConfigOverview(IReadOnlyList<EmulatorConfigRow> Emulators);

public enum SetupStepKind { InstallEmulator, InstallCores, GrantFolders, PlaceVault, RestoreSaves, ChooseConfig }

public sealed record SetupStep(SetupStepKind Kind, string EmulatorId, string Label, bool Done, string? Detail);

public sealed record SetupChecklist(IReadOnlyList<SetupStep> Steps);
