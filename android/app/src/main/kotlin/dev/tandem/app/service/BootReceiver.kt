package dev.tandem.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts [SyncService] after boot when this device can sync (signed in, not removed on the server; specialUse may
 * start from BOOT_COMPLETED), and schedules the [Watchdog]. The service waits for Shizuku.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppGraph.store(context).canSync) return
        Watchdog.schedule(context)
        try {
            SyncService.start(context)
        } catch (e: Exception) {
            android.util.Log.w("Tandem", "boot start failed: ${e.message}")
        }
    }
}
