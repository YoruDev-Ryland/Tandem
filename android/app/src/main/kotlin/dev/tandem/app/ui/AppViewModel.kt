package dev.tandem.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.tandem.app.api.Api
import dev.tandem.app.api.ApiClient
import dev.tandem.app.service.AppGraph
import dev.tandem.app.modules.ModuleRegistry
import dev.tandem.app.service.SyncService
import dev.tandem.app.ui.screens.ActivityViewModel
import dev.tandem.app.ui.screens.ConflictsViewModel
import dev.tandem.app.ui.screens.DeviceViewModel
import dev.tandem.app.ui.screens.SettingsViewModel
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import dev.tandem.app.ui.setup.SetupViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import dev.tandem.app.ui.state.AppState
import dev.tandem.app.ui.state.ServerRepo

/**
 * The screens' state, kept across rotation: the server cache, the app state over it and each tab's model. The
 * server and key are read from the store on every refresh, so signing in or out takes effect on the next one.
 */
class AppViewModel(app: Application, handle: SavedStateHandle) : AndroidViewModel(app) {
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
    private val modules = ModuleRegistry.default().all()
    private val emulatorNames = modules.associate { it.emulatorId to it.displayName }
    val conflicts = ConflictsViewModel(
        conflicts = server.conflicts,
        running = state.running,
        signedIn = state.top.map { it.signedIn }.stateIn(viewModelScope, SharingStarted.Eagerly, false),
        deviceId = { store.deviceId },
        api = ::api,
        scope = viewModelScope,
        // A choice can change what this device should hold: apply it (the service holds it while the emulator runs).
        afterResolve = { SyncService.tryStart(app, SyncService.ACTION_APPLY); server.refresh() },
        emulatorName = { emulatorNames[it] ?: it },
    )
    // No device topic carries activity: refresh on the delivery signal and on every server cache refresh.
    val activity = ActivityViewModel(
        api = ::api,
        deviceId = { store.deviceId },
        scope = viewModelScope,
        nudges = merge<Any?>(AppGraph.deliveryNudges, server.games.map { it.updatedAt }),
    )

    val settings = SettingsViewModel(
        store = store,
        modules = modules,
        // The manifest's <queries> make every known emulator package visible; a variant chosen on the
        // diagnostics screen may be another package, which is then visible only if it is queryable too.
        installed = { (modules.flatMap { it.packages } + store.settings.variants.values).filterTo(HashSet()) { app.isInstalled(it) } },
        label = { app.appLabel(it) },
        deviceName = server.deviceName,
        reload = { SyncService.tryStart(app, SyncService.ACTION_RELOAD) },
        stopSync = { SyncService.stop(app) },
        afterSignOut = { server.refresh() },   // no key: the server data clears
        scope = viewModelScope,
    )

    /** The first-run setup flow; its place and choices live in [handle], so process death resumes it. */
    val setup = SetupViewModel(
        handle = handle,
        store = store,
        modules = modules,
        anon = { url -> ApiClient(url, null) },
        api = ::api,
        shizuku = AppGraph.gate(app).state,
        askShizuku = { AppGraph.gate(app).requestPermission() },
        snapshot = AppGraph.snapshots,
        settings = settings,
        service = { SyncService.tryStart(app, it) },
        afterSignIn = { server.refresh() },
        scope = viewModelScope,
        running = state.running,
        emulatorName = { emulatorNames[it] ?: it },
        defaultDeviceName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android",
    )

    /** The server URL for links to the web app, or null when signed out. */
    val serverUrl: String? get() = store.serverUrl

    init { server.start() }

    override fun onCleared() = server.stop()
}

private fun Application.isInstalled(pkg: String): Boolean = try {
    packageManager.getPackageInfo(pkg, 0); true
} catch (_: PackageManager.NameNotFoundException) {
    false
}

private fun Application.appLabel(pkg: String): String? = try {
    packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString().ifBlank { null }
} catch (_: PackageManager.NameNotFoundException) {
    null
}
