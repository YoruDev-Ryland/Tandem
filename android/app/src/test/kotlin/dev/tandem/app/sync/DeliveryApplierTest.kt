package dev.tandem.app.sync

import dev.tandem.app.api.Api
import dev.tandem.app.api.ApiException
import dev.tandem.app.fs.Fs
import dev.tandem.app.fs.FsUnavailableException
import dev.tandem.app.fs.LocalFs
import dev.tandem.app.model.*
import dev.tandem.app.modules.*
import dev.tandem.app.modules.azahar.AzaharModule
import dev.tandem.app.modules.eden.EdenModule
import dev.tandem.app.modules.melonds.MelonDsModule
import dev.tandem.app.modules.ppsspp.PpssppModule
import dev.tandem.app.modules.retroarch.RetroArchModule
import dev.tandem.app.modules.dolphin.DolphinModule
import dev.tandem.app.modules.vita3k.Vita3kModule
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DeliveryApplierTest {
    @get:Rule val tmp = TemporaryFolder()

    // ---- fakes ----

    class FakeApi : Api {
        override suspend fun games(emulator: String?): List<GameRow> = error("unused")
        override suspend fun game(emulatorId: String, gameKey: String): GameDetail = error("unused")
        override suspend fun conflicts(): List<ConflictRow> = error("unused")
        override suspend fun resolveConflict(itemId: String, req: ResolveConflictRequest) = error("unused")
        override suspend fun firstSync(req: BulkFirstSyncRequest) = error("unused")
        override suspend fun configs(): ConfigOverview = error("unused")
        override suspend fun joinGroup(groupId: String, deviceId: String) = error("unused")
        override suspend fun applyConfig(req: ApplyConfigRequest): ApplyConfigResponse = error("unused")
        override suspend fun keepOwn(deviceId: String, emulatorId: String) = error("unused")
        override suspend fun setup(deviceId: String): SetupChecklist = error("unused")
        override suspend fun activity(device: String?, before: Long?, limit: Int): ActivityPage = error("unused")
        override suspend fun devices(): List<DeviceSummary> = error("unused")
        override suspend fun me(): DeviceSummary = error("unused")
        override suspend fun serverInfo(): ServerInfo = error("unused")

        /** Each deliveries() call pops the next list; when empty, returns [standing]. */
        val pages = ArrayDeque<List<DeliveryDto>>()
        var standing: List<DeliveryDto> = emptyList()
        var deliveryCalls = 0
        val blobs = HashMap<String, ByteArray>()
        val acks = mutableListOf<Pair<String, AckRequest>>()
        val ackErrors = HashMap<String, ApiException>()
        var onGetBlob: (String) -> Unit = {}
        val saves = mutableListOf<SaveUploadRequest>()
        val configs = mutableListOf<ConfigUploadRequest>()
        var saveStatus = SaveUploadStatus.Accepted
        var configStatus = SaveUploadStatus.Accepted
        var uploadError: Exception? = null
        private var n = 0

        override suspend fun register(email: String, password: String, deviceName: String) = error("unused")
        override suspend fun login(email: String, password: String, deviceName: String) = error("unused")
        override suspend fun redeemPairing(code: String, deviceName: String) = error("unused")
        override suspend fun putStatus(req: DeviceStatusRequest) {}
        override suspend fun putInstalls(emulatorId: String, games: List<InstalledGame>) = InstallsResponse()
        override suspend fun forget(emulatorIds: List<String>) {}
        override suspend fun missingBlobs(hashes: List<String>): List<String> = hashes
        override suspend fun putBlob(hash: String, size: Long, source: () -> InputStream) { source().use { it.readBytes() } }
        override suspend fun getBlob(hash: String): InputStream {
            onGetBlob(hash)
            return ByteArrayInputStream(blobs[hash] ?: throw ApiException(404, "blob_not_found", "no blob"))
        }
        override suspend fun uploadSave(req: SaveUploadRequest): SaveUploadResponse {
            uploadError?.let { throw it }
            saves += req; n++
            return SaveUploadResponse(saveStatus, "item", "up$n", "up$n")
        }
        override suspend fun uploadConfig(req: ConfigUploadRequest): ConfigUploadResponse {
            uploadError?.let { throw it }
            configs += req; n++
            return ConfigUploadResponse(configStatus, "bundle", "cup$n")
        }
        var deliveriesError: Throwable? = null
        override suspend fun deliveries(): List<DeliveryDto> {
            deliveriesError?.let { throw it }
            deliveryCalls++
            val acked = acks.map { it.first }.toSet()
            return (pages.removeFirstOrNull() ?: standing).filter { it.id !in acked }
        }
        override suspend fun ack(id: String, req: AckRequest) {
            acks += id to req
            ackErrors[id]?.let { throw it }
        }
        var vaultItems: List<VaultItemDto> = emptyList()
        var vaultError: Exception? = null
        var vaultCalls = 0
        override suspend fun vault(): List<VaultItemDto> { vaultCalls++; vaultError?.let { throw it }; return vaultItems }
    }

    /** Each dir under <root>/saves is one Save item; files in <root>/config and <root>/cfg2 form the bundle. */
    class FakeModule(val root: String) : EmulatorModule {
        override val emulatorId = "fake"
        override val packages = listOf("com.fake.emu", "com.fake.emu.duo")   // the variant is never detected
        override val configRoots = setOf("data", "config")
        val hidden = mutableSetOf<String>()   // game dirs saveItems doesn't report (untracked files)
        var failTarget: RuntimeException? = null
        override fun detect(fs: Fs, settings: DeviceSettings): Detected? =
            if ("com.fake.emu" in fs.installedPackages()) Detected(emulatorId, "com.fake.emu", "", "$root/data",
                mapOf("config" to "$root/cfg2", "secret" to "$root/secret")) else null
        override fun saveItems(fs: Fs, d: Detected): List<LocalItem> =
            fs.list("$root/saves").filter { it.isDir && it.path.substringAfterLast('/') !in hidden }.map { e ->
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
            failTarget?.let { throw it }
            return "$root/saves/$gameKey"
        }
        /** Keys go to <root>/data/keys, like Eden's; BIOS to <root>/system, like RetroArch's; others have no known folder. */
        override fun vaultTarget(fs: Fs, d: Detected, category: String, name: String): String? {
            vaultError?.let { throw it }
            return when (category) { "keys" -> "$root/data/keys"; "bios" -> "$root/system"; else -> null }
        }
        override fun vaultFolderIsTarget(category: String) = category == "keys"
        var vaultError: ModuleException? = null
    }

    // ---- harness ----

    private var now = 1_000_000_000L
    private val logs = mutableListOf<String>()
    private val notes = mutableListOf<String>()
    private val received = mutableListOf<ReceivedDelivery>()
    private val api = FakeApi()
    private val root by lazy { tmp.newFolder("emu").path }
    private val backupRoot by lazy { tmp.newFolder("backups").path }
    private val vaultRoot by lazy { File(tmp.root, "vault").path }
    private val cache by lazy { tmp.newFolder("cache") }
    private val store by lazy { StateStore(File(tmp.root, "state.json")) }
    private val module by lazy { FakeModule(root) }
    private val none = DeviceSettings(emptyList(), emptyMap())

    private fun fs() = LocalFs().apply { packages.add("com.fake.emu") }

    private lateinit var engine: SyncEngine

    private fun applier(fs: LocalFs = fs(), modules: List<EmulatorModule> = listOf(module)): DeliveryApplier {
        val registry = ModuleRegistry(modules)
        engine = SyncEngine(fs, api, store, registry, none, clock = { now }, log = { logs += it },
            versionOf = { null }, appVersion = "0", shizukuOk = { true }, onConflict = {},
            storageRoot = tmp.newFolder().path)
        return DeliveryApplier(fs, api, store, registry, engine, notifier = { notes += it }, settings = none,
            cacheDir = cache, backups = LocalBackups(backupRoot, clock = { now }), vaultRoot = vaultRoot,
            onReceived = { received += it }, log = { logs += it })
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

    @Test fun after_superseded_upload_delivery_stays_unacked_then_applies() = runBlocking {
        put("saves/G1/a.sav", "losing")   // never synced
        api.saveStatus = SaveUploadStatus.Superseded
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "head"))
        val a = applier()
        assertEquals(1, a.applyAll().skipped)
        assertEquals(1, api.saves.size)
        assertTrue("superseded: not acked, re-evaluated next pass", api.acks.isEmpty())
        assertEquals("losing", read("saves/G1/a.sav"))
        val r = a.applyAll()             // local now equals the known (adopted) version: the delivery applies
        assertEquals(1, r.applied)
        assertEquals(DeliveryResult.Applied, ackOf("d1").result)
        assertEquals("head", read("saves/G1/a.sav"))
        assertEquals(1, api.saves.size)
    }

    @Test fun after_unchanged_upload_delivery_stays_unacked() = runBlocking {
        put("saves/G1/a.sav", "same-as-head")   // never synced
        api.saveStatus = SaveUploadStatus.Unchanged
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "newer"))
        assertEquals(1, applier().applyAll().skipped)
        assertTrue(api.acks.isEmpty())
        assertEquals("same-as-head", read("saves/G1/a.sav"))
    }

    // ---- final review I3: no silent stall on a stale cache or a failed upload ----

    @Test fun applier_invalidates_cache_before_upload() = runBlocking {
        put("saves/G1/a.sav", "aaaa"); markSynced("G1")
        val a = applier()
        engine.fullScan()                                  // primes the cache with "aaaa" (= known)
        put("saves/G1/a.sav", "bbbb")                      // same size and mtime: the cache would say unchanged
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "theirs"))
        assertEquals(1, a.applyAll().skipped)
        assertEquals(1, api.saves.size)
        assertEquals(h("bbbb"), api.saves.single().manifest.single().hash)
        assertEquals(DeliveryResult.SkippedLocalChanged, ackOf("d1").result)
        assertEquals("bbbb", read("saves/G1/a.sav"))
    }

    @Test fun applier_invalidates_cache_before_config_upload() = runBlocking {
        put("data/main.cfg", "aaaa"); markConfigSynced()
        val a = applier()
        engine.fullScan()
        put("data/main.cfg", "bbbb")
        api.standing = listOf(configDelivery("c1", "data/main.cfg" to "theirs"))
        assertEquals(1, a.applyAll().skipped)
        assertEquals(h("bbbb"), api.configs.single().files.single().hash)
        assertEquals(DeliveryResult.SkippedLocalChanged, ackOf("c1").result)
    }

    @Test fun a_pass_reports_whether_it_reached_the_server() = runBlocking {
        api.standing = emptyList()
        assertTrue(applier().applyAll().reachedServer)
        api.deliveriesError = IOException("offline")
        assertFalse(applier().applyAll().reachedServer)
    }

    @Test fun failed_upload_of_local_change_leaves_delivery_unacked() = runBlocking {
        put("saves/G1/a.sav", "mine")
        api.uploadError = IOException("network down")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "theirs"))
        val r = applier().applyAll()
        assertEquals(1, r.failed)
        assertTrue(api.acks.isEmpty())
        assertEquals("mine", read("saves/G1/a.sav"))
    }

    @Test fun failed_config_upload_leaves_delivery_unacked() = runBlocking {
        put("data/main.cfg", "mine")
        api.uploadError = IOException("network down")
        api.standing = listOf(configDelivery("c1", "data/main.cfg" to "theirs"))
        assertEquals(1, applier().applyAll().failed)
        assertTrue(api.acks.isEmpty())
        assertEquals("mine", read("data/main.cfg"))
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

    private fun cachedIn(game: String) = engine.cachedPaths().filter { it.contains("/saves/$game/") }

    @Test fun applier_write_invalidates_engine_cache() = runBlocking {
        put("saves/G1/a.sav", "aaaa"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "bbbb", version = "v9"))
        val a = applier()
        engine.fullScan()                       // primes the engine's hash cache with "aaaa"
        assertEquals(1, cachedIn("G1").size)
        a.applyAll()
        assertEquals("bbbb", read("saves/G1/a.sav"))
        assertTrue(cachedIn("G1").isEmpty())
        File(root, "saves/G1/a.sav").setLastModified(5_000)   // storage keeps a stale mtime
        api.standing = emptyList()
        now += 100_000
        engine.fullScan()
        assertTrue("stale cached hash was uploaded", api.saves.isEmpty())
    }

    @Test fun failed_write_undo_invalidates_engine_cache() = runBlocking {
        put("saves/G1/a.sav", "old-a"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new-a", "c.sav" to "new-c"))
        val fs = object : LocalFs() {
            override fun writeAtomic(path: String, data: InputStream) {
                if (path.endsWith("/c.sav")) throw IOException("disk full")
                super.writeAtomic(path, data)
            }
        }.apply { packages.add("com.fake.emu") }
        val a = applier(fs)
        engine.fullScan()
        assertEquals(1, cachedIn("G1").size)
        a.applyAll()
        assertEquals("old-a", read("saves/G1/a.sav"))
        assertTrue(cachedIn("G1").isEmpty())
    }

    @Test fun marker_recovery_invalidates_engine_cache() = runBlocking {
        put("saves/G1/a.sav", "new"); put("saves/G1/b.sav", "created")
        backupFile("a.sav", "old")
        val a = applier()
        engine.fullScan()
        assertEquals(2, cachedIn("G1").size)
        crashMarker(mapOf("a.sav" to MarkerFile(h("new"), "a.sav", h("old")), "b.sav" to MarkerFile(h("created"), null, null)))
        a.applyAll()
        assertEquals("old", read("saves/G1/a.sav"))
        assertTrue(cachedIn("G1").isEmpty())
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
        assertTrue(store.applyMarkers().isEmpty())
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
            override fun isAnyProcessAlive(pkgs: Collection<String>): Boolean = throw FsUnavailableException("probe down")
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
        assertEquals(AckRequest(DeliveryResult.Applied, DeliveryApplier.STAGED), ackOf("v1"))
        assertTrue(notes.any { it.contains("is ready in") })
        assertEquals("old", File(backupRoot, "vault/${ItemKey.safe("vault|vault-1")}/$now/bios/old.bin").readText())
    }

    private fun keysDelivery(id: String = "v1", item: String = "item-keys", vararg files: Pair<String, String>) =
        DeliveryDto(id, DeliveryKind.Vault, item, "vv-$id", null, "fake", manifest = files.map { entry(it.first, it.second) }.sortedBy { it.path })

    @Test fun keys_go_into_the_emulators_own_folder_with_a_backup_of_what_they_replace() = runBlocking {
        api.vaultItems = listOf(VaultItemDto("item-keys", "keys", "fake", "keys"))
        put("data/keys/prod.keys", "old keys")
        api.standing = listOf(keysDelivery("v1", "item-keys", "prod.keys" to "new keys", "title.keys" to "titles"))
        val r = applier().applyAll()
        assertEquals(1, r.applied)
        assertEquals("new keys", read("data/keys/prod.keys"))
        assertEquals("titles", read("data/keys/title.keys"))
        assertEquals(AckRequest(DeliveryResult.Applied, DeliveryApplier.PLACED), ackOf("v1"))
        assertEquals("old keys", File(backupRoot, "fake/${ItemKey.safe("vault|item-keys")}/$now/prod.keys").readText())
        assertFalse("nothing staged in Tandem/vault", File(vaultRoot).exists())
        assertTrue(notes.contains("Placed keys in fake's folder"))
        assertTrue(store.applyMarkers().isEmpty())
    }

    @Test fun keys_wait_while_the_emulator_runs_and_land_when_it_closes() = runBlocking {
        api.vaultItems = listOf(VaultItemDto("item-keys", "keys", "fake", "keys"))
        put("data/keys/prod.keys", "old keys")
        api.standing = listOf(keysDelivery("v1", "item-keys", "prod.keys" to "new keys"))
        val fs = fs().apply { alive.add("com.fake.emu") }
        val a = applier(fs)
        val r = a.applyAll()
        assertEquals(1, r.waitingForEmulator)
        assertEquals(setOf("fake"), r.waitingOn)
        assertTrue(api.acks.isEmpty())
        assertEquals("old keys", read("data/keys/prod.keys"))
        assertEquals(listOf("Close fake to receive keys from the vault"), notes)
        fs.alive.clear()
        assertEquals(1, a.onEmulatorExited("fake").applied)
        assertEquals("new keys", read("data/keys/prod.keys"))
    }

    @Test fun keys_already_in_place_are_not_rewritten() = runBlocking {
        api.vaultItems = listOf(VaultItemDto("item-keys", "keys", "fake", "keys"))
        put("data/keys/prod.keys", "same")
        api.standing = listOf(keysDelivery("v1", "item-keys", "prod.keys" to "same"))
        assertEquals(1, applier().applyAll().applied)
        assertEquals(AckRequest(DeliveryResult.Applied, DeliveryApplier.PLACED), ackOf("v1"))
        assertFalse("no backup taken: nothing was replaced", File(backupRoot, "fake").exists())
    }

    @Test fun an_unmapped_category_is_staged_by_emulator_category_and_name() = runBlocking {
        api.vaultItems = listOf(VaultItemDto("item-fw", "firmware", "fake", "Firmware 19.0.1"))
        api.standing = listOf(keysDelivery("v1", "item-fw", "a.nca" to "nca"))
        assertEquals(1, applier().applyAll().applied)
        assertEquals("nca", File(vaultRoot, "fake/firmware/Firmware 19.0.1/a.nca").readText())
        assertEquals(AckRequest(DeliveryResult.Applied, DeliveryApplier.STAGED), ackOf("v1"))
        assertFalse(File(root, "data/keys").exists())
    }

    @Test fun a_bios_folder_keeps_its_own_folder_below_the_system_folder() = runBlocking {
        api.vaultItems = listOf(VaultItemDto("item-dc", "bios", "fake", "dc"))
        put("system/dc_boot.bin", "someone else's")
        api.standing = listOf(keysDelivery("v1", "item-dc", "dc/dc_boot.bin" to "boot", "dc/dc_flash.bin" to "flash"))
        assertEquals(1, applier().applyAll().applied)
        assertEquals("boot", read("system/dc/dc_boot.bin"))
        assertEquals("flash", read("system/dc/dc_flash.bin"))
        assertEquals("not clobbered", "someone else's", read("system/dc_boot.bin"))
        assertEquals(AckRequest(DeliveryResult.Applied, DeliveryApplier.PLACED), ackOf("v1"))
    }

    @Test fun a_single_bios_file_lands_directly_in_the_system_folder() = runBlocking {
        api.vaultItems = listOf(VaultItemDto("item-psx", "bios", "fake", "scph5501.bin"))
        api.standing = listOf(keysDelivery("v1", "item-psx", "scph5501.bin" to "psx"))
        assertEquals(1, applier().applyAll().applied)
        assertEquals("psx", read("system/scph5501.bin"))
    }

    @Test fun a_keys_folder_goes_into_the_keys_folder_not_below_it() = runBlocking {
        api.vaultItems = listOf(VaultItemDto("item-keys", "keys", "fake", "switch keys"))
        api.standing = listOf(keysDelivery("v1", "item-keys", "switch keys/prod.keys" to "p", "switch keys/title.keys" to "t"))
        assertEquals(1, applier().applyAll().applied)
        assertEquals("p", read("data/keys/prod.keys"))
        assertEquals("t", read("data/keys/title.keys"))
        assertFalse(File(root, "data/keys/switch keys").exists())
    }

    @Test fun a_staged_folder_is_not_nested_twice() = runBlocking {
        api.vaultItems = listOf(VaultItemDto("item-fw", "firmware", "fake", "fw19"))
        api.standing = listOf(keysDelivery("v1", "item-fw", "fw19/a.nca" to "a", "fw19/sub/b.nca" to "b"))
        assertEquals(1, applier().applyAll().applied)
        assertEquals("a", File(vaultRoot, "fake/firmware/fw19/a.nca").readText())
        assertEquals("b", File(vaultRoot, "fake/firmware/fw19/sub/b.nca").readText())
    }

    @Test fun a_module_error_while_finding_the_folder_stages_the_item_instead_of_failing() = runBlocking {
        api.vaultItems = listOf(VaultItemDto("item-keys", "keys", "fake", "keys"))
        module.vaultError = ModuleException("retroarch.cfg unreadable")
        api.standing = listOf(keysDelivery("v1", "item-keys", "keys/prod.keys" to "k"))
        assertEquals(1, applier().applyAll().applied)
        assertEquals("k", File(vaultRoot, "fake/keys/keys/prod.keys").readText())
        assertEquals(AckRequest(DeliveryResult.Applied, DeliveryApplier.STAGED), ackOf("v1"))
    }

    @Test fun without_the_vault_list_the_delivery_waits_for_the_next_pass() = runBlocking {
        api.vaultError = java.io.IOException("offline")
        api.standing = listOf(keysDelivery("v1", "item-keys", "prod.keys" to "k"), keysDelivery("v2", "item-other", "x.bin" to "x"))
        val r = applier().applyAll()
        assertEquals(2, r.failed)
        assertTrue("never acked, so the server offers it again", api.acks.isEmpty())
        assertEquals("the list is fetched once per pass, not per delivery", 1, api.vaultCalls)
    }

    @Test fun vault_unsafe_target_rejected() = runBlocking {
        api.standing = listOf(DeliveryDto("v1", DeliveryKind.Vault, "..", "vv1", null, "fake",
            manifest = listOf(entry("x.bin", "x"))))
        applier().applyAll()
        assertEquals(AckRequest(DeliveryResult.Failed, "unsafe path"), ackOf("v1"))
    }

    // ---- modules ----

    @Test fun module_vault_targets_only_where_the_module_knows_the_folder() {
        val fs = LocalFs()
        val eden = Detected("eden", "dev.eden.eden_emulator", "", "/e/files")
        assertEquals("/e/files/keys", EdenModule().vaultTarget(fs, eden, "keys", "prod.keys"))
        assertNull("firmware is installed from inside Eden", EdenModule().vaultTarget(fs, eden, "firmware", "fw"))
        val az = Detected("azahar", "org.azahar_emu.azahar", "", "/a/user")
        assertEquals("/a/user/sysdata", AzaharModule().vaultTarget(fs, az, "sysdata", "aes_keys.txt"))
        assertEquals("/a/user/sysdata", AzaharModule().vaultTarget(fs, az, "keys", "aes_keys.txt"))
        assertNull(AzaharModule().vaultTarget(fs, az, "bios", "x"))
        assertTrue("a keys folder goes into keys/", EdenModule().vaultFolderIsTarget("keys"))
        assertTrue("a sysdata folder goes into sysdata/", AzaharModule().vaultFolderIsTarget("sysdata"))
        assertFalse("a BIOS folder stays a folder in system/", RetroArchModule().vaultFolderIsTarget("bios"))
        for (m in listOf(PpssppModule(), DolphinModule(), MelonDsModule(), Vita3kModule())) {
            for (c in listOf("bios", "keys", "firmware", "sysdata", "system")) {
                assertNull("${m.emulatorId} $c", m.vaultTarget(fs, Detected(m.emulatorId, "p", "", "/x"), c, "n"))
            }
        }
        // RetroArch: the cfg's system_directory, else RetroArch/system when it exists, else nothing.
        val internal = tmp.newFolder("ra-internal").path
        val ra = RetroArchModule(internal)
        val rfs = LocalFs().apply { packages.add("com.retroarch.aarch64") }
        val cfg = File("$internal/Android/data/com.retroarch.aarch64/files/retroarch.cfg").apply { parentFile.mkdirs() }
        File("$internal/RetroArch").mkdirs()
        cfg.writeText("savefile_directory = \"default\"\n")
        assertNull(ra.vaultTarget(rfs, ra.detect(rfs, none)!!, "bios", "scph5501.bin"))
        File("$internal/RetroArch/system").mkdirs()
        assertEquals("$internal/RetroArch/system", ra.vaultTarget(rfs, ra.detect(rfs, none)!!, "bios", "scph5501.bin"))
        cfg.writeText("system_directory = \"/storage/emulated/0/BIOS\"\n")
        assertEquals("$internal/BIOS", ra.vaultTarget(rfs, ra.detect(rfs, none)!!, "system", "x"))
        assertNull(ra.vaultTarget(rfs, ra.detect(rfs, none)!!, "keys", "x"))
    }

    @Test fun module_config_roots() {
        assertEquals(setOf("data", "config"), RetroArchModule().configRoots)
        assertEquals(setOf("root"), AzaharModule().configRoots)
        assertEquals(setOf("memstick"), PpssppModule().configRoots)
        assertEquals(setOf("export"), MelonDsModule().configRoots)
        assertEquals(setOf("data"), EdenModule().configRoots)
        assertEquals(setOf("data"), DolphinModule().configRoots)
        assertEquals(setOf("data"), Vita3kModule().configRoots)
    }

    // ---- fix round 1: write-time re-checks ----

    @Test fun emulator_started_during_download_waits() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        val fs = fs()
        api.onGetBlob = { fs.alive.add("com.fake.emu") }
        val a = applier(fs)
        val r = a.applyAll()
        assertEquals(1, r.waitingForEmulator)
        assertTrue(api.acks.isEmpty())
        assertEquals("old", read("saves/G1/a.sav"))
        assertTrue(backupDirs("G1").isEmpty())
        assertEquals(listOf("Close fake to receive the newer save for G1"), notes)
        api.onGetBlob = {}
        fs.alive.clear()
        assertEquals(1, a.onEmulatorExited("fake").applied)
        assertEquals("new", read("saves/G1/a.sav"))
    }

    @Test fun local_change_during_download_aborts_without_write() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        api.onGetBlob = { put("saves/G1/a.sav", "played") }
        val a = applier()
        val r = a.applyAll()
        assertEquals(1, r.failed)
        assertTrue(api.acks.isEmpty())
        assertEquals("played", read("saves/G1/a.sav"))
        assertTrue(store.applyMarkers().isEmpty())
        api.onGetBlob = {}
        a.applyAll()   // the guard re-runs: the local change is uploaded and the delivery skipped
        assertEquals(DeliveryResult.SkippedLocalChanged, ackOf("d1").result)
        assertEquals("played", read("saves/G1/a.sav"))
    }

    @Test fun write_phase_holds_engine_lock() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        var lockedDuringWrite: Boolean? = null
        var lockedDuringDownload: Boolean? = null
        val fs = object : LocalFs() {
            override fun writeAtomic(path: String, data: InputStream) {
                if (path.startsWith(root)) lockedDuringWrite = runBlocking { withTimeoutOrNull(100) { engine.withLock { } } } == null
                super.writeAtomic(path, data)
            }
        }.apply { packages.add("com.fake.emu") }
        val a = applier(fs)
        api.onGetBlob = { lockedDuringDownload = runBlocking { withTimeoutOrNull(100) { engine.withLock { } } } == null }
        a.applyAll()
        assertEquals(true, lockedDuringWrite)
        assertEquals(false, lockedDuringDownload)
        assertEquals("new", read("saves/G1/a.sav"))
    }

    @Test fun prune_keeps_new_backup_when_clock_behind() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        val dir = File(backupRoot, "fake/${ItemKey.safe(saveKey("G1"))}")
        for (t in 1..5) File(dir, "${now + t * 100}/a.sav").apply { parentFile.mkdirs(); writeText("b$t") }
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        applier().applyAll()
        val expected = listOf(now + 200, now + 300, now + 400, now + 500, now + 501).map { it.toString() }
        assertEquals(expected, backupDirs("G1"))
        assertEquals("old", File(dir, "${now + 501}/a.sav").readText())
    }

    @Test fun case_insensitive_match_writes_existing_spelling() = runBlocking {
        put("saves/G1/Game.srm", "old"); put("saves/G1/Other.srm", "x"); markSynced("G1")
        val d = saveDelivery("d1", "G1", "game.srm" to "new")
        api.standing = listOf(d)
        applier().applyAll()
        assertEquals(DeliveryResult.Applied, ackOf("d1").result)
        assertEquals("new", read("saves/G1/Game.srm"))
        assertFalse(File(root, "saves/G1/game.srm").exists())
        assertFalse(File(root, "saves/G1/Other.srm").exists())
        // Known hash is over the spelling on disk, so the engine sees no local change.
        val e = d.manifest[0]
        assertEquals(Manifests.hash(listOf(ManifestEntry("Game.srm", e.hash, e.size))), store.get(saveKey("G1"))!!.knownManifestHash)
    }

    @Test fun case_insensitive_extras_not_deleted() = runBlocking {
        put("saves/G1/SAVE.DAT", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "save.dat" to "new", "more.dat" to "m"))
        applier().applyAll()
        assertEquals("new", read("saves/G1/SAVE.DAT"))
        assertEquals("m", read("saves/G1/more.dat"))
    }

    @Test fun case_variant_local_file_never_deleted_as_extra() = runBlocking {
        // Two local spellings (possible only on case-sensitive storage): neither is deleted as an "extra".
        put("saves/G1/Game.srm", "one"); put("saves/G1/GAME.srm", "two"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "game.srm" to "new"))
        applier().applyAll()
        assertEquals(DeliveryResult.Applied, ackOf("d1").result)
        assertTrue(File(root, "saves/G1/Game.srm").exists())
        assertTrue(File(root, "saves/G1/GAME.srm").exists())
    }

    @Test fun case_duplicate_paths_rejected() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "A.sav" to "one", "a.sav" to "two"))
        applier().applyAll()
        assertEquals(AckRequest(DeliveryResult.Failed, "bad manifest: duplicate path"), ackOf("d1"))
        assertEquals("old", read("saves/G1/a.sav"))
    }

    @Test fun untracked_identical_file_acks_applied() = runBlocking {
        put("saves/G1/a.sav", "same"); module.hidden += "G1"
        val d = saveDelivery("d1", "G1", "a.sav" to "same", version = "v3")
        api.standing = listOf(d)
        val fs = object : LocalFs() {
            override fun writeAtomic(path: String, data: InputStream) = throw AssertionError("wrote $path")
        }.apply { packages.add("com.fake.emu") }
        assertEquals(1, applier(fs).applyAll().applied)
        assertEquals(DeliveryResult.Applied, ackOf("d1").result)
        assertEquals("v3", store.get(saveKey("G1"))!!.knownVersionId)
    }

    @Test fun untracked_different_file_fails_and_notifies() = runBlocking {
        put("saves/G1/a.sav", "unsynced"); module.hidden += "G1"
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "theirs"))
        applier().applyAll()
        assertEquals(AckRequest(DeliveryResult.Failed, "untracked local file at target"), ackOf("d1"))
        assertEquals("unsynced", read("saves/G1/a.sav"))
        assertTrue(notes.contains("A file for G1 exists here that Tandem hasn't synced; not overwriting."))
    }

    @Test fun unexpected_exception_acks_failed_and_continues() = runBlocking {
        module.failTarget = IllegalStateException("module bug")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "x"))
        val a = applier()
        assertEquals(1, a.applyAll().failed)
        assertEquals(DeliveryResult.Failed, ackOf("d1").result)
        assertTrue(ackOf("d1").detail!!.contains("module bug"))
        module.failTarget = null
        put("saves/G2/a.sav", "old"); markSynced("G2")
        api.standing = listOf(saveDelivery("d2", "G2", "a.sav" to "new"))
        assertEquals(1, a.applyAll().applied)
    }

    // ---- fix round 1: apply markers ----

    private fun failingRestoreFs() = object : LocalFs() {
        var failWrites = true
        var failRestore = true
        override fun writeAtomic(path: String, data: InputStream) {
            if (failWrites && path.endsWith("/c.sav")) throw IOException("disk full")
            super.writeAtomic(path, data)
        }
        override fun copy(src: String, dst: String) {
            if (failRestore && src.startsWith(backupRoot)) throw IOException("restore broke")
            super.copy(src, dst)
        }
    }.apply { packages.add("com.fake.emu") }

    private fun h(text: String) = sha(text.toByteArray())
    private fun backupFile(rel: String, text: String) =
        File(backupRoot, "crash/$rel").apply { parentFile.mkdirs(); writeText(text) }
    /** A marker as left by a crash mid-write for G1; [files] keyed by path relative to the G1 dir. */
    private fun crashMarker(files: Map<String, MarkerFile>) {
        val abs = "$root/saves/G1"
        store.putApplyMarker(ApplyMarker(saveKey("G1"), "fake", "d0", File(backupRoot, "crash").path, abs,
            files.filterValues { it.backupRel == null }.keys.map { "$abs/$it" },
            files.mapKeys { "$abs/${it.key}" }, "Game G1"))
    }

    @Test fun marker_recovered_on_next_pass() = runBlocking {
        put("saves/G1/a.sav", "new"); put("saves/G1/b.sav", "created")
        backupFile("a.sav", "old")
        crashMarker(mapOf("a.sav" to MarkerFile(h("new"), "a.sav", h("old")), "b.sav" to MarkerFile(h("created"), null, null)))
        applier().applyAll()
        assertEquals("old", read("saves/G1/a.sav"))
        assertFalse(File(root, "saves/G1/b.sav").exists())
        assertTrue(store.applyMarkers().isEmpty())
    }

    @Test fun recovery_restores_file_we_wrote() = runBlocking {
        put("saves/G1/a.sav", "new"); put("saves/G1/u.sav", "u")
        backupFile("a.sav", "old"); backupFile("x.sav", "extra"); backupFile("u.sav", "u")
        crashMarker(mapOf(
            "a.sav" to MarkerFile(h("new"), "a.sav", h("old")),     // we wrote it -> restored
            "x.sav" to MarkerFile(null, "x.sav", h("extra")),       // we deleted it -> restored
            "u.sav" to MarkerFile(h("not yet"), "u.sav", h("u")),   // never written -> left
        ))
        applier().applyAll()
        assertEquals("old", read("saves/G1/a.sav"))
        assertEquals("extra", read("saves/G1/x.sav"))
        assertEquals("u", read("saves/G1/u.sav"))
        assertTrue(store.applyMarkers().isEmpty())
        assertTrue(notes.none { it.startsWith("Restore failed") })
    }

    @Test fun recovery_deletes_only_unchanged_files_we_created() = runBlocking {
        put("saves/G1/b.sav", "new-b"); put("saves/G1/keep.sav", "k")
        backupFile("keep.sav", "k")
        crashMarker(mapOf(
            "b.sav" to MarkerFile(h("new-b"), null, null),           // ours, unchanged -> deleted
            "d.sav" to MarkerFile(h("new-d"), null, null),           // never written -> nothing to do
            "keep.sav" to MarkerFile(h("new-k"), "keep.sav", h("k")), // not yet overwritten -> left
        ))
        applier().applyAll()
        assertFalse(File(root, "saves/G1/b.sav").exists())
        assertFalse(File(root, "saves/G1/d.sav").exists())
        assertEquals("k", read("saves/G1/keep.sav"))
        assertTrue(store.applyMarkers().isEmpty())
    }

    @Test fun recovery_leaves_user_changed_file_and_lets_engine_upload_it() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        put("saves/G1/a.sav", "played")   // after the crash the user played and saved
        backupFile("a.sav", "old")
        crashMarker(mapOf("a.sav" to MarkerFile(h("new"), "a.sav", h("old"))))
        val a = applier()
        a.applyAll()
        assertEquals("played", read("saves/G1/a.sav"))
        assertTrue(store.applyMarkers().isEmpty())
        val det = module.detect(fs(), none)!!
        assertEquals(SaveUploadStatus.Accepted, engine.upload(module, det, module.saveItems(fs(), det)[0]))
        assertEquals(listOf(h("played")), api.saves.single().manifest.map { it.hash })
    }

    @Test fun recovery_keeps_whole_item_when_any_file_user_changed() = runBlocking {
        put("saves/G1/a.sav", "new-a"); put("saves/G1/b.sav", "played-b"); put("saves/G1/c.sav", "new-c")
        backupFile("a.sav", "old-a"); backupFile("b.sav", "old-b"); backupFile("x.sav", "extra")
        crashMarker(mapOf(
            "a.sav" to MarkerFile(h("new-a"), "a.sav", h("old-a")),   // delivered content
            "b.sav" to MarkerFile(h("new-b"), "b.sav", h("old-b")),   // user changed after the crash
            "c.sav" to MarkerFile(h("new-c"), null, null),            // we created it
            "x.sav" to MarkerFile(null, "x.sav", h("extra")),         // we deleted it
        ))
        applier().applyAll()
        assertEquals("new-a", read("saves/G1/a.sav"))
        assertEquals("played-b", read("saves/G1/b.sav"))
        assertEquals("new-c", read("saves/G1/c.sav"))
        assertFalse(File(root, "saves/G1/x.sav").exists())
        assertTrue(store.applyMarkers().isEmpty())
        assertTrue(logs.any { it.contains("user changed Game G1 after an interrupted apply; keeping their files") })
        assertTrue(notes.none { it.startsWith("Restore failed") })
    }

    @Test fun undetectable_emulator_with_live_marker_notifies_once() = runBlocking {
        put("saves/G1/a.sav", "new"); backupFile("a.sav", "old")
        crashMarker(mapOf("a.sav" to MarkerFile(h("new"), "a.sav", h("old"))))
        val a = applier(LocalFs())
        a.applyAll(); a.applyAll()
        val dir = File(backupRoot, "crash").path
        assertEquals(listOf("Tandem couldn't finish restoring Game G1; backup at $dir"), notes)
        assertEquals(1, store.applyMarkers().size)
    }

    @Test fun recovery_keeps_created_file_that_user_changed() = runBlocking {
        put("saves/G1/a.sav", "new-a"); put("saves/G1/c.sav", "user-c")
        backupFile("a.sav", "old-a")
        crashMarker(mapOf(
            "a.sav" to MarkerFile(h("new-a"), "a.sav", h("old-a")),   // ours
            "c.sav" to MarkerFile(h("new-c"), null, null),            // we created it, then the user saved over it
        ))
        applier().applyAll()
        assertEquals("new-a", read("saves/G1/a.sav"))
        assertEquals("user-c", read("saves/G1/c.sav"))
        assertTrue(store.applyMarkers().isEmpty())
    }

    @Test fun recovery_keeps_item_when_deleted_extra_reappears_with_other_content() = runBlocking {
        put("saves/G1/a.sav", "new-a"); put("saves/G1/x.sav", "user-x")
        backupFile("a.sav", "old-a"); backupFile("x.sav", "extra")
        crashMarker(mapOf(
            "a.sav" to MarkerFile(h("new-a"), "a.sav", h("old-a")),
            "x.sav" to MarkerFile(null, "x.sav", h("extra")),          // we deleted it; the user wrote a new one
        ))
        applier().applyAll()
        assertEquals("new-a", read("saves/G1/a.sav"))
        assertEquals("user-x", read("saves/G1/x.sav"))
        assertTrue(store.applyMarkers().isEmpty())
    }

    @Test fun file_vanished_before_backup_is_treated_as_created() = runBlocking {
        // Untracked identical a.sav exists at the guard, vanishes just before the backup copies it; b.sav's write fails.
        put("saves/G1/a.sav", "same"); module.hidden += "G1"
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "same", "b.sav" to "new-b"))
        val aPath = "$root/saves/G1/a.sav"
        var calls = 0
        val fs = object : LocalFs() {
            override fun exists(path: String): Boolean {
                if (path == aPath && ++calls == 3) File(path).delete()   // guard, write-phase check, then backup
                return super.exists(path)
            }
            override fun writeAtomic(path: String, data: InputStream) {
                if (path.endsWith("/b.sav")) throw IOException("disk full")
                super.writeAtomic(path, data)
            }
        }.apply { packages.add("com.fake.emu") }
        applier(fs).applyAll()
        assertEquals(DeliveryResult.Failed, ackOf("d1").result)
        assertFalse(File(aPath).exists())                     // our written copy removed
        assertFalse(File(root, "saves/G1/b.sav").exists())
        assertTrue(store.applyMarkers().isEmpty())
        assertTrue(notes.none { it.startsWith("Restore failed") })
    }

    @Test fun failed_inflow_undo_not_notified_twice() = runBlocking {
        put("saves/G1/a.sav", "old-a"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new-a", "c.sav" to "new-c"))
        val a = applier(failingRestoreFs())
        a.applyAll(); a.applyAll()
        assertEquals(1, notes.count { it.startsWith("Restore failed") })
    }

    @Test fun recovery_probe_runs_under_engine_lock() = runBlocking {
        put("saves/G1/a.sav", "new"); backupFile("a.sav", "old")
        crashMarker(mapOf("a.sav" to MarkerFile(h("new"), "a.sav", h("old"))))
        val locked = mutableListOf<Boolean>()
        val fs = object : LocalFs() {
            override fun isAnyProcessAlive(pkgs: Collection<String>): Boolean {
                locked += runBlocking { withTimeoutOrNull(100) { engine.withLock { } } } == null
                return super.isAnyProcessAlive(pkgs)
            }
        }.apply { packages.add("com.fake.emu") }
        applier(fs).applyAll()
        assertEquals(listOf(true), locked)
        assertEquals("old", read("saves/G1/a.sav"))
    }

    @Test fun undetected_notice_resets_after_detection() = runBlocking {
        put("saves/G1/a.sav", "new"); backupFile("a.sav", "old")
        crashMarker(mapOf("a.sav" to MarkerFile(h("new"), "a.sav", h("old"))))
        val fs = LocalFs().apply { alive.add("com.fake.emu") }
        val a = applier(fs)
        a.applyAll()                              // undetected: notice
        fs.packages.add("com.fake.emu"); a.applyAll()   // detected but running: no notice, resets
        fs.packages.clear(); a.applyAll()         // undetected again: notice again
        assertEquals(2, notes.count { it.startsWith("Tandem couldn't finish restoring") })
        assertEquals(1, store.applyMarkers().size)
    }

    @Test fun recovery_skipped_when_detect_fails() = runBlocking {
        put("saves/G1/a.sav", "new"); backupFile("a.sav", "old")
        crashMarker(mapOf("a.sav" to MarkerFile(h("new"), "a.sav", h("old"))))
        applier(LocalFs()).applyAll()   // emulator package not detected
        assertEquals("new", read("saves/G1/a.sav"))
        assertEquals(1, store.applyMarkers().size)
        assertTrue(notes.none { it.startsWith("Restore failed") })
    }

    @Test fun fs_unavailable_during_recovery_is_not_reported() = runBlocking {
        put("saves/G1/a.sav", "new"); backupFile("a.sav", "old")
        crashMarker(mapOf("a.sav" to MarkerFile(h("new"), "a.sav", h("old"))))
        val fs = object : LocalFs() {
            override fun copy(src: String, dst: String) = throw FsUnavailableException("binder died")
        }.apply { packages.add("com.fake.emu") }
        try { applier(fs).applyAll(); fail("expected FsUnavailableException") } catch (e: FsUnavailableException) {}
        assertEquals(1, store.applyMarkers().size)
        assertTrue(notes.none { it.startsWith("Restore failed") })
    }

    @Test fun fs_unavailable_during_inflow_restore_is_not_reported() = runBlocking {
        put("saves/G1/a.sav", "old-a"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new-a", "c.sav" to "new-c"))
        val fs = object : LocalFs() {
            override fun writeAtomic(path: String, data: InputStream) {
                if (path.endsWith("/c.sav")) throw IOException("disk full")
                super.writeAtomic(path, data)
            }
            override fun copy(src: String, dst: String) {
                if (src.startsWith(backupRoot)) throw FsUnavailableException("binder died")
                super.copy(src, dst)
            }
        }.apply { packages.add("com.fake.emu") }
        try { applier(fs).applyAll(); fail("expected FsUnavailableException") } catch (e: FsUnavailableException) {}
        assertTrue(api.acks.isEmpty())
        assertNotNull(store.applyMarker(saveKey("G1")))
        assertTrue(notes.none { it.startsWith("Restore failed") })
    }

    @Test fun marker_recovery_waits_while_emulator_runs() = runBlocking {
        put("saves/G1/a.sav", "new"); backupFile("a.sav", "old")
        crashMarker(mapOf("a.sav" to MarkerFile(h("new"), "a.sav", h("old"))))
        val fs = fs().apply { alive.add("com.fake.emu") }
        val a = applier(fs)
        a.applyAll()
        assertEquals("new", read("saves/G1/a.sav"))
        assertEquals(1, store.applyMarkers().size)
        fs.alive.clear()
        a.onEmulatorExited("fake")
        assertEquals("old", read("saves/G1/a.sav"))
        assertTrue(store.applyMarkers().isEmpty())
    }

    @Test fun notifications_use_emulator_and_game_display_names() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        val named = object : EmulatorModule by module { override val displayName = "Fake Emu" }
        val fs = fs()
        val a = applier(fs, listOf(named))
        engine.fullScan()                                 // learns the title "Game G1"
        fs.alive.add("com.fake.emu")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"),
            configDelivery("c1", "data/main.cfg" to "x"))
        a.applyAll()
        assertEquals(listOf("Close Fake Emu to receive the newer save for Game G1",
            "Close Fake Emu to receive the newer settings"), notes)
    }

    // ---- final review C1: any variant or sub-process counts as running ----

    @Test fun undetected_variant_running_blocks_delivery() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        val fs = fs().apply { alive.add("com.fake.emu.duo") }   // detected package is com.fake.emu
        val a = applier(fs)
        assertEquals(1, a.applyAll().waitingForEmulator)
        assertTrue(api.acks.isEmpty())
        assertEquals("old", read("saves/G1/a.sav"))
        fs.alive.clear()
        assertEquals(1, a.onEmulatorExited("fake").applied)
        assertEquals("new", read("saves/G1/a.sav"))
    }

    @Test fun subprocess_running_blocks_delivery() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        val fs = fs().apply { alive.add("com.fake.emu:emu") }
        assertEquals(1, applier(fs).applyAll().waitingForEmulator)
        assertTrue(api.acks.isEmpty())
        assertEquals("old", read("saves/G1/a.sav"))
    }

    @Test fun variant_started_during_download_waits() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        val fs = fs()
        api.onGetBlob = { fs.alive.add("com.fake.emu.duo:sub") }
        assertEquals(1, applier(fs).applyAll().waitingForEmulator)
        assertTrue(api.acks.isEmpty())
        assertEquals("old", read("saves/G1/a.sav"))
        assertTrue(backupDirs("G1").isEmpty())
    }

    @Test fun config_delivery_waits_while_variant_runs() = runBlocking {
        put("data/emu.cfg", "a=1")
        api.standing = listOf(configDelivery("c1", "data/emu.cfg" to "a=2"))
        val fs = fs().apply { alive.add("com.fake.emu.duo") }
        assertEquals(1, applier(fs).applyAll().waitingForEmulator)
        assertTrue(api.acks.isEmpty())
        assertEquals("a=1", read("data/emu.cfg"))
    }

    @Test fun marker_recovery_waits_while_variant_runs() = runBlocking {
        put("saves/G1/a.sav", "new"); backupFile("a.sav", "old")
        crashMarker(mapOf("a.sav" to MarkerFile(h("new"), "a.sav", h("old"))))
        val fs = fs().apply { alive.add("com.fake.emu.duo") }
        applier(fs).applyAll()
        assertEquals("new", read("saves/G1/a.sav"))
        assertEquals(1, store.applyMarkers().size)
    }

    @Test fun failed_restore_keeps_marker() = runBlocking {
        put("saves/G1/a.sav", "old-a"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new-a", "c.sav" to "new-c"))
        val fs = failingRestoreFs()
        val a = applier(fs)
        a.applyAll()
        assertEquals(DeliveryResult.Failed, ackOf("d1").result)
        val mk = store.applyMarker(saveKey("G1"))!!
        assertEquals("d1", mk.deliveryId)
        assertNotNull(mk.backupDir)
        a.applyAll()   // recovery still fails: marker kept
        assertNotNull(store.applyMarker(saveKey("G1")))
        fs.failRestore = false
        a.applyAll()   // recovery succeeds
        assertNull(store.applyMarker(saveKey("G1")))
        assertEquals("old-a", read("saves/G1/a.sav"))
    }

    @Test fun delivery_waits_while_marker_live() = runBlocking {
        put("saves/G1/a.sav", "old-a"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new-a", "c.sav" to "new-c"))
        val fs = failingRestoreFs()
        val a = applier(fs)
        a.applyAll()
        fs.failWrites = false
        api.standing = listOf(saveDelivery("d2", "G1", "a.sav" to "newer"))
        a.applyAll()
        assertTrue(api.acks.none { it.first == "d2" })
        assertNotEquals("newer", read("saves/G1/a.sav"))
    }

    @Test fun engine_does_not_upload_item_with_marker() = runBlocking {
        put("saves/G1/a.sav", "local")
        applier()
        store.putApplyMarker(ApplyMarker(saveKey("G1"), "fake", "d0", null, "$root/saves/G1", emptyList()))
        assertNull(engine.upload(module, module.detect(fs(), none)!!, module.saveItems(fs(), module.detect(fs(), none)!!)[0]))
        assertTrue(api.saves.isEmpty())
        store.clearApplyMarker(saveKey("G1"))
        assertNotNull(engine.upload(module, module.detect(fs(), none)!!, module.saveItems(fs(), module.detect(fs(), none)!!)[0]))
        assertEquals(1, api.saves.size)
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

    // ---- final review I-1 and (b): asked-for applies finish on exit; held deliveries that land are reported ----

    @Test fun report_names_the_emulators_a_pass_waited_for() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        val r = applier(fs().apply { alive.add("com.fake.emu") }).applyAll()
        assertEquals(setOf("fake"), r.waitingOn)
    }

    @Test fun forced_exit_pass_applies_after_a_restart_lost_what_was_held() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        val fs = fs().apply { alive.add("com.fake.emu") }
        applier(fs).applyAll()                       // held in the old session
        fs.alive.clear()
        val fresh = applier(fs)                      // a new session: nothing held in memory
        assertEquals(0, fresh.onEmulatorExited("fake").applied)
        assertEquals("old", read("saves/G1/a.sav"))
        assertEquals(1, fresh.onEmulatorExited("fake", force = true).applied)
        assertEquals("new", read("saves/G1/a.sav"))
    }

    @Test fun forced_exit_pass_still_waits_while_the_emulator_runs() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        val a = applier(fs().apply { alive.add("com.fake.emu") })
        val r = a.onEmulatorExited("fake", force = true)   // reopened before the pass ran
        assertEquals(0, r.applied)
        assertEquals(setOf("fake"), r.waitingOn)
        assertTrue(api.acks.isEmpty())
        assertEquals("old", read("saves/G1/a.sav"))
    }

    @Test fun a_held_delivery_that_lands_is_reported_received() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        put("saves/G2/a.sav", "old"); markSynced("G2")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        val fs = fs().apply { alive.add("com.fake.emu") }
        val a = applier(fs)
        a.applyAll()
        assertEquals(emptyList<ReceivedDelivery>(), received)
        fs.alive.clear()
        a.onEmulatorExited("fake")
        assertEquals(listOf(Triple("d1", "fake", "G1")), received.map { Triple(it.deliveryId, it.emulatorId, it.gameKey) })

        // A delivery that never waited lands silently.
        received.clear()
        api.standing = listOf(saveDelivery("d2", "G2", "a.sav" to "new"))
        assertEquals(1, a.applyAll().applied)
        assertEquals(emptyList<ReceivedDelivery>(), received)
    }

    @Test fun a_held_delivery_applied_by_sync_now_is_reported_received() = runBlocking {
        put("saves/G1/a.sav", "old"); markSynced("G1")
        api.standing = listOf(saveDelivery("d1", "G1", "a.sav" to "new"))
        val fs = fs().apply { alive.add("com.fake.emu") }
        val a = applier(fs)
        a.applyAll()
        fs.alive.clear()
        assertEquals(1, a.applyAll().applied)
        assertEquals(listOf("d1"), received.map { it.deliveryId })
    }
}
