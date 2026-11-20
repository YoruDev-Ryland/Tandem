package dev.emusync.app.service

import dev.emusync.app.fs.Fs
import dev.emusync.app.fs.LocalFs
import dev.emusync.app.model.*
import dev.emusync.app.modules.*
import dev.emusync.app.sync.DeliveryApplier
import dev.emusync.app.sync.DeliveryApplierTest
import dev.emusync.app.sync.ItemState
import dev.emusync.app.sync.LocalBackups
import dev.emusync.app.sync.StateStore
import dev.emusync.app.sync.SyncEngine
import dev.emusync.app.sync.WaitingDelivery
import java.io.File
import java.security.MessageDigest
import dev.emusync.app.api.Api
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Deliveries held back because their emulator runs are published for the UI, and cleared once applied. */
class WaitingDeliveriesTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A file-backed "Eden": each dir under <root>/saves is one save, titled "Title <dir>". */
    private class EdenLike(private val root: String) : EmulatorModule {
        override val emulatorId = "eden"
        override val displayName = "Eden"
        override val packages = listOf("dev.eden.eden_emulator")
        override fun detect(fs: Fs, settings: DeviceSettings): Detected? =
            if (packages[0] in fs.installedPackages()) Detected(emulatorId, packages[0], "", "$root/data") else null
        override fun saveItems(fs: Fs, d: Detected): List<LocalItem> =
            fs.list("$root/saves").filter { it.isDir }.map { e ->
                val key = e.path.substringAfterLast('/')
                LocalItem(ItemKind.Save, key, "", "", e.path, fs.walk(e.path).map { it.path.removePrefix(e.path + "/") },
                    "Title $key")
            }
        override fun watchDirs(fs: Fs, d: Detected) = listOf("$root/saves")
        override fun installedGames(fs: Fs, d: Detected, settings: DeviceSettings) = emptyList<InstalledGame>()
        override fun configBundle(fs: Fs, d: Detected) = ConfigBundleFiles(emptyMap())
        override fun targetDirFor(fs: Fs, d: Detected, kind: ItemKind, gameKey: String, slot: String, core: String) =
            "$root/saves/$gameKey"
    }

    private val api = DeliveryApplierTest.FakeApi()
    private val root by lazy { tmp.newFolder("emu").path }
    private val store by lazy { StateStore(File(tmp.root, "state.json")) }
    private val none = DeviceSettings(emptyList(), emptyMap())
    private val fs by lazy { LocalFs().apply { packages.add("dev.eden.eden_emulator") } }
    private val waiting = MutableStateFlow<List<WaitingDelivery>>(emptyList())
    private val notes = mutableListOf<String>()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun put(rel: String, text: String) {
        val f = File(root, rel); f.parentFile!!.mkdirs(); f.writeText(text); f.setLastModified(5_000)
    }

    private fun delivery(id: String, game: String, text: String): DeliveryDto {
        val b = text.toByteArray(); api.blobs[sha(b)] = b
        return DeliveryDto(id, DeliveryKind.Save, "item-$game", "v-$id", null, "eden", game, ItemKind.Save, "", "",
            listOf(ManifestEntry("a.sav", sha(b), b.size.toLong())))
    }

    private suspend fun setUp(
        deliveriesApi: Api = api,
        onWaiting: (List<WaitingDelivery>) -> Unit = { waiting.value = it },
    ): Pair<SyncEngine, DeliveryApplier> {
        put("saves/0100A/a.sav", "old")
        val dir = "$root/saves/0100A"
        val h = Manifests.hash(Manifests.build(LocalFs(), dir, listOf("a.sav")))
        store.put(ItemKey.of("eden", "Save", "0100A", "", ""), ItemState("old", h, h))
        val registry = ModuleRegistry(listOf(EdenLike(root)))
        val engine = SyncEngine(fs, api, store, registry, none, clock = { 1_000_000_000L }, log = {},
            versionOf = { null }, appVersion = "0", shizukuOk = { true }, onConflict = {},
            storageRoot = tmp.newFolder().path)
        engine.fullScan()   // learns the game titles
        val applier = DeliveryApplier(fs, deliveriesApi, store, registry, engine, notifier = { notes += it }, settings = none,
            cacheDir = tmp.newFolder("cache"), backups = LocalBackups(tmp.newFolder("backups").path, clock = { 0L }),
            vaultRoot = File(tmp.root, "vault").path, onWaiting = onWaiting)
        return engine to applier
    }

    @Test fun apply_pass_with_running_eden_publishes_one_waiting_delivery() = runBlocking {
        val (_, applier) = setUp()
        api.standing = listOf(delivery("d1", "0100A", "new"))
        fs.alive.add("dev.eden.eden_emulator")
        val r = applier.applyAll()
        assertEquals(1, r.waitingForEmulator)
        assertEquals(listOf(WaitingDelivery("d1", "eden", "Eden", "0100A", "Title 0100A", null)), waiting.value)
        assertEquals("existing notification unchanged",
            listOf("Close Eden to receive the newer save for Title 0100A"), notes)
    }

    @Test fun waiting_is_cleared_when_exit_applies_it() = runBlocking {
        val (_, applier) = setUp()
        api.standing = listOf(delivery("d1", "0100A", "new"))
        fs.alive.add("dev.eden.eden_emulator")
        applier.applyAll()
        assertEquals(1, waiting.value.size)
        fs.alive.clear()
        val r = applier.onEmulatorExited("eden")
        assertEquals(1, r.applied)
        assertTrue(waiting.value.isEmpty())
        assertEquals("new", File(root, "saves/0100A/a.sav").readText())
    }

    @Test fun repeated_passes_do_not_duplicate_and_an_applied_delivery_drops_out() = runBlocking {
        val (_, applier) = setUp()
        api.standing = listOf(delivery("d1", "0100A", "new"))
        fs.alive.add("dev.eden.eden_emulator")
        applier.applyAll(); applier.applyAll()
        assertEquals(listOf("d1"), waiting.value.map { it.deliveryId })
        fs.alive.clear()
        applier.applyAll()   // an explicit apply after the exit also clears it
        assertTrue(waiting.value.isEmpty())
    }

    @Test fun forgetting_an_exited_emulator_clears_its_entries_without_applying() = runBlocking {
        val (_, applier) = setUp()
        api.standing = listOf(delivery("d1", "0100A", "new"))
        fs.alive.add("dev.eden.eden_emulator")
        applier.applyAll()
        fs.alive.clear()
        applier.forgetWaiting("eden")   // auto-apply off: the exit doesn't apply, but nothing waits for Eden now
        assertTrue(waiting.value.isEmpty())
        assertEquals("old", File(root, "saves/0100A/a.sav").readText())
        assertTrue(api.acks.isEmpty())
    }

    @Test fun retaining_detected_emulators_drops_entries_of_undetected_ones() = runBlocking {
        val (_, applier) = setUp()
        api.standing = listOf(delivery("d1", "0100A", "new"))
        fs.alive.add("dev.eden.eden_emulator")
        applier.applyAll()
        applier.retainWaiting(setOf("eden"))
        assertEquals(1, waiting.value.size)
        applier.retainWaiting(emptySet())   // Eden uninstalled or no longer detected
        assertTrue(waiting.value.isEmpty())
        assertTrue(api.acks.isEmpty())
    }

    @Test fun a_pass_returning_after_its_session_ended_does_not_republish() = runBlocking {
        val flows = SessionFlows()
        val token = flows.begin()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocking = object : Api by api {
            override suspend fun deliveries(): List<DeliveryDto> {
                entered.countDown()
                release.await()   // a blocking, non-cancellable network call
                return api.deliveries()
            }
        }
        val (_, applier) = setUp(blocking) { flows.publishWaiting(token, it) }
        api.standing = listOf(delivery("d1", "0100A", "new"))
        fs.alive.add("dev.eden.eden_emulator")
        val pass = async(Dispatchers.IO) { applier.applyAll() }
        entered.await()
        flows.end(token)   // the session ends while the pass is stuck in the call
        release.countDown()
        assertEquals(1, pass.await().waitingForEmulator)
        assertTrue(flows.waiting.value.isEmpty())
    }
}
