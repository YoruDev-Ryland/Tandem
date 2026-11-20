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
    private class Built(
        val entries: List<ManifestEntry>,
        val sources: Map<String, String>,            // hash -> absolute path
        val stats: Map<String, FileEntry>,           // absolute path -> stat taken before hashing
        val hashed: Map<String, CachedHash>,         // newly computed hashes, committed once stable
    ) : Build
    private class TooLarge(val path: String, val size: Long) : Build
    private object Fresh : Build                     // a file was modified within the quiet window
    private data class CachedHash(val size: Long, val mtime: Long, val hash: String)

    private val mutex = Mutex()

    // Event bookkeeping, guarded by [ev].
    private val ev = Any()
    private val detected = LinkedHashMap<String, Pair<EmulatorModule, Detected>>()
    private val watchDirs = HashMap<String, List<String>>()
    private val tracked = LinkedHashMap<String, Tracked>()   // store key -> item from the last scan
    private val dirty = HashMap<String, Long>()               // store key -> time of last event
    private val rescan = HashMap<String, Long>()              // emulatorId -> time of last event

    private val marks = HashMap<String, Long>()               // store key -> sequence of its last event
    private var eventSeq = 0L
    private val shadowLogged = HashSet<String>()
    private val queued = HashSet<String>()                   // store keys whose last upload attempt failed

    // Touched only under [mutex].
    private val lastInstalls = HashMap<String, List<InstalledGame>>()
    /** Absolute path -> last stable hash. Only ever used to conclude "unchanged"; never feeds an uploaded manifest. */
    private val hashCache = java.util.concurrent.ConcurrentHashMap<String, CachedHash>()
    private val notified = HashSet<String>()                  // "<key>|<manifest hash>" conflicts already reported

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

    /** Drops cached hashes for [paths] (files the applier wrote, deleted or restored). Lock-free. */
    fun invalidate(paths: Collection<String>) {
        val s = paths.toSet()
        hashCache.keys.removeIf { it in s }
    }

    /** Marks items under [dir] dirty and their module for rescan. */
    fun onFsEvent(dir: String) {
        hashCache.keys.removeIf { under(it, dir) }
        val t = clock()
        synchronized(ev) {
            val hit = HashSet<String>()
            for ((key, tr) in tracked) if (under(dir, tr.item.baseDir)) {
                dirty[key] = t; marks[key] = ++eventSeq; hit += tr.module.emulatorId
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

    /**
     * The module's items with duplicate store keys collapsed: per key, the item with the newest file mtime wins
     * (tie: smallest baseDir). Throws what the module throws.
     */
    fun currentItems(module: EmulatorModule, detected: Detected): List<LocalItem> =
        dedupe(module, module.saveItems(fs, detected))

    /** Uploads one item. Null when skipped: unchanged, not uploadable, deferred, or failed. */
    suspend fun upload(module: EmulatorModule, detected: Detected, item: LocalItem): SaveUploadStatus? =
        mutex.withLock { uploadItem(module, detected, item) }

    /** Uploads the config bundle. Null when skipped: unchanged, not uploadable, deferred, or failed. */
    suspend fun uploadConfig(module: EmulatorModule, detected: Detected): SaveUploadStatus? =
        mutex.withLock { uploadConfigBundle(module, detected) }

    /** Every watch dir of the modules detected by the last scan, deduped and sorted. */
    fun allWatchDirs(): List<String> = synchronized(ev) { watchDirs.values.flatten().distinct().sorted() }

    /** The emulators detected by the last scan, in registry order. */
    fun detectedEmulators(): List<Detected> = synchronized(ev) { detected.values.map { it.second } }

    /** Tracked item count per emulator id, from the last scan. */
    fun itemCounts(): Map<String, Int> = synchronized(ev) { tracked.values.groupingBy { it.module.emulatorId }.eachCount() }

    /** Items and config bundles whose last upload failed on a server or network error (retried by the next scan). */
    fun queuedCount(): Int = synchronized(ev) { queued.size }

    /** Runs [block] under the engine's lock (non-reentrant: [block] must not call the engine's public suspend functions). */
    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    // ---- uploads ----

    private suspend fun uploadItem(m: EmulatorModule, d: Detected, item: LocalItem): SaveUploadStatus? {
        val id = m.emulatorId
        val key = storeKey(id, item)
        val name = "$id/${item.kind}/${item.gameKey}" + (if (item.slot.isNotEmpty()) "#${item.slot}" else "")
        if (store.applyMarker(key) != null) {
            log("$name: delivery being applied or awaiting restore, skipped")
            return null
        }
        val seq0 = synchronized(ev) { marks[key] }
        fun defer(why: String): SaveUploadStatus? {
            synchronized(ev) { tracked[key] = Tracked(m, d, item); dirty[key] = clock() }
            log("$name: $why, deferred")
            return null
        }
        val built = when (val b = buildOrNull(name, item.files.map { it to "${item.baseDir}/$it" }, rejectFresh = true)) {
            null -> return null
            Fresh -> return defer("modified within quiet window")
            else -> b as Built
        }
        if (!stable(built)) return defer("changed while reading")
        if (synchronized(ev) { marks[key] } != seq0) return defer("event while reading")
        hashCache.putAll(built.hashed)
        val known = store.get(key)
        // The cache may only conclude "unchanged"; a manifest that goes to the server is hashed fresh from disk.
        if (Manifests.hash(built.entries) == known?.knownManifestHash) { synchronized(ev) { queued -= key }; return null }
        val fresh = when (val b = buildOrNull(name, item.files.map { it to "${item.baseDir}/$it" }, rejectFresh = true, useCache = false)) {
            null -> return null
            Fresh -> return defer("modified within quiet window")
            else -> b as Built
        }
        if (!stable(fresh)) return defer("changed while reading")
        if (synchronized(ev) { marks[key] } != seq0) return defer("event while reading")
        hashCache.putAll(fresh.hashed)
        val hash = Manifests.hash(fresh.entries)
        if (hash == known?.knownManifestHash) { synchronized(ev) { queued -= key }; return null }
        return queueing(key, apiCall(name) {
            val built = fresh
            pushBlobs(built)
            val r = api.uploadSave(SaveUploadRequest(id, item.kind, item.gameKey, item.slot, item.core,
                known?.knownVersionId, built.entries, item.displayName))
            when (r.status) {
                SaveUploadStatus.Accepted, SaveUploadStatus.Unchanged -> {
                    val v = if (r.status == SaveUploadStatus.Accepted) r.versionId else r.headVersionId
                    store.put(key, ItemState(v, hash, hash))
                    log("$name: ${r.status} $v")
                }
                SaveUploadStatus.Superseded -> {
                    // content equals a version the user already decided against; adopt the matched version
                    store.put(key, ItemState(r.versionId, hash, hash))
                    log("$name: superseded by ${r.versionId}")
                }
                SaveUploadStatus.Conflict -> {
                    log("$name: conflict (head ${r.headVersionId})")
                    if (notified.add("$key|$hash"))
                        onConflict(SyncConflict(id, item.kind, item.gameKey, item.slot, item.core, item.displayName))
                }
            }
            r.status
        })
    }

    private suspend fun uploadConfigBundle(m: EmulatorModule, d: Detected): SaveUploadStatus? {
        val id = m.emulatorId
        val name = "$id/config"
        if (store.applyMarker(ItemKey.config(id)) != null) {
            log("$name: delivery being applied or awaiting restore, skipped")
            return null
        }
        val files = moduleCall(m, "configBundle") { m.configBundle(fs, d).files } ?: return null
        val built = buildOrNull(name, files.entries.map { it.key to it.value }, rejectFresh = false) as Built? ?: return null
        if (!stable(built)) {
            log("$name: changed while reading, skipped")
            return null
        }
        hashCache.putAll(built.hashed)
        val known = store.configState(id)
        if (Manifests.hash(built.entries) == known?.knownManifestHash) { synchronized(ev) { queued -= ItemKey.config(id) }; return null }
        val fresh = buildOrNull(name, files.entries.map { it.key to it.value }, rejectFresh = false, useCache = false) as Built? ?: return null
        if (!stable(fresh)) {
            log("$name: changed while reading, skipped")
            return null
        }
        hashCache.putAll(fresh.hashed)
        val hash = Manifests.hash(fresh.entries)
        if (hash == known?.knownManifestHash) { synchronized(ev) { queued -= ItemKey.config(id) }; return null }
        return queueing(ItemKey.config(id), apiCall(name) {
            val built = fresh
            pushBlobs(built)
            val r = api.uploadConfig(ConfigUploadRequest(id, known?.knownVersionId, built.entries))
            when (r.status) {
                SaveUploadStatus.Accepted, SaveUploadStatus.Unchanged -> {
                    store.putConfigState(id, ConfigState(r.bundleId, r.versionId, hash, hash))
                    log("$name: ${r.status} ${r.versionId}")
                }
                SaveUploadStatus.Superseded -> {
                    store.putConfigState(id, ConfigState(r.bundleId, r.versionId, hash, hash))
                    log("$name: superseded by ${r.versionId}")
                }
                SaveUploadStatus.Conflict -> {
                    log("$name: conflict")
                    if (notified.add("config:$id|$hash")) onConflict(SyncConflict(id, null, null, "", "", null))
                }
            }
            r.status
        })
    }

    /** Records whether the upload of [key] failed ([status] null from [apiCall]) for [queuedCount]. */
    private fun queueing(key: String, status: SaveUploadStatus?): SaveUploadStatus? {
        synchronized(ev) { if (status == null) queued += key else queued -= key }
        return status
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

    /**
     * Builds over (manifest path, absolute path) pairs. Returns [Built], [Fresh] (only with [rejectFresh]), or
     * null (logged) when the item can't be uploaded.
     */
    private fun buildOrNull(name: String, files: List<Pair<String, String>>, rejectFresh: Boolean, useCache: Boolean = true): Build? {
        val b = try {
            build(files, rejectFresh, useCache)
        } catch (e: IOException) {
            log("$name: read failed, skipped (${e.message})"); return null
        } catch (e: ModuleException) {
            log("$name: ${e.message}"); return null
        }
        if (b is TooLarge) {
            log("$name: ${b.path} too large (${b.size} bytes), skipped"); return null
        }
        if (b !is Built) return b
        if (!Manifests.isUploadable(b.entries)) {
            if (b.entries.size > Manifests.MAX_ENTRIES) log("$name: ${b.entries.size} files, over the limit, skipped")
            return null
        }
        return b
    }

    /**
     * Stats every file first (size cap; with [rejectFresh], an mtime within quietMillis of now — a future mtime
     * beyond that is treated as clock skew, not as activity). Hashes reuse [hashCache] when size and mtime match.
     */
    private fun build(files: List<Pair<String, String>>, rejectFresh: Boolean, useCache: Boolean = true): Build {
        files.forEach { Paths.requireSafeRelative(it.first) }
        val stats = HashMap<String, FileEntry>()
        val now = clock()
        for ((rel, abs) in files) {
            val st = fs.stat(abs) ?: throw IOException("vanished: $abs")
            if (st.size > SyncConfig.maxBlobBytes) return TooLarge(rel, st.size)
            stats[abs] = st
        }
        if (rejectFresh && stats.values.any { it.mtimeMillis > now - quietMillis && it.mtimeMillis <= now + quietMillis }) return Fresh
        val sources = HashMap<String, String>()
        val hashed = HashMap<String, CachedHash>()
        val entries = files.map { (rel, abs) ->
            val st = stats.getValue(abs)
            val c = if (useCache) hashCache[abs] else null
            if (c != null && c.size == st.size && c.mtime == st.mtimeMillis) {
                sources.putIfAbsent(c.hash, abs)
                return@map ManifestEntry(rel, c.hash, c.size)
            }
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
            hashed[abs] = CachedHash(st.size, st.mtimeMillis, h)
            ManifestEntry(rel, h, size)
        }.sortedBy { it.path }
        return Built(entries, sources, stats, hashed)
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
        val items = moduleCall(m, "saveItems") { dedupe(m, m.saveItems(fs, d)) } ?: return null
        val byKey = items.associateBy { storeKey(m.emulatorId, it) }
        synchronized(ev) {
            tracked.entries.removeIf { it.value.module.emulatorId == m.emulatorId && it.key !in byKey }
            for ((k, item) in byKey) tracked[k] = Tracked(m, d, item)
            dirty.keys.retainAll(tracked.keys)
            marks.keys.retainAll(tracked.keys)
        }
        return items
    }

    /** Collapses items sharing a store key (RetroArch core folders, Dolphin regions): newest mtime wins. */
    private fun dedupe(m: EmulatorModule, items: List<LocalItem>): List<LocalItem> =
        items.groupBy { storeKey(m.emulatorId, it) }.map { (key, group) ->
            if (group.size == 1) return@map group[0]
            val newest = group.associateWith { item ->
                item.files.maxOfOrNull { f ->
                    try { fs.stat("${item.baseDir}/$f")?.mtimeMillis ?: 0L } catch (e: IOException) { 0L }
                } ?: 0L
            }
            val chosen = group.sortedWith(compareByDescending<LocalItem> { newest.getValue(it) }.thenBy { it.baseDir }).first()
            for (other in group) if (other !== chosen && synchronized(ev) { shadowLogged.add("$key|${other.baseDir}") })
                log("$key: ${other.baseDir} shadowed by ${chosen.baseDir}")
            chosen
        }

    private suspend fun reportStatus(found: List<Pair<EmulatorModule, Detected>>) {
        val emulators = found.map { (m, d) ->
            EmulatorStatus(m.emulatorId, d.pkg, versionOf(d.pkg), moduleCall(m, "cores") { m.cores(fs, d) })
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
    } catch (e: kotlinx.serialization.SerializationException) {
        log("$name: server response not understood (update the app?)"); null
    }

    private fun storeKey(emulatorId: String, item: LocalItem) =
        ItemKey.of(emulatorId, item.kind.name, item.gameKey, item.slot, if (item.kind == ItemKind.Save) "" else item.core)

    private fun under(path: String, base: String) = path == base || path.startsWith("$base/")

    private companion object {
        val sdName = Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")
    }
}
