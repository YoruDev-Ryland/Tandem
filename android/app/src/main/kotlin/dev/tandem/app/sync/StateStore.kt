package dev.tandem.app.sync

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.tandem.app.model.ItemKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

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
    /**
     * When true (the default for a new install) arriving deliveries are applied as they come, or when their emulator
     * closes; when false only on an explicit request (Sync now, a resolve, a setup choice). A state file written
     * before this setting existed loads as false, so an existing install keeps what it did.
     */
    val autoApply: Boolean = true,
    /** The app's colours: "system" (follow the device), "light" or "dark". */
    val theme: String = "system",
    /** A haptic tick per cursor step, a confirm on A, a reject at a list's end (the system's own setting still rules). */
    val haptics: Boolean = true,
    /** Short UI sounds (move, open, back); off unless chosen. */
    val sounds: Boolean = false,
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
    /**
     * Emulators forgotten locally whose server records (POST /v1/devices/me/forget) are not yet dropped. Until they
     * are, every upload of theirs is sent with fresh=true (see StateStore.isFresh).
     */
    val serverForgetPending: Set<String> = emptySet(),
    /**
     * Emulators whose running held back an apply the user asked for (Sync now, a resolve, a setup choice): their next
     * exit applies what waits for them, even with auto-apply off. Persisted so a session restart doesn't lose the ask.
     */
    val applyOnExit: Set<String> = emptySet(),
    /** Epoch ms of the last sync that reached the server with nothing left failing (see StateStore.lastSyncedAt). */
    val lastSyncedAt: Long? = null,
    /** The server answered 401 device_revoked: this device was removed there. Cleared by signing in or out. */
    val revoked: Boolean = false,
    /** The setup step "Finish later" left, so Settings › Set up again resumes there; null when setup finished. */
    val setupResume: String? = null,
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
            val text = file.readText()
            val d = json.decodeFromString(StateData.serializer(), text)
            // Written before auto-apply existed: that install never applied on its own, so it still doesn't.
            val settings = json.parseToJsonElement(text).jsonObject["settings"] as? JsonObject
            migrate(if (settings != null && "autoApply" !in settings) d.copy(settings = d.settings.copy(autoApply = false)) else d)
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
    /** Every item key this device has sync state for (ItemKey.of). */
    fun itemKeys(): Set<String> = synchronized(lock) { data.items.keys.toSet() }
    fun put(itemKey: String, s: ItemState) = mutate { it.copy(items = it.items + (itemKey to s)) }
    /** Forgets what this device knew of [itemKeys] (the server no longer has them): they upload again as new. */
    fun forgetItems(itemKeys: Collection<String>) = mutate { it.copy(items = it.items - itemKeys.toSet()) }
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
        it.copy(serverUrl = serverUrl, deviceKey = deviceKey, deviceId = deviceId, pendingForget = it.pendingForget + emulatorIds,
            applyOnExit = emptySet(),   // the ask was made of another account or device record
            revoked = false, lastSyncedAt = null)
    }

    /**
     * Moves this device to another address of the same server (a new domain or proxy), in one write. The key, the
     * device id and every bit of sync state stay: the caller has checked the new address answers for this device.
     */
    fun changeServer(serverUrl: String) = mutate {
        it.copy(serverUrl = serverUrl, signInForm = it.signInForm.copy(serverUrl = serverUrl))
    }

    /** See StateData.lastSyncedAt. */
    val lastSyncedAt: Long? get() = synchronized(lock) { data.lastSyncedAt }

    /**
     * Records a sync at [at] (epoch ms). Written at most once per [minGapMillis], so a busy session doesn't rewrite the
     * state file for every upload; returns whether it was written.
     */
    fun markSynced(at: Long, minGapMillis: Long = 60_000L): Boolean = synchronized(lock) {
        val last = data.lastSyncedAt
        if (last != null && at - last in 0 until minGapMillis) return false
        mutate { it.copy(lastSyncedAt = at) }
        true
    }

    /** See StateData.revoked. */
    val revoked: Boolean get() = synchronized(lock) { data.revoked }
    /** Marks this device removed on the server; returns true only the first time (one notification). */
    fun markRevoked(): Boolean = synchronized(lock) {
        if (data.revoked) return false
        mutate { it.copy(revoked = true) }
        true
    }

    /** Whether sync should run: signed in, and not removed on the server. */
    val canSync: Boolean get() = synchronized(lock) { !data.deviceKey.isNullOrBlank() && !data.revoked }

    /** See StateData.setupResume. */
    var setupResume: String?
        get() = synchronized(lock) { data.setupResume }
        set(v) { if (v != setupResume) mutate { it.copy(setupResume = v) } }

    /**
     * Forgets the per-item and config sync state of every emulator in [pendingForget] and marks its server records
     * to be dropped ([serverForgetPending]), in one write. Called by the service at the start of a session, after the previous session has ended, so nothing that
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
                serverForgetPending = it.serverForgetPending + ids,
                pendingForget = emptySet(),
            )
        }
        ids
    }

    val pendingForget: Set<String> get() = synchronized(lock) { data.pendingForget }
    /** Emulators whose server records still have to be dropped (POST /v1/devices/me/forget). */
    val serverForgetPending: Set<String> get() = synchronized(lock) { data.serverForgetPending }

    /** The server dropped its records for [ids]: their uploads are ordinary again. */
    fun forgetDone(ids: Collection<String>) = mutate { it.copy(serverForgetPending = it.serverForgetPending - ids.toSet()) }

    /**
     * Whether uploads of [emulatorId] are sent with fresh=true: its state was forgotten here and the server may still
     * record this device as holding versions it no longer has. Answers to uploads never end it; [forgetDone] does.
     */
    fun isFresh(emulatorId: String): Boolean = synchronized(lock) { emulatorId in data.serverForgetPending }

    /**
     * Forgets this device's key and id, and what it knew of the server's versions (per-item and config state), in one
     * write. The server URL stays for the next sign-in. Apply markers stay: they guard local files, not server state.
     */
    fun signOut(emulatorIds: Collection<String>) = mutate {
        // Cleared now, and again when the next sign-in's session starts: the stopping session may still write.
        it.copy(deviceKey = null, deviceId = null, items = emptyMap(), configs = emptyMap(), pendingForget = it.pendingForget + emulatorIds,
            applyOnExit = emptySet(), revoked = false, lastSyncedAt = null, setupResume = null)
    }

    /** See StateData.applyOnExit. */
    val applyOnExit: Set<String> get() = synchronized(lock) { data.applyOnExit }
    fun flagApplyOnExit(ids: Set<String>) = mutate { it.copy(applyOnExit = it.applyOnExit + ids) }
    fun clearApplyOnExit(id: String) = mutate { it.copy(applyOnExit = it.applyOnExit - id) }
    fun setApplyOnExit(ids: Set<String>) = mutate { it.copy(applyOnExit = ids) }

    var signInForm: SignInForm
        get() = synchronized(lock) { data.signInForm }
        set(v) = mutate { it.copy(signInForm = v) }
}

private const val LEGACY_EDEN_PACKAGE = "eden.package"

/** fsyncs a directory so a rename inside it is durable. Best effort: some file systems refuse to open a directory. */
private fun syncDir(dir: File) {
    FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
}
