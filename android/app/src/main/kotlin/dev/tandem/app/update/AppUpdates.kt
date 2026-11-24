package dev.tandem.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import dev.tandem.app.BuildConfig
import dev.tandem.app.fs.ShizukuState
import dev.tandem.app.service.AppGraph
import dev.tandem.app.service.LogBuffer
import dev.tandem.app.service.Notifier
import dev.tandem.app.shell.pmFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * The process's one [Updater] and what drives it from outside the sync service: Settings' Check now and Install now,
 * and the update notification. While the sync service runs, it installs (see SyncService: in its work queue once sync
 * is idle); otherwise nothing syncs, so Install now installs at once.
 */
object AppUpdates {
    @Volatile private var ref: Updater? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nudges = MutableStateFlow(0L)

    /** Bumped by Install now: the running service looks at the ready update at once rather than on its next poll. */
    val installNudges: StateFlow<Long> get() = nudges

    fun updater(context: Context): Updater = ref ?: synchronized(this) {
        ref ?: build(context.applicationContext).also { ref = it }
    }

    private fun build(ctx: Context) = Updater(
        store = UpdateStore(File(ctx.filesDir, "update.json")),
        dir = File(ctx.cacheDir, "update"),
        installedCode = BuildConfig.VERSION_CODE,
        debug = BuildConfig.DEBUG,
        inspector = PackageManagerInspector(ctx),
        latestUrl = latestReleaseUrl(BuildConfig.UPDATE_REPO),
        log = LogBuffer::add,
        onReadyToAsk = { v -> Notifier(ctx).also { it.ensureChannels() }.updateReady(v) },
    )

    fun installer(context: Context): Installer = AppInstaller(context.applicationContext)

    /** Asks GitHub in the background (a sync session started: only when due). */
    fun checkSoon(context: Context, sessionStart: Boolean) {
        val u = updater(context)
        scope.launch { u.check(sessionStart = sessionStart) }
    }

    /** Settings › Check now. */
    fun checkNow(context: Context) {
        val u = updater(context)
        scope.launch { u.check(force = true) }
    }

    /** Settings › Install now, or the notification: installs the ready update as soon as sync is idle. */
    fun installNow(context: Context) {
        val ctx = context.applicationContext
        val u = updater(ctx)
        u.requestInstall()
        if (AppGraph.snapshot.value.running) {
            nudges.value++
        } else {
            scope.launch { u.install(installer(ctx)) }
        }
    }
}

/**
 * Installs through the Shizuku shell service when it is up (`pm install`, silent), else through a PackageInstaller
 * session, which shows Android's confirm prompt (a notification opens it; [InstallResultReceiver] hears the result).
 */
class AppInstaller(private val ctx: Context) : Installer {
    override suspend fun install(apk: File, version: String, userAsked: Boolean): InstallOutcome {
        shizuku(apk)?.let { return it }
        return session(apk, version, userAsked)
    }

    /** Null when the shell can't do it (not ready, an old service, the shell died): try a session instead. */
    private suspend fun shizuku(apk: File): InstallOutcome? {
        val gate = withContext(Dispatchers.Main) { AppGraph.gate(ctx) }   // created on the main thread if it is the first use
        if (gate.state.value != ShizukuState.Ready) return null
        return try {
            val shell = withContext(Dispatchers.Main) { withTimeout(30_000) { gate.bind() } }
            val out = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> shell.installApk(pfd, apk.length()) }
            } ?: return null.also { LogBuffer.add("update: the shell service can't install (an old one); asking Android") }
            val failure = pmFailure(out)
            if (failure == null) InstallOutcome.Started else InstallOutcome.Failed(failure)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DeadObjectException) {
            LogBuffer.add("update: the shell service stopped during the install; asking Android")
            null
        } catch (e: Exception) {
            LogBuffer.add("update: Shizuku install failed (${e.javaClass.simpleName}: ${e.message}); asking Android")
            null
        }
    }

    private suspend fun session(apk: File, version: String, userAsked: Boolean): InstallOutcome = withContext(Dispatchers.IO) {
        val installer = ctx.packageManager.packageInstaller
        var id = -1
        try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(ctx.packageName)
                setSize(apk.length())
                // Once Tandem installed itself, Android lets later updates through without the prompt.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            id = installer.createSession(params)
            installer.openSession(id).use { s ->
                s.openWrite(APK_ASSET, 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val result = Intent(ctx, InstallResultReceiver::class.java)
                    .putExtra(InstallResultReceiver.EXTRA_VERSION, version)
                    .putExtra(InstallResultReceiver.EXTRA_USER_ASKED, userAsked)
                // Mutable: the installer adds the status and the prompt to it.
                val pending = PendingIntent.getBroadcast(ctx, id, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                s.commit(pending.intentSender)
            }
            InstallOutcome.Started
        } catch (e: Exception) {
            if (id >= 0) runCatching { installer.abandonSession(id) }
            InstallOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}

/** The PackageInstaller session's status: the prompt to show, or why it failed. */
class InstallResultReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_VERSION = "dev.tandem.app.extra.UPDATE_VERSION"
        const val EXTRA_USER_ASKED = "dev.tandem.app.extra.USER_ASKED"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val updater = AppUpdates.updater(context)
        val version = intent.getStringExtra(EXTRA_VERSION) ?: "The update"
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    ?: return updater.installFailed("Android gave no install prompt")
                // The notification always; the prompt at once too when the user just asked (Android may refuse an
                // activity start from the background, and then the notification is the way).
                Notifier(context).also { it.ensureChannels() }.updateReady(version, Intent(confirm))
                if (intent.getBooleanExtra(EXTRA_USER_ASKED, false)) {
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                updater.prompted()
            }
            PackageInstaller.STATUS_SUCCESS -> LogBuffer.add("update $version installed")
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.installFailed("the install was cancelled")
            else -> updater.installFailed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Android refused it (status $status)")
        }
    }
}
