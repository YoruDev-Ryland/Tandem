package dev.tandem.app.model

import kotlinx.serialization.Serializable

// Kotlin mirrors of the server's /v1/web/* read models (Tandem.Protocol/Web.cs). Guids and timestamps are strings.

@Serializable enum class DeviceGameState { Newest, HasIt, Pending, Waiting, Behind, NotInstalled, Differs }
@Serializable enum class ConflictReason { Concurrent, FirstSync }
@Serializable enum class ResolveChoice { KeepVersion, KeepBoth }
@Serializable enum class FirstSyncChoice { UseDevice, UseServer }
@Serializable enum class ApplyMode { Linked, Copy }
@Serializable enum class SetupStepKind { InstallEmulator, InstallCores, GrantFolders, PlaceVault, RestoreSaves, ChooseConfig }

@Serializable data class GameDeviceState(val deviceId: String, val deviceName: String, val state: DeviceGameState)

@Serializable data class GameRow(
    val emulatorId: String,
    val gameKey: String,
    val displayName: String? = null,
    val installedOn: List<String>,
    val saveItemId: String? = null,
    val newestOnDeviceId: String? = null,
    val newestUtc: String? = null,
    val stateCount: Int,
    val openConflict: Boolean,
    // added by the web-UI server work; absent on older servers
    val devices: List<GameDeviceState> = emptyList(),
    // added by the web-UI server work; absent on older servers
    val newestOnDeviceName: String? = null,
    /** The game's system label as the server settled it (GBA, GC, …); absent on older servers. */
    val system: String? = null,
    /** True when the server can give a real case spine for the game at /v1/web/spine. */
    val spine: Boolean = false,
)

@Serializable data class VersionSummary(
    val id: String,
    val seq: Int,
    val deviceName: String? = null,
    val receivedUtc: String,
    val totalSize: Long,
    val core: String,
    val variant: String? = null,
    val pinned: Boolean,
    val conflictReason: ConflictReason? = null,
    val isHead: Boolean,
    val resolved: Boolean,
    // added by the web-UI server work; absent on older servers
    val manifestHash: String = "",
    val fileCount: Int = 0,
)

@Serializable data class SaveItemDetail(
    val itemId: String,
    val kind: ItemKind,
    val slot: String,
    val core: String,
    val headVersionId: String? = null,
    val openConflict: Boolean,
    val versions: List<VersionSummary>,
)

@Serializable data class GameDetail(val row: GameRow, val items: List<SaveItemDetail>)

@Serializable data class ConflictSide(
    val versionId: String,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val receivedUtc: String,
    val totalSize: Long,
    val fileCount: Int,
    val manifestHash: String,
    val isHead: Boolean,
)

@Serializable data class ConflictRow(
    val itemId: String,
    val emulatorId: String,
    val gameKey: String,
    val displayName: String? = null,
    val kind: ItemKind,
    val slot: String? = null,
    val core: String? = null,
    val reason: ConflictReason,
    val sides: List<ConflictSide>,
)

@Serializable data class ResolveConflictRequest(
    val choice: ResolveChoice,
    val keepVersionId: String? = null,
    val variantName: String? = null,
)

@Serializable data class BulkFirstSyncRequest(
    val deviceId: String,
    val choice: FirstSyncChoice,
    val itemIds: List<String>? = null,
)

@Serializable data class DeviceRef(val id: String, val name: String)

@Serializable data class GroupRow(
    val id: String,
    val name: String,
    val members: List<DeviceRef>,
    val headVersionId: String? = null,
    val openConflict: Boolean,
)

@Serializable data class SoloRow(
    val device: DeviceRef,
    val bundleId: String,
    val headVersionId: String? = null,
    val openConflict: Boolean,
)

@Serializable data class EmulatorConfigRow(
    val emulatorId: String,
    val groups: List<GroupRow>,
    val solo: List<SoloRow>,
)

@Serializable data class ConfigOverview(val emulators: List<EmulatorConfigRow>)

@Serializable data class JoinGroupRequest(val deviceId: String)

@Serializable data class ApplyConfigRequest(
    val targetDeviceId: String,
    val emulatorId: String,
    val sourceVersionId: String,
    val mode: ApplyMode,
)

@Serializable data class ApplyConfigResponse(val bundleId: String, val headVersionId: String, val groupId: String? = null)

@Serializable data class SetupStep(
    val kind: SetupStepKind,
    val emulatorId: String,
    val label: String,
    val done: Boolean,
    val detail: String? = null,
)

@Serializable data class SetupChecklist(val steps: List<SetupStep>)

@Serializable data class ActivityItem(
    val id: Long,
    val utc: String,
    val kind: String,
    val message: String,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val emulatorId: String? = null,
    val gameKey: String? = null,
)

@Serializable data class ActivityPage(val items: List<ActivityItem>, val nextBefore: Long? = null)

@Serializable data class DeviceSummary(
    val id: String,
    val name: String,
    val platform: String,
    val lastSeenUtc: String? = null,
    val shizukuOk: Boolean? = null,
    val isCurrent: Boolean,
    // added by the web-UI server work; absent on older servers
    val runningEmulators: List<String> = emptyList(),
)

/** GET /v1/vault (device key or web session): the user's vault items. [emulatorId] is null for an item of none. */
@Serializable data class VaultItemDto(
    val id: String,
    val category: String,
    val emulatorId: String? = null,
    val name: String,
    val headVersionId: String? = null,
    val totalSize: Long = 0,
)
