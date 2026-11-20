package dev.emusync.app.ui.state

import dev.emusync.app.fs.ShizukuGate
import dev.emusync.app.fs.ShizukuState
import dev.emusync.app.modules.Detected
import dev.emusync.app.service.AppGraph
import dev.emusync.app.service.EmulatorInfo
import dev.emusync.app.service.ServiceSnapshot
import dev.emusync.app.sync.StateStore
import dev.emusync.app.sync.WaitingDelivery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.plus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AppStateTest {
    @get:Rule val tmp = TemporaryFolder()
    private val shizuku = MutableStateFlow(ShizukuState.Ready)
    private val token = AppGraph.sessionFlows.begin()

    @After fun reset() {
        AppGraph.sessionFlows.end(null)
        AppGraph.snapshot.value = ServiceSnapshot()
    }

    private fun TestScope.state(store: StateStore, names: MutableStateFlow<String?> = MutableStateFlow(null),
                                groups: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap())) =
        AppState(AppGraph, store, shizuku, backgroundScope + UnconfinedTestDispatcher(testScheduler), names, groups)

    private fun store() = StateStore(tmp.newFile("s.json").also { it.delete() })

    @Test fun top_is_signed_out_without_a_device_key() = runTest {
        val s = state(store())
        assertEquals(ServerConn.SignedOut, s.top.value.server)
        assertEquals(false, s.top.value.signedIn)
    }

    @Test fun top_follows_store_snapshot_and_name() = runTest {
        val st = store(); val names = MutableStateFlow<String?>("Deck")
        val s = state(st, names)
        st.deviceKey = "k"
        assertEquals(ServerConn.Offline, s.top.value.server)
        AppGraph.snapshot.value = ServiceSnapshot(running = true, online = true)
        assertEquals(ServerConn.Connected, s.top.value.server)
        assertEquals("Deck", s.top.value.deviceName)
        assertEquals(true, s.top.value.signedIn)
    }

    @Test fun shizuku_not_running_is_reflected() = runTest {
        val s = state(store())
        shizuku.value = ShizukuState.NotRunning
        assertEquals(ShizukuState.NotRunning, s.top.value.shizuku)
    }

    @Test fun emulator_rows_cover_all_modules_and_states() = runTest {
        val groups = MutableStateFlow(emptyMap<String, String>())
        val s = state(store(), groups = groups)
        fun det(id: String) = Detected(id, "pkg", "1", "/d")
        AppGraph.snapshot.value = ServiceSnapshot(emulators = listOf(
            EmulatorInfo(det("eden"), 3), EmulatorInfo(det("dolphin"), 2), EmulatorInfo(det("retroarch"), 0),
            EmulatorInfo(det("melonds"), 5)))
        groups.value = mapOf("dolphin" to "Handhelds")
        AppGraph.sessionFlows.publishWaiting(token, listOf(
            WaitingDelivery("1", "melonds", "melonDS", "g", "Game", "Phone")))
        val rows = s.emulators.value.associateBy { it.id }
        assertEquals(7, s.emulators.value.size)
        assertEquals("eden", s.emulators.value.first().id)
        assertEquals(EmuRowState.Saves(3), rows.getValue("eden").state)
        assertEquals(EmuRowState.ConfigGroup("Handhelds"), rows.getValue("dolphin").state)
        assertEquals(EmuRowState.Ok, rows.getValue("retroarch").state)
        assertEquals(EmuRowState.Open(1), rows.getValue("melonds").state)
        assertEquals(5, rows.getValue("melonds").count)
        assertEquals(EmuRowState.NotSetUp, rows.getValue("azahar").state)
    }

    @Test fun waiting_row_disappears_when_delivery_clears() = runTest {
        val s = state(store())
        AppGraph.snapshot.value = ServiceSnapshot(emulators = listOf(EmulatorInfo(Detected("eden", "p", "1", "/d"), 1)))
        AppGraph.sessionFlows.publishWaiting(token, listOf(WaitingDelivery("1", "eden", "Eden", "g", "G", "P")))
        AppGraph.sessionFlows.publishRunning(token, setOf("eden"))
        assertEquals(EmuRowState.Open(1), s.emulators.value.first { it.id == "eden" }.state)
        assertEquals(setOf("eden"), s.running.value)
        AppGraph.sessionFlows.publishWaiting(token, emptyList())
        AppGraph.sessionFlows.publishRunning(token, emptySet())
        assertEquals(EmuRowState.Saves(1), s.emulators.value.first { it.id == "eden" }.state)
        assertEquals(emptyList<WaitingDelivery>(), s.waiting.value)
    }
}
