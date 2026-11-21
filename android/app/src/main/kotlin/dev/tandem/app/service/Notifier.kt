package dev.tandem.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.tandem.app.MainActivity
import dev.tandem.app.sync.SyncConflict
import java.util.concurrent.atomic.AtomicInteger

/** The web app's Configs page, where a config conflict is chosen (the device has no config conflict view). */
fun configsUrl(serverUrl: String): String = "${serverUrl.trimEnd('/')}/configs"

/**
 * Where tapping a conflict notification goes: a config conflict opens the web Configs page in the browser, as A opens
 * web pages elsewhere in the app; a save conflict (or no server) opens the app, whose Conflicts tab lists it.
 */
internal fun conflictTarget(ctx: Context, c: SyncConflict, serverUrl: String?): Intent =
    if (c.kind == null && !serverUrl.isNullOrBlank()) Intent(Intent.ACTION_VIEW, Uri.parse(configsUrl(serverUrl))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    else Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Notification channels and ids. `sync` (low importance) carries the persistent foreground notification;
 * `alerts` (default importance) carries the "close the emulator", conflict, restore and vault messages.
 * [serverUrl] is read when a config conflict is posted, for its link to the web.
 */
class Notifier(context: Context, private val serverUrl: () -> String? = { null }) {
    private val ctx = context.applicationContext
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val nextOther = AtomicInteger(0)

    companion object {
        const val CHANNEL_SYNC = "sync"
        const val CHANNEL_ALERTS = "alerts"
        const val ID_STATUS = 1
        const val ID_CLOSE_EMULATOR = 2
        const val ID_CONFLICT = 3
        const val ID_REVOKED = 4
        private const val ID_OTHER_BASE = 100
        private const val ID_OTHER_COUNT = 20
        const val REVOKED_TITLE = "This device was removed from the server"
        const val REVOKED_TEXT = "Tandem stopped syncing here. Open Tandem to sign in again or pair this device."
    }

    fun ensureChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SYNC, "Sync status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows that Tandem is watching your emulators"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Sync alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Conflicts, emulators to close, restores and vault files"
            },
        )
    }

    private fun openApp(): PendingIntent = pending(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), 0)

    private fun pending(i: Intent, code: Int): PendingIntent =
        PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun status(text: String): Notification = Notification.Builder(ctx, CHANNEL_SYNC)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("Tandem")
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
        post(id, if (id == ID_CLOSE_EMULATOR) "Close the emulator" else "Tandem", message)
    }

    fun conflict(c: SyncConflict) {
        val what = when {
            c.kind == null -> "${c.emulatorName} settings"
            else -> "${c.emulatorName} ${c.kind.name.lowercase()} for ${c.displayName ?: c.gameKey}" +
                (if (c.slot.isNotEmpty()) " (slot ${c.slot})" else "")
        }
        val next = if (c.kind == null) " Choose one on the web's Configs page." else ""
        post(ID_CONFLICT, "Sync conflict", "$what changed on another device too. Both versions are kept on the server.$next",
            pending(conflictTarget(ctx, c, serverUrl()), 1))
    }

    /** Once, when the server first answers device_revoked: the persistent state lives in the app. */
    fun revoked() = post(ID_REVOKED, REVOKED_TITLE, REVOKED_TEXT)

    private fun post(id: Int, title: String, text: String, open: PendingIntent = openApp()) {
        runCatching {
            nm.notify(
                id,
                Notification.Builder(ctx, CHANNEL_ALERTS)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setContentIntent(open)
                    .build(),
            )
        }
    }
}
