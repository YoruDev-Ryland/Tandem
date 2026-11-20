package dev.emusync.app.sync

import dev.emusync.app.api.ApiClient
import dev.emusync.app.fs.Fs
import dev.emusync.app.fs.LocalFs
import dev.emusync.app.model.*
import dev.emusync.app.modules.*
import java.io.File
import dev.emusync.app.api.Api
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The status report (PUT /v1/devices/me/status) carries the running emulators and is resent when they change. */
class StatusReportTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    @Volatile private var statusCode = 204

    private class Emu(override val emulatorId: String, private val pkg: String, private val root: String) : EmulatorModule {
        override val packages = listOf(pkg)
        override fun detect(fs: Fs, settings: DeviceSettings): Detected? =
            if (pkg in fs.installedPackages()) Detected(emulatorId, pkg, "", "$root/$emulatorId") else null
        override fun saveItems(fs: Fs, d: Detected) = emptyList<LocalItem>()
        override fun watchDirs(fs: Fs, d: Detected) = emptyList<String>()
        override fun installedGames(fs: Fs, d: Detected, settings: DeviceSettings) = emptyList<InstalledGame>()
        override fun configBundle(fs: Fs, d: Detected) = ConfigBundleFiles(emptyMap())
        override fun targetDirFor(fs: Fs, d: Detected, kind: ItemKind, gameKey: String, slot: String, core: String) =
            error("unused")
    }

    private var now = 1_000_000_000L
    private val fs = LocalFs().apply { packages.addAll(listOf("dev.eden", "org.ppsspp")) }

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.url.encodedPath == "/v1/devices/me/status") MockResponse.Builder().code(statusCode).build()
                else MockResponse.Builder().code(200).body("{}").build()
        }
        server.start()
    }

    @After fun tearDown() { server.close() }

    private val client by lazy { ApiClient(server.url("/").toString().trimEnd('/'), "k") }
    private val coreCalls = AtomicInteger()

    private val logs = java.util.concurrent.LinkedBlockingQueue<String>()

    private fun engine(api: Api = client, statusTimeoutMillis: Long = 10_000L): SyncEngine {
        val root = tmp.newFolder().path
        val counting = object : EmulatorModule by Emu("eden", "dev.eden", root) {
            override fun cores(fs: Fs, d: Detected): List<String>? { coreCalls.incrementAndGet(); return listOf("c") }
        }
        val registry = ModuleRegistry(listOf(counting, Emu("ppsspp", "org.ppsspp", root)))
        return SyncEngine(fs, api, StateStore(File(tmp.root, "s.json")),
            registry, DeviceSettings(emptyList(), emptyMap()), clock = { now }, log = { logs += it }, versionOf = { "1.0" },
            appVersion = "0", shizukuOk = { true }, onConflict = {}, storageRoot = tmp.newFolder().path,
            statusTimeoutMillis = statusTimeoutMillis)
    }

    /** Bodies of the status reports received since the last call. */
    private fun statusBodies(): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val r = server.takeRequest(50, TimeUnit.MILLISECONDS) ?: return out
            if (r.url.encodedPath == "/v1/devices/me/status") out += r.body!!.utf8()
        }
    }

    @Test fun status_report_contains_running_emulators() = runBlocking {
        val e = engine()
        e.fullScan()
        val initial = statusBodies().single()
        assertTrue(initial, initial.contains("\"runningEmulators\":[]"))
        e.reportRunning(setOf("eden"))
        val body = statusBodies().single()
        assertTrue(body, body.contains("\"runningEmulators\":[\"eden\"]"))
        assertTrue(body, body.contains("\"emulatorId\":\"eden\""))
    }

    @Test fun reports_only_on_change_while_nothing_runs() = runBlocking {
        val e = engine()
        e.fullScan(); statusBodies()
        e.reportRunning(emptySet())
        now += 10 * 60_000L
        e.reportRunning(emptySet())
        assertTrue(statusBodies().isEmpty())
    }

    @Test fun resends_every_90_seconds_while_running_and_once_on_exit() = runBlocking {
        val e = engine()
        e.fullScan(); statusBodies()
        e.reportRunning(setOf("eden"))
        assertEquals(1, statusBodies().size)
        now += 89_000L
        e.reportRunning(setOf("eden"))
        assertTrue("not due yet", statusBodies().isEmpty())
        now += 1_000L
        e.reportRunning(setOf("eden"))
        assertEquals(1, statusBodies().size)
        e.reportRunning(setOf("eden", "ppsspp"))
        val both = statusBodies().single()
        assertTrue(both, both.contains("\"runningEmulators\":[\"eden\",\"ppsspp\"]"))
        e.reportRunning(emptySet())
        val off = statusBodies().single()
        assertTrue(off, off.contains("\"runningEmulators\":[]"))
        now += 10 * 60_000L
        e.reportRunning(emptySet())
        assertTrue(statusBodies().isEmpty())
    }

    @Test fun a_failed_report_is_retried_after_90_seconds_not_every_tick() = runBlocking {
        val e = engine()
        e.fullScan(); statusBodies()
        statusCode = 503
        e.reportRunning(setOf("eden"))
        assertEquals(1, statusBodies().size)
        statusCode = 204
        now += 5_000L
        e.reportRunning(setOf("eden"))
        assertTrue(statusBodies().isEmpty())
        now += 85_000L
        e.reportRunning(setOf("eden"))
        assertEquals(1, statusBodies().size)
    }

    @Test fun running_ids_of_undetected_emulators_are_not_reported() = runBlocking {
        val e = engine()
        e.fullScan(); statusBodies()
        e.reportRunning(setOf("eden", "dolphin"))
        val body = statusBodies().single()
        assertTrue(body, body.contains("\"runningEmulators\":[\"eden\"]"))
        assertFalse(body, body.contains("dolphin"))
    }

    @Test fun a_clock_step_backwards_does_not_stall_the_refresh() = runBlocking {
        val e = engine()
        e.fullScan(); statusBodies()
        e.reportRunning(setOf("eden"))
        statusBodies()
        now -= 60 * 60_000L
        e.reportRunning(setOf("eden"))
        assertEquals(1, statusBodies().size)
    }

    @Test fun running_report_reuses_the_last_full_scan_status() = runBlocking {
        val e = engine()
        e.fullScan()
        val scanned = statusBodies().single()
        assertEquals(1, coreCalls.get())
        e.reportRunning(setOf("eden"))
        val body = statusBodies().single()
        assertEquals("no module or storage calls for a running report", 1, coreCalls.get())
        assertEquals(scanned.replace("\"runningEmulators\":[]", "\"runningEmulators\":[\"eden\"]"), body)
    }

    @Test(timeout = 10_000L) fun a_hung_server_does_not_hold_the_engine_or_pile_up_reports() = runBlocking {
        val release = CountDownLatch(1)
        val puts = AtomicInteger()
        val hung = object : Api by client {
            override suspend fun putStatus(req: DeviceStatusRequest) {
                if (req.runningEmulators.orEmpty().isEmpty()) return client.putStatus(req)   // the full scan's report
                puts.incrementAndGet()
                release.await()   // blocking, non-cancellable
            }
            override suspend fun putStatus(req: DeviceStatusRequest, callTimeoutMillis: Long) = putStatus(req)
        }
        val e = engine(hung, statusTimeoutMillis = 0L)   // gives up at once instead of waiting 10 s
        e.fullScan(); statusBodies()
        withTimeout(5_000) { e.reportRunning(setOf("eden")) }   // returns although the PUT hangs
        withTimeout(5_000) { e.onEmulatorExited("eden"); e.tick() }   // the engine lock is free
        now += 90_000L
        e.reportRunning(setOf("eden"))   // due, but the previous report is still in flight: not resent
        assertEquals(1, puts.get())
        release.countDown()
    }

    @Test fun a_running_report_the_server_answers_too_late_is_cancelled() = runBlocking {
        val e = engine(statusTimeoutMillis = 300L)
        e.fullScan(); statusBodies()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse.Builder().code(204).headersDelay(3, TimeUnit.SECONDS).build()
        }
        e.reportRunning(setOf("eden"))
        // The call itself times out (an io error), rather than staying open and landing after a newer report.
        var line: String?
        do { line = logs.poll(2, TimeUnit.SECONDS) } while (line != null && !line.startsWith("status: io error"))
        assertTrue("PUT failed by its call timeout", line != null)
    }
}
