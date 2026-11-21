package dev.tandem.app.ui.screens

import dev.tandem.app.api.ApiException
import dev.tandem.app.model.DeviceGameState
import dev.tandem.app.model.GameRow
import dev.tandem.app.service.ReceivedAt
import dev.tandem.app.sync.WaitingDelivery
import dev.tandem.app.ui.kit.Tone
import dev.tandem.app.ui.state.AppState
import dev.tandem.app.ui.state.EmuRowState
import dev.tandem.app.ui.state.EmulatorRowUi
import dev.tandem.app.ui.state.Loaded
import dev.tandem.app.ui.state.ServerConn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Duration

/** One game in the right pane: GAME, NEWEST ON and HERE, with the tones of NEWEST ON and HERE. */
data class GameLine(val key: String, val name: String, val newestOn: String, val here: String, val tone: Tone, val newestTone: Tone = Tone.Ink2)

/** "Close {emulator} to get the newer {game} save from {device}." */
data class WaitLine(val emulator: String, val game: String, val device: String) {
    val close: String get() = "Close $emulator to get the newer $game save from $device."
    val rest: String get() = "Nothing is written while $emulator is open."
}

/** "Received {game}": a held-back delivery landed. Shown in ink, with no status colour. */
data class ReceivedLine(val text: String)

/**
 * How long after a delivery lands a games refresh still keeps its "Received" line. The refresh the emulator's exit
 * starts races the apply and may finish just after it; the line should last until the next one (one cycle).
 */
internal val RECEIVED_GRACE: Duration = Duration.ofSeconds(10)

/** One emulator: its row on the left, and its games, waiting and just-received deliveries for the right pane. */
data class EmuPane(
    val id: String,
    val name: String,
    val state: String,
    val tone: Tone,
    val games: List<GameLine>,
    val waiting: List<WaitLine>,
    val received: List<ReceivedLine> = emptyList(),
)

/**
 * Everything the "This device" tab shows. [loaded] is false until the first games list arrives. [revoked]: the server
 * removed this device. [synced] is the "Synced 5 min ago" line for the status area, or null when it never synced.
 */
data class DeviceUi(
    val signedIn: Boolean,
    val panes: List<EmuPane>,
    val loaded: Boolean,
    val error: ApiException?,
    val revoked: Boolean = false,
    val synced: String? = null,
)

const val REVOKED_LINE = "This device was removed from the server."
const val REVOKED_REST = "Sync is off here. Sign in again or pair this device to use it with Tandem."

/** "Synced just now", "Synced 5 min ago", "Synced 3 h ago", else "Synced Nov 19, 22:14"; null when never. */
fun syncedAgo(at: java.time.Instant?, now: java.time.Instant = java.time.Instant.now(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? {
    at ?: return null
    return "Synced " + activityTime(at.toString(), now, zone)
}

private fun plural(n: Int, one: String) = if (n == 1) "1 $one" else "$n ${one}s"

private fun hereCopy(state: DeviceGameState?, emulator: String): Pair<String, Tone> = when (state) {
    DeviceGameState.Newest -> "this is the newest" to Tone.Ink2
    DeviceGameState.HasIt -> "up to date" to Tone.Ink2
    DeviceGameState.Waiting -> "waiting for $emulator to close" to Tone.Wait
    DeviceGameState.Pending -> "on its way" to Tone.Ink2
    DeviceGameState.Behind -> "older save" to Tone.Ink2
    DeviceGameState.Differs -> "two saves · choose in Conflicts" to Tone.Fault
    DeviceGameState.NotInstalled, null -> "not on this device" to Tone.Ink3
}

/**
 * Builds the tab from the emulator rows (registry order), the server's games, the service's waiting deliveries and
 * this device's id. A game waits when the server says so for this device (it does only while the emulator is
 * reported running) or the service holds a delivery for it; the line names the newest device, else "another device".
 */
fun deviceUi(
    emulators: List<EmulatorRowUi>,
    games: Loaded<List<GameRow>>,
    waiting: List<WaitingDelivery>,
    deviceId: String?,
    signedIn: Boolean,
    received: List<ReceivedAt> = emptyList(),
): DeviceUi {
    val rows = if (signedIn) games.value.orEmpty() else emptyList()
    val panes = emulators.map { emu ->
        val mine = rows.filter { it.emulatorId == emu.id }
        val byKey = mine.associateBy { it.gameKey }
        fun here(g: GameRow) = g.devices.firstOrNull { it.deviceId == deviceId }?.state

        // Waiting games, local deliveries first, each game once; a delivery with no game key counts on its own.
        val waits = LinkedHashMap<String, WaitLine>()
        for (w in waiting.filter { it.emulatorId == emu.id }) {
            val row = w.gameKey?.let(byKey::get)
            val game = w.displayName ?: row?.displayName ?: w.gameKey ?: continue
            waits.putIfAbsent(w.gameKey ?: "delivery:${w.deliveryId}", WaitLine(emu.name, game, row?.newestOnDeviceName ?: "another device"))
        }
        for (g in mine.filter { here(it) == DeviceGameState.Waiting }) {
            waits.putIfAbsent(g.gameKey, WaitLine(emu.name, g.displayName ?: g.gameKey, g.newestOnDeviceName ?: "another device"))
        }

        val (state, tone) = when {
            waits.isNotEmpty() -> "Open · ${waits.size} waiting" to Tone.Wait
            else -> when (val s = emu.state) {
                is EmuRowState.Open -> "Open" to Tone.Ink2
                is EmuRowState.Saves -> plural(s.n, "save") to Tone.Ink2
                is EmuRowState.ConfigGroup -> "config group" to Tone.Ink2
                EmuRowState.NotSetUp -> "not set up" to Tone.Ink3
                EmuRowState.Looking -> "looking…" to Tone.Ink3
                EmuRowState.NoShizuku -> "Shizuku off" to Tone.Ink3
                EmuRowState.Idle -> "—" to Tone.Ink3
                EmuRowState.Ok -> "no saves yet" to Tone.Ink2
            }
        }
        val lines = mine.map { g ->
            val (text, t) = hereCopy(here(g), emu.name)
            // No newest device (nothing uploaded yet): a dash in ink3, as the web's Games page shows it.
            val newest = g.newestOnDeviceName
            GameLine(g.gameKey, g.displayName ?: g.gameKey, newest ?: "—", text, t, if (newest == null) Tone.Ink3 else Tone.Ink2)
        }
        // Held deliveries that landed since the last refresh, newest first, each game once; none for a waiting game.
        val updated = games.updatedAt
        val got = LinkedHashMap<String, ReceivedLine>()
        for (r in received.asReversed()) {
            val d = r.delivery
            if (d.emulatorId != emu.id || !signedIn) continue
            if (updated != null && updated >= r.at + RECEIVED_GRACE) continue
            val key = d.gameKey ?: "config"
            if (key in waits) continue
            val name = if (d.gameKey == null) "${emu.name} settings" else d.displayName ?: byKey[d.gameKey]?.displayName ?: d.gameKey
            got.putIfAbsent(key, ReceivedLine("Received $name"))
        }
        EmuPane(emu.id, emu.name, state, tone, lines, waits.values.toList(), got.values.toList())
    }
    return DeviceUi(signedIn, panes, loaded = games.value != null, error = if (signedIn) games.error else null)
}

/**
 * Feeds [deviceUi] from the app state and the server's games. Not an androidx ViewModel: the activity's AppViewModel
 * owns it, so it lives as long as the AppState it reads.
 */
class DeviceViewModel(app: AppState, games: StateFlow<Loaded<List<GameRow>>>, deviceId: () -> String?, scope: CoroutineScope) {
    val ui: StateFlow<DeviceUi> = combine(app.emulators, games, app.waiting, app.top, app.received) { emus, g, w, top, got ->
        deviceUi(emus, g, w, deviceId(), top.signedIn, got).copy(
            revoked = top.server == ServerConn.Revoked,
            synced = if (top.signedIn) syncedAgo(top.lastSyncedAt) else null,
        )
    }.stateIn(scope, SharingStarted.Eagerly, DeviceUi(false, emptyList(), false, null))
}
