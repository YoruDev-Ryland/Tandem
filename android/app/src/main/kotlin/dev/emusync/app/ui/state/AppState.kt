package dev.emusync.app.ui.state

import dev.emusync.app.fs.ShizukuGate
import dev.emusync.app.fs.ShizukuState
import dev.emusync.app.modules.ModuleRegistry
import dev.emusync.app.service.AppGraph
import dev.emusync.app.sync.StateStore
import dev.emusync.app.sync.WaitingDelivery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class ServerConn { Connected, Offline, SignedOut }

data class TopStatus(val deviceName: String?, val server: ServerConn, val shizuku: ShizukuState, val signedIn: Boolean, val lastSync: java.time.Instant? = null)

sealed interface EmuRowState {
    data class Open(val waiting: Int) : EmuRowState
    data class Saves(val n: Int) : EmuRowState
    data class ConfigGroup(val name: String) : EmuRowState
    data object NotSetUp : EmuRowState
    /** The service has not finished its first scan: nothing is known about this emulator yet. */
    data object Looking : EmuRowState
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

    val top: StateFlow<TopStatus> = combine(
        store.changes, shizuku, graph.snapshots, server.deviceName, server.games,
    ) { _, z, snap, name, games ->
        val key = store.deviceKey
        TopStatus(
            deviceName = name,
            server = when {
                key == null -> ServerConn.SignedOut
                games.offline -> ServerConn.Offline
                snap.online || games.updatedAt != null -> ServerConn.Connected
                else -> ServerConn.Offline
            },
            shizuku = z,
            signedIn = key != null,
            lastSync = games.updatedAt,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TopStatus(null, ServerConn.SignedOut, ShizukuState.NotRunning, false))

    val emulators: StateFlow<List<EmulatorRowUi>> =
        combine(graph.snapshots, graph.waiting, graph.running, server.configGroups) { snap, waits, run, groups ->
            val found = snap.emulators.associateBy { it.detected.emulatorId }
            registry.all().map { m ->
                val info = found[m.emulatorId]
                val n = waits.count { it.emulatorId == m.emulatorId }
                val state = when {
                    n > 0 || m.emulatorId in run -> EmuRowState.Open(n)
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
