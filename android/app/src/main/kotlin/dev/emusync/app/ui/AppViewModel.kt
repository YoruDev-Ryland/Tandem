package dev.emusync.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.emusync.app.api.Api
import dev.emusync.app.api.ApiClient
import dev.emusync.app.service.AppGraph
import dev.emusync.app.modules.ModuleRegistry
import dev.emusync.app.service.SyncService
import dev.emusync.app.ui.screens.ConflictsViewModel
import dev.emusync.app.ui.screens.DeviceViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import dev.emusync.app.ui.state.AppState
import dev.emusync.app.ui.state.ServerRepo

/**
 * The screens' state, kept across rotation: the server cache, the app state over it and each tab's model. The
 * server and key are read from the store on every refresh, so signing in or out takes effect on the next one.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = AppGraph.store(app)
    private var client: Triple<String, String, ApiClient>? = null

    /** One client per server and key, so refreshes share its connection pool. */
    @Synchronized private fun api(): Api? {
        val url = store.serverUrl ?: return null
        val key = store.deviceKey ?: return null
        client?.let { (u, k, c) -> if (u == url && k == key) return c }
        return ApiClient(url, key).also { client = Triple(url, key, it) }
    }

    val server = ServerRepo(
        api = ::api,
        scope = viewModelScope,
        deliveryNudges = AppGraph.deliveryNudges,
        running = AppGraph.running,
        deviceId = { store.deviceId },
    )
    val state = AppState.create(AppGraph, store, AppGraph.gate(app), viewModelScope, server)
    val device = DeviceViewModel(state, server.games, { store.deviceId }, viewModelScope)
    private val emulatorNames = ModuleRegistry.default().all().associate { it.emulatorId to it.displayName }
    val conflicts = ConflictsViewModel(
        conflicts = server.conflicts,
        running = state.running,
        signedIn = state.top.map { it.signedIn }.stateIn(viewModelScope, SharingStarted.Eagerly, false),
        deviceId = { store.deviceId },
        api = ::api,
        scope = viewModelScope,
        // A choice can change what this device should hold: apply it (the service holds it while the emulator runs).
        afterResolve = { SyncService.start(app, SyncService.ACTION_APPLY); server.refresh() },
        emulatorName = { emulatorNames[it] ?: it },
    )

    /** The server URL for links to the web app, or null when signed out. */
    val serverUrl: String? get() = store.serverUrl

    init { server.start() }

    override fun onCleared() = server.stop()
}
