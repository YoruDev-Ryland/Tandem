package dev.tandem.app.ui.state

import dev.tandem.app.api.Api
import dev.tandem.app.api.ApiException
import dev.tandem.app.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerRepoTest {
    private val row = GameRow("eden", "k", "Game", listOf("d1"), null, null, null, 1, false)
    private class Fake {
        var down = false; var gameCalls = 0; var conflictCalls = 0
        var games = listOf<GameRow>()
        var groups = ConfigOverview(emptyList())
    }

    private val unused: Api = Proxy.newProxyInstance(Api::class.java.classLoader, arrayOf(Api::class.java)) { _, m, _ ->
        error("unexpected " + m.name)
    } as Api

    private fun api(f: Fake, fail: () -> Exception = { IOException("down") }): Api = object : Api by unused {
        override suspend fun games(emulator: String?): List<GameRow> { if (f.down) throw fail(); f.gameCalls++; return f.games }
        override suspend fun conflicts(): List<ConflictRow> { if (f.down) throw fail(); f.conflictCalls++; return emptyList() }
        override suspend fun configs(): ConfigOverview { if (f.down) throw fail(); return f.groups }
        override suspend fun me() = if (f.down) throw fail() else DeviceSummary("d1", "Deck", "android", null, null, true)
    }

    private var now = Instant.parse("2026-01-01T00:00:00Z")
    private fun TestScope.repo(f: Fake, running: MutableStateFlow<Set<String>> = MutableStateFlow(emptySet()),
                               nudges: MutableStateFlow<Long> = MutableStateFlow(0)) =
        ServerRepo({ api(f) }, backgroundScope, nudges, running, { "d1" }, { now })

    @Test fun offline_keeps_last_value_and_updatedAt() = runTest {
        val f = Fake().apply { games = listOf(row) }
        val r = repo(f); r.start(); runCurrent()
        val first = r.games.value
        assertEquals(listOf(row), first.value); assertFalse(first.offline)
        f.down = true; now = now.plusSeconds(100)
        r.refresh(); runCurrent()
        val g = r.games.value
        assertEquals(listOf(row), g.value)
        assertTrue(g.offline)
        assertEquals(first.updatedAt, g.updatedAt)
        assertNotNull(g.error)
        f.down = false
        r.refresh(); runCurrent()
        assertFalse(r.games.value.offline); assertNull(r.games.value.error)
    }

    @Test fun an_emulator_leaving_running_refreshes_once() = runTest {
        val f = Fake().apply { games = listOf(row) }
        val running = MutableStateFlow(setOf("eden"))
        val r = repo(f, running); r.start(); runCurrent()
        val base = f.gameCalls
        running.value = emptySet(); runCurrent()
        assertEquals(base + 1, f.gameCalls)
        assertEquals(1, f.conflictCalls - 1)
    }

    @Test fun delivery_nudge_refreshes() = runTest {
        val f = Fake(); val n = MutableStateFlow(0L)
        val r = repo(f, nudges = n); r.start(); runCurrent()
        val base = f.gameCalls
        n.value = 1; runCurrent()
        assertEquals(base + 1, f.gameCalls)
    }

    @Test fun timer_runs_only_while_visible() = runTest {
        val f = Fake(); val r = repo(f); r.start(); runCurrent()
        val base = f.gameCalls
        advanceTimeBy(130_000); runCurrent()
        assertEquals(base, f.gameCalls)
        r.setVisible(true)
        advanceTimeBy(121_000); runCurrent()
        assertEquals(base + 2, f.gameCalls)
        r.setVisible(false)
        advanceTimeBy(200_000); runCurrent()
        assertEquals(base + 2, f.gameCalls)
    }

    @Test fun config_group_for_this_device() = runTest {
        val f = Fake().apply {
            groups = ConfigOverview(listOf(EmulatorConfigRow("dolphin",
                listOf(GroupRow("g1", "Handhelds", listOf(DeviceRef("d1", "Deck")), null, false)), emptyList())))
        }
        val r = repo(f); r.start(); runCurrent()
        assertEquals(mapOf("dolphin" to "Handhelds"), r.configGroups.value)
        assertEquals("Deck", r.deviceName.value)
    }

    @Test fun http_error_is_not_offline() = runTest {
        val f = Fake().apply { down = true }
        val r = ServerRepo({ api(f) { ApiException(500, null, "boom") } }, backgroundScope)
        r.start(); runCurrent()
        assertFalse(r.games.value.offline); assertEquals(500, r.games.value.error?.status)
        assertNull(r.games.value.value)
    }

    @Test fun serialization_failure_is_an_error_not_offline() = runTest {
        val f = Fake().apply { down = true }
        val r = ServerRepo({ api(f) { kotlinx.serialization.SerializationException("bad json") } }, backgroundScope)
        r.start(); runCurrent()
        assertFalse(r.games.value.offline)
        assertNotNull(r.games.value.error)
        assertFalse(r.games.value.error is OfflineError)
    }

    @Test fun io_failures_are_offline_with_marker() = runTest {
        for (e in listOf<() -> Exception>({ java.net.SocketTimeoutException("t") }, { java.net.ConnectException("c") }, { java.net.UnknownHostException("u") })) {
            val f = Fake().apply { down = true }
            val r = ServerRepo({ api(f, e) }, backgroundScope)
            r.start(); runCurrent()
            assertTrue(r.games.value.offline)
            assertTrue(r.games.value.error is OfflineError)
        }
    }

    @Test fun signing_out_clears_everything() = runTest {
        val f = Fake().apply { games = listOf(row)
            groups = ConfigOverview(listOf(EmulatorConfigRow("dolphin",
                listOf(GroupRow("g1", "H", listOf(DeviceRef("d1", "Deck")), null, false)), emptyList()))) }
        var signedIn = true
        val r = ServerRepo({ if (signedIn) api(f) else null }, backgroundScope, deviceId = { "d1" })
        r.start(); runCurrent()
        assertEquals(listOf(row), r.games.value.value)
        signedIn = false
        r.refresh(); runCurrent()
        assertEquals(Loaded<List<GameRow>>(), r.games.value)
        assertEquals(Loaded<List<ConflictRow>>(), r.conflicts.value)
        assertTrue(r.configGroups.value.isEmpty()); assertNull(r.deviceName.value)
    }

    @Test fun burst_of_nudges_coalesces_to_one_running_plus_one_pending() = runTest {
        val f = Fake(); val n = MutableStateFlow(0L)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : Api by unused {
            override suspend fun games(emulator: String?): List<GameRow> { f.gameCalls++; gate.await(); return emptyList() }
            override suspend fun conflicts(): List<ConflictRow> = emptyList()
            override suspend fun configs() = ConfigOverview(emptyList())
            override suspend fun me() = DeviceSummary("d1", "Deck", "android", null, null, true)
        }
        val r = ServerRepo({ slow }, backgroundScope, n); r.start(); runCurrent()
        for (i in 1..10) { n.value = i.toLong(); runCurrent() }
        gate.complete(Unit); runCurrent()
        assertEquals(2, f.gameCalls)   // the first (blocked) one plus a single pending
    }

    @Test fun offline_failure_short_circuits_remaining_calls() = runTest {
        val f = Fake().apply { down = true }
        var other = 0
        val a = object : Api by unused {
            override suspend fun games(emulator: String?): List<GameRow> = throw IOException("down")
            override suspend fun conflicts(): List<ConflictRow> { other++; return emptyList() }
            override suspend fun configs(): ConfigOverview { other++; return ConfigOverview(emptyList()) }
            override suspend fun me(): DeviceSummary { other++; throw IOException() }
        }
        val r = ServerRepo({ a }, backgroundScope); r.start(); runCurrent()
        assertEquals(0, other)
        assertTrue(r.conflicts.value.offline)
    }
}
