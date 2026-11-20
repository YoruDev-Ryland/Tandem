package dev.emusync.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.emusync.app.BuildConfig
import dev.emusync.app.api.Api
import dev.emusync.app.api.ApiClient
import dev.emusync.app.api.EventStream
import dev.emusync.app.api.defaultHttpClient
import dev.emusync.app.fs.FsUnavailableException
import dev.emusync.app.fs.RemoteFs
import dev.emusync.app.fs.ShizukuState
import dev.emusync.app.model.DeviceStatusRequest
import dev.emusync.app.modules.DeviceSettings
import dev.emusync.app.modules.ModuleRegistry
import dev.emusync.app.modules.isEmulatorRunning
import dev.emusync.app.sync.DeliveryApplier
import dev.emusync.app.sync.StateStore
import dev.emusync.app.sync.SyncConfig
import dev.emusync.app.sync.SyncEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The specialUse foreground service that runs the sync while Shizuku is ready.
 *
 * Structure: one [CoroutineScope] on [Dispatchers.IO]. A supervisor coroutine follows the Shizuku state and a
 * reload counter; each `Ready` (or reload) starts a fresh *session* and cancels the previous one. A session binds the
 * shell, builds [RemoteFs], the [SyncEngine] and [DeliveryApplier], runs a full scan, starts watching, and then feeds
 * a single [WorkQueue] from a 1 s ticker, a 5 s process monitor, the server event stream, a 60 s delivery poll and a
 * 15 min full scan. The queue's one consumer runs the work, so scans, ticks, applies and exit handling never overlap.
 *
 * Errors never crash the service: each work item is caught and logged. [FsUnavailableException] (the shell died or
 * Shizuku went away) ends the session; it restarts after a short delay while Shizuku stays ready.
 */
class SyncService : Service() {
    companion object {
        const val ACTION_SCAN = "dev.emusync.app.action.SCAN"
        const val ACTION_APPLY = "dev.emusync.app.action.APPLY"
        /** Settings, server URL or device key changed: rebuild the engine and applier. */
        const val ACTION_RELOAD = "dev.emusync.app.action.RELOAD"

        private const val TICK_MS = 1_000L
        private const val MONITOR_MS = 5_000L
        private const val FULL_SCAN_MS = 15 * 60_000L
        private const val RESTART_DELAY_MS = 5_000L
        private const val BIND_TIMEOUT_MS = 30_000L

        /**
         * Runs [start], logging instead of throwing when Android refuses it: from Android 12 a foreground-service start
         * while the app is in the background throws ForegroundServiceStartNotAllowedException (an
         * IllegalStateException). Returns whether it started.
         */
        fun guardedStart(start: () -> Unit): Boolean = try {
            start()
            true
        } catch (e: IllegalStateException) {
            LogBuffer.add("sync service start refused: ${e.message}")
            false
        }

        /** [start] for callers that may run in the background (a request finishing after the app left the screen). */
        fun tryStart(context: Context, action: String? = null): Boolean = guardedStart { start(context, action) }

        /** Stops sync (sign-out). Nothing restarts it until a sign-in: the launcher and boot start it only with a key. */
        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }

        fun start(context: Context, action: String? = null) {
            context.startForegroundService(Intent(context, SyncService::class.java).setAction(action))
        }
    }

    /** The objects of one Shizuku-ready session. */
    private class Session(
        val fs: RemoteFs,
        val engine: SyncEngine,
        val applier: DeliveryApplier,
        val queue: WorkQueue<SyncWork>,
        val planner: WorkPlanner,
        val flows: SessionFlows.Token,
        val exits: ExitDetector = ExitDetector(),
        var watched: List<String> = emptyList(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reloads = MutableStateFlow(0)
    @Volatile private var session: Session? = null
    @Volatile private var online: Boolean? = null   // server event stream; null until the first connect attempt
    /** The last status the server accepted, with the server and key it was sent with. */
    private class SentStatus(val url: String, val key: String, val req: DeviceStatusRequest)
    @Volatile private var lastStatus: SentStatus? = null
    @Volatile private var destroyed = false

    private lateinit var notifier: Notifier
    private lateinit var store: StateStore
    private val gate by lazy { AppGraph.gate(this) }
    private val registry = ModuleRegistry.default()
    private val log: (String) -> Unit = LogBuffer::add

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier = Notifier(this).also { it.ensureChannels() }
        store = AppGraph.store(this)
        gate   // starts the Shizuku listeners on the main thread
        goForeground()
        log("service started (app ${BuildConfig.VERSION_NAME})")
        AppGraph.sessions.start(scope) { supervise() }   // joins a previous instance's supervisor first
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()   // required after every startForegroundService
        when (intent?.action) {
            ACTION_SCAN -> session?.let { s -> s.planner.explicitScan().forEach { s.queue.offer(it) } }
                ?: log("scan requested, but sync isn't running")
            ACTION_APPLY -> session?.let { s -> s.planner.explicitApply().forEach { s.queue.offer(it) } }
                ?: log("apply requested, but sync isn't running")
            ACTION_RELOAD -> { log("settings changed, restarting sync"); reloads.value++ }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        log("service stopped")
        destroyed = true
        scope.cancel()
        AppGraph.snapshot.value = ServiceSnapshot(running = false, status = "Not running", scans = AppGraph.fullScans.get(), failedScans = AppGraph.failedScans.get())
        AppGraph.sessionFlows.end(null)   // drops the live session's late writes too
        super.onDestroy()
    }

    private fun goForeground() {
        val n = notifier.status(currentText())
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(Notifier.ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(Notifier.ID_STATUS, n)
            }
        } catch (e: Exception) {
            log("startForeground failed, stopping: ${e.message}")
            stopSelf()
        }
    }

    // ---- lifecycle ----

    private suspend fun supervise() {
        combine(gate.state, reloads) { st, _ -> st }.collectLatest { st ->
            publish()
            if (st != ShizukuState.Ready) {
                log("Shizuku $st: waiting")
                reportShizukuOff()
                return@collectLatest
            }
            while (true) {
                try {
                    runSession()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: FsUnavailableException) {
                    log("shell unavailable (${e.message}); restarting in ${RESTART_DELAY_MS / 1000} s")
                } catch (e: Throwable) {
                    log("session failed: ${e.javaClass.simpleName}: ${e.message}; restarting in ${RESTART_DELAY_MS / 1000} s")
                }
                session = null
                publish()
                delay(RESTART_DELAY_MS)
                gate.refresh()
            }
        }
    }

    /** One Shizuku-ready session. Returns only by throwing (or never, when not signed in). */
    private suspend fun runSession(): Unit = coroutineScope {
        // The previous session has ended (collectLatest joins it): forgets recorded since can no longer be undone.
        prepareSession(store).takeIf { it.isNotEmpty() }?.let { log("forgot what this device knew of ${it.sorted()}; their next uploads are fresh") }
        val url = store.serverUrl?.trimEnd('/')
        val key = store.deviceKey
        if (url.isNullOrBlank() || key.isNullOrBlank()) {
            log("not signed in: set the server and sign in on the EmuSync screen")
            publish()
            awaitCancellation()
        }
        val shell = try {
            withContext(Dispatchers.Main) { withTimeout(BIND_TIMEOUT_MS) { gate.bind() } }
        } catch (e: TimeoutCancellationException) {
            log("shell bind timed out after ${BIND_TIMEOUT_MS / 1000} s")
            throw FsUnavailableException("shell bind timed out")
        }
        val fs = RemoteFs(shell)
        val flows = AppGraph.sessionFlows.begin()
        try {
            val api = StatusRecordingApi(ApiClient(url, key), AppGraph.sessionFlows, flows) {
                lastStatus = SentStatus(url, key, it)
            }
            val stored = store.settings
            val s = DeviceSettings(stored.romDirs, stored.overrides, stored.variants)
            val planner = WorkPlanner(stored.autoApply, StoredApplyOnExitFlags(store))
            val engine = SyncEngine(
                fs, api, store, registry, s,
                clock = System::currentTimeMillis,   // compared with file mtimes: must be wall-clock time
                log = log,
                versionOf = ::versionOf,
                appVersion = BuildConfig.VERSION_NAME,
                shizukuOk = { gate.state.value == ShizukuState.Ready },
                onConflict = notifier::conflict,
            )
            val applier = DeliveryApplier(
                fs, api, store, registry, engine, notifier::alert, s, cacheDir,
                onWaiting = { AppGraph.sessionFlows.publishWaiting(flows, it) },
                onReceived = { AppGraph.sessionFlows.publishReceived(flows, it, java.time.Instant.now()) },
                log = log,
            )
            val sess = Session(fs, engine, applier, WorkQueue(), planner, flows)
            log("sync session starting ($url, auto-apply ${if (stored.autoApply) "on" else "off"})")

            // Initial pass before anything else is queued.
            handle(sess, SyncWork.FullScan)
            session = sess
            publish()

            fun offer(work: List<SyncWork>) = work.forEach { sess.queue.offer(it) }
            val opens = java.util.concurrent.atomic.AtomicInteger(0)
            val events = EventStream(
                defaultHttpClient(), url, key,
                onDeliveries = { AppGraph.sessionFlows.nudgeDeliveries(flows); offer(planner.deliveriesEvent()) },
                onState = { up ->
                    if (up != online) log(if (up) "server events connected" else "server events disconnected")
                    online = up
                    // A reconnect rescans to catch up on what was missed.
                    if (up) offer(planner.streamOpened(reconnect = opens.getAndIncrement() > 0))
                    publish()
                },
            )
            try {
                launch { sess.queue.consume { handle(sess, it) } }
                launch { every(TICK_MS) { offer(planner.tick()) } }
                launch { every(MONITOR_MS) { offer(planner.monitor()) } }
                launch { every(SyncConfig.pollMillis) { offer(planner.poll()) } }
                launch { every(FULL_SCAN_MS) { offer(planner.periodicScan()) } }
                offer(planner.sessionStarted())
                events.start()
                awaitCancellation()
            } finally {
                events.stop()
                online = null
                sess.queue.close()
                runCatching { fs.unwatchAll() }
                if (session === sess) session = null
            }
        } finally {
            // This session's knowledge ends with it, and its late writes (blocked calls returning) are dropped.
            AppGraph.sessionFlows.end(flows)
        }
    }

    private suspend fun every(ms: Long, f: () -> Unit) {
        while (true) {
            delay(ms)
            f()
        }
    }

    /** Runs one work item. Everything but [FsUnavailableException] and cancellation is logged and swallowed. */
    private suspend fun handle(s: Session, w: SyncWork) {
        var error: Throwable? = null
        try {
            when (w) {
                SyncWork.FullScan -> {
                    s.engine.fullScan()
                    rewatch(s)
                }
                SyncWork.Tick -> s.engine.tick()
                SyncWork.ApplyAll, SyncWork.ApplyRequested -> {
                    val r = s.applier.applyAll()
                    s.planner.applied(w, r)
                    if (r.applied + r.failed + r.waitingForEmulator > 0)
                        log("deliveries: ${r.applied} applied, ${r.skipped} skipped, ${r.failed} failed, ${r.waitingForEmulator} waiting")
                }
                SyncWork.Monitor -> monitor(s)
            }
        } catch (e: CancellationException) {
            error = e
            throw e
        } catch (e: FsUnavailableException) {
            error = e
            throw e
        } catch (e: Throwable) {
            error = e
            log("$w failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            // Whoever waits for a scan hears how it ended: only one that got every upload through is a success.
            if (w == SyncWork.FullScan) countScan(scanSucceeded(error, s.engine.queuedCount()))
            if (w != SyncWork.Tick && w != SyncWork.Monitor) publish()
        }
    }

    /**
     * Probes each detected emulator, publishes the running set (and reports it to the server when it changed or is
     * due), then on alive -> dead uploads its config and applies what waited for the exit.
     */
    private suspend fun monitor(s: Session) {
        val found = s.engine.detectedEmulators()
        val ids = found.map { it.emulatorId }.toSet()
        s.exits.retain(ids)
        s.applier.retainWaiting(ids)   // an emulator no longer detected holds nothing back
        val running = LinkedHashSet<String>()
        val exited = ArrayList<String>()
        for (d in found) {
            val m = registry.byId(d.emulatorId) ?: continue
            val alive = s.fs.isEmulatorRunning(m, d)   // any variant or sub-process; throws (ends the session) on probe failure
            if (alive) running += d.emulatorId
            if (s.exits.observe(d.emulatorId, alive)) exited += d.emulatorId
        }
        AppGraph.sessionFlows.publishRunning(s.flows, running)
        s.engine.reportRunning(running)
        for (id in exited) {
            log("$id exited")
            s.engine.onEmulatorExited(id)
            // Runs in the queue's consumer like any apply; the applier re-checks the process before every write.
            val r = s.planner.exited(id,
                apply = { requested -> s.applier.onEmulatorExited(id, force = requested) },
                forget = { s.applier.forgetWaiting(id) })
            if (r != null && r.applied + r.failed > 0) log("$id: ${r.applied} applied, ${r.failed} failed after exit")
            rewatch(s)
            publish()
        }
    }

    /** (Re)starts watching when the detected modules' watch dirs changed. Replaces the previous watch. */
    private fun rewatch(s: Session) {
        val dirs = s.engine.allWatchDirs()
        if (dirs == s.watched) return
        s.fs.watch(dirs) { dir, _ -> s.engine.onFsEvent(dir) }   // oneway binder thread: only bookkeeping
        s.watched = dirs
        log("watching ${dirs.size} dirs")
    }

    /** Best effort: tells the server Shizuku is off, resending the last emulator list so it isn't wiped. */
    private suspend fun reportShizukuOff() {
        val sent = lastStatus ?: return
        val url = store.serverUrl?.trimEnd('/') ?: return
        val key = store.deviceKey ?: return
        if (sent.url != url || sent.key != key || !sent.req.shizukuOk) return   // only for the same server and device
        try {
            val off = sent.req.copy(shizukuOk = false)
            withTimeoutOrNull(20_000) { ApiClient(url, key).putStatus(off) } ?: return
            lastStatus = SentStatus(url, key, off)
            log("reported Shizuku off to the server")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("couldn't report Shizuku off: ${e.message}")
        }
    }

    private fun versionOf(pkg: String): String? = try {
        packageManager.getPackageInfo(pkg, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    // ---- status ----

    private fun currentText(): String {
        val e = session?.engine
        return statusText(
            gate.state.value,
            watching = e?.detectedEmulators()?.size ?: 0,
            queued = e?.queuedCount() ?: 0,
            online = online != false,
            signedIn = !store.serverUrl.isNullOrBlank() && !store.deviceKey.isNullOrBlank(),
        )
    }

    @Synchronized
    private fun publish() {
        if (destroyed) return   // a cancelled session's last publish must not re-post the notification
        val text = currentText()
        val e = session?.engine
        val counts = e?.itemCounts() ?: emptyMap()
        val next = ServiceSnapshot(
            running = true,
            status = text,
            online = online == true,
            emulators = e?.detectedEmulators()?.map { EmulatorInfo(it, counts[it.emulatorId] ?: 0) } ?: emptyList(),
            watchDirs = session?.watched?.size ?: 0,
            live = session != null,
            scans = AppGraph.fullScans.get(),
            failedScans = AppGraph.failedScans.get(),
        )
        val prev = AppGraph.snapshot.value
        AppGraph.snapshot.value = next
        if (prev.status != text || !prev.running) notifier.updateStatus(text)
    }
}

/**
 * Passes calls through and remembers the last status the server accepted (for the Shizuku-off report), but only
 * while [token]'s session is live.
 */
internal class StatusRecordingApi(
    private val inner: Api,
    private val flows: SessionFlows,
    private val token: SessionFlows.Token,
    private val onStatus: (DeviceStatusRequest) -> Unit,
) : Api by inner {
    override suspend fun putStatus(req: DeviceStatusRequest) {
        inner.putStatus(req)
        record(req)
    }

    override suspend fun putStatus(req: DeviceStatusRequest, callTimeoutMillis: Long) {
        inner.putStatus(req, callTimeoutMillis)
        record(req)
    }

    /** A call that returns after its session ended must not replace what the Shizuku-off report resends. */
    private fun record(req: DeviceStatusRequest) = flows.ifLive(token) { onStatus(req) }
}
