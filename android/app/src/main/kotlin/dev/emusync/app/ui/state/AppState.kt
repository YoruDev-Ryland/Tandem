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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn

enum class ServerConn { Connected, Offline, SignedOut }

data class TopStatus(val deviceName: String?, val server: ServerConn, val shizuku: ShizukuState, val signedIn: Boolean)

sealed interface EmuRowState {
    data class Open(val waiting: Int) : EmuRowState
    data class Saves(val n: Int) : EmuRowState
    data class ConfigGroup(val name: String) : EmuRowState
    data object NotSetUp : EmuRowState
    data object Ok : EmuRowState
}

data class EmulatorRowUi(val id: String, val name: String, val state: EmuRowState, val count: Int)

/** What the screens read: top-bar status and the emulator list, derived from the graph, store and Shizuku gate. */
class AppState(
    graph: AppGraph,
    private val store: StateStore,
    shizuku: Flow<ShizukuState>,
    scope: CoroutineScope,
    deviceName: Flow<String?> = MutableStateFlow(null),
    configGroups: Flow<Map<String, String>> = MutableStateFlow(emptyMap()),
    private val registry: ModuleRegistry = ModuleRegistry.default(),
) {
    constructor(graph: AppGraph, store: StateStore, gate: ShizukuGate, scope: CoroutineScope) :
        this(graph, store, gate.state, scope)

    val waiting: StateFlow<List<WaitingDelivery>> = graph.waiting
    val running: StateFlow<Set<String>> = graph.running

    val top: StateFlow<TopStatus> = combine(store.changes, shizuku, graph.snapshots, deviceName) { _, z, snap, name ->
        val key = store.deviceKey
        TopStatus(
            deviceName = name,
            server = when {
                key == null -> ServerConn.SignedOut
                snap.online -> ServerConn.Connected
                else -> ServerConn.Offline
            },
            shizuku = z,
            signedIn = key != null,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TopStatus(null, ServerConn.SignedOut, ShizukuState.NotRunning, false))

    val emulators: StateFlow<List<EmulatorRowUi>> =
        combine(graph.snapshots, graph.waiting, configGroups) { snap, waits, groups ->
            val found = snap.emulators.associateBy { it.detected.emulatorId }
            registry.all().map { m ->
                val info = found[m.emulatorId]
                val n = waits.count { it.emulatorId == m.emulatorId }
                val state = when {
                    n > 0 -> EmuRowState.Open(n)
                    info == null -> EmuRowState.NotSetUp
                    groups[m.emulatorId] != null -> EmuRowState.ConfigGroup(groups.getValue(m.emulatorId))
                    info.itemCount > 0 -> EmuRowState.Saves(info.itemCount)
                    else -> EmuRowState.Ok
                }
                EmulatorRowUi(m.emulatorId, m.displayName, state, info?.itemCount ?: 0)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
}
