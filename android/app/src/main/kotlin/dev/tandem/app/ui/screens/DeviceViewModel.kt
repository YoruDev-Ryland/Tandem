package dev.tandem.app.ui.screens

import dev.tandem.app.model.DeviceGameState
import dev.tandem.app.model.GameRow
import dev.tandem.app.service.ReceivedAt
import dev.tandem.app.sync.WaitingDelivery
import dev.tandem.app.ui.kit.Tone
import dev.tandem.app.ui.state.EmuShelf
import dev.tandem.app.ui.state.EmuTab
import dev.tandem.app.ui.state.Notice
import dev.tandem.app.ui.state.ShelfUi
import dev.tandem.app.ui.state.railDevices
import dev.tandem.app.ui.state.shelfOrder
import dev.tandem.app.ui.state.spineOf
import dev.tandem.app.ui.state.MAX_RAIL
import dev.tandem.app.ui.state.bareKey
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

/** "Close {emulator} to get the newer {game} save from {device}." */
data class WaitLine(val emulator: String, val game: String, val device: String) {
    val close: String get() = "Close $emulator to get the newer $game save from $device."
    val rest: String get() = "Nothing is written while $emulator is open."
}

/**
 * "{game} from {device} is in place.": a held-back delivery landed, and the amber close line it replaces becomes this
 * ink line, with no status colour.
 */
data class ReceivedLine(val text: String)

/** The received line's words; [from] is the device the save came from, when known. */
fun receivedText(game: String, from: String?, config: Boolean = false): String = when {
    config -> "$game are in place."
    from != null -> "$game from $from is in place."
    else -> "$game is in place."
}

/**
 * The words for a delivery that landed: "Pokémon Sword from Odin 3 is in place.", or "Eden settings are in place."
 * for a config. [row] is the game on the server, for its name and the device the newest save came from.
 */
fun receivedLine(d: dev.tandem.app.sync.ReceivedDelivery, emulator: String, row: GameRow?): String =
    if (d.gameKey == null) receivedText("$emulator settings", null, config = true)
    else receivedText(d.displayName ?: row?.displayName ?: bareKey(d.emulatorId, d.gameKey), row?.newestOnDeviceName)

/**
 * How long after a delivery lands a games refresh still keeps its "Received" line. The refresh the emulator's exit
 * starts races the apply and may finish just after it; the line should last until the next one (one cycle).
 */
internal val RECEIVED_GRACE: Duration = Duration.ofSeconds(10)

const val REVOKED_LINE = "This device was removed from the server."
const val REVOKED_REST = "Sync is off here. Sign in again or pair this device to use it with Tandem."

/** "Synced just now", "Synced 5 min ago", "Synced 3 h ago", else "Synced Nov 19, 22:14"; null when never. */
fun syncedAgo(at: java.time.Instant?, now: java.time.Instant = java.time.Instant.now(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? {
    at ?: return null
    return "Synced " + activityTime(at.toString(), now, zone)
}

private fun plural(n: Int, one: String) = if (n == 1) "1 $one" else "$n ${one}s"

/** An emulator's state after its name in the strip, and its tone; [waiting] counts what waits for it to close. */
private fun emuState(s: EmuRowState, waiting: Int): Pair<String?, Tone> = when {
    waiting > 0 -> "Open · $waiting waiting" to Tone.Wait
    else -> when (s) {
        is EmuRowState.Open -> "Open" to Tone.Ink2
        is EmuRowState.Saves -> plural(s.n, "save") to Tone.Ink2
        is EmuRowState.ConfigGroup -> "config group" to Tone.Ink2
        EmuRowState.NotSetUp -> "not set up" to Tone.Ink3
        EmuRowState.Looking -> "looking…" to Tone.Ink3
        EmuRowState.NoShizuku -> "Shizuku off" to Tone.Ink3
        EmuRowState.Idle -> null to Tone.Ink3
        EmuRowState.Ok -> "no saves yet" to Tone.Ink2
    }
}

/**
 * Builds the Library tab: a shelf per emulator (registry order) from the server's games, the service's waiting and
 * just-received deliveries and this device's id. An emulator not set up here keeps its shelf of the server's games for
 * it, with this device's marks all "not installed". A game waits when the server says so for this device or the
 * service holds a delivery for it; the notice line names the newest device, else "another device".
 */
fun shelfUi(
    emulators: List<EmulatorRowUi>,
    games: Loaded<List<GameRow>>,
    waiting: List<WaitingDelivery>,
    deviceId: String?,
    signedIn: Boolean,
    received: List<ReceivedAt> = emptyList(),
    /** The RetroArch core a game's saves were made with, from this device's own sync state, for its system. */
    core: (emulatorId: String, gameKey: String) -> String? = { _, _ -> null },
    /** This device's name, for its rail row before any game lists it. */
    thisDeviceName: String? = null,
    now: java.time.Instant = java.time.Instant.now(),
): ShelfUi {
    val rows = if (signedIn) games.value.orEmpty() else emptyList()
    val devices = if (signedIn) railDevices(rows, deviceId, thisDeviceName) else emptyList()
    val rail = devices.take(MAX_RAIL)
    val loaded = games.value != null
    val shelves = emulators.map { emu ->
        val mine = shelfOrder(rows.filter { it.emulatorId == emu.id })
        val byKey = mine.associateBy { it.gameKey }
        fun here(g: GameRow) = g.devices.firstOrNull { it.deviceId == deviceId }?.state

        // Waiting games, local deliveries first, each game once; a delivery with no game key is counted on its own.
        val waits = LinkedHashMap<String, WaitLine>()
        var keyless = 0
        for (w in waiting.filter { it.emulatorId == emu.id }) {
            if (w.gameKey == null) { keyless++; continue }
            val row = byKey[w.gameKey]
            val game = w.displayName ?: row?.displayName ?: bareKey(emu.id, w.gameKey)
            waits.putIfAbsent(w.gameKey, WaitLine(emu.name, game, row?.newestOnDeviceName ?: "another device"))
        }
        for (g in mine.filter { here(it) == DeviceGameState.Waiting }) {
            waits.putIfAbsent(g.gameKey, WaitLine(emu.name, g.displayName ?: bareKey(emu.id, g.gameKey), g.newestOnDeviceName ?: "another device"))
        }
        // Held deliveries that landed since the last refresh, newest first, each game once; none for a waiting game.
        val updated = games.updatedAt
        val got = LinkedHashMap<String, ReceivedLine>()
        if (signedIn) for (r in received.asReversed()) {
            val d = r.delivery
            if (d.emulatorId != emu.id) continue
            if (updated != null && updated >= r.at + RECEIVED_GRACE) continue
            val key = d.gameKey ?: "config"
            if (key in waits) continue
            got.putIfAbsent(key, ReceivedLine(receivedLine(d, emu.name, byKey[d.gameKey])))
        }
        val notices = buildList {
            for (w in waits.values) add(Notice(w.close, Tone.Wait, 0, w.rest))
            if (keyless > 0) add(Notice("${plural(keyless, "save")} waiting for ${emu.name} to close", Tone.Wait, 0))
            for (r in got.values) add(Notice(r.text, Tone.Ink, 0))
        }
        val notice = notices.firstOrNull()?.copy(more = notices.size - 1)

        val (state, tone) = emuState(emu.state, waits.size + keyless)
        val tab = EmuTab(emu.id, emu.name, state, tone, dimmed = emu.state == EmuRowState.NotSetUp)
        val spines = mine.map { spineOf(it, deviceId, emu.name, rail, core(emu.id, it.gameKey), devices, now) }
        val empty = if (signedIn && loaded && mine.isEmpty()) "No games for ${emu.name} yet." else null
        EmuShelf(tab, spines, mine.size, notice, empty)
    }
    val start = shelves.indexOfFirst { it.spines.isNotEmpty() }.coerceAtLeast(0)
    return ShelfUi(shelves, start, rail, signedIn, loaded, if (signedIn) games.error else null)
}

/**
 * Feeds [shelfUi] from the app state and the server's games. Not an androidx ViewModel: the activity's AppViewModel
 * owns it, so it lives as long as the AppState it reads.
 */
class DeviceViewModel(
    app: AppState,
    games: StateFlow<Loaded<List<GameRow>>>,
    deviceId: () -> String?,
    scope: CoroutineScope,
    core: (emulatorId: String, gameKey: String) -> String? = { _, _ -> null },
) {
    /** The Library tab's shelves. */
    val ui: StateFlow<ShelfUi> = combine(app.emulators, games, app.waiting, app.top, app.received) { emus, g, w, top, got ->
        shelfUi(emus, g, w, deviceId(), top.signedIn, got, core, top.deviceName).copy(revoked = top.server == ServerConn.Revoked)
    }.stateIn(scope, SharingStarted.Eagerly, ShelfUi(emptyList(), 0, emptyList(), signedIn = false, loaded = false))
}
