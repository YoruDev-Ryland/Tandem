package dev.tandem.app.ui.state

import dev.tandem.app.fs.ShizukuGate
import dev.tandem.app.fs.ShizukuState
import dev.tandem.app.model.GameRow
import dev.tandem.app.modules.Detected
import java.time.Instant
import dev.tandem.app.service.AppGraph
import dev.tandem.app.service.EmulatorInfo
import dev.tandem.app.service.ServiceSnapshot
import dev.tandem.app.sync.StateStore
import dev.tandem.app.sync.WaitingDelivery
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

    private class FakeServer(
        override val deviceName: MutableStateFlow<String?> = MutableStateFlow(null),
        override val configGroups: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap()),
        override val games: MutableStateFlow<Loaded<List<GameRow>>> = MutableStateFlow(Loaded()),
    ) : ServerView

    private fun TestScope.state(store: StateStore, server: FakeServer = FakeServer()) =
        AppState(AppGraph, store, shizuku, backgroundScope + UnconfinedTestDispatcher(testScheduler), server)

    private fun store() = StateStore(tmp.newFile("s.json").also { it.delete() })

    @Test fun top_is_signed_out_without_a_device_key() = runTest {
        val s = state(store())
        assertEquals(ServerConn.SignedOut, s.top.value.server)
        assertEquals(false, s.top.value.signedIn)
    }

    @Test fun top_follows_store_snapshot_and_name() = runTest {
        val st = store(); val srv = FakeServer(deviceName = MutableStateFlow("Deck"))
        val s = state(st, srv)
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
        val s = state(store(), FakeServer(configGroups = groups))
        fun det(id: String) = Detected(id, "pkg", "1", "/d")
        AppGraph.snapshot.value = ServiceSnapshot(live = true, emulators = listOf(
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

    @Test fun before_the_first_scan_rows_are_looking_not_not_set_up() = runTest {
        val s = state(store())
        AppGraph.snapshot.value = ServiceSnapshot(running = true, live = false)
        AppGraph.sessionFlows.publishRunning(token, setOf("eden"))
        val rows = s.emulators.value.associateBy { it.id }
        assertEquals(EmuRowState.Looking, rows.getValue("azahar").state)
        assertEquals(EmuRowState.Looking, rows.getValue("dolphin").state)
        assertEquals(EmuRowState.Open(0), rows.getValue("eden").state)   // what the service already knows still shows
        AppGraph.sessionFlows.publishRunning(token, emptySet())
    }

    @Test fun waiting_row_disappears_when_delivery_clears() = runTest {
        val s = state(store())
        AppGraph.snapshot.value = ServiceSnapshot(live = true, emulators = listOf(EmulatorInfo(Detected("eden", "p", "1", "/d"), 1)))
        AppGraph.sessionFlows.publishWaiting(token, listOf(WaitingDelivery("1", "eden", "Eden", "g", "G", "P")))
        AppGraph.sessionFlows.publishRunning(token, setOf("eden"))
        assertEquals(EmuRowState.Open(1), s.emulators.value.first { it.id == "eden" }.state)
        assertEquals(setOf("eden"), s.running.value)
        AppGraph.sessionFlows.publishWaiting(token, emptyList())
        AppGraph.sessionFlows.publishRunning(token, emptySet())
        assertEquals(EmuRowState.Saves(1), s.emulators.value.first { it.id == "eden" }.state)
        assertEquals(emptyList<WaitingDelivery>(), s.waiting.value)
    }

    @Test fun running_emulator_with_nothing_waiting_is_open_zero() = runTest {
        val s = state(store())
        AppGraph.snapshot.value = ServiceSnapshot(live = true, emulators = listOf(EmulatorInfo(Detected("eden", "p", "1", "/d"), 4)))
        assertEquals(EmuRowState.Saves(4), s.emulators.value.first { it.id == "eden" }.state)
        AppGraph.sessionFlows.publishRunning(token, setOf("eden"))
        assertEquals(EmuRowState.Open(0), s.emulators.value.first { it.id == "eden" }.state)
        AppGraph.sessionFlows.publishRunning(token, emptySet())
        assertEquals(EmuRowState.Saves(4), s.emulators.value.first { it.id == "eden" }.state)
    }

    @Test fun connection_combines_sse_and_repo() = runTest {
        val st = store(); val srv = FakeServer()
        val s = state(st, srv)
        assertEquals(ServerConn.SignedOut, s.top.value.server)
        st.deviceKey = "k"
        assertEquals(ServerConn.Offline, s.top.value.server)
        val t = Instant.parse("2026-01-01T00:00:00Z")
        // repo refresh succeeded while SSE is down: still connected
        srv.games.value = Loaded(emptyList(), t)
        assertEquals(ServerConn.Connected, s.top.value.server)
        // repo reports offline: offline, with its last good time
        srv.games.value = Loaded(emptyList(), t, dev.tandem.app.ui.state.OfflineError("x"), true)
        assertEquals(ServerConn.Offline, s.top.value.server)
        assertEquals(t, s.top.value.lastSync)
        // SSE up, repo has not failed: connected
        srv.games.value = Loaded()
        AppGraph.snapshot.value = ServiceSnapshot(running = true, online = true)
        assertEquals(ServerConn.Connected, s.top.value.server)
    }
}
