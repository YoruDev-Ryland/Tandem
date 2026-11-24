package dev.tandem.app.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val ProtocolJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

@Serializable enum class ItemKind { Save, State }
@Serializable enum class SaveUploadStatus { Accepted, Unchanged, Conflict, Superseded }
@Serializable enum class DeliveryKind { Save, Config, Vault }
@Serializable enum class DeliveryResult { Applied, SkippedLocalChanged, Failed }

/** GET /v1/server-info: no key needed. [protocol] is the server's Tandem-Protocol version. */
@Serializable data class ServerInfo(val name: String, val protocol: Int, val registrationOpen: Boolean)

@Serializable data class RegisterRequest(val email: String, val password: String, val deviceName: String, val platform: String)
@Serializable data class LoginRequest(val email: String, val password: String, val deviceName: String, val platform: String)
@Serializable data class RedeemPairingRequest(val code: String, val deviceName: String, val platform: String)
@Serializable data class DeviceKeyResponse(val userId: String, val deviceId: String, val deviceKey: String)

@Serializable data class MissingBlobsRequest(val hashes: List<String>)
@Serializable data class MissingBlobsResponse(val missing: List<String> = emptyList())
/** A save or state item by identity (for a Save, core is ignored by the server). */
@Serializable data class SaveItemKey(val emulatorId: String, val kind: ItemKind, val gameKey: String, val slot: String, val core: String)
@Serializable data class MissingSavesRequest(val items: List<SaveItemKey>)
@Serializable data class MissingSavesResponse(val missing: List<SaveItemKey> = emptyList())

@Serializable data class EmulatorStatus(
    val emulatorId: String,
    val `package`: String,
    val version: String,                // required by the server; "unknown" when the package has no versionName
    val cores: List<String>? = null,
)

@Serializable data class DeviceStatusRequest(
    val platform: String,
    val appVersion: String,
    val shizukuOk: Boolean,
    val emulators: List<EmulatorStatus>,
    val roots: Map<String, String>,
    /** Emulator ids running right now; replaces the server's list on every report. Null (omitted) means none. */
    val runningEmulators: List<String>? = null,
)

@Serializable data class InstalledGame(
    val gameKey: String,
    val displayName: String? = null,
    val crc32: String? = null,
    /** The game's system label (GBA, SNES, …) when the device can tell it; RetroArch only. */
    val system: String? = null,
    /** The game's GameTDB id when the device can read it from the game file (DS game code, 3DS product code). */
    val gameId: String? = null,
)
@Serializable data class InstallsRequest(val games: List<InstalledGame>)
/** POST /v1/devices/me/forget: the server drops what it recorded this device as holding for these emulators. */
@Serializable data class ForgetRequest(val emulatorIds: List<String>)
@Serializable data class InstallsResponse(val added: Int = 0, val removed: Int = 0)

@Serializable data class SaveUploadRequest(
    val emulatorId: String,
    val kind: ItemKind,
    val gameKey: String,
    val slot: String,
    val core: String,
    val baseVersionId: String? = null,
    val manifest: List<ManifestEntry>,
    val displayName: String? = null,
    /** True while this device has forgotten what it knew of the item (variant switch, new sign-in); null otherwise. */
    val fresh: Boolean? = null,
)
@Serializable data class SaveUploadResponse(
    val status: SaveUploadStatus,
    val itemId: String,
    val versionId: String,
    val headVersionId: String,
)

@Serializable data class ConfigUploadRequest(
    val emulatorId: String,
    val baseVersionId: String? = null,
    val files: List<ManifestEntry>,
    /** As [SaveUploadRequest.fresh], for the emulator's config bundle. */
    val fresh: Boolean? = null,
)
@Serializable data class ConfigUploadResponse(
    val status: SaveUploadStatus,
    val bundleId: String,
    val versionId: String,
)

@Serializable data class DeliveryDto(
    val id: String,
    val kind: DeliveryKind,
    val targetId: String,
    val versionId: String,
    val knownVersionId: String? = null,
    val emulatorId: String,
    val gameKey: String? = null,
    val itemKind: ItemKind? = null,
    val slot: String? = null,
    val core: String? = null,
    val manifest: List<ManifestEntry> = emptyList(),
    val missingLocalKeys: List<String>? = null,
) {
    val slotOrEmpty: String get() = slot ?: ""
    val coreOrEmpty: String get() = core ?: ""
}
@Serializable data class DeliveryList(val items: List<DeliveryDto> = emptyList())
@Serializable data class AckRequest(val result: DeliveryResult, val detail: String? = null)
