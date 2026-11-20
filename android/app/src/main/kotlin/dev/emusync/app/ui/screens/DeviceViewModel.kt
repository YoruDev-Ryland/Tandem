package dev.emusync.app.ui.screens

import dev.emusync.app.api.ApiException
import dev.emusync.app.model.DeviceGameState
import dev.emusync.app.model.GameRow
import dev.emusync.app.sync.WaitingDelivery
import dev.emusync.app.ui.kit.Tone
import dev.emusync.app.ui.state.AppState
import dev.emusync.app.ui.state.EmuRowState
import dev.emusync.app.ui.state.EmulatorRowUi
import dev.emusync.app.ui.state.Loaded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One game in the right pane: GAME, NEWEST ON and HERE, with HERE's tone. */
data class GameLine(val key: String, val name: String, val newestOn: String, val here: String, val tone: Tone)

/** "Close {emulator} to get the newer {game} save from {device}." */
data class WaitLine(val emulator: String, val game: String, val device: String) {
    val close: String get() = "Close $emulator to get the newer $game save from $device."
    val rest: String get() = "Nothing is written while $emulator is open."
}

/** One emulator: its row on the left, and its games and waiting deliveries for the right pane. */
data class EmuPane(val id: String, val name: String, val state: String, val tone: Tone, val games: List<GameLine>, val waiting: List<WaitLine>)

/** Everything the "This device" tab shows. [loaded] is false until the first games list arrives. */
data class DeviceUi(val signedIn: Boolean, val panes: List<EmuPane>, val loaded: Boolean, val error: ApiException?)

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
                EmuRowState.Ok -> "no saves yet" to Tone.Ink2
            }
        }
        val lines = mine.map { g ->
            val (text, t) = hereCopy(here(g), emu.name)
            GameLine(g.gameKey, g.displayName ?: g.gameKey, g.newestOnDeviceName.orEmpty(), text, t)
        }
        EmuPane(emu.id, emu.name, state, tone, lines, waits.values.toList())
    }
    return DeviceUi(signedIn, panes, loaded = games.value != null, error = if (signedIn) games.error else null)
}

/**
 * Feeds [deviceUi] from the app state and the server's games. Not an androidx ViewModel: the activity's AppViewModel
 * owns it, so it lives as long as the AppState it reads.
 */
class DeviceViewModel(app: AppState, games: StateFlow<Loaded<List<GameRow>>>, deviceId: () -> String?, scope: CoroutineScope) {
    val ui: StateFlow<DeviceUi> = combine(app.emulators, games, app.waiting, app.top) { emus, g, w, top ->
        deviceUi(emus, g, w, deviceId(), top.signedIn)
    }.stateIn(scope, SharingStarted.Eagerly, DeviceUi(false, emptyList(), false, null))
}
