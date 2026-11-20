package dev.emusync.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.emusync.app.MainActivity
import dev.emusync.app.sync.SyncConflict
import java.util.concurrent.atomic.AtomicInteger

/**
 * Notification channels and ids. `sync` (low importance) carries the persistent foreground notification;
 * `alerts` (default importance) carries the "close the emulator", conflict, restore and vault messages.
 */
class Notifier(context: Context) {
    private val ctx = context.applicationContext
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val nextOther = AtomicInteger(0)

    companion object {
        const val CHANNEL_SYNC = "sync"
        const val CHANNEL_ALERTS = "alerts"
        const val ID_STATUS = 1
        const val ID_CLOSE_EMULATOR = 2
        const val ID_CONFLICT = 3
        private const val ID_OTHER_BASE = 100
        private const val ID_OTHER_COUNT = 20
    }

    fun ensureChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, "Sync status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows that EmuSync is watching your emulators"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Sync alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Conflicts, emulators to close, restores and vault files"
            },
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun status(text: String): Notification = Notification.Builder(ctx, CHANNEL_SYNC)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("EmuSync")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(openApp())
        .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        .build()

    fun updateStatus(text: String) = runCatching { nm.notify(ID_STATUS, status(text)) }

    /** An applier message. "Close …" messages share one id (the newest replaces the older). */
    fun alert(message: String) {
        val id = if (message.startsWith("Close ")) ID_CLOSE_EMULATOR
        else ID_OTHER_BASE + Math.floorMod(nextOther.getAndIncrement(), ID_OTHER_COUNT)
        post(id, if (id == ID_CLOSE_EMULATOR) "Close the emulator" else "EmuSync", message)
    }

    fun conflict(c: SyncConflict) {
        val what = when {
            c.kind == null -> "${c.emulatorName} settings"
            else -> "${c.emulatorName} ${c.kind.name.lowercase()} for ${c.displayName ?: c.gameKey}" +
                (if (c.slot.isNotEmpty()) " (slot ${c.slot})" else "")
        }
        post(ID_CONFLICT, "Sync conflict", "$what changed on another device too. Both versions are kept on the server.")
    }

    private fun post(id: Int, title: String, text: String) {
        runCatching {
            nm.notify(
                id,
                Notification.Builder(ctx, CHANNEL_ALERTS)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(openApp())
                    .build(),
            )
        }
    }
}
