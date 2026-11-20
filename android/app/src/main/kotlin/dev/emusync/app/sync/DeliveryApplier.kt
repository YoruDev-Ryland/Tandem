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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ApplyReport(val applied: Int, val skipped: Int, val failed: Int, val waitingForEmulator: Int)

/**
 * Applies server deliveries (saves, config bundles, vault files) to this device.
 *
 * Safety rules, in order: never write while the emulator runs; never overwrite a local item whose content differs
 * from what was last synced (upload it instead); validate every path; verify every blob before touching anything;
 * back up before overwriting; on a failed write restore the backup and remove files this delivery created.
 *
 * Errors: [FsUnavailableException] stops the pass with nothing acked for the delivery in flight and is rethrown.
 * Permanent problems ack `Failed(detail)`. Transient ones (network, local read errors) leave the delivery un-acked
 * so the server re-offers it next pass; they count as failed in the report.
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
    private val log: (String) -> Unit = {},
) {
    private enum class Outcome { Applied, Skipped, Failed, Waiting }

    /** Ack `Failed(detail)`. */
    private class Reject(val detail: String) : Exception(detail)
    /** Leave un-acked; the server re-offers it. */
    private class NoAck(msg: String) : Exception(msg)

    /** One write job: delivered entries with their absolute targets, plus the local files they replace. */
    private class Target(
        val name: String,
        val backupId: String,
        val safeKey: String,
        val files: List<Pair<ManifestEntry, String>>,   // delivered entry -> absolute path
        val local: Map<String, String>,                 // current local files: rel -> absolute
        val deletable: Set<String>,                     // local rels to delete when absent from the delivery
    )

    private val mutex = Mutex()
    private val pendingWhileRunning = HashMap<String, String>()   // delivery id -> emulatorId
    private val notifiedRunning = HashSet<String>()                // delivery ids already notified

    // ---- public API ----

    /** Applies every pending delivery, refetching after each pass (up to [MAX_PASSES]) until none are new. */
    suspend fun applyAll(): ApplyReport = mutex.withLock { pass { true } }

    /** Retries deliveries held back while [emulatorId] ran, against the server's current list. */
    suspend fun onEmulatorExited(emulatorId: String): ApplyReport = mutex.withLock {
        if (pendingWhileRunning.values.none { it == emulatorId }) return@withLock ApplyReport(0, 0, 0, 0)
        pendingWhileRunning.values.removeAll { it == emulatorId }
        pass { it.emulatorId == emulatorId }
    }

    // ---- loop ----

    private suspend fun pass(filter: (DeliveryDto) -> Boolean): ApplyReport {
        val seen = HashSet<String>()
        var applied = 0; var skipped = 0; var failed = 0; var waiting = 0
        for (i in 1..MAX_PASSES) {
            val fetched = try {
                api.deliveries()
            } catch (e: ApiException) {
                log("deliveries: server error ${e.status} ${e.code ?: ""}".trimEnd()); break
            } catch (e: IOException) {
                log("deliveries: io error (${e.message})"); break
            }
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
    }

    private suspend fun applyEmulator(d: DeliveryDto): Outcome {
        val m = registry.byId(d.emulatorId) ?: throw Reject("emulator not found")
        val det = moduleCall { m.detect(fs, settings) } ?: throw Reject("emulator not found")
        if (fs.isProcessAlive(det.pkg)) {
            pendingWhileRunning[d.id] = m.emulatorId
            if (notifiedRunning.add(d.id)) notifier(
                if (d.kind == DeliveryKind.Config) "Close ${m.emulatorId} to receive the newer settings"
                else "Close ${m.emulatorId} to receive the newer save for ${d.gameKey}")
            return Outcome.Waiting
        }
        pendingWhileRunning.remove(d.id)
        return if (d.kind == DeliveryKind.Config) applyConfig(d, m, det) else applySave(d, m, det)
    }

    // ---- kinds ----

    private suspend fun applySave(d: DeliveryDto, m: EmulatorModule, det: Detected): Outcome {
        val kind = d.itemKind ?: throw Reject("bad delivery: no item kind")
        val gameKey = d.gameKey ?: throw Reject("bad delivery: no game key")
        validate(d.manifest)
        val key = ItemKey.of(m.emulatorId, kind.name, gameKey, d.slotOrEmpty, if (kind == ItemKind.Save) "" else d.coreOrEmpty)
        val local = moduleCall { engine.currentItems(m, det) }
            .firstOrNull { storeKey(m.emulatorId, it) == key }?.takeIf { it.files.isNotEmpty() }
        val known = store.get(key)
        val delivered = Manifests.hash(d.manifest)
        if (local != null) {
            val localHash = localHash(local.files.associateWith { "${local.baseDir}/$it" })
            if (localHash == delivered) {
                store.put(key, ItemState(d.versionId, delivered, known?.lastUploadedHash))
                ack(d, DeliveryResult.Applied)
                return Outcome.Applied
            }
            if (localHash != known?.knownManifestHash) {
                engine.upload(m, det, local)
                ack(d, DeliveryResult.SkippedLocalChanged)
                return Outcome.Skipped
            }
        }
        val dir = local?.baseDir ?: moduleCall { m.targetDirFor(fs, det, kind, gameKey, d.slotOrEmpty, d.coreOrEmpty) }
        install(d, Target(
            name = local?.displayName ?: gameKey,
            backupId = m.emulatorId,
            safeKey = ItemKey.safe(key),
            files = d.manifest.map { it to "$dir/${it.path}" },
            local = local?.files?.associateWith { "$dir/$it" } ?: emptyMap(),
            deletable = local?.files?.toSet() ?: emptySet(),
        ))
        store.put(key, ItemState(d.versionId, delivered, known?.lastUploadedHash))
        ack(d, DeliveryResult.Applied)
        return Outcome.Applied
    }

    private suspend fun applyConfig(d: DeliveryDto, m: EmulatorModule, det: Detected): Outcome {
        validate(d.manifest)
        val id = m.emulatorId
        val bundle = moduleCall { m.configBundle(fs, det).files }
        val files = d.manifest.map { e ->
            val seg = e.path.substringBefore('/', "")
            if (seg !in m.configRoots) throw Reject("path not allowed")
            val rest = e.path.substringAfter('/')
            safeRelative(rest)
            val base = if (seg == "data") det.dataRoot else det.extraRoots[seg] ?: throw Reject("no '$seg' folder on this device")
            e to (bundle[e.path] ?: "$base/$rest")
        }
        val roots = d.manifest.map { it.path.substringBefore('/') }.toSet()
        val known = store.configState(id)
        val delivered = Manifests.hash(d.manifest)
        fun done(): Outcome {
            if (!d.missingLocalKeys.isNullOrEmpty()) notifier("Set your ROM folders in $id on this device")
            return Outcome.Applied
        }
        if (bundle.isNotEmpty()) {
            val localHash = localHash(bundle)
            if (localHash == delivered) {
                store.putConfigState(id, ConfigState(d.targetId, d.versionId, delivered, known?.lastUploadedHash))
                ack(d, DeliveryResult.Applied)
                return done()
            }
            if (localHash != known?.knownManifestHash) {
                engine.uploadConfig(m, det)
                ack(d, DeliveryResult.SkippedLocalChanged)
                return Outcome.Skipped
            }
        }
        install(d, Target(
            name = "$id settings",
            backupId = id,
            safeKey = ItemKey.safe("$id|config"),
            files = files,
            local = bundle,
            deletable = bundle.keys.filter { it.substringBefore('/') in roots }.toSet(),
        ))
        store.putConfigState(id, ConfigState(d.targetId, d.versionId, delivered, known?.lastUploadedHash))
        ack(d, DeliveryResult.Applied)
        return done()
    }

    private suspend fun applyVault(d: DeliveryDto): Outcome {
        validate(d.manifest)
        try { Paths.requireSafeSegment(d.targetId) } catch (e: ModuleException) { throw Reject("unsafe path") }
        val dir = "$vaultRoot/${d.targetId}"
        install(d, Target(
            name = "vault ${d.targetId}",
            backupId = "vault",
            safeKey = ItemKey.safe("vault|${d.targetId}"),
            files = d.manifest.map { it to "$dir/${it.path}" },
            local = emptyMap(),
            deletable = emptySet(),
        ))
        notifier("Vault files ready in EmuSync/vault")
        ack(d, DeliveryResult.Applied)
        return Outcome.Applied
    }

    // ---- download, backup, write, restore ----

    /** Downloads and verifies every blob, backs up, writes, then deletes extras. Throws [Reject] with nothing changed
     *  (or everything restored) on failure. */
    private suspend fun install(d: DeliveryDto, t: Target) {
        val dl = File(cacheDir, "dl")
        val hashes = t.files.associate { it.first.hash to it.first.size }
        try {
            dl.mkdirs()
            for ((h, size) in hashes) download(h, size, dl)

            val existed = t.files.filter { fs.exists(it.second) }.map { it.first.path }.toSet()
            val toBackup = t.local + t.files.filter { it.first.path in existed }.associate { it.first.path to it.second }
            val backupDir = try {
                backups.backupFiles(fs, t.backupId, t.safeKey, toBackup)
            } catch (e: FsUnavailableException) {
                throw e
            } catch (e: Exception) {
                throw Reject("backup failed: ${e.message}")
            }
            val created = t.files.filter { it.first.path !in existed }.map { it.second }
            try {
                for ((e, abs) in t.files.sortedBy { it.first.path }) {
                    File(dl, e.hash).inputStream().buffered().use { fs.writeAtomic(abs, it) }
                }
                val delivered = t.files.map { it.first.path }.toSet()
                for (rel in t.deletable.sorted()) {
                    if (rel in delivered) continue
                    val abs = t.local[rel] ?: continue
                    if (!fs.delete(abs) && fs.exists(abs)) throw IOException("could not delete $rel")
                }
            } catch (e: Exception) {
                restore(t.name, backupDir, toBackup, created)
                if (e is FsUnavailableException) throw e
                throw Reject("write failed: ${e.message}")
            }
        } finally {
            for (h in hashes.keys) { File(dl, h).delete(); File(dl, "$h.part").delete() }
        }
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
        } catch (e: IOException) {
            throw NoAck("download $hash: ${e.message}")
        }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (n != size || got != hash) throw Reject("hash mismatch for $hash")
        Files.move(part.toPath(), File(dl, hash).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Copies every backed-up file back and removes files the failed delivery created. Never throws. */
    private fun restore(name: String, backupDir: String?, backedUp: Map<String, String>, created: List<String>) {
        val errors = mutableListOf<String>()
        if (backupDir != null) for ((rel, abs) in backedUp) {
            try { fs.copy("$backupDir/$rel", abs) } catch (e: Exception) { errors += "$rel: ${e.message}" }
        }
        for (abs in created) {
            try {
                if (!fs.delete(abs) && fs.exists(abs)) errors += "$abs: not deleted"
            } catch (e: Exception) { errors += "$abs: ${e.message}" }
        }
        if (errors.isEmpty()) return
        log("!!! RESTORE FAILED for $name (backup at ${backupDir ?: "none"}): ${errors.joinToString("; ")}")
        notifier("Restore failed for $name — backup at ${backupDir ?: "none"}")
    }

    // ---- helpers ----

    /** Rejects empty manifests, unsafe or duplicate paths, malformed hashes and bad sizes. */
    private fun validate(manifest: List<ManifestEntry>) {
        if (manifest.isEmpty()) throw Reject("empty manifest")
        for (e in manifest) safeRelative(e.path)
        if (manifest.map { it.path }.toSet().size != manifest.size) throw Reject("bad manifest: duplicate path")
        if (manifest.any { !sha256.matches(it.hash) || it.size < 0 || it.size > SyncConfig.maxBlobBytes })
            throw Reject("bad manifest")
        if (manifest.groupBy { it.hash }.values.any { g -> g.map { it.size }.toSet().size > 1 })
            throw Reject("bad manifest: size mismatch")
    }

    private fun safeRelative(p: String) {
        try { Paths.requireSafeRelative(p) } catch (e: ModuleException) { throw Reject("unsafe path") }
    }

    /** Manifest hash of the local files (rel -> abs). Read errors leave the delivery for the next pass. */
    private fun localHash(files: Map<String, String>): String = try {
        Manifests.hash(files.map { (rel, abs) ->
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
            ManifestEntry(rel, md.digest().joinToString("") { "%02x".format(it) }, size)
        })
    } catch (e: IOException) {
        throw NoAck("local read failed: ${e.message}")
    }

    private suspend fun ack(d: DeliveryDto, result: DeliveryResult, detail: String? = null) {
        try {
            api.ack(d.id, AckRequest(result, detail))
        } catch (e: ApiException) {
            if (e.status == 404 || e.status == 409) log("delivery ${d.id}: already settled (${e.code})")
            else log("delivery ${d.id}: ack failed ${e.status} ${e.code ?: ""}".trimEnd())
        } catch (e: IOException) {
            log("delivery ${d.id}: ack io error (${e.message})")
        }
    }

    private fun <T> moduleCall(f: () -> T): T = try {
        f()
    } catch (e: ModuleException) {
        throw Reject(e.message ?: "module error")
    } catch (e: IOException) {
        throw NoAck("module read failed: ${e.message}")
    }

    private fun storeKey(emulatorId: String, item: LocalItem) =
        ItemKey.of(emulatorId, item.kind.name, item.gameKey, item.slot, if (item.kind == ItemKind.Save) "" else item.core)

    companion object {
        const val DEFAULT_VAULT_ROOT = "/storage/emulated/0/EmuSync/vault"
        const val MAX_PASSES = 5
        private val sha256 = Regex("[0-9a-f]{64}")
    }
}
