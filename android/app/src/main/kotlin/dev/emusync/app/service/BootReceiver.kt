package dev.emusync.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts [SyncService] after boot when this device is signed in (specialUse may start from BOOT_COMPLETED); the
 * service waits for Shizuku.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (AppGraph.store(context).deviceKey == null) return
        try {
            SyncService.start(context)
        } catch (e: Exception) {
            android.util.Log.w("EmuSync", "boot start failed: ${e.message}")
        }
    }
}
