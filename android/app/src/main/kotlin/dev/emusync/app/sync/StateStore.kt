package dev.emusync.app.sync

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ItemState(val knownVersionId: String?, val knownManifestHash: String?, val lastUploadedHash: String?)

@Serializable
data class ConfigState(
    val bundleId: String?,
    val knownVersionId: String?,
    val knownManifestHash: String?,
    val lastUploadedHash: String?,
)

/**
 * One target file of an in-progress apply. [deliveredHash] is what the apply writes there, or null for a local
 * extra the apply deletes. [backupRel] is its copy under the marker's backupDir (null: the apply created it) and
 * [backupHash] that copy's hash.
 */
@Serializable
data class MarkerFile(val deliveredHash: String?, val backupRel: String?, val backupHash: String?)

/**
 * A delivery write in progress for [storeKey]. Written before the first file write and cleared atomically with the
 * state update. A live marker means the item may be half-written: recovery undoes only the changes whose content
 * is still what the apply wrote ([files], keyed by absolute path). [newFiles] are absolute paths the apply created.
 */
@Serializable
data class ApplyMarker(
    val storeKey: String,
    val emulatorId: String,
    val deliveryId: String,
    val backupDir: String?,
    val targetDir: String,
    val newFiles: List<String>,
    val files: Map<String, MarkerFile> = emptyMap(),
    val name: String = storeKey,
)

@Serializable
data class DeviceSettingsData(
    val romDirs: List<String> = emptyList(),
    val overrides: Map<String, String> = emptyMap(),
    /** When false (the default) deliveries are applied only on an explicit request from the debug screen. */
    val autoApply: Boolean = false,
)

/** What the debug screen's sign-in form last held (never the password). Not used for syncing. */
@Serializable
data class SignInForm(val serverUrl: String? = null, val email: String? = null)

@Serializable
private data class StateData(
    val items: Map<String, ItemState> = emptyMap(),
    val configs: Map<String, ConfigState> = emptyMap(),
    val deviceKey: String? = null,
    val serverUrl: String? = null,
    val deviceId: String? = null,
    val settings: DeviceSettingsData = DeviceSettingsData(),
    val applying: Map<String, ApplyMarker> = emptyMap(),
    val signInForm: SignInForm = SignInForm(),
)

class StateStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private var data: StateData = load()

    private fun load(): StateData {
        if (!file.exists()) return StateData()
        return try {
            json.decodeFromString(StateData.serializer(), file.readText())
        } catch (e: Exception) {
            if (e !is kotlinx.serialization.SerializationException && e !is IllegalArgumentException) throw e
            // Throws if the corrupt copy can't be preserved: never start empty and overwrite it.
            Files.move(
                file.toPath(),
                File(file.absoluteFile.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}").toPath(),
            )
            StateData()
        }
    }

    private fun mutate(f: (StateData) -> StateData): Unit = synchronized(lock) {
        val next = f(data)
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.absoluteFile.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(StateData.serializer(), next))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        data = next
    }

    fun get(itemKey: String): ItemState? = synchronized(lock) { data.items[itemKey] }
    fun put(itemKey: String, s: ItemState) = mutate { it.copy(items = it.items + (itemKey to s)) }
    fun configState(emulatorId: String): ConfigState? = synchronized(lock) { data.configs[emulatorId] }
    fun putConfigState(emulatorId: String, s: ConfigState) = mutate { it.copy(configs = it.configs + (emulatorId to s)) }

    fun applyMarker(key: String): ApplyMarker? = synchronized(lock) { data.applying[key] }
    fun applyMarkers(): Map<String, ApplyMarker> = synchronized(lock) { data.applying }
    fun putApplyMarker(m: ApplyMarker) = mutate { it.copy(applying = it.applying + (m.storeKey to m)) }
    fun clearApplyMarker(key: String) = mutate { it.copy(applying = it.applying - key) }

    /** Stores the applied state and clears the marker in one atomic write. */
    fun finishApply(markerKey: String, item: Pair<String, ItemState>? = null, config: Pair<String, ConfigState>? = null) =
        mutate {
            it.copy(
                applying = it.applying - markerKey,
                items = if (item != null) it.items + item else it.items,
                configs = if (config != null) it.configs + config else it.configs,
            )
        }

    var deviceKey: String?
        get() = synchronized(lock) { data.deviceKey }
        set(v) = mutate { it.copy(deviceKey = v) }
    var serverUrl: String?
        get() = synchronized(lock) { data.serverUrl }
        set(v) = mutate { it.copy(serverUrl = v) }
    var deviceId: String?
        get() = synchronized(lock) { data.deviceId }
        set(v) = mutate { it.copy(deviceId = v) }
    var settings: DeviceSettingsData
        get() = synchronized(lock) { data.settings }
        set(v) = mutate { it.copy(settings = v) }
    var signInForm: SignInForm
        get() = synchronized(lock) { data.signInForm }
        set(v) = mutate { it.copy(signInForm = v) }
}
