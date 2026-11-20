package dev.emusync.app.sync

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.emusync.app.model.ItemKey
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
    /** The app's colours: "system" (follow the device), "light" or "dark". */
    val theme: String = "system",
    /** The active variant (package) per emulator id; see EmulatorModule.activePackage. */
    val variants: Map<String, String> = emptyMap(),
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
    /** Emulators whose sync state the next session start forgets (see StateStore.applyPendingForget). */
    val pendingForget: Set<String> = emptySet(),
    /** Emulators whose items upload with fresh=true until each has had an answer (see StateStore.isFresh). */
    val freshEmulators: Set<String> = emptySet(),
    /** Store keys (items, and ItemKey.config for configs) of fresh emulators that have had their answer. */
    val freshSettled: Set<String> = emptySet(),
)

/**
 * Persistent agent state in one JSON file. Every change is written to `<file>.tmp`, fsynced ([fsync]), atomically
 * renamed over the file, and then the directory is fsynced ([dirSync], best effort) so the rename survives a crash.
 */
class StateStore(
    private val file: File,
    private val fsync: (FileOutputStream) -> Unit = { it.fd.sync() },
    private val dirSync: (File) -> Unit = ::syncDir,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private var data: StateData = load()
    private val changeCount = MutableStateFlow(0L)

    /** Incremented after every successful write (a failed write leaves it unchanged). For observers such as the UI. */
    val changes: StateFlow<Long> get() = changeCount

    private fun load(): StateData {
        if (!file.exists()) return StateData()
        return try {
            migrate(json.decodeFromString(StateData.serializer(), file.readText()))
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

    /**
     * Brings an older file up to date in memory; the next write persists it. The Eden package override that predates
     * per-emulator variants becomes Eden's variant (a variant already chosen wins) and leaves the overrides.
     */
    private fun migrate(d: StateData): StateData {
        val eden = d.settings.overrides[LEGACY_EDEN_PACKAGE] ?: return d
        val variants = if ("eden" in d.settings.variants || eden.isBlank()) d.settings.variants else d.settings.variants + ("eden" to eden)
        return d.copy(settings = d.settings.copy(overrides = d.settings.overrides - LEGACY_EDEN_PACKAGE, variants = variants))
    }

    private fun mutate(f: (StateData) -> StateData): Unit = synchronized(lock) {
        val next = f(data)
        file.absoluteFile.parentFile?.mkdirs()
        val dir = file.absoluteFile.parentFile
        val tmp = File(dir, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(StateData.serializer(), next).toByteArray(Charsets.UTF_8))
            out.flush()
            fsync(out)
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        data = next
        runCatching { dirSync(dir) }
        changeCount.value++
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
    /** Changes the settings from their current value in one write. */
    fun updateSettings(f: (DeviceSettingsData) -> DeviceSettingsData) = mutate { it.copy(settings = f(it.settings)) }

    /**
     * Makes [pkg] the active variant of [emulatorId] in one write. With [forgetState] the emulator's per-item and
     * config sync state goes too: it describes the previous variant's folder, and kept, the first scan of the new
     * folder would upload against the old base and fast-forward over the server's head.
     */
    fun setVariant(emulatorId: String, pkg: String, forgetState: Boolean) = mutate {
        val chosen = it.copy(settings = it.settings.copy(variants = it.settings.variants + (emulatorId to pkg)))
        if (forgetState) chosen.copy(pendingForget = it.pendingForget + emulatorId) else chosen
    }

    /**
     * Signs this device in with a new key, in one write. What it knew of the server's versions may belong to another
     * account or device record, so every emulator in [emulatorIds] is forgotten at the next session start.
     */
    fun signIn(serverUrl: String, deviceKey: String, deviceId: String, emulatorIds: Collection<String>) = mutate {
        it.copy(serverUrl = serverUrl, deviceKey = deviceKey, deviceId = deviceId, pendingForget = it.pendingForget + emulatorIds)
    }

    /**
     * Forgets the per-item and config sync state of every emulator in [pendingForget] and marks it fresh, in one
     * write. Called by the service at the start of a session, after the previous session has ended, so nothing that
     * session still writes can survive it. Returns the emulator ids it forgot.
     */
    fun applyPendingForget(): Set<String> = synchronized(lock) {
        val ids = data.pendingForget
        if (ids.isEmpty()) return emptySet()
        fun of(key: String) = ids.any { key.startsWith("$it|") }
        mutate {
            it.copy(
                items = it.items.filterKeys { k -> !of(k) },
                configs = it.configs - ids,
                freshEmulators = it.freshEmulators + ids,
                freshSettled = it.freshSettled.filterNot(::of).toSet(),
                pendingForget = emptySet(),
            )
        }
        ids
    }

    val pendingForget: Set<String> get() = synchronized(lock) { data.pendingForget }
    val freshEmulators: Set<String> get() = synchronized(lock) { data.freshEmulators }

    /**
     * Whether an upload of [key] (an item key, or ItemKey.config) of [emulatorId] is sent with fresh=true: the
     * emulator was forgotten and this key has had no answer since.
     */
    fun isFresh(emulatorId: String, key: String): Boolean =
        synchronized(lock) { emulatorId in data.freshEmulators && key !in data.freshSettled }

    /** Stores [s] for [itemKey]; with [settleFresh] the key's fresh mark goes in the same write. */
    fun put(itemKey: String, s: ItemState, settleFresh: Boolean) = mutate {
        it.copy(items = it.items + (itemKey to s), freshSettled = if (settleFresh) it.freshSettled + itemKey else it.freshSettled)
    }

    /** Stores [s] for [emulatorId]'s config; with [settleFresh] its fresh mark goes in the same write. */
    fun putConfigState(emulatorId: String, s: ConfigState, settleFresh: Boolean) = mutate {
        val key = ItemKey.config(emulatorId)
        it.copy(configs = it.configs + (emulatorId to s), freshSettled = if (settleFresh) it.freshSettled + key else it.freshSettled)
    }

    /** [key] had its answer without state to store (a conflict). */
    fun settleFresh(key: String) = mutate { it.copy(freshSettled = it.freshSettled + key) }

    /** Ends [emulatorId]'s fresh period once every key in [keys] (its current items and config) has had its answer. */
    fun clearFreshIfSettled(emulatorId: String, keys: Collection<String>) = synchronized(lock) {
        if (emulatorId !in data.freshEmulators || !data.freshSettled.containsAll(keys)) return
        mutate {
            it.copy(
                freshEmulators = it.freshEmulators - emulatorId,
                freshSettled = it.freshSettled.filterNot { k -> k.startsWith("$emulatorId|") }.toSet(),
            )
        }
    }

    /**
     * Forgets this device's key and id, and what it knew of the server's versions (per-item and config state), in one
     * write. The server URL stays for the next sign-in. Apply markers stay: they guard local files, not server state.
     */
    fun signOut(emulatorIds: Collection<String>) = mutate {
        // Cleared now, and again when the next sign-in's session starts: the stopping session may still write.
        it.copy(deviceKey = null, deviceId = null, items = emptyMap(), configs = emptyMap(), pendingForget = it.pendingForget + emulatorIds)
    }

    var signInForm: SignInForm
        get() = synchronized(lock) { data.signInForm }
        set(v) = mutate { it.copy(signInForm = v) }
}

private const val LEGACY_EDEN_PACKAGE = "eden.package"

/** fsyncs a directory so a rename inside it is durable. Best effort: some file systems refuse to open a directory. */
private fun syncDir(dir: File) {
    FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
}
