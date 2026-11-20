package dev.emusync.app.sync

import dev.emusync.app.api.Api
import dev.emusync.app.api.ApiException
import dev.emusync.app.fs.FileEntry
import dev.emusync.app.fs.Fs
import dev.emusync.app.fs.FsUnavailableException
import dev.emusync.app.model.*
import dev.emusync.app.modules.*
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A server-reported conflict. [kind] and [gameKey] are null for a config bundle. */
data class SyncConflict(
    val emulatorId: String,
    val kind: ItemKind?,
    val gameKey: String?,
    val slot: String,
    val core: String,
    val displayName: String?,
)

/**
 * Scans emulator modules and uploads changed saves, states and config bundles.
 *
 * Concurrency: the service calls the suspend functions from one coroutine at a time; they are guarded by a
 * [Mutex] anyway (non-reentrant: internal code never calls the public suspend functions). [onFsEvent] may be
 * called from any thread and only touches the event bookkeeping, which has its own lock.
 *
 * Errors: [FsUnavailableException] aborts the pass and is rethrown with no state written for the item in
 * flight; [ApiException] skips one item; [ModuleException] skips one module; [IOException] skips one item.
 */
class SyncEngine(
    private val fs: Fs,
    private val api: Api,
    private val store: StateStore,
    private val registry: ModuleRegistry,
    private val settings: DeviceSettings,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val versionOf: (String) -> String?,
    private val appVersion: String,
    private val shizukuOk: () -> Boolean,
    private val onConflict: (SyncConflict) -> Unit,
    private val storageRoot: String = "/storage",
    private val quietMillis: Long = SyncConfig.quietMillis,
) {
    private class Tracked(val module: EmulatorModule, val detected: Detected, val item: LocalItem)

    private sealed interface Build
    private class Built(val entries: List<ManifestEntry>, val sources: Map<String, String>, val stats: Map<String, FileEntry>) : Build
    private class TooLarge(val path: String, val size: Long) : Build

    private val mutex = Mutex()

    // Event bookkeeping, guarded by [ev].
    private val ev = Any()
    private val detected = LinkedHashMap<String, Pair<EmulatorModule, Detected>>()
    private val watchDirs = HashMap<String, List<String>>()
    private val tracked = LinkedHashMap<String, Tracked>()   // store key -> item from the last scan
    private val dirty = HashMap<String, Long>()               // store key -> time of last event
    private val rescan = HashMap<String, Long>()              // emulatorId -> time of last event

    private val lastInstalls = HashMap<String, List<InstalledGame>>()   // touched only under [mutex]

    // ---- public API ----

    /** Detects modules, reports status and installs, then uploads every changed item and idle config bundle. */
    suspend fun fullScan(): Unit = mutex.withLock {
        val found = LinkedHashMap<String, Pair<EmulatorModule, Detected>>()
        for (m in registry.all()) detectOne(m)?.let { found[m.emulatorId] = m to it }
        val dirs = found.mapValues { (_, p) -> moduleCall(p.first, "watchDirs") { p.first.watchDirs(fs, p.second) } ?: emptyList() }
        synchronized(ev) {
            detected.clear(); detected.putAll(found)
            watchDirs.clear(); watchDirs.putAll(dirs)
            tracked.entries.removeIf { it.value.module.emulatorId !in found }
        }
        reportStatus(found.values.toList())
        for ((m, d) in found.values) reportInstalls(m, d)
        val now = clock()
        for ((m, d) in found.values) {
            val items = scanModule(m, d) ?: continue
            for (item in items) {
                val key = storeKey(m.emulatorId, item)
                val pending = synchronized(ev) { dirty[key] }
                if (pending != null && pending + quietMillis > now) continue   // tick() uploads it once quiet
                uploadItem(m, d, item)
            }
        }
        for ((m, d) in found.values) if (!fs.isProcessAlive(d.pkg)) uploadConfigBundle(m, d)
    }

    /** Marks items under [dir] dirty and their module for rescan. */
    fun onFsEvent(dir: String) {
        val t = clock()
        synchronized(ev) {
            val hit = HashSet<String>()
            for ((key, tr) in tracked) if (under(dir, tr.item.baseDir)) {
                dirty[key] = t; hit += tr.module.emulatorId
            }
            for ((id, dirs) in watchDirs) if (id in hit || dirs.any { under(dir, it) || under(it, dir) }) rescan[id] = t
        }
    }

    /** Rescans modules that saw events, then uploads dirty items quiet for at least quietMillis. */
    suspend fun tick(): Unit = mutex.withLock {
        val now = clock()
        val (rescans, mods) = synchronized(ev) {
            val r = HashMap(rescan); rescan.clear()
            r to r.keys.mapNotNull { detected[it] }
        }
        val due = HashMap<String, Long>()
        try {
            for ((m, d) in mods) {
                val t = rescans.getValue(m.emulatorId)
                val before = synchronized(ev) { tracked.filterValues { it.module.emulatorId == m.emulatorId }.mapValues { it.value.item } }
                val items = scanModule(m, d) ?: continue
                synchronized(ev) {
                    for (item in items) {
                        val key = storeKey(m.emulatorId, item)
                        if (before[key] != item) dirty[key] = maxOf(dirty[key] ?: t, t)   // new dirs or changed file lists
                    }
                }
            }
            synchronized(ev) {
                dirty.keys.retainAll(tracked.keys)
                for ((k, t) in dirty) if (t + quietMillis <= now) due[k] = t
                dirty.keys.removeAll(due.keys)
            }
            for (key in due.keys.sorted()) {
                val tr = synchronized(ev) { tracked[key] } ?: continue
                uploadItem(tr.module, tr.detected, tr.item)
                synchronized(ev) { due.remove(key) }
            }
        } catch (e: FsUnavailableException) {
            synchronized(ev) {   // put back what this pass took, so nothing is lost
                for ((id, t) in rescans) rescan[id] = maxOf(rescan[id] ?: t, t)
                for ((k, t) in due) dirty[k] = maxOf(dirty[k] ?: t, t)
            }
            throw e
        }
    }

    /** Uploads the config bundle, reports installs and uploads changed items of [emulatorId]. */
    suspend fun onEmulatorExited(emulatorId: String): Unit = mutex.withLock {
        val m = registry.byId(emulatorId) ?: return@withLock
        val d = detectOne(m) ?: return@withLock
        val dirs = moduleCall(m, "watchDirs") { m.watchDirs(fs, d) } ?: emptyList()
        synchronized(ev) { detected[emulatorId] = m to d; watchDirs[emulatorId] = dirs }
        uploadConfigBundle(m, d)
        reportInstalls(m, d)
        val items = scanModule(m, d) ?: return@withLock
        for (item in items) uploadItem(m, d, item)
    }

    /** Uploads one item. Null when skipped: unchanged, not uploadable, deferred, or failed. */
    suspend fun upload(module: EmulatorModule, detected: Detected, item: LocalItem): SaveUploadStatus? =
        mutex.withLock { uploadItem(module, detected, item) }

    /** Uploads the config bundle. Null when skipped: unchanged, not uploadable, deferred, or failed. */
    suspend fun uploadConfig(module: EmulatorModule, detected: Detected): SaveUploadStatus? =
        mutex.withLock { uploadConfigBundle(module, detected) }

    // ---- uploads ----

    private suspend fun uploadItem(m: EmulatorModule, d: Detected, item: LocalItem): SaveUploadStatus? {
        val id = m.emulatorId
        val key = storeKey(id, item)
        val name = "$id/${item.kind}/${item.gameKey}" + (if (item.slot.isNotEmpty()) "#${item.slot}" else "")
        val built = buildOrNull(name, item.files.map { it to "${item.baseDir}/$it" }) ?: return null
        val hash = Manifests.hash(built.entries)
        val known = store.get(key)
        if (hash == known?.knownManifestHash) return null
        if (!stable(built)) {
            synchronized(ev) { tracked[key] = Tracked(m, d, item); dirty[key] = clock() }
            log("$name: changed while reading, deferred")
            return null
        }
        return apiCall(name) {
            pushBlobs(built)
            val r = api.uploadSave(SaveUploadRequest(id, item.kind, item.gameKey, item.slot, item.core,
                known?.knownVersionId, built.entries, item.displayName))
            when (r.status) {
                SaveUploadStatus.Accepted, SaveUploadStatus.Unchanged -> {
                    val v = if (r.status == SaveUploadStatus.Accepted) r.versionId else r.headVersionId
                    store.put(key, ItemState(v, hash, hash))
                    log("$name: ${r.status} $v")
                }
                SaveUploadStatus.Conflict -> {
                    log("$name: conflict (head ${r.headVersionId})")
                    onConflict(SyncConflict(id, item.kind, item.gameKey, item.slot, item.core, item.displayName))
                }
            }
            r.status
        }
    }

    private suspend fun uploadConfigBundle(m: EmulatorModule, d: Detected): SaveUploadStatus? {
        val id = m.emulatorId
        val name = "$id/config"
        val files = moduleCall(m, "configBundle") { m.configBundle(fs, d).files } ?: return null
        val built = buildOrNull(name, files.entries.map { it.key to it.value }) ?: return null
        val hash = Manifests.hash(built.entries)
        val known = store.configState(id)
        if (hash == known?.knownManifestHash) return null
        if (!stable(built)) {
            log("$name: changed while reading, skipped")
            return null
        }
        return apiCall(name) {
            pushBlobs(built)
            val r = api.uploadConfig(ConfigUploadRequest(id, known?.knownVersionId, built.entries))
            when (r.status) {
                SaveUploadStatus.Accepted, SaveUploadStatus.Unchanged -> {
                    store.putConfigState(id, ConfigState(r.bundleId, r.versionId, hash, hash))
                    log("$name: ${r.status} ${r.versionId}")
                }
                SaveUploadStatus.Conflict -> {
                    log("$name: conflict")
                    onConflict(SyncConflict(id, null, null, "", "", null))
                }
            }
            r.status
        }
    }

    private suspend fun pushBlobs(b: Built) {
        if (b.sources.isEmpty()) return
        val missing = api.missingBlobs(b.sources.keys.toList())
        val sizes = b.entries.associate { it.hash to it.size }
        for (h in missing) {
            val abs = b.sources[h] ?: continue
            api.putBlob(h, sizes.getValue(h)) { fs.openRead(abs) }
        }
    }

    // ---- manifests ----

    /** Builds over (manifest path, absolute path) pairs; null (logged) when the item can't be uploaded. */
    private fun buildOrNull(name: String, files: List<Pair<String, String>>): Built? {
        val b = try {
            build(files)
        } catch (e: IOException) {
            log("$name: read failed, skipped (${e.message})"); return null
        } catch (e: ModuleException) {
            log("$name: ${e.message}"); return null
        }
        if (b is TooLarge) {
            log("$name: ${b.path} too large (${b.size} bytes), skipped"); return null
        }
        b as Built
        if (!Manifests.isUploadable(b.entries)) {
            if (b.entries.size > Manifests.MAX_ENTRIES) log("$name: ${b.entries.size} files, over the limit, skipped")
            return null
        }
        return b
    }

    private fun build(files: List<Pair<String, String>>): Build {
        files.forEach { Paths.requireSafeRelative(it.first) }
        val stats = HashMap<String, FileEntry>()
        for ((rel, abs) in files) {
            val st = fs.stat(abs) ?: throw IOException("vanished: $abs")
            if (st.size > SyncConfig.maxBlobBytes) return TooLarge(rel, st.size)
            stats[abs] = st
        }
        val sources = HashMap<String, String>()
        val entries = files.map { (rel, abs) ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            var size = 0L
            fs.openRead(abs).use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n); size += n
                }
            }
            val h = md.digest().joinToString("") { "%02x".format(it) }
            sources.putIfAbsent(h, abs)
            ManifestEntry(rel, h, size)
        }.sortedBy { it.path }
        return Built(entries, sources, stats)
    }

    /** True when every file still has the size and mtime it had before hashing. */
    private fun stable(b: Built): Boolean = b.stats.all { (abs, before) ->
        val now = fs.stat(abs)
        now != null && now.size == before.size && now.mtimeMillis == before.mtimeMillis
    }

    // ---- scanning, status, installs ----

    private fun detectOne(m: EmulatorModule): Detected? = moduleCall(m, "detect") { m.detect(fs, settings) }

    /** Lists the module's items and refreshes [tracked]; null (logged) when the module failed. */
    private fun scanModule(m: EmulatorModule, d: Detected): List<LocalItem>? {
        val items = moduleCall(m, "saveItems") { m.saveItems(fs, d) } ?: return null
        val byKey = items.associateBy { storeKey(m.emulatorId, it) }
        synchronized(ev) {
            tracked.entries.removeIf { it.value.module.emulatorId == m.emulatorId && it.key !in byKey }
            for ((k, item) in byKey) tracked[k] = Tracked(m, d, item)
            dirty.keys.retainAll(tracked.keys)
        }
        return items
    }

    private suspend fun reportStatus(found: List<Pair<EmulatorModule, Detected>>) {
        val emulators = found.map { (m, d) ->
            EmulatorStatus(m.emulatorId, d.pkg, versionOf(d.pkg) ?: "", moduleCall(m, "cores") { m.cores(fs, d) })
        }
        val roots = LinkedHashMap<String, String>()
        roots["{INTERNAL}"] = "$storageRoot/emulated/0"
        sdCard()?.let { roots["{SD}"] = it }
        for ((m, d) in found) roots["{EMU:${m.emulatorId}}"] = d.dataRoot
        apiCall("status") { api.putStatus(DeviceStatusRequest("android", appVersion, shizukuOk(), emulators, roots)) }
    }

    private fun sdCard(): String? = try {
        fs.list(storageRoot).filter { it.isDir && sdName.matches(it.path.substringAfterLast('/')) }.map { it.path }.minOrNull()
    } catch (e: FsUnavailableException) {
        throw e
    } catch (e: Exception) {
        log("sd lookup failed: ${e.message}"); null
    }

    private suspend fun reportInstalls(m: EmulatorModule, d: Detected) {
        val games = moduleCall(m, "installedGames") { m.installedGames(fs, d, settings) } ?: return
        if (lastInstalls[m.emulatorId] == games) return
        apiCall("${m.emulatorId}/installs") {
            api.putInstalls(m.emulatorId, games)
            lastInstalls[m.emulatorId] = games
        }
    }

    // ---- helpers ----

    private fun <T> moduleCall(m: EmulatorModule, what: String, f: () -> T): T? = try {
        f()
    } catch (e: ModuleException) {
        log("${m.emulatorId}: $what failed, skipped (${e.message})"); null
    } catch (e: IOException) {
        log("${m.emulatorId}: $what read failed, skipped (${e.message})"); null
    }

    private inline fun <T> apiCall(name: String, f: () -> T): T? = try {
        f()
    } catch (e: ApiException) {
        log("$name: server error ${e.status} ${e.code ?: ""}".trimEnd()); null
    } catch (e: IOException) {
        log("$name: io error, skipped (${e.message})"); null
    }

    private fun storeKey(emulatorId: String, item: LocalItem) =
        ItemKey.of(emulatorId, item.kind.name, item.gameKey, item.slot, if (item.kind == ItemKind.Save) "" else item.core)

    private fun under(path: String, base: String) = path == base || path.startsWith("$base/")

    private companion object {
        val sdName = Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")
    }
}
