package dev.emusync.app.sync

import dev.emusync.app.api.Api
import dev.emusync.app.api.ApiException
import dev.emusync.app.fs.Fs
import dev.emusync.app.fs.FsUnavailableException
import dev.emusync.app.model.*
import dev.emusync.app.modules.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ApplyReport(val applied: Int, val skipped: Int, val failed: Int, val waitingForEmulator: Int)

/**
 * A delivery held back because its emulator is running. [gameKey] and [displayName] are null for config bundles;
 * [displayName] is also null when the game's title is unknown. [fromDeviceName] is null when the server doesn't say
 * which device the version came from (it currently never does).
 */
data class WaitingDelivery(
    val deliveryId: String,
    val emulatorId: String,
    val emulatorName: String,
    val gameKey: String?,
    val displayName: String?,
    val fromDeviceName: String?,
)

/**
 * Applies server deliveries (saves, config bundles, vault files) to this device.
 *
 * Safety rules, in order: never write while the emulator runs; never overwrite a local item whose content differs
 * from what was last synced (upload it instead) or an untracked file that differs; validate every path; verify every
 * blob before touching anything; back up before overwriting; on a failed write restore the backup and remove files
 * this delivery created.
 *
 * Write phase: the running check, the local-content re-check, backup, writes, deletes and the state update run under
 * [SyncEngine.withLock] (never across downloads or engine uploads), with a persisted [ApplyMarker] from before the
 * first write until the state update. Markers left by a crash or a failed restore are recovered at the start of
 * every pass; while one is live, the engine doesn't upload that item and deliveries for it wait.
 *
 * Errors: [FsUnavailableException] stops the pass with nothing acked for the delivery in flight and is rethrown.
 * Permanent problems ack `Failed(detail)`. Transient ones (network, local read errors, content that changed during
 * the download) leave the delivery un-acked so the server re-offers it; they count as failed in the report.
 */
class DeliveryApplier(
    private val fs: Fs,
    private val api: Api,
    private val store: StateStore,
    private val registry: ModuleRegistry,
    private val engine: SyncEngine,
    private val notifier: (String) -> Unit,
    private val settings: DeviceSettings,
    private val cacheDir: File,
    private val backups: LocalBackups = LocalBackups(),
    private val vaultRoot: String = DEFAULT_VAULT_ROOT,
    /** Receives the deliveries waiting for a running emulator after every pass (and [forgetWaiting]). */
    private val onWaiting: (List<WaitingDelivery>) -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private enum class Outcome { Applied, Skipped, Failed, Waiting }

    /** Ack `Failed(detail)`. */
    private class Reject(val detail: String) : Exception(detail)
    /** Leave un-acked; the server re-offers it. */
    private class NoAck(msg: String) : Exception(msg)
    /** The emulator started before the write phase. */
    private class StartedRunning : Exception()

    /** One delivered file: [rel] is the spelling written (an existing local file's spelling when it matches). */
    private class Planned(val entry: ManifestEntry, val rel: String, val abs: String, val matched: Boolean)

    private class Target(
        val name: String,
        val markerKey: String,
        val backupId: String,
        val safeKey: String,
        val procs: List<String>?,             // packages re-probed before writing; null for vault files
        val targetDir: String,
        val files: List<Planned>,
        val local: Map<String, String>,       // current local files: rel -> absolute
        val deletable: Set<String>,           // local rels to delete when absent from the delivery
        val guardHash: String?,               // local manifest hash at guard time; null when no local files
        val untrackedOk: Set<String>,         // untracked absolute paths verified identical at guard time
        val checkUntracked: Boolean,
        val item: Pair<String, ItemState>? = null,
        val config: Pair<String, ConfigState>? = null,
    )

    private val mutex = Mutex()
    private val pendingWhileRunning = HashMap<String, String>()   // delivery id -> emulatorId
    private val notifiedRunning = HashSet<String>()                // delivery ids already notified
    private val notifiedRecovery = HashSet<String>()               // marker keys whose failed recovery was notified
    private val notifiedUndetected = HashSet<String>()             // marker keys postponed because detect failed
    private val waitingNow = LinkedHashMap<String, WaitingDelivery>()   // delivery id -> entry, as last published
    private val passWaiting = LinkedHashMap<String, WaitingDelivery>()  // entries found by the pass in progress

    // ---- public API ----

    /** Recovers interrupted writes, then applies pending deliveries, refetching up to [MAX_PASSES] times. */
    suspend fun applyAll(): ApplyReport = mutex.withLock { pass(null) }

    /** Retries deliveries (and interrupted writes) held back while [emulatorId] ran, against the server's list. */
    suspend fun onEmulatorExited(emulatorId: String): ApplyReport = mutex.withLock {
        // The emulator stopped: nothing waits for it any more, whether or not a pass reaches the server.
        val dropped = waitingNow.values.removeAll { it.emulatorId == emulatorId }
        val hasMarker = store.applyMarkers().values.any { it.emulatorId == emulatorId }
        if (!hasMarker && pendingWhileRunning.values.none { it == emulatorId }) {
            if (dropped) publishWaiting()
            return@withLock ApplyReport(0, 0, 0, 0)
        }
        pendingWhileRunning.values.removeAll { it == emulatorId }
        pass(emulatorId)
    }

    /**
     * Drops the published waiting entries of [emulatorId] without applying anything: it exited and its deliveries
     * are no longer held back by it (they stay pending until the next apply).
     */
    suspend fun forgetWaiting(emulatorId: String): Unit = mutex.withLock {
        if (waitingNow.values.removeAll { it.emulatorId == emulatorId }) publishWaiting()
    }

    private fun publishWaiting() = onWaiting(waitingNow.values.toList())

    // ---- loop ----

    /** One apply pass over every delivery, or only [emulatorId]'s. Then republishes the waiting deliveries. */
    private suspend fun pass(emulatorId: String?): ApplyReport {
        passWaiting.clear()
        var fetchedAny = false
        var completed = false
        try {
            return passInner(emulatorId) { fetchedAny = true }.also { completed = true }
        } finally {
            // A completed pass saw every pending delivery in scope (its first fetch lists them all), so its outcome
            // replaces the scope's entries. An interrupted or offline pass only adds what it found.
            if (completed && fetchedAny) waitingNow.values.removeAll { emulatorId == null || it.emulatorId == emulatorId }
            waitingNow.putAll(passWaiting)
            passWaiting.clear()
            publishWaiting()
        }
    }

    private suspend fun passInner(emulatorId: String?, onFetched: () -> Unit): ApplyReport {
        val filter: (DeliveryDto) -> Boolean = { emulatorId == null || it.emulatorId == emulatorId }
        recoverMarkers()
        val seen = HashSet<String>()
        var applied = 0; var skipped = 0; var failed = 0; var waiting = 0
        for (i in 1..MAX_PASSES) {
            val fetched = try {
                api.deliveries()
            } catch (e: ApiException) {
                log("deliveries: server error ${e.status} ${e.code ?: ""}".trimEnd()); break
            } catch (e: IOException) {
                log("deliveries: io error (${e.message})"); break
            } catch (e: kotlinx.serialization.SerializationException) {
                log("deliveries: server response not understood (update the app?)"); break
            }
            onFetched()
            val list = fetched.filter { filter(it) && it.id !in seen }
            if (list.isEmpty()) break
            for (d in list) {
                seen += d.id
                when (applyOne(d)) {
                    Outcome.Applied -> applied++
                    Outcome.Skipped -> skipped++
                    Outcome.Failed -> failed++
                    Outcome.Waiting -> waiting++
                }
            }
        }
        return ApplyReport(applied, skipped, failed, waiting)
    }

    private suspend fun applyOne(d: DeliveryDto): Outcome = try {
        if (d.kind == DeliveryKind.Vault) applyVault(d) else applyEmulator(d)
    } catch (r: Reject) {
        log("delivery ${d.id}: failed (${r.detail})")
        ack(d, DeliveryResult.Failed, r.detail)
        Outcome.Failed
    } catch (n: NoAck) {
        log("delivery ${d.id}: not applied, will retry (${n.message})")
        Outcome.Failed
    } catch (e: FsUnavailableException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        log("delivery ${d.id}: io error, will retry (${e.message})")
        Outcome.Failed
    } catch (e: Exception) {
        val detail = "internal error: ${e.javaClass.simpleName}: ${e.message}"
        log("delivery ${d.id}: $detail")
        ack(d, DeliveryResult.Failed, detail)
        Outcome.Failed
    }

    private suspend fun applyEmulator(d: DeliveryDto): Outcome {
        val m = registry.byId(d.emulatorId) ?: throw Reject("emulator not found")
        val det = moduleCall { m.detect(fs, settings) } ?: throw Reject("emulator not found")
        if (fs.isEmulatorRunning(m, det)) return waiting(d, m)
        pendingWhileRunning.remove(d.id)
        return try {
            if (d.kind == DeliveryKind.Config) applyConfig(d, m, det) else applySave(d, m, det)
        } catch (e: StartedRunning) {
            waiting(d, m)
        }
    }

    private fun waiting(d: DeliveryDto, m: EmulatorModule): Outcome {
        pendingWhileRunning[d.id] = m.emulatorId
        passWaiting[d.id] = WaitingDelivery(d.id, m.emulatorId, m.displayName, d.gameKey,
            d.gameKey?.let { engine.gameTitle(m.emulatorId, it) }, fromDeviceName = null)
        if (notifiedRunning.add(d.id)) notifier(
            if (d.kind == DeliveryKind.Config) "Close ${m.displayName} to receive the newer settings"
            else "Close ${m.displayName} to receive the newer save for ${d.gameKey?.let { title(m, it) }}")
        return Outcome.Waiting
    }

    // ---- kinds ----

    private suspend fun applySave(d: DeliveryDto, m: EmulatorModule, det: Detected): Outcome {
        val kind = d.itemKind ?: throw Reject("bad delivery: no item kind")
        val gameKey = d.gameKey ?: throw Reject("bad delivery: no game key")
        validate(d.manifest)
        val key = ItemKey.of(m.emulatorId, kind.name, gameKey, d.slotOrEmpty, if (kind == ItemKind.Save) "" else d.coreOrEmpty)
        if (store.applyMarker(key) != null) throw NoAck("an earlier write for $key awaits restore")
        val local = moduleCall { engine.currentItems(m, det) }
            .firstOrNull { storeKey(m.emulatorId, it) == key }?.takeIf { it.files.isNotEmpty() }
        val name = local?.displayName?.takeIf { it.isNotBlank() } ?: title(m, gameKey)
        val dir = local?.baseDir ?: moduleCall { m.targetDirFor(fs, det, kind, gameKey, d.slotOrEmpty, d.coreOrEmpty) }
        val localFiles = local?.files?.associateWith { "$dir/$it" } ?: emptyMap()
        val byLower = localFiles.keys.associateBy { lower(it) }
        val planned = d.manifest.map { e ->
            val match = byLower[lower(e.path)]
            if (match != null) Planned(e, match, "$dir/$match", true) else Planned(e, e.path, "$dir/${e.path}", false)
        }
        val written = Manifests.hash(planned.map { ManifestEntry(it.rel, it.entry.hash, it.entry.size) })
        val known = store.get(key)
        val state = key to ItemState(d.versionId, written, known?.lastUploadedHash)

        var guardHash: String? = null
        if (local != null) {
            guardHash = localHash(localFiles)
            if (guardHash == written) {
                store.put(state.first, state.second)
                ack(d, DeliveryResult.Applied)
                return Outcome.Applied
            }
            if (guardHash != known?.knownManifestHash) {
                engine.invalidate(localFiles.values)   // the guard read fresh content; the engine must not trust its cache
                return uploadedFirst(d, engine.upload(m, det, local))
            }
        }
        val untrackedOk = checkUntracked(planned, name)
        if (local == null && untrackedOk.size == planned.size) {
            store.put(state.first, state.second)   // every delivered file is already here, identical
            ack(d, DeliveryResult.Applied)
            return Outcome.Applied
        }
        install(d, Target(
            name = name, markerKey = key, backupId = m.emulatorId, safeKey = ItemKey.safe(key), procs = m.processPackages(det),
            targetDir = dir, files = planned, local = localFiles, deletable = localFiles.keys,
            guardHash = guardHash, untrackedOk = untrackedOk, checkUntracked = true, item = state,
        ))
        ack(d, DeliveryResult.Applied)
        return Outcome.Applied
    }

    private suspend fun applyConfig(d: DeliveryDto, m: EmulatorModule, det: Detected): Outcome {
        validate(d.manifest)
        val id = m.emulatorId
        val key = ItemKey.config(id)
        if (store.applyMarker(key) != null) throw NoAck("an earlier config write awaits restore")
        val bundle = moduleCall { m.configBundle(fs, det).files }
        val byLower = bundle.keys.associateBy { lower(it) }
        val planned = d.manifest.map { e ->
            val seg = e.path.substringBefore('/', "")
            if (seg !in m.configRoots) throw Reject("path not allowed")
            val rest = e.path.substringAfter('/')
            safeRelative(rest)
            val base = if (seg == "data") det.dataRoot else det.extraRoots[seg] ?: throw Reject("no '$seg' folder on this device")
            val match = byLower[lower(e.path)]
            if (match != null) Planned(e, match, bundle.getValue(match), true) else Planned(e, e.path, "$base/$rest", false)
        }
        val roots = d.manifest.map { lower(it.path.substringBefore('/')) }.toSet()
        val written = Manifests.hash(planned.map { ManifestEntry(it.rel, it.entry.hash, it.entry.size) })
        val known = store.configState(id)
        val state = id to ConfigState(d.targetId, d.versionId, written, known?.lastUploadedHash)
        val name = "${m.displayName} settings"
        fun done(): Outcome {
            if (!d.missingLocalKeys.isNullOrEmpty()) notifier("Set your ROM folders in ${m.displayName} on this device")
            return Outcome.Applied
        }
        var guardHash: String? = null
        if (bundle.isNotEmpty()) {
            guardHash = localHash(bundle)
            if (guardHash == written) {
                store.putConfigState(state.first, state.second)
                ack(d, DeliveryResult.Applied)
                return done()
            }
            if (guardHash != known?.knownManifestHash) {
                engine.invalidate(bundle.values)
                return uploadedFirst(d, engine.uploadConfig(m, det))
            }
        }
        val untrackedOk = checkUntracked(planned, name)
        if (bundle.isEmpty() && untrackedOk.size == planned.size) {
            store.putConfigState(state.first, state.second)
            ack(d, DeliveryResult.Applied)
            return done()
        }
        install(d, Target(
            name = name, markerKey = key, backupId = id, safeKey = ItemKey.safe(key), procs = m.processPackages(det),
            targetDir = det.dataRoot, files = planned, local = bundle,
            deletable = bundle.keys.filter { lower(it.substringBefore('/')) in roots }.toSet(),
            guardHash = guardHash, untrackedOk = untrackedOk, checkUntracked = true, config = state,
        ))
        ack(d, DeliveryResult.Applied)
        return done()
    }

    private suspend fun applyVault(d: DeliveryDto): Outcome {
        validate(d.manifest)
        try { Paths.requireSafeSegment(d.targetId) } catch (e: ModuleException) { throw Reject("unsafe path") }
        val key = "vault|${d.targetId}"
        if (store.applyMarker(key) != null) throw NoAck("an earlier vault write awaits restore")
        val dir = "$vaultRoot/${d.targetId}"
        install(d, Target(
            name = "vault ${d.targetId}", markerKey = key, backupId = "vault", safeKey = ItemKey.safe(key), procs = null,
            targetDir = dir, files = d.manifest.map { Planned(it, it.path, "$dir/${it.path}", false) },
            local = emptyMap(), deletable = emptySet(), guardHash = null, untrackedOk = emptySet(),
            checkUntracked = false,
        ))
        notifier("Vault files ready in EmuSync/vault")
        ack(d, DeliveryResult.Applied)
        return Outcome.Applied
    }

    /**
     * Settles a delivery after the local change was uploaded first. Accepted or Conflict: the server now has the local
     * content, ack `SkippedLocalChanged`. Superseded or Unchanged: local content now equals a known server version,
     * so leave it un-acked and let the next pass re-evaluate it against the updated state. Null (deferred, unreadable
     * or failed): leave it un-acked so it's retried, never acked as if the local change had reached the server.
     */
    private suspend fun uploadedFirst(d: DeliveryDto, status: SaveUploadStatus?): Outcome = when (status) {
        null -> throw NoAck("local change not uploaded yet")
        SaveUploadStatus.Superseded, SaveUploadStatus.Unchanged -> {
            log("delivery ${d.id}: local content matches a server version ($status); re-evaluated next pass")
            Outcome.Skipped
        }
        SaveUploadStatus.Accepted, SaveUploadStatus.Conflict -> {
            ack(d, DeliveryResult.SkippedLocalChanged)
            Outcome.Skipped
        }
    }

    /**
     * Delivered files with no local match that already exist at the target. Returns their absolute paths when every
     * one is identical to the delivered entry; otherwise notifies and rejects (never overwrite unsynced files).
     */
    private fun checkUntracked(planned: List<Planned>, name: String): Set<String> {
        val untracked = planned.filter { !it.matched && fs.exists(it.abs) }
        if (untracked.all { fileMatches(it.abs, it.entry) }) return untracked.map { it.abs }.toSet()
        notifier("A file for $name exists here that EmuSync hasn't synced; not overwriting.")
        throw Reject("untracked local file at target")
    }

    // ---- download, write phase, restore ----

    /** Downloads and verifies every blob, then runs the write phase under the engine lock. */
    private suspend fun install(d: DeliveryDto, t: Target) {
        val dl = File(cacheDir, "dl")
        val hashes = t.files.associate { it.entry.hash to it.entry.size }
        try {
            dl.mkdirs()
            for ((h, size) in hashes) download(h, size, dl)
            engine.withLock { writePhase(d, t, dl) }
        } finally {
            for (h in hashes.keys) { File(dl, h).delete(); File(dl, "$h.part").delete() }
        }
    }

    private fun writePhase(d: DeliveryDto, t: Target, dl: File) {
        if (t.procs != null && fs.isAnyProcessAlive(t.procs)) throw StartedRunning()
        val existed = t.files.filter { fs.exists(it.abs) }.map { it.abs }.toSet()
        if (t.checkUntracked && t.files.any { !it.matched && it.abs in existed && it.abs !in t.untrackedOk })
            throw NoAck("a file appeared at the target during the download")
        val localAbs = t.local.values.toSet()
        val toBackup = LinkedHashMap(t.local)
        for (p in t.files) if (p.abs in existed && p.abs !in localAbs) toBackup[p.rel] = p.abs
        val backupDir = try {
            backups.backupFiles(fs, t.backupId, t.safeKey, toBackup)
        } catch (e: FsUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw Reject("backup failed: ${e.message}")
        }
        // Hash the copies just taken: they re-verify the guard and let recovery tell our writes from the user's.
        val copied: Map<String, Pair<String, Long>> = try {
            if (backupDir == null) emptyMap()
            else toBackup.keys.filter { fs.exists("$backupDir/$it") }.associateWith { sha("$backupDir/$it") }
        } catch (e: IOException) {
            fs.delete(backupDir!!)
            throw NoAck("backup unreadable: ${e.message}")
        }
        if (t.guardHash != null) {
            val now = if (t.local.keys.all { it in copied })
                Manifests.hash(t.local.keys.map { copied.getValue(it).let { (h, sz) -> ManifestEntry(it, h, sz) } }) else null
            if (now != t.guardHash) {
                backupDir?.let { fs.delete(it) }
                throw NoAck("local files changed during the download")
            }
        }
        // Only files actually in the backup count as backed up; one that vanished before the copy counts as ours.
        val relOf = toBackup.entries.filter { it.key in copied }.associate { it.value to it.key }
        fun backed(abs: String): Pair<String?, String?> = relOf[abs]?.let { it to copied.getValue(it).first } ?: (null to null)
        val created = t.files.filter { it.abs !in relOf }.map { it.abs }
        val delivered = t.files.map { lower(it.rel) }.toSet()
        val extras = t.deletable.filter { lower(it) !in delivered }.mapNotNull { t.local[it] }
        val files = LinkedHashMap<String, MarkerFile>()
        for (p in t.files) backed(p.abs).let { (rel, h) -> files[p.abs] = MarkerFile(p.entry.hash, rel, h) }
        for (abs in extras) backed(abs).let { (rel, h) -> files[abs] = MarkerFile(null, rel, h) }
        val marker = ApplyMarker(t.markerKey, t.backupId, d.id, backupDir, t.targetDir, created, files, t.name)
        store.putApplyMarker(marker)
        try {
            for (p in t.files.sortedBy { it.rel }) {
                File(dl, p.entry.hash).inputStream().buffered().use { fs.writeAtomic(p.abs, it) }
            }
            for (abs in extras.sorted()) {
                if (!fs.delete(abs) && fs.exists(abs)) throw IOException("could not delete $abs")
            }
        } catch (e: Exception) {
            // Throws FsUnavailableException (marker kept, retried later) without a "Restore failed" notification.
            if (undo(marker)) store.clearApplyMarker(t.markerKey)
            else if (notifiedRecovery.add(t.markerKey)) notifier("Restore failed for ${t.name} — backup at ${backupDir ?: "none"}")
            if (e is FsUnavailableException) throw e
            throw Reject("write failed: ${e.message}")
        } finally {
            // The storage stack may keep a stale mtime after a rewrite; never let the engine trust a cached hash.
            engine.invalidate(t.files.map { it.abs } + extras)
        }
        try {
            store.finishApply(t.markerKey, t.item, t.config)   // state + marker clear in one atomic write
        } catch (e: Exception) {
            throw NoAck("state update failed, restore pending: ${e.message}")   // the marker restores it next pass
        }
    }

    /**
     * Recovers every live marker, under the engine lock and only while its emulator is detected and not running
     * (vault markers have no emulator). Keeps and reports markers that can't be fully recovered.
     * [FsUnavailableException] propagates with the marker kept and nothing reported.
     */
    private suspend fun recoverMarkers() {
        for (mk in store.applyMarkers().values) {
            var procs: List<String>? = null
            if (mk.emulatorId != "vault") {
                procs = registry.byId(mk.emulatorId)?.let { m ->
                    val det = try { m.detect(fs, settings) } catch (e: ModuleException) { null } catch (e: IOException) { null }
                    det?.let { m.processPackages(it) }
                }
                if (procs == null) {
                    log("${mk.storeKey}: ${mk.emulatorId} not detected; recovery of interrupted write postponed")
                    if (notifiedUndetected.add(mk.storeKey))
                        notifier("EmuSync couldn't finish restoring ${mk.name}; backup at ${mk.backupDir ?: "none"}")
                    continue
                }
                notifiedUndetected.remove(mk.storeKey)
            }
            val ok = engine.withLock {
                if (procs != null && fs.isAnyProcessAlive(procs)) return@withLock null   // probed right before the undo
                val ok = undo(mk)
                if (ok) store.clearApplyMarker(mk.storeKey)
                ok
            }
            if (ok == null) {
                log("${mk.storeKey}: recovery of interrupted write waits for ${mk.emulatorId} to close")
            } else if (ok) {
                log("${mk.storeKey}: interrupted write of delivery ${mk.deliveryId} recovered")
                notifiedRecovery.remove(mk.storeKey)
            } else if (notifiedRecovery.add(mk.storeKey)) {
                notifier("Restore failed for ${mk.name} — backup at ${mk.backupDir ?: "none"}")
            }
        }
    }

    /**
     * Undoes only this apply's own changes, decided per item from current content. If any file holds content that
     * is neither what we wrote nor its backup (or, for a file we deleted, reappeared with other content), the user
     * played after the crash: every file is left exactly as it is and the engine later uploads the item as a local
     * change. Otherwise a file still holding what we wrote (for a deleted extra: still missing) is restored from the
     * backup, or deleted when we created it; missing or backup-content files are left. Returns true when the item is
     * handled. [FsUnavailableException] propagates.
     */
    private fun undo(mk: ApplyMarker): Boolean {
        try { return undoInner(mk) } finally { engine.invalidate(mk.files.keys) }
    }

    private fun undoInner(mk: ApplyMarker): Boolean {
        val errors = mutableListOf<String>()
        val ours = ArrayList<Pair<String, MarkerFile>>()
        var userChanged = false
        for ((abs, f) in mk.files.toSortedMap()) {
            try {
                val exists = fs.exists(abs)
                val current = if (exists) sha(abs).first else null
                when {
                    if (f.deliveredHash == null) !exists else current == f.deliveredHash -> ours += abs to f
                    !exists || current == f.backupHash -> {}
                    else -> userChanged = true
                }
            } catch (e: FsUnavailableException) {
                throw e
            } catch (e: Exception) {
                errors += "$abs: ${e.message}"
            }
        }
        if (errors.isEmpty() && userChanged) {
            log("${mk.storeKey}: user changed ${mk.name} after an interrupted apply; keeping their files")
            return true
        }
        if (errors.isEmpty()) for ((abs, f) in ours) {
            try {
                if (f.backupRel != null) {
                    if (mk.backupDir == null) errors += "$abs: no backup dir" else fs.copy("${mk.backupDir}/${f.backupRel}", abs)
                } else if (!fs.delete(abs) && fs.exists(abs)) errors += "$abs: not deleted"
            } catch (e: FsUnavailableException) {
                throw e
            } catch (e: Exception) {
                errors += "$abs: ${e.message}"
            }
        }
        if (errors.isEmpty()) return true
        log("!!! RESTORE FAILED for ${mk.name} (backup at ${mk.backupDir ?: "none"}): ${errors.joinToString("; ")}")
        return false
    }

    /** Streams one blob into `dl/<hash>.part`, checking size and SHA-256 as it goes, then renames to `dl/<hash>`. */
    private suspend fun download(hash: String, size: Long, dl: File) {
        val part = File(dl, "$hash.part")
        val ins = try {
            api.getBlob(hash)
        } catch (e: ApiException) {
            if (e.status == 404) throw Reject("blob missing on server: $hash")
            throw NoAck("download $hash: server error ${e.status}")
        } catch (e: IOException) {
            throw NoAck("download $hash: ${e.message}")
        }
        val md = MessageDigest.getInstance("SHA-256")
        var n = 0L
        try {
            ins.use { input ->
                FileOutputStream(part).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        n += r
                        if (n > size) throw Reject("size mismatch for $hash")
                        md.update(buf, 0, r)
                        out.write(buf, 0, r)
                    }
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (n != size || got != hash) throw Reject("hash mismatch for $hash")
            Files.move(part.toPath(), File(dl, hash).toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw NoAck("download $hash: ${e.message}")
        }
    }

    // ---- helpers ----

    /** Rejects empty manifests, unsafe or (case-insensitively) duplicate paths, malformed hashes and bad sizes. */
    private fun validate(manifest: List<ManifestEntry>) {
        if (manifest.isEmpty()) throw Reject("empty manifest")
        for (e in manifest) safeRelative(e.path)
        if (manifest.map { lower(it.path) }.toSet().size != manifest.size) throw Reject("bad manifest: duplicate path")
        if (manifest.any { !sha256.matches(it.hash) || it.size < 0 || it.size > SyncConfig.maxBlobBytes })
            throw Reject("bad manifest")
        if (manifest.groupBy { it.hash }.values.any { g -> g.map { it.size }.toSet().size > 1 })
            throw Reject("bad manifest: size mismatch")
    }

    private fun safeRelative(p: String) {
        try { Paths.requireSafeRelative(p) } catch (e: ModuleException) { throw Reject("unsafe path") }
    }

    private fun lower(s: String) = s.lowercase(Locale.ROOT)

    private fun sha(abs: String): Pair<String, Long> {
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
        return md.digest().joinToString("") { "%02x".format(it) } to size
    }

    /** Manifest hash of the files (rel -> abs). Read errors leave the delivery for the next pass. */
    private fun localHash(files: Map<String, String>): String = try {
        Manifests.hash(files.map { (rel, abs) -> sha(abs).let { (h, s) -> ManifestEntry(rel, h, s) } })
    } catch (e: IOException) {
        throw NoAck("local read failed: ${e.message}")
    }

    private fun fileMatches(abs: String, e: ManifestEntry): Boolean = try {
        sha(abs) == (e.hash to e.size)
    } catch (ex: IOException) {
        throw NoAck("local read failed: ${ex.message}")
    }

    private suspend fun ack(d: DeliveryDto, result: DeliveryResult, detail: String? = null) {
        try {
            api.ack(d.id, AckRequest(result, detail))
        } catch (e: ApiException) {
            if (e.status == 404 || e.status == 409) log("delivery ${d.id}: already settled (${e.code})")
            else log("delivery ${d.id}: ack failed ${e.status} ${e.code ?: ""}".trimEnd())
        } catch (e: IOException) {
            log("delivery ${d.id}: ack io error (${e.message})")
        } catch (e: kotlinx.serialization.SerializationException) {
            log("delivery ${d.id}: server response not understood (update the app?)")
        }
    }

    private fun <T> moduleCall(f: () -> T): T = try {
        f()
    } catch (e: ModuleException) {
        throw Reject(e.message ?: "module error")
    } catch (e: IOException) {
        throw NoAck("module read failed: ${e.message}")
    }

    /** The game's title for notifications: from the engine's last scan, else the game key. */
    private fun title(m: EmulatorModule, gameKey: String) = engine.gameTitle(m.emulatorId, gameKey) ?: gameKey

    private fun storeKey(emulatorId: String, item: LocalItem) =
        ItemKey.of(emulatorId, item.kind.name, item.gameKey, item.slot, if (item.kind == ItemKind.Save) "" else item.core)

    companion object {
        const val DEFAULT_VAULT_ROOT = "/storage/emulated/0/EmuSync/vault"
        const val MAX_PASSES = 5
        private val sha256 = Regex("[0-9a-f]{64}")
    }
}
