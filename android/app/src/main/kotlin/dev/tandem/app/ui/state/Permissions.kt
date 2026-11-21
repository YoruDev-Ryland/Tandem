package dev.tandem.app.ui.state

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.PowerManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What keeps sync working in the background: [notifications] (POST_NOTIFICATIONS, so conflicts and "close the
 * emulator" reach the user) and [battery] (exempt from battery optimisation, so the ROM doesn't kill the service and
 * the watchdog may restart it).
 */
data class PermissionState(val notifications: Boolean = true, val battery: Boolean = true) {
    val allGranted: Boolean get() = notifications && battery
}

/** A system prompt the activity has to show (both need an Activity). */
enum class PermissionRequest { Notifications, Battery }

/** The permission state, read again on [refresh] (the activity calls it on resume and after a prompt). */
interface Permissions {
    val state: StateFlow<PermissionState>
    fun refresh()
    fun request(r: PermissionRequest)
}

/** Fixed answers and recorded requests, for tests and previews. */
class FixedPermissions(initial: PermissionState = PermissionState()) : Permissions {
    private val s = MutableStateFlow(initial)
    val requested = mutableListOf<PermissionRequest>()
    override val state: StateFlow<PermissionState> get() = s
    fun set(v: PermissionState) { s.value = v }
    override fun refresh() {}
    override fun request(r: PermissionRequest) { requested += r }
}

/**
 * The real checks. Requests go out on [requests] for the activity to show the system prompt: the notification
 * permission dialog, or ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
 */
class AndroidPermissions(private val app: Application) : Permissions {
    private val s = MutableStateFlow(read())
    private val out = MutableSharedFlow<PermissionRequest>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val state: StateFlow<PermissionState> get() = s
    val requests: SharedFlow<PermissionRequest> get() = out

    private fun read() = PermissionState(
        notifications = app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        battery = runCatching { app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName) }
            .getOrDefault(true),
    )

    override fun refresh() { s.value = read() }
    override fun request(r: PermissionRequest) { out.tryEmit(r) }
}
