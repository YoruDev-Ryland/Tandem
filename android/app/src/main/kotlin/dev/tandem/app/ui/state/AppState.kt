package dev.tandem.app.ui.state

import dev.tandem.app.fs.ShizukuGate
import dev.tandem.app.fs.ShizukuState
import dev.tandem.app.modules.ModuleRegistry
import dev.tandem.app.service.AppGraph
import dev.tandem.app.sync.StateStore
import dev.tandem.app.sync.WaitingDelivery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** [Revoked]: the server answered device_revoked; sync is stopped until a sign-in. [Moving]: the server holds uploads while it moves its files. */
enum class ServerConn { Connected, Offline, SignedOut, Revoked, Moving }

/**
 * The top bar's status. [lastSync] is when the server cache last refreshed; [lastSyncedAt] when this device last
 * synced with nothing left failing (a clean scan, apply pass or accepted upload), kept across restarts.
 */
data class TopStatus(
    val deviceName: String?,
    val server: ServerConn,
    val shizuku: ShizukuState,
    val signedIn: Boolean,
    val lastSync: java.time.Instant? = null,
    val lastSyncedAt: java.time.Instant? = null,
)

sealed interface EmuRowState {
    data class Open(val waiting: Int) : EmuRowState
    data class Saves(val n: Int) : EmuRowState
    data class ConfigGroup(val name: String) : EmuRowState
    data object NotSetUp : EmuRowState
    /** The service has not finished its first scan: nothing is known about this emulator yet. */
    data object Looking : EmuRowState
    /** No scan can run: Shizuku is off or not allowed (the top bar says so). */
    data object NoShizuku : EmuRowState
    /** Sync isn't running (signed out, or removed on the server): nothing is being looked at. */
    data object Idle : EmuRowState
    data object Ok : EmuRowState
}

data class EmulatorRowUi(val id: String, val name: String, val state: EmuRowState, val count: Int)

/** What the screens read: top-bar status and the emulator list, derived from the graph, store and Shizuku gate. */
class AppState(
    graph: AppGraph,
    private val store: StateStore,
    shizuku: Flow<ShizukuState>,
    scope: CoroutineScope,
    server: ServerView,
    private val registry: ModuleRegistry = ModuleRegistry.default(),
) {
    companion object {
        /** Production wiring: the Shizuku gate and the shared [ServerRepo]. */
        fun create(graph: AppGraph, store: StateStore, gate: ShizukuGate, scope: CoroutineScope, server: ServerRepo) =
            AppState(graph, store, gate.state, scope, server)
    }

    val waiting: StateFlow<List<WaitingDelivery>> = graph.waiting
    val running: StateFlow<Set<String>> = graph.running
    val received: StateFlow<List<dev.tandem.app.service.ReceivedAt>> = graph.received

    val top: StateFlow<TopStatus> = combine(
        store.changes, shizuku, graph.snapshots, server.deviceName, server.games,
    ) { _, z, snap, name, games ->
        val key = store.deviceKey
        TopStatus(
            deviceName = name,
            server = when {
                key == null -> ServerConn.SignedOut
                store.revoked -> ServerConn.Revoked
                games.offline -> ServerConn.Offline
                snap.moving -> ServerConn.Moving
                snap.online || games.updatedAt != null -> ServerConn.Connected
                else -> ServerConn.Offline
            },
            shizuku = z,
            signedIn = key != null,
            lastSync = games.updatedAt,
            lastSyncedAt = store.lastSyncedAt?.let(java.time.Instant::ofEpochMilli),
        )
    }.stateIn(scope, SharingStarted.Eagerly, TopStatus(null, ServerConn.SignedOut, ShizukuState.NotRunning, false))

    val emulators: StateFlow<List<EmulatorRowUi>> =
        combine(graph.snapshots, graph.waiting, graph.running, server.configGroups, shizuku) { snap, waits, run, groups, z ->
            val found = snap.emulators.associateBy { it.detected.emulatorId }
            registry.all().map { m ->
                val info = found[m.emulatorId]
                val n = waits.count { it.emulatorId == m.emulatorId }
                val state = when {
                    n > 0 || m.emulatorId in run -> EmuRowState.Open(n)
                    // No first scan can come without Shizuku, or while sync isn't running at all: say so, not "looking".
                    !snap.live && z != ShizukuState.Ready -> EmuRowState.NoShizuku
                    !snap.live && !snap.running -> EmuRowState.Idle
                    // Until the service's first scan, an emulator it has not reported is not known to be missing.
                    !snap.live -> EmuRowState.Looking
                    info == null -> EmuRowState.NotSetUp
                    groups[m.emulatorId] != null -> EmuRowState.ConfigGroup(groups.getValue(m.emulatorId))
                    info.itemCount > 0 -> EmuRowState.Saves(info.itemCount)
                    else -> EmuRowState.Ok
                }
                EmulatorRowUi(m.emulatorId, m.displayName, state, info?.itemCount ?: 0)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
}
