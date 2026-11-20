package dev.emusync.app.sync

import dev.emusync.app.api.Api
import dev.emusync.app.api.ApiException
import dev.emusync.app.fs.Fs
import dev.emusync.app.fs.FsUnavailableException
import dev.emusync.app.fs.LocalFs
import dev.emusync.app.model.*
import dev.emusync.app.modules.*
import dev.emusync.app.modules.eden.EdenModule
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncEngineTest {
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

        val statuses = mutableListOf<DeviceStatusRequest>()
        val installs = mutableListOf<Pair<String, List<InstalledGame>>>()
        val missingQueries = mutableListOf<List<String>>()
        val putBlobs = mutableListOf<String>()
        val saves = mutableListOf<SaveUploadRequest>()
        val configs = mutableListOf<ConfigUploadRequest>()
        val serverBlobs = mutableSetOf<String>()
        var saveStatus = SaveUploadStatus.Accepted
        var configStatus = SaveUploadStatus.Accepted
        var failSaveFor: String? = null
        var unparseableSaveFor: String? = null
        var blobRetries = 0                     // the next N blob PUTs answer 503 blob_retry
        private var n = 0

        override suspend fun register(email: String, password: String, deviceName: String) = error("unused")
        override suspend fun login(email: String, password: String, deviceName: String) = error("unused")
        override suspend fun redeemPairing(code: String, deviceName: String) = error("unused")
        override suspend fun putStatus(req: DeviceStatusRequest) { statuses += req }
        override suspend fun putInstalls(emulatorId: String, games: List<InstalledGame>): InstallsResponse {
            installs += emulatorId to games; return InstallsResponse(games.size, 0)
        }
        override suspend fun missingBlobs(hashes: List<String>): List<String> {
            missingQueries += hashes; return hashes.filter { it !in serverBlobs }
        }
        override suspend fun putBlob(hash: String, size: Long, source: () -> InputStream) {
            source().use { it.readBytes() }
            if (blobRetries > 0) { blobRetries--; throw ApiException(503, "blob_retry", "send it again") }
            putBlobs += hash; serverBlobs += hash
        }
        override suspend fun getBlob(hash: String): InputStream = error("unused")
        override suspend fun uploadSave(req: SaveUploadRequest): SaveUploadResponse {
            if (req.gameKey == failSaveFor) throw ApiException(500, "boom", "boom")
            if (req.gameKey == unparseableSaveFor) throw kotlinx.serialization.SerializationException("unknown enum")
            saves += req; n++
            return SaveUploadResponse(saveStatus, "item-${req.gameKey}", "v$n", "head$n")
        }
        override suspend fun uploadConfig(req: ConfigUploadRequest): ConfigUploadResponse {
            configs += req; n++
            return ConfigUploadResponse(configStatus, "bundle-${req.emulatorId}", "cv$n")
        }
        override suspend fun deliveries(): List<DeliveryDto> = emptyList()
        override suspend fun ack(id: String, req: AckRequest) {}
    }

    /** Each dir under <root>/saves is one Save item; files in <root>/config form the config bundle. */
    class FakeModule(val root: String, override val emulatorId: String = "fake") : EmulatorModule {
        override val packages = listOf("com.fake.emu", "com.fake.emu.duo")   // the variant is never detected
        var throwOnItems = false
        var crashOnItems = false
        override fun detect(fs: Fs, settings: DeviceSettings): Detected? =
            if ("com.fake.emu" in fs.installedPackages()) Detected(emulatorId, "com.fake.emu", "", root) else null
        override fun saveItems(fs: Fs, d: Detected): List<LocalItem> {
            if (throwOnItems) throw ModuleException("broken")
            if (crashOnItems) throw IllegalStateException("module bug")
            // "<game>@<core>" dirs give the same Save key with different cores (like RetroArch core folders).
            return fs.list("$root/saves").filter { it.isDir }.map { e ->
                val n = e.path.substringAfterLast('/')
                LocalItem(ItemKind.Save, n.substringBefore('@'), "", if ('@' in n) n.substringAfter('@') else "core1", e.path,
                    fs.walk(e.path).map { it.path.removePrefix(e.path + "/") })
            }
        }
        override fun watchDirs(fs: Fs, d: Detected) = listOf("$root/saves")
        override fun installedGames(fs: Fs, d: Detected, settings: DeviceSettings) =
            saveItems(fs, d).map { InstalledGame(it.gameKey) }
        override fun configBundle(fs: Fs, d: Detected) = ConfigBundleFiles(
            fs.list("$root/config").filter { !it.isDir }.associate { "data/${it.path.substringAfterLast('/')}" to it.path })
        override fun targetDirFor(fs: Fs, d: Detected, kind: ItemKind, gameKey: String, slot: String, core: String) = "$root/saves/$gameKey"
        override fun cores(fs: Fs, d: Detected) = listOf("core1", "core2")
    }

    // ---- harness ----

    private var now = 1_000_000L
    private val logs = mutableListOf<String>()
    private val conflicts = mutableListOf<SyncConflict>()
    private val api = FakeApi()
    private val root by lazy { tmp.newFolder("emu").path }
    private val storage by lazy { tmp.newFolder("storage").path }
    private val store by lazy { StateStore(File(tmp.root, "state.json")) }
    private val module by lazy { FakeModule(root) }

    private fun fs() = LocalFs().apply { packages.add("com.fake.emu") }

    private val delays = mutableListOf<Long>()

    private fun engine(
        fs: Fs = fs(),
        modules: List<EmulatorModule> = listOf(module),
        versionOf: (String) -> String? = { if (it == "com.fake.emu") "1.2.3" else null },
    ) = SyncEngine(
        fs, api, store, ModuleRegistry(modules), DeviceSettings(emptyList(), emptyMap()),
        clock = { now }, log = { logs += it },
        versionOf = versionOf,
        appVersion = "0.1.0", shizukuOk = { true },
        onConflict = { conflicts += it },
        storageRoot = storage,
        retryDelay = { delays += it },
    )

    private fun put(rel: String, text: String, mtime: Long = 5_000) {
        val f = File(root, rel); f.parentFile!!.mkdirs(); f.writeText(text); f.setLastModified(mtime)
    }

    private fun saveKey(game: String) = ItemKey.of("fake", "Save", game, "", "")

    // ---- tests ----

    @Test fun first_scan_uploads_items_with_null_base() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        put("saves/GAME1/sub/b.sav", "world")
        engine().fullScan()
        assertEquals(1, api.saves.size)
        val r = api.saves[0]
        assertNull(r.baseVersionId)
        assertEquals("fake", r.emulatorId)
        assertEquals("GAME1", r.gameKey)
        assertEquals(ItemKind.Save, r.kind)
        assertEquals("", r.slot)
        assertEquals("core1", r.core)  // wire still carries the core
        assertEquals(listOf("a.sav", "sub/b.sav"), r.manifest.map { it.path })
    }

    @Test fun unchanged_item_not_reuploaded() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        val e = engine()
        e.fullScan()
        e.fullScan()
        assertEquals(1, api.saves.size)
        // and a new engine instance (process restart) also skips it
        engine().fullScan()
        assertEquals(1, api.saves.size)
    }

    @Test fun accepted_stores_known_version() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        engine().fullScan()
        val s = store.get(saveKey("GAME1"))!!
        assertEquals("v1", s.knownVersionId)
        assertEquals(Manifests.hash(api.saves[0].manifest), s.knownManifestHash)
        assertNull(store.get(ItemKey.of("fake", "Save", "GAME1", "", "core1")))  // save key ignores core
        put("saves/GAME1/a.sav", "hello2", 6_000)
        engine().fullScan()
        assertEquals("v1", api.saves[1].baseVersionId)
        assertEquals("v2", store.get(saveKey("GAME1"))!!.knownVersionId)
    }

    @Test fun unchanged_response_stores_head_version() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        api.saveStatus = SaveUploadStatus.Unchanged
        engine().fullScan()
        assertEquals("head1", store.get(saveKey("GAME1"))!!.knownVersionId)
    }

    @Test fun superseded_response_stores_matched_version_without_conflict_notice() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        api.saveStatus = SaveUploadStatus.Superseded
        engine().fullScan()
        val s = store.get(saveKey("GAME1"))!!
        assertEquals("v1", s.knownVersionId)
        assertEquals(Manifests.hash(api.saves[0].manifest), s.knownManifestHash)
        assertTrue(conflicts.isEmpty())
    }

    @Test fun conflict_does_not_store_and_notifies() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        api.saveStatus = SaveUploadStatus.Conflict
        engine().fullScan()
        assertNull(store.get(saveKey("GAME1")))
        assertEquals(1, conflicts.size)
        assertEquals("fake", conflicts[0].emulatorId)
        assertEquals("GAME1", conflicts[0].gameKey)
        assertTrue(logs.any { it.contains("conflict", ignoreCase = true) })
    }

    @Test fun only_missing_blobs_are_put() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        put("saves/GAME1/b.sav", "world")
        put("saves/GAME1/c.sav", "hello")  // duplicate content -> one hash
        val helloHash = Manifests.build(LocalFs(), "$root/saves/GAME1", listOf("a.sav")).single().hash
        api.serverBlobs += helloHash
        engine().fullScan()
        assertEquals(1, api.missingQueries.size)
        assertEquals(2, api.missingQueries[0].size)  // distinct hashes
        assertEquals(1, api.putBlobs.size)
        assertNotEquals(helloHash, api.putBlobs[0])
    }

    @Test fun event_then_quiet_period_uploads_once() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        val e = engine()
        e.fullScan()
        assertEquals(1, api.saves.size)
        put("saves/GAME1/a.sav", "changed", 9_000)
        now += 10_000; e.onFsEvent("$root/saves/GAME1")
        now += 1_000; e.onFsEvent("$root/saves/GAME1")
        now += 1_000; e.onFsEvent("$root/saves/GAME1")
        val last = now
        now = last + 2_000; e.tick()
        assertEquals(1, api.saves.size)
        now = last + 3_100; e.tick()
        assertEquals(2, api.saves.size)
        now = last + 10_000; e.tick()
        assertEquals(2, api.saves.size)
    }

    private fun hashOf(text: String) = Manifests.hash(listOf(ManifestEntry("a.sav",
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) },
        text.length.toLong())))
    private fun blobHash(text: String) =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun same_size_same_mtime_rewrite_is_uploaded_with_true_content() = runBlocking {
        put("saves/GAME1/a.sav", "AAAA")
        val e = engine()
        e.fullScan()
        assertEquals(1, api.saves.size)
        put("saves/GAME1/a.sav", "BBBB", 5_000)   // same size, old mtime forced
        now += 10_000; e.onFsEvent("$root/saves/GAME1")
        now += 4_000; e.tick()
        assertEquals(2, api.saves.size)
        assertEquals(hashOf("BBBB"), Manifests.hash(api.saves[1].manifest))
        assertTrue(blobHash("BBBB") in api.putBlobs)
    }

    @Test fun cache_never_feeds_upload_manifest() = runBlocking {
        put("saves/GAME1/a.sav", "AAAA")
        api.saveStatus = SaveUploadStatus.Conflict   // nothing stored; the cache is primed with A
        val e = engine()
        e.fullScan()
        put("saves/GAME1/a.sav", "BBBB", 5_000)      // same size and mtime, no event
        val d = module.detect(fs(), DeviceSettings(emptyList(), emptyMap()))!!
        val item = module.saveItems(fs(), d).single()
        e.upload(module, d, item)
        assertEquals(hashOf("BBBB"), Manifests.hash(api.saves.last().manifest))
    }

    @Test fun event_between_build_and_commit_drops_entry() = runBlocking {
        put("saves/GAME1/a.sav", "AAAA")
        lateinit var e: SyncEngine
        var fired = false
        val fs = object : LocalFs() {
            override fun openRead(path: String): InputStream {
                if (!fired && path.endsWith("/a.sav")) {
                    fired = true
                    val a = File(path).readBytes()
                    File(path).writeText("BBBB"); File(path).setLastModified(5_000)   // same size, stale mtime
                    e.onFsEvent("$root/unrelated")   // bumps the epoch without touching this item's marks
                    return a.inputStream()
                }
                return super.openRead(path)
            }
        }.apply { packages.add("com.fake.emu") }
        e = engine(fs)
        e.fullScan()
        assertFalse(e.cachedPaths().any { it.endsWith("/a.sav") })
        e.fullScan()
        assertEquals(hashOf("BBBB"), Manifests.hash(api.saves.last().manifest))
    }

    @Test fun cache_never_feeds_config_upload_manifest() = runBlocking {
        put("config/c.ini", "AAAA")
        api.configStatus = SaveUploadStatus.Conflict   // nothing stored; the cache is primed with A
        val e = engine()
        e.fullScan()
        assertEquals(1, api.configs.size)
        put("config/c.ini", "BBBB", 5_000)             // same size and mtime, no event
        val d = module.detect(fs(), DeviceSettings(emptyList(), emptyMap()))!!
        e.uploadConfig(module, d)
        assertEquals(2, api.configs.size)
        assertTrue(api.configs.last().files.any { it.hash == blobHash("BBBB") })
    }

    @Test fun config_change_with_same_size_and_mtime_uploaded() = runBlocking {
        put("config/c.ini", "AAAA")
        val e = engine()
        e.fullScan()                                   // Accepted: known hash is A
        assertEquals(1, api.configs.size)
        put("config/c.ini", "BBBB", 5_000)             // exFAT/FUSE: same size and mtime, no event
        e.onEmulatorExited("fake")
        assertEquals(2, api.configs.size)
        assertTrue(api.configs.last().files.any { it.hash == blobHash("BBBB") })
    }

    @Test fun event_in_parent_dir_picks_up_new_item() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        val e = engine()
        e.fullScan()
        put("saves/GAME2/x.sav", "new")
        e.onFsEvent("$root/saves")
        e.tick()
        assertEquals(1, api.saves.size)  // not quiet yet
        now += 3_000; e.tick()
        assertEquals(listOf("GAME1", "GAME2"), api.saves.map { it.gameKey })
    }

    @Test fun file_changing_during_upload_defers() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        var changes = 1
        val fs = object : LocalFs() {
            override fun openRead(path: String): InputStream {
                val s = super.openRead(path)
                if (changes > 0 && path.endsWith("a.sav")) {
                    changes--
                    File(path).appendText("more"); File(path).setLastModified(7_000)
                }
                return s
            }
        }.apply { packages.add("com.fake.emu") }
        val e = engine(fs)
        e.fullScan()
        assertEquals(0, api.saves.size)
        assertNull(store.get(saveKey("GAME1")))
        now += 2_000; e.tick()
        assertEquals(0, api.saves.size)
        now += 1_100; e.tick()
        assertEquals(1, api.saves.size)
        assertEquals("hellomore".length.toLong(), api.saves[0].manifest.single().size)
    }

    @Test fun fs_unavailable_aborts_without_state_change() {
        put("saves/GAME1/a.sav", "hello")
        val fs = object : LocalFs() {
            override fun openRead(path: String): InputStream = throw FsUnavailableException("shizuku gone")
        }.apply { packages.add("com.fake.emu") }
        try {
            runBlocking { engine(fs).fullScan() }
            fail("expected FsUnavailableException")
        } catch (_: FsUnavailableException) {}
        assertEquals(0, api.saves.size)
        assertNull(store.get(saveKey("GAME1")))
    }

    @Test fun api_error_on_one_item_continues() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        put("saves/GAME2/a.sav", "world")
        api.failSaveFor = "GAME1"
        engine().fullScan()
        assertEquals(listOf("GAME2"), api.saves.map { it.gameKey })
        assertNull(store.get(saveKey("GAME1")))
        assertNotNull(store.get(saveKey("GAME2")))
    }

    @Test fun unparseable_server_response_logs_and_continues() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        put("saves/GAME2/a.sav", "world")
        api.unparseableSaveFor = "GAME1"
        engine().fullScan()
        assertEquals(listOf("GAME2"), api.saves.map { it.gameKey })
        assertNull(store.get(saveKey("GAME1")))
        assertTrue(logs.any { it.contains("server response not understood") })
    }

    @Test fun failed_upload_counts_as_queued_until_retried() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        put("saves/GAME2/a.sav", "world")
        api.failSaveFor = "GAME1"
        val e = engine()
        e.fullScan()
        assertEquals(1, e.queuedCount())
        api.failSaveFor = null
        e.fullScan()
        assertEquals(0, e.queuedCount())
    }

    @Test fun exposes_detected_watch_dirs_and_item_counts() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        put("saves/GAME2/a.sav", "world")
        val e = engine()
        e.fullScan()
        assertEquals(listOf("fake"), e.detectedEmulators().map { it.emulatorId })
        assertEquals(listOf("$root/saves"), e.allWatchDirs())
        assertEquals(mapOf("fake" to 2), e.itemCounts())
    }

    @Test fun module_error_skips_module() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        val other = FakeModule(tmp.newFolder("emu2").path, "other").also {
            File(it.root, "saves/G2").mkdirs(); File(it.root, "saves/G2/x").writeText("x")
        }
        module.throwOnItems = true
        engine(modules = listOf(module, other)).fullScan()
        assertEquals(listOf("other"), api.saves.map { it.emulatorId })
    }

    @Test fun empty_item_not_uploaded() = runBlocking {
        File(root, "saves/EMPTY").mkdirs()
        engine().fullScan()
        assertEquals(0, api.saves.size)
    }

    @Test fun status_reports_roots_and_emulators() = runBlocking {
        File(storage, "emulated/0").mkdirs()
        File(storage, "self").mkdirs()
        File(storage, "1A2B-3C4D").mkdirs()
        engine().fullScan()
        val s = api.statuses.single()
        assertEquals("android", s.platform)
        assertEquals("0.1.0", s.appVersion)
        assertTrue(s.shizukuOk)
        assertEquals(listOf(EmulatorStatus("fake", "com.fake.emu", "1.2.3", listOf("core1", "core2"))), s.emulators)
        assertEquals(
            mapOf("{INTERNAL}" to "$storage/emulated/0", "{SD}" to "$storage/1A2B-3C4D", "{EMU:fake}" to root),
            s.roots,
        )
    }

    @Test fun status_without_sd_and_unknown_version_sends_unknown() = runBlocking {
        File(storage, "emulated/0").mkdirs()
        engine(versionOf = { null }).fullScan()
        assertFalse(api.statuses.single().roots.containsKey("{SD}"))
        val st = api.statuses.single()
        assertEquals("unknown", st.emulators.single().version)
        // the server requires a non-null version: it must be on the wire
        val wire = ProtocolJson.encodeToString(DeviceStatusRequest.serializer(), st)
        assertTrue(wire, wire.contains("\"version\":\"unknown\""))
    }

    @Test fun duplicate_store_keys_upload_once_and_do_not_flip() = runBlocking {
        put("saves/GAME1@coreA/a.sav", "old", 5_000)
        put("saves/GAME1@coreB/a.sav", "new", 6_000)
        val e = engine()
        e.fullScan()
        e.fullScan()
        assertEquals(1, api.saves.size)
        assertEquals("coreB", api.saves[0].core)
        assertEquals(listOf("$root/saves/GAME1@coreB"),
            e.currentItems(module, Detected("fake", "com.fake.emu", "", root)).map { it.baseDir })
        assertEquals(1, logs.count { it.contains("shadowed") })
    }

    @Test fun fullScan_defers_freshly_modified_file() = runBlocking {
        put("saves/GAME1/a.sav", "hello", now - 1_000)
        val e = engine()
        e.fullScan()
        assertEquals(0, api.saves.size)
        now += 2_900; e.tick()
        assertEquals(0, api.saves.size)
        now += 200; e.tick()
        assertEquals(1, api.saves.size)
    }

    @Test fun event_during_hash_defers_upload() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        lateinit var e: SyncEngine
        var fire = false
        val fs = object : LocalFs() {
            override fun openRead(path: String): InputStream {
                if (fire) { fire = false; e.onFsEvent("$root/saves/GAME1") }
                return super.openRead(path)
            }
        }.apply { packages.add("com.fake.emu") }
        e = engine(fs)
        e.fullScan()                       // tracks the item, uploads v1
        put("saves/GAME1/a.sav", "hello2", 6_000)
        e.onFsEvent("$root/saves/GAME1")
        now += 3_000
        fire = true
        e.tick()                           // event lands while hashing -> deferred
        assertEquals(1, api.saves.size)
        now += 3_000; e.tick()
        assertEquals(2, api.saves.size)
    }

    @Test fun unchanged_files_are_not_rehashed() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        put("saves/GAME2/b.sav", "world")
        var reads = 0
        val fs = object : LocalFs() {
            override fun openRead(path: String): InputStream { reads++; return super.openRead(path) }
        }.apply { packages.add("com.fake.emu") }
        val e = engine(fs)
        e.fullScan()
        val first = reads                  // 2 hashes + 2 blob uploads
        e.fullScan()
        assertEquals(first, reads)
        put("saves/GAME2/b.sav", "world2", 6_000)
        e.fullScan()
        assertEquals(first + 2, reads)     // one rehash (reused for the upload manifest) + one blob upload
    }

    @Test fun repeated_conflict_for_same_content_notifies_once() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        api.saveStatus = SaveUploadStatus.Conflict
        val e = engine()
        e.fullScan(); e.fullScan()
        assertEquals(2, api.saves.size)
        assertEquals(1, conflicts.size)
        put("saves/GAME1/a.sav", "hello2", 6_000)
        e.fullScan()
        assertEquals(2, conflicts.size)
    }

    @Test fun installs_sent_on_scan_and_deduplicated() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        val e = engine()
        e.fullScan()
        e.fullScan()
        assertEquals(1, api.installs.size)
        assertEquals("fake" to listOf(InstalledGame("GAME1")), api.installs[0])
        put("saves/GAME2/a.sav", "x")
        e.onEmulatorExited("fake")
        assertEquals(2, api.installs.size)
    }

    @Test fun emulator_exit_uploads_config_bundle() = runBlocking {
        put("config/emu.cfg", "a=1")
        val e = engine()
        e.fullScan()
        val before = api.configs.size
        put("config/emu.cfg", "a=2", 8_000)
        e.onEmulatorExited("fake")
        assertEquals(before + 1, api.configs.size)
        val req = api.configs.last()
        assertEquals("fake", req.emulatorId)
        assertEquals(listOf("data/emu.cfg"), req.files.map { it.path })
        val cs = store.configState("fake")!!
        assertEquals(Manifests.hash(req.files), cs.knownManifestHash)
        assertEquals("bundle-fake", cs.bundleId)
        e.onEmulatorExited("fake")
        assertEquals(before + 1, api.configs.size)  // unchanged
    }

    @Test fun full_scan_skips_config_while_undetected_variant_runs() = runBlocking {
        put("config/emu.cfg", "a=1")
        val fs = fs().apply { alive.add("com.fake.emu.duo:sub") }
        engine(fs).fullScan()
        assertTrue(api.configs.isEmpty())
        fs.alive.clear()
        engine(fs).fullScan()
        assertEquals(1, api.configs.size)
    }

    @Test fun unexpected_exception_in_one_module_does_not_block_another() = runBlocking {
        val rootB = tmp.newFolder("emuB").path
        val a = FakeModule(root, "aaa").apply { crashOnItems = true }
        val b = FakeModule(rootB, "bbb")
        put("saves/GA/a.sav", "a")
        File(rootB, "saves/GB/b.sav").apply { parentFile.mkdirs(); writeText("b"); setLastModified(5_000) }
        engine(modules = listOf(a, b)).fullScan()
        assertEquals(listOf("bbb" to "GB"), api.saves.map { it.emulatorId to it.gameKey })
        assertTrue(logs.any { it.startsWith("aaa: saveItems failed") })
    }

    @Test fun fs_unavailable_in_module_still_aborts_scan() = runBlocking {
        val m = object : EmulatorModule by module {
            override fun saveItems(fs: Fs, d: Detected): List<LocalItem> = throw FsUnavailableException("shell died")
        }
        try { engine(modules = listOf(m)).fullScan(); fail("expected FsUnavailableException") } catch (e: FsUnavailableException) {}
    }

    @Test fun blob_retry_is_retried_with_backoff_within_the_upload() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        api.blobRetries = 3
        val e = engine()
        e.fullScan()
        assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
        assertEquals(1, api.putBlobs.size)
        assertEquals(1, api.saves.size)
        assertEquals(0, e.queuedCount())
    }

    @Test fun blob_retry_beyond_three_falls_back_to_next_scan() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        api.blobRetries = 4
        val e = engine()
        e.fullScan()
        assertEquals(listOf(1_000L, 2_000L, 4_000L), delays)
        assertTrue(api.saves.isEmpty())
        assertEquals(1, e.queuedCount())
        assertNull(store.get(saveKey("GAME1")))
        e.fullScan()                                  // next scan: the server takes it now
        assertEquals(1, api.saves.size)
        assertEquals(0, e.queuedCount())
    }

    @Test fun conflicts_carry_display_names() = runBlocking {
        put("saves/GAME1/a.sav", "hello"); put("config/emu.cfg", "a=1")
        val named = object : EmulatorModule by module {
            override val displayName = "Fake Emu"
            override fun installedGames(fs: Fs, d: Detected, settings: DeviceSettings) = listOf(InstalledGame("GAME1", "The Game"))
        }
        api.saveStatus = SaveUploadStatus.Conflict; api.configStatus = SaveUploadStatus.Conflict
        engine(modules = listOf(named)).fullScan()
        val item = conflicts.single { it.kind != null }
        assertEquals("The Game", item.displayName)
        assertEquals("Fake Emu", item.emulatorName)
        assertEquals("Fake Emu", conflicts.single { it.kind == null }.emulatorName)
    }

    @Test fun config_conflict_does_not_store() = runBlocking {
        put("config/emu.cfg", "a=1")
        api.configStatus = SaveUploadStatus.Conflict
        val e = engine()
        assertEquals(SaveUploadStatus.Conflict, e.uploadConfig(module, Detected("fake", "com.fake.emu", "", root)))
        assertNull(store.configState("fake"))
        assertTrue(conflicts.any { it.kind == null && it.emulatorId == "fake" })
    }

    @Test fun oversize_blob_skips_item() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        val fs = object : LocalFs() {
            // Reports just over the limit; the engine must skip on the stat, before hashing.
            override fun stat(path: String) = super.stat(path)?.let {
                if (path.endsWith("a.sav")) it.copy(size = SyncConfig.maxBlobBytes + 1) else it
            }
            override fun openRead(path: String): InputStream = throw AssertionError("must not read $path")
        }.apply { packages.add("com.fake.emu") }
        assertNull(engine(fs).upload(module, Detected("fake", "com.fake.emu", "", root),
            LocalItem(ItemKind.Save, "GAME1", "", "core1", "$root/saves/GAME1", listOf("a.sav"))))
        assertEquals(0, api.saves.size)
        assertTrue(logs.any { it.contains("too large") })
    }

    @Test fun real_eden_module_with_thor_config() = runBlocking {
        val internal = tmp.newFolder("internal").path
        val pkg = "dev.eden.eden_emulator"
        val cfg = File(internal, "Android/data/$pkg/files/config/config.ini")
        cfg.parentFile!!.mkdirs()
        File(javaClass.getResource("/fixtures/eden/thor/config.ini")!!.toURI()).copyTo(cfg)
        val title = "01002C9022770000"
        val save = File(internal, "Games/switch-saves/user/save/0000000000000000/23E468EE7BBC25842DE9EAD88AB25814/$title/data.dat")
        save.parentFile!!.mkdirs(); save.writeText("zelda"); save.setLastModified(5_000)
        val fs = LocalFs().apply { packages.add(pkg) }
        engine(fs, listOf(EdenModule(internal))).fullScan()
        val r = api.saves.single()
        assertEquals("eden", r.emulatorId)
        assertEquals(title, r.gameKey)
        assertEquals(listOf("data.dat"), r.manifest.map { it.path })
        assertNotNull(store.get(ItemKey.of("eden", "Save", title, "", "")))
        assertEquals("eden", api.installs.single().first)
        assertEquals("{EMU:eden}" to "$internal/Android/data/$pkg/files", api.statuses.single().roots.entries.first { it.key == "{EMU:eden}" }.toPair())
    }

    @Test fun engine_skips_item_and_config_with_apply_marker() = runBlocking {
        put("saves/GAME1/a.sav", "hello")
        put("config/x.cfg", "c")
        store.putApplyMarker(ApplyMarker(saveKey("GAME1"), "fake", "d1", null, "$root/saves/GAME1", emptyList()))
        store.putApplyMarker(ApplyMarker(ItemKey.config("fake"), "fake", "d2", null, root, emptyList()))
        val e = engine()
        e.fullScan()
        e.onEmulatorExited("fake")
        assertTrue(api.saves.isEmpty())
        assertTrue(api.configs.isEmpty())
        store.clearApplyMarker(saveKey("GAME1"))
        store.clearApplyMarker(ItemKey.config("fake"))
        e.fullScan()
        assertEquals(1, api.saves.size)
        assertEquals(1, api.configs.size)
    }
}
