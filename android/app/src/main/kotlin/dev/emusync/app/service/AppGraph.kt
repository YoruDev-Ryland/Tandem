package dev.emusync.app.service

import android.content.Context
import dev.emusync.app.fs.ShizukuGate
import dev.emusync.app.modules.Detected
import dev.emusync.app.sync.StateStore
import dev.emusync.app.sync.WaitingDelivery
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One emulator as the service last saw it. */
data class EmulatorInfo(val detected: Detected, val itemCount: Int)

/** What the running service publishes for the debug screen. */
data class ServiceSnapshot(
    val running: Boolean = false,
    val status: String = "Not running",
    val online: Boolean = false,
    val emulators: List<EmulatorInfo> = emptyList(),
    val watchDirs: Int = 0,
)

/**
 * Process-wide singletons. The [StateStore] caches its file in memory and rewrites it whole, so the service and the
 * debug screen must share one instance or one would overwrite the other's changes.
 */
object AppGraph {
    @Volatile private var storeRef: StateStore? = null
    @Volatile private var gateRef: ShizukuGate? = null

    /** The one sync supervisor of this process (see [SessionRunner]). */
    val sessions = SessionRunner()

    val snapshot = MutableStateFlow(ServiceSnapshot())
    val snapshots: StateFlow<ServiceSnapshot> get() = snapshot

    /** Written only by the live [SyncService] session (see [SessionFlows]). */
    internal val sessionFlows = SessionFlows()

    /** The deliveries the last apply pass held back because their emulator runs; empty while sync is off. */
    val waiting: StateFlow<List<WaitingDelivery>> get() = sessionFlows.waiting

    /** The emulator ids running, refreshed on every process monitor tick; empty while sync is off. */
    val running: StateFlow<Set<String>> get() = sessionFlows.running

    fun store(context: Context): StateStore = storeRef ?: synchronized(this) {
        storeRef ?: StateStore(File(context.applicationContext.filesDir, "state.json")).also { storeRef = it }
    }

    /** The shared Shizuku gate, started on first use. Call from the main thread the first time. */
    fun gate(context: Context): ShizukuGate = gateRef ?: synchronized(this) {
        gateRef ?: ShizukuGate(context.applicationContext).also { it.start(); gateRef = it }
    }
}
