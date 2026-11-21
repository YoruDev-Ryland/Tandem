package dev.tandem.app.fs

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import dev.tandem.app.BuildConfig
import dev.tandem.app.shell.IShellService
import dev.tandem.app.shell.ShellService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ShizukuState { NotRunning, NoPermission, Ready }

class ShizukuGate(private val context: Context) {
    private val _state = MutableStateFlow(ShizukuState.NotRunning)
    val state: StateFlow<ShizukuState> = _state

    private val requestCode = 4242
    @Volatile private var service: IShellService? = null

    private val received = Shizuku.OnBinderReceivedListener { refresh() }
    private val dead = Shizuku.OnBinderDeadListener { service = null; refresh() }
    private val permResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    @Volatile private var started = false

    /** Registers the Shizuku listeners once per gate (idempotent). Call from the main thread. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permResult)
        refresh()
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        Shizuku.removeBinderReceivedListener(received)
        Shizuku.removeBinderDeadListener(dead)
        Shizuku.removeRequestPermissionResultListener(permResult)
    }

    /** Re-reads the Shizuku state (e.g. when a screen resumes after the user granted access in Shizuku). */
    fun refresh() {
        _state.value = try {
            when {
                !Shizuku.pingBinder() -> ShizukuState.NotRunning
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NoPermission
                else -> ShizukuState.Ready
            }
        } catch (e: Throwable) {
            ShizukuState.NotRunning
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder()) Shizuku.requestPermission(requestCode)
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
            .tag("tandem").processNameSuffix("shell").version(BuildConfig.SHELL_SERVICE_VERSION).daemon(false)
    }

    /** Binds the shell service (cached while alive). Rebinding after binder death happens on the next call. */
    suspend fun bind(): IShellService {
        service?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        if (_state.value != ShizukuState.Ready) throw FsUnavailableException("Shizuku not ready: ${_state.value}")
        return suspendCancellableCoroutine { cont ->
            val conn = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder?) {
                    if (binder != null && binder.pingBinder()) {
                        val s = IShellService.Stub.asInterface(binder)
                        service = s
                        if (cont.isActive) cont.resume(s)
                    } else if (cont.isActive) {
                        cont.resumeWithException(FsUnavailableException("empty binder"))
                    }
                }
                override fun onServiceDisconnected(name: ComponentName) { service = null }
            }
            try {
                Shizuku.bindUserService(args, conn)
            } catch (e: Throwable) {
                cont.resumeWithException(FsUnavailableException("bindUserService failed: ${e.message}"))
            }
            cont.invokeOnCancellation { runCatching { Shizuku.unbindUserService(args, conn, false) } }
        }
    }
}
