package dev.tandem.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts [SyncService] after boot, and after an app update replaced this app (its process was killed), when this
 * device can sync (signed in, not removed on the server; specialUse may start from BOOT_COMPLETED and
 * MY_PACKAGE_REPLACED), and schedules the [Watchdog]. The service waits for Shizuku.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            LogBuffer.add("app updated")
            Notifier(context).cancelUpdate()   // "ready to install" is done
        }
        if (!AppGraph.store(context).canSync) return
        Watchdog.schedule(context)
        try {
            SyncService.start(context)
        } catch (e: Exception) {
            android.util.Log.w("Tandem", "start after ${action.substringAfterLast('.')} failed: ${e.message}")
        }
    }
}
