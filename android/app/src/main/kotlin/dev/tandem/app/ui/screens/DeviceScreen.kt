package dev.tandem.app.ui.screens

import android.content.Context
import dev.tandem.app.api.encodeKey
import dev.tandem.app.api.encodeSegment
import dev.tandem.app.service.AppGraph
import dev.tandem.app.service.SyncService
import dev.tandem.app.sync.StateStore

/** What the Library tab ([ShelfScreen]) asks its host to do. */
class DeviceActions(
    val openGame: (emulatorId: String, gameKey: String) -> Unit = { _, _ -> },
    val syncNow: () -> Unit = {},
    val settings: (emulatorId: String) -> Unit = {},
    val setup: () -> Unit = {},
    /** Removed on the server: sign out (nothing on the device is deleted) and open sign-in. */
    val pairAgain: () -> Unit = {},
    val back: () -> Unit = {},
)

/**
 * Sync now: a scan for local changes, then an apply of what the server has for this device. Signed out it does
 * nothing (sync stays stopped until a sign-in), and a start Android refuses is logged, not thrown.
 */
fun syncNow(context: Context, store: StateStore = AppGraph.store(context)) {
    if (!store.canSync) return
    SyncService.tryStart(context, SyncService.ACTION_SCAN)
    SyncService.tryStart(context, SyncService.ACTION_APPLY)
}

/** The web page of a game, encoded like the web app's own gamePath. */
fun gameUrl(serverUrl: String, emulatorId: String, gameKey: String): String =
    "${serverUrl.trimEnd('/')}/games/${encodeSegment(emulatorId)}/${encodeKey(gameKey)}"
