package dev.tandem.app.service

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.tandem.app.sync.StateStore
import java.util.concurrent.TimeUnit

/**
 * Restarts sync when an OEM battery killer (or anything else) stopped it: every 15 minutes WorkManager runs
 * [SyncWatchdog], which starts [SyncService] when this device can sync and the service isn't running. Android lets a
 * worker start a foreground service only while the app is exempt from battery optimisation (setup and Settings ask
 * for it); otherwise the start is refused and logged, and the next launch or boot starts sync instead.
 */
object Watchdog {
    private const val NAME = "sync-watchdog"
    const val PERIOD_MINUTES = 15L

    /** Schedules the watchdog once; later calls keep the existing schedule. Never throws. */
    fun schedule(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWatchdog>(PERIOD_MINUTES, TimeUnit.MINUTES).build(),
            )
        }.onFailure { LogBuffer.add("watchdog not scheduled: ${it.message}") }
    }

    /** One check: starts sync through [start] when [store] can sync and it isn't [running]. Returns whether it tried. */
    fun check(store: StateStore, running: Boolean, start: () -> Boolean): Boolean {
        if (running || !store.canSync) return false
        LogBuffer.add("watchdog: sync wasn't running, starting it")
        start()
        return true
    }
}

class SyncWatchdog(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        Watchdog.check(AppGraph.store(applicationContext), AppGraph.snapshot.value.running) {
            SyncService.tryStart(applicationContext)
        }
        return Result.success()
    }
}
