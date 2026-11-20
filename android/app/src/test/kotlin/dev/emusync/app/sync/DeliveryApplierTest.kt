package dev.emusync.app.sync

import dev.emusync.app.api.Api
import dev.emusync.app.api.ApiException
import dev.emusync.app.fs.Fs
import dev.emusync.app.fs.FsUnavailableException
import dev.emusync.app.fs.LocalFs
import dev.emusync.app.model.*
import dev.emusync.app.modules.*
import dev.emusync.app.modules.azahar.AzaharModule
import dev.emusync.app.modules.eden.EdenModule
import dev.emusync.app.modules.melonds.MelonDsModule
import dev.emusync.app.modules.ppsspp.PpssppModule
import dev.emusync.app.modules.retroarch.RetroArchModule
import dev.emusync.app.modules.dolphin.DolphinModule
import dev.emusync.app.modules.vita3k.Vita3kModule
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeliveryApplierTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- fakes ----

    class FakeApi : Api {
        /** Each deliveries() call pops the next list; when empty, returns [standing]. */
        val pages = ArrayDeque<List<DeliveryDto>>()
        var standing: List<DeliveryDto> = emptyList()
        var deliveryCalls = 0
        val blobs = HashMap<String, ByteArray>()
        val acks = mutableListOf<Pair<String, AckRequest>>()
        val ackErrors = HashMap<String, ApiException>()
        val saves = mutableListOf<SaveUploadRequest>()
        val configs = mutableListOf<ConfigUploadRequest>()
        private var n = 0

        override suspend fun register(email: String, password: String, deviceName: String) = error("unused")
        override suspend fun login(email: String, password: String, deviceName: String) = error("unused")
        override suspend fun redeemPairing(code: String, deviceName: String) = error("unused")
        override suspend fun putStatus(req: DeviceStatusRequest) {}
        override suspend fun putInstalls(emulatorId: String, games: List<InstalledGame>) = InstallsResponse()
        override suspend fun missingBlobs(hashes: List<String>): List<String> = hashes
        override suspend fun putBlob(hash: String, size: Long, source: () -> InputStream) { source().use { it.readBytes() } }
        override suspend fun getBlob(hash: String): InputStream =
            ByteArrayInputStream(blobs[hash] ?: throw ApiException(404, "blob_not_found", "no blob"))
        override suspend fun uploadSave(req: SaveUploadRequest): SaveUploadResponse {
            saves += req; n++
            return SaveUploadResponse(SaveUploadStatus.Accepted, "item", "up$n", "up$n")
        }
        override suspend fun uploadConfig(req: ConfigUploadRequest): ConfigUploadResponse {
            configs += req; n++
            return ConfigUploadResponse(SaveUploadStatus.Accepted, "bundle", "cup$n")
        }
        override suspend fun deliveries(): List<DeliveryDto> {
            deliveryCalls++
            val acked = acks.map { it.first }.toSet()
            return (pages.removeFirstOrNull() ?: standing).filter { it.id !in acked }
        }
        override suspend fun ack(id: String, req: AckRequest) {
            acks += id to req
            ackErrors[id]?.let { throw it }
        }
    }

    /** Each dir under <root>/saves is one Save item; files in <root>/config and <root>/cfg2 form the bundle. */
    class FakeModule(val root: String) : EmulatorModule {
        override val emulatorId = "fake"
        override val packages = listOf("com.fake.emu")
        override val configRoots = setOf("data", "config")
        override fun detect(fs: Fs, settings: DeviceSettings): Detected? =
            if ("com.fake.emu" in fs.installedPackages()) Detected(emulatorId, "com.fake.emu", "", "$root/data",
                mapOf("config" to "$root/cfg2", "secret" to "$root/secret")) else null
        override fun saveItems(fs: Fs, d: Detected): List<LocalItem> =
            fs.list("$root/saves").filter { it.isDir }.map { e ->
                LocalItem(ItemKind.Save, e.path.substringAfterLast('/'), "", "core1", e.path,
                    fs.walk(e.path).map { it.path.removePrefix(e.path + "/") }, "Game ${e.path.substringAfterLast('/')}")
            }
        override fun watchDirs(fs: Fs, d: Detected) = listOf("$root/saves")
        override fun installedGames(fs: Fs, d: Detected, settings: DeviceSettings) = emptyList<InstalledGame>()
        override fun configBundle(fs: Fs, d: Detected): ConfigBundleFiles {
            val out = sortedMapOf<String, String>()
            fs.walk("$root/data").forEach { out["data/" + it.path.removePrefix("$root/data/")] = it.path }
            fs.walk("$root/cfg2").forEach { out["config/" + it.path.removePrefix("$root/cfg2/")] = it.path }
            return ConfigBundleFiles(out)
        }
        override fun targetDirFor(fs: Fs, d: Detected, kind: ItemKind, gameKey: String, slot: String, core: String): String {
            Paths.requireSafeSegment(gameKey)
            return "$root/saves/$gameKey"
        }
    }

    // ---- harness ----

    private var now = 1_000_000_000L
    private val logs = mutableListOf<String>()
    private val notes = mutableListOf<String>()
    private val api = FakeApi()
    private val root by lazy { tmp.newFolder("emu").path }
    private val backupRoot by lazy { tmp.newFolder("backups").path }
    private val vaultRoot by lazy { File(tmp.root, "vault").path }
    private val cache by lazy { tmp.newFolder("cache") }
    private val store by lazy { StateStore(File(tmp.root, "state.json")) }
    private val module by lazy { FakeModule(root) }
    private val none = DeviceSettings(emptyList(), emptyMap())

    private fun fs() = LocalFs().apply { packages.add("com.fake.emu") }

    private fun applier(fs: LocalFs = fs(), modules: List<EmulatorModule> = listOf(module)): DeliveryApplier {
        val registry = ModuleRegistry(modules)
        val engine = SyncEngine(fs, api, store, registry, none, clock = { now }, log = { logs += it },
            versionOf = { null }, appVersion = "0", shizukuOk = { true }, onConflict = {},
            storageRoot = tmp.newFolder().path)
        return DeliveryApplier(fs, api, store, registry, engine, notifier = { notes += it }, settings = none,
            cacheDir = cache, backups = LocalBackups(backupRoot, clock = { now }), vaultRoot = vaultRoot,
            log = { logs += it })
    }

    private fun put(rel: String, text: String) {
        val f = File(root, rel); f.parentFile!!.mkdirs(); f.writeText(text); f.setLastModified(5_000)
    }
    private fun read(rel: String) = File(root, rel).readText()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun entry(path: String, text: String): ManifestEntry {
        val b = text.toByteArray(); api.blobs[sha(b)] = b
        return ManifestEntry(path, sha(b), b.size.toLong())
    }

    private fun saveDelivery(id: String, game: String, vararg files: Pair<String, String>, version: String = "v-$id") =
        DeliveryDto(id, DeliveryKind.Save, "item-$game", version, null, "fake", game, ItemKind.Save, "", "core1",
            files.map { entry(it.first, it.second) }.sortedBy { it.path })

    private fun saveKey(game: String) = ItemKey.of("fake", "Save", game, "", "")

    /** Marks the current local content of [game] as synced. */
    private fun markSynced(game: String) {
        val dir = "$root/saves/$game"
        val files = File(dir).walk().filter { it.isFile }.map { it.path.removePrefix("$dir/") }.toList()
        val h = Manifests.hash(Manifests.build(LocalFs(), dir, files))
        store.put(saveKey(game), ItemState("old", h, h))
    }

    private fun ackOf(id: String) = api.acks.single { it.first == id }.second
    private fun backupDirs(game: String) =
        File(backupRoot, "fake/${ItemKey.safe(saveKey(game))}").listFiles()?.map { it.name }?.sorted() ?: emptyList()

    // ---- tests ----

    @Test fun running_emulator_blocks_write_and_notifies_once() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        val fs = fs().apply { alive.add("com.fake.emu") }
        val a = applier(fs)
        val r1 = a.applyAll()
        val r2 = a.applyAll()
        assertEquals(1, r1.waitingForEmulator)
        assertEquals(1, r2.waitingForEmulator)
        assertTrue(api.acks.isEmpty())
        assertEquals("old", read("saves/G1/a.sav"))
        assertEquals(listOf("Close fake to receive the newer save for G1"), notes)
    }

    @Test fun applies_after_emulator_exits() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        val fs = fs().apply { alive.add("com.fake.emu") }
        val a = applier(fs)
        a.applyAll()
        fs.alive.clear()
        val r = a.onEmulatorExited("fake")
        assertEquals(1, r.applied)
        assertEquals(DeliveryResult.Applied, ackOf("d1").result)
        assertEquals("new", read("saves/G1/a.sav"))
    }

    @Test fun local_change_uploads_first_and_acks_skipped() = runBlocking {
        put("saves/G1/a.sav", "mine")   // never synced: known is null
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "theirs"))
        val r = applier().applyAll()
        assertEquals(1, r.skipped)
        assertEquals(1, api.saves.size)
        assertEquals("G1", api.saves[0].gameKey)
        assertEquals(DeliveryResult.SkippedLocalChanged, ackOf("d1").result)
        assertEquals("mine", read("saves/G1/a.sav"))
        assertTrue(backupDirs("G1").isEmpty())
    }

    @Test fun local_change_against_known_hash_also_skips() = runBlocking {
        put("saves/G1/a.sav", "synced"); markSynced("G1")
        put("saves/G1/a.sav", "edited")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "theirs"))
        applier().applyAll()
        assertEquals(DeliveryResult.SkippedLocalChanged, ackOf("d1").result)
        assertEquals("edited", read("saves/G1/a.sav"))
    }

    @Test fun backup_taken_before_overwrite_and_pruned_to_5() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        val dir = File(backupRoot, "fake/${ItemKey.safe(saveKey("G1"))}")
        for (t in listOf(100L, 200L, 300L, 400L, 500L)) File(dir, "$t/a.sav").apply { parentFile.mkdirs(); writeText("b$t") }
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        applier().applyAll()
        assertEquals(listOf("200", "300", "400", "500", "$now").sorted(), backupDirs("G1"))
        assertEquals("old", File(dir, "$now/a.sav").readText())
        assertEquals("new", read("saves/G1/a.sav"))
    }

    @Test fun extra_local_files_deleted_after_backup() = runBlocking {
        put("saves/G1/a.sav", "old"); put("saves/G1/sub/extra.sav", "x"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        applier().applyAll()
        assertFalse(File(root, "saves/G1/sub/extra.sav").exists())
        val b = File(backupRoot, "fake/${ItemKey.safe(saveKey("G1"))}/$now")
        assertEquals("x", File(b, "sub/extra.sav").readText())
        assertEquals("old", File(b, "a.sav").readText())
        assertEquals(DeliveryResult.Applied, ackOf("d1").result)
    }

    @Test fun hash_mismatch_on_download_acks_failed_and_restores() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        val d = saveDelivery("d1", "G1", "a.sav" to "new", "b.sav" to "more")
        api.blobs[d.manifest[1].hash] = "tampered".toByteArray()
        api.standing = listOf(d)
        val r = applier().applyAll()
        assertEquals(1, r.failed)
        assertEquals(DeliveryResult.Failed, ackOf("d1").result)
        assertEquals("old", read("saves/G1/a.sav"))
        assertFalse(File(root, "saves/G1/b.sav").exists())
        assertTrue(cache.walk().none { it.isFile })   // downloads cleaned up
        assertEquals("old", store.get(saveKey("G1"))!!.knownVersionId)
    }

    @Test fun same_size_tampered_blob_rejected() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        val d = saveDelivery("d1", "G1", "a.sav" to "new1")
        api.blobs[d.manifest[0].hash] = "evil".toByteArray()
        api.standing = listOf(d)
        applier().applyAll()
        assertEquals(DeliveryResult.Failed, ackOf("d1").result)
        assertTrue(ackOf("d1").detail!!.startsWith("hash mismatch"))
        assertEquals("old", read("saves/G1/a.sav"))
    }

    @Test fun applied_updates_known_version() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        val d = saveDelivery("d1", "G1", "a.sav" to "new", version = "v9")
        api.standing = listOf(d)
        val r = applier().applyAll()
        assertEquals(ApplyReport(1, 0, 0, 0), r)
        val s = store.get(saveKey("G1"))!!
        assertEquals("v9", s.knownVersionId)
        assertEquals(Manifests.hash(d.manifest), s.knownManifestHash)
        assertEquals(DeliveryResult.Applied, ackOf("d1").result)
        assertTrue(cache.walk().none { it.isFile })
    }

    @Test fun new_item_written_to_module_target_dir() = runBlocking {
        api.standing = listOf(saveDelivery("d1", "G2", "x/y.sav" to "hi"))
        applier().applyAll()
        assertEquals("hi", read("saves/G2/x/y.sav"))
        assertEquals(DeliveryResult.Applied, ackOf("d1").result)
    }

    @Test fun content_already_present_acks_applied_without_write() = runBlocking {
        put("saves/G1/a.sav", "same")   // never synced
        val d = saveDelivery("d1", "G1", "a.sav" to "same", version = "v5")
        api.standing = listOf(d)
        val fs = object : LocalFs() {
            override fun writeAtomic(path: String, data: InputStream) = throw AssertionError("wrote $path")
        }.apply { packages.add("com.fake.emu") }
        val r = applier(fs).applyAll()
        assertEquals(1, r.applied)
        assertEquals(DeliveryResult.Applied, ackOf("d1").result)
        assertTrue(api.saves.isEmpty())
        assertTrue(backupDirs("G1").isEmpty())
        assertEquals("v5", store.get(saveKey("G1"))!!.knownVersionId)
        assertEquals(Manifests.hash(d.manifest), store.get(saveKey("G1"))!!.knownManifestHash)
    }

    @Test fun unsafe_path_fails_without_write() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new", "../../escape.sav" to "evil"))
        val r = applier().applyAll()
        assertEquals(1, r.failed)
        assertEquals(AckRequest(DeliveryResult.Failed, "unsafe path"), ackOf("d1"))
        assertEquals("old", read("saves/G1/a.sav"))
        assertFalse(File(root, "escape.sav").exists())
        assertTrue(backupDirs("G1").isEmpty())
    }

    @Test fun unknown_emulator_acks_failed() = runBlocking {
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new").copy(emulatorId = "nope"))
        applier().applyAll()
        assertEquals(AckRequest(DeliveryResult.Failed, "emulator not found"), ackOf("d1"))
    }

    @Test fun write_failure_restores_backup_and_removes_new_files() = runBlocking {
        put("saves/G1/a.sav", "old-a"); put("saves/G1/z.sav", "old-z"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new-a", "b.sav" to "new-b", "c.sav" to "new-c"))
        val fs = object : LocalFs() {
            override fun writeAtomic(path: String, data: InputStream) {
                if (path.endsWith("/c.sav")) throw IOException("disk full")
                super.writeAtomic(path, data)
            }
        }.apply { packages.add("com.fake.emu") }
        val r = applier(fs).applyAll()
        assertEquals(1, r.failed)
        assertEquals(DeliveryResult.Failed, ackOf("d1").result)
        assertEquals("old-a", read("saves/G1/a.sav"))
        assertEquals("old-z", read("saves/G1/z.sav"))   // extras are only deleted after every write succeeded
        assertFalse(File(root, "saves/G1/b.sav").exists())
        assertFalse(File(root, "saves/G1/c.sav").exists())
        assertEquals("old", store.get(saveKey("G1"))!!.knownVersionId)
        assertTrue(notes.none { it.startsWith("Restore failed") })
    }

    @Test fun restore_failure_notifies_with_backup_dir() = runBlocking {
        put("saves/G1/a.sav", "old-a"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new-a", "c.sav" to "new-c"))
        val fs = object : LocalFs() {
            override fun writeAtomic(path: String, data: InputStream) {
                if (path.endsWith("/c.sav")) throw IOException("disk full")
                super.writeAtomic(path, data)
            }
            override fun copy(src: String, dst: String) {
                if (src.startsWith(backupRoot)) throw IOException("restore broke")
                super.copy(src, dst)
            }
        }.apply { packages.add("com.fake.emu") }
        applier(fs).applyAll()
        assertEquals(DeliveryResult.Failed, ackOf("d1").result)
        val dir = File(backupRoot, "fake/${ItemKey.safe(saveKey("G1"))}/$now").path
        assertTrue(notes.toString(), notes.contains("Restore failed for Game G1 — backup at $dir"))
    }

    @Test fun backup_failure_acks_failed_without_write() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        val fs = object : LocalFs() {
            override fun copy(src: String, dst: String) = throw IOException("no space")
        }.apply { packages.add("com.fake.emu") }
        applier(fs).applyAll()
        assertEquals(DeliveryResult.Failed, ackOf("d1").result)
        assertEquals("old", read("saves/G1/a.sav"))
    }

    @Test fun ack_404_or_409_is_settled() = runBlocking {
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "one"), saveDelivery("d2", "G2", "a.sav" to "two"))
        api.ackErrors["d1"] = ApiException(404, "delivery_not_found", "gone")
        api.ackErrors["d2"] = ApiException(409, "already_acked", "acked")
        val r = applier().applyAll()
        assertEquals(2, r.applied)
        assertEquals("one", read("saves/G1/a.sav"))
        assertEquals("two", read("saves/G2/a.sav"))
    }

    @Test fun ack_server_error_logged_and_continues() = runBlocking {
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "one"), saveDelivery("d2", "G2", "a.sav" to "two"))
        api.ackErrors["d1"] = ApiException(500, "boom", "boom")
        applier().applyAll()
        assertEquals(listOf("d1", "d2"), api.acks.map { it.first })
        assertTrue(logs.any { it.contains("d1") && it.contains("500") })
    }

    @Test fun refetches_after_ack() = runBlocking {
        api.pages.add(listOf(saveDelivery("d1", "G1", "a.sav" to "one")))
        api.pages.add(listOf(saveDelivery("d2", "G2", "a.sav" to "two")))
        val r = applier().applyAll()
        assertEquals(2, r.applied)
        assertEquals(listOf("d1", "d2"), api.acks.map { it.first })
        assertEquals(3, api.deliveryCalls)
    }

    @Test fun refetch_is_bounded_to_5_passes() = runBlocking {
        var k = 0
        val endless = object : Api by api {
            override suspend fun deliveries(): List<DeliveryDto> { k++; return listOf(saveDelivery("d$k", "G$k", "a.sav" to "x$k")) }
        }
        val fs = fs()
        val registry = ModuleRegistry(listOf(module))
        val engine = SyncEngine(fs, endless, store, registry, none, { now }, { logs += it }, { null }, "0", { true }, {},
            tmp.newFolder().path)
        val a = DeliveryApplier(fs, endless, store, registry, engine, { notes += it }, none, cache,
            LocalBackups(backupRoot) { now }, vaultRoot) { logs += it }
        assertEquals(5, a.applyAll().applied)
        assertEquals(5, k)
    }

    @Test fun probe_failure_aborts_without_write() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        val fs = object : LocalFs() {
            override fun isProcessAlive(pkg: String): Boolean = throw FsUnavailableException("probe down")
        }.apply { packages.add("com.fake.emu") }
        try {
            applier(fs).applyAll(); fail("expected FsUnavailableException")
        } catch (e: FsUnavailableException) {}
        assertTrue(api.acks.isEmpty())
        assertEquals("old", read("saves/G1/a.sav"))
    }

    @Test fun fs_unavailable_mid_delivery_rethrows_without_ack() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        val fs = object : LocalFs() {
            override fun writeAtomic(path: String, data: InputStream) = throw FsUnavailableException("binder died")
        }.apply { packages.add("com.fake.emu") }
        try {
            applier(fs).applyAll(); fail("expected FsUnavailableException")
        } catch (e: FsUnavailableException) {}
        assertTrue(api.acks.isEmpty())
    }

    // ---- config ----

    private fun configDelivery(id: String, vararg files: Pair<String, String>, missing: List<String>? = null) =
        DeliveryDto(id, DeliveryKind.Config, "bundle-1", "cv-$id", null, "fake",
            manifest = files.map { entry(it.first, it.second) }.sortedBy { it.path }, missingLocalKeys = missing)

    private fun markConfigSynced() {
        val files = module.configBundle(LocalFs(), module.detect(fs(), none)!!).files
        val h = Manifests.hash(files.map { (rel, abs) ->
            val b = File(abs).readBytes(); ManifestEntry(rel, sha(b), b.size.toLong())
        })
        store.putConfigState("fake", ConfigState("bundle-1", "cold", h, h))
    }

    @Test fun config_delivery_maps_bundle_paths() = runBlocking {
        put("data/main.cfg", "old-main"); put("cfg2/core/old.opt", "stale"); markConfigSynced()
        val d = configDelivery("c1", "data/main.cfg" to "new-main", "config/core/a.opt" to "opt",
            missing = listOf("data/main.cfg#rom_dir"))
        api.standing = listOf(d)
        val r = applier().applyAll()
        assertEquals(1, r.applied)
        assertEquals("new-main", read("data/main.cfg"))
        assertEquals("opt", read("cfg2/core/a.opt"))
        assertFalse(File(root, "cfg2/core/old.opt").exists())
        assertEquals(DeliveryResult.Applied, ackOf("c1").result)
        val s = store.configState("fake")!!
        assertEquals("cv-c1", s.knownVersionId)
        assertEquals(Manifests.hash(d.manifest), s.knownManifestHash)
        assertEquals("bundle-1", s.bundleId)
        assertTrue(notes.contains("Set your ROM folders in fake on this device"))
        val b = File(backupRoot, "fake/${ItemKey.safe("fake|config")}/$now")
        assertEquals("old-main", File(b, "data/main.cfg").readText())
        assertEquals("stale", File(b, "config/core/old.opt").readText())
    }

    @Test fun config_extra_files_deleted_only_within_delivered_roots() = runBlocking {
        put("data/main.cfg", "old-main"); put("cfg2/keep.opt", "keep"); markConfigSynced()
        api.standing = listOf(configDelivery("c1", "data/main.cfg" to "new-main"))
        applier().applyAll()
        assertEquals("new-main", read("data/main.cfg"))
        assertEquals("keep", read("cfg2/keep.opt"))
    }

    @Test fun config_local_change_uploads_and_skips() = runBlocking {
        put("data/main.cfg", "mine")
        api.standing = listOf(configDelivery("c1", "data/main.cfg" to "theirs"))
        val r = applier().applyAll()
        assertEquals(1, r.skipped)
        assertEquals(1, api.configs.size)
        assertEquals(DeliveryResult.SkippedLocalChanged, ackOf("c1").result)
        assertEquals("mine", read("data/main.cfg"))
    }

    @Test fun config_path_outside_configRoots_rejected() = runBlocking {
        api.standing = listOf(configDelivery("c1", "data/main.cfg" to "x", "secret/key.bin" to "evil"))
        val r = applier().applyAll()
        assertEquals(1, r.failed)
        assertEquals(AckRequest(DeliveryResult.Failed, "path not allowed"), ackOf("c1"))
        assertFalse(File(root, "data/main.cfg").exists())
        assertFalse(File(root, "secret/key.bin").exists())
    }

    @Test fun config_unsafe_rest_rejected() = runBlocking {
        api.standing = listOf(configDelivery("c1", "data/../secret/x" to "evil"))
        applier().applyAll()
        assertEquals(AckRequest(DeliveryResult.Failed, "unsafe path"), ackOf("c1"))
    }

    // ---- vault ----

    @Test fun vault_delivery_written_and_notifies() = runBlocking {
        File(vaultRoot, "vault-1/bios/old.bin").apply { parentFile.mkdirs(); writeText("old") }
        val d = DeliveryDto("v1", DeliveryKind.Vault, "vault-1", "vv1", null, "fake",
            manifest = listOf(entry("bios/old.bin", "new"), entry("bios/b.bin", "bb")).sortedBy { it.path })
        api.standing = listOf(d)
        val r = applier().applyAll()
        assertEquals(1, r.applied)
        assertEquals("new", File(vaultRoot, "vault-1/bios/old.bin").readText())
        assertEquals("bb", File(vaultRoot, "vault-1/bios/b.bin").readText())
        assertEquals(DeliveryResult.Applied, ackOf("v1").result)
        assertTrue(notes.contains("Vault files ready in EmuSync/vault"))
        assertEquals("old", File(backupRoot, "vault/${ItemKey.safe("vault|vault-1")}/$now/bios/old.bin").readText())
    }

    @Test fun vault_unsafe_target_rejected() = runBlocking {
        api.standing = listOf(DeliveryDto("v1", DeliveryKind.Vault, "..", "vv1", null, "fake",
            manifest = listOf(entry("x.bin", "x"))))
        applier().applyAll()
        assertEquals(AckRequest(DeliveryResult.Failed, "unsafe path"), ackOf("v1"))
    }

    // ---- modules ----

    @Test fun module_config_roots() {
        assertEquals(setOf("data", "config"), RetroArchModule().configRoots)
        assertEquals(setOf("root"), AzaharModule().configRoots)
        assertEquals(setOf("memstick"), PpssppModule().configRoots)
        assertEquals(setOf("export"), MelonDsModule().configRoots)
        assertEquals(setOf("data"), EdenModule().configRoots)
        assertEquals(setOf("data"), DolphinModule().configRoots)
        assertEquals(setOf("data"), Vita3kModule().configRoots)
    }

    // ---- integration ----

    @Test fun eden_delivery_written_into_this_devices_profile() = runBlocking {
        val dev = tmp.newFolder("dev").path
        val data = "$dev/Android/data/dev.eden.eden_emulator/files"
        File(javaClass.getResource("/fixtures/eden/thor/config.ini")!!.toURI())
            .copyTo(File(data, "config/config.ini").also { it.parentFile.mkdirs() })
        val thorProfile = "23E468EE7BBC25842DE9EAD88AB25814"
        val profile = "$dev/Games/switch-saves/user/save/0000000000000000/$thorProfile"
        File("$profile/01002C9022770000/savedata/save000/data.dat").apply { parentFile.mkdirs(); writeText("other game"); setLastModified(5_000) }
        val fs = LocalFs().apply { packages.add("dev.eden.eden_emulator") }
        val d = DeliveryDto("e1", DeliveryKind.Save, "item-e", "ve1", null, "eden", "0100AAAA00000000", ItemKind.Save,
            null, null, listOf(entry("savedata/save000/data.dat", "zelda"), entry("savedata/save000/meta.bin", "m")))
        api.standing = listOf(d)
        val r = applier(fs, listOf(EdenModule(dev))).applyAll()
        assertEquals(ApplyReport(1, 0, 0, 0), r)
        assertEquals("zelda", File("$profile/0100AAAA00000000/savedata/save000/data.dat").readText())
        assertEquals("m", File("$profile/0100AAAA00000000/savedata/save000/meta.bin").readText())
        assertEquals("other game", File("$profile/01002C9022770000/savedata/save000/data.dat").readText())
        assertEquals("ve1", store.get(ItemKey.of("eden", "Save", "0100AAAA00000000", "", ""))!!.knownVersionId)
    }
}
