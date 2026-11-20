package dev.emusync.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.emusync.app.api.encodeKey
import dev.emusync.app.api.encodeSegment
import dev.emusync.app.service.AppGraph
import dev.emusync.app.service.SyncService
import dev.emusync.app.sync.StateStore
import dev.emusync.app.ui.input.Action
import dev.emusync.app.ui.input.OnAction
import dev.emusync.app.ui.kit.Cell
import dev.emusync.app.ui.kit.ConsoleScaffold
import dev.emusync.app.ui.kit.CursorList
import dev.emusync.app.ui.kit.EmptyLine
import dev.emusync.app.ui.kit.ErrorLine
import dev.emusync.app.ui.kit.Hint
import dev.emusync.app.ui.kit.Tone
import dev.emusync.app.ui.theme.EmuType
import dev.emusync.app.ui.theme.LocalEmu

/** What the tab asks its host to do. */
class DeviceActions(
    val openGame: (emulatorId: String, gameKey: String) -> Unit = { _, _ -> },
    val syncNow: () -> Unit = {},
    val settings: (emulatorId: String) -> Unit = {},
    val setup: () -> Unit = {},
    val back: () -> Unit = {},
)

/**
 * Sync now: a scan for local changes, then an apply of what the server has for this device. Signed out it does
 * nothing (sync stays stopped until a sign-in), and a start Android refuses is logged, not thrown.
 */
fun syncNow(context: Context, store: StateStore = AppGraph.store(context)) {
    if (store.deviceKey.isNullOrBlank()) return
    SyncService.tryStart(context, SyncService.ACTION_SCAN)
    SyncService.tryStart(context, SyncService.ACTION_APPLY)
}

/** The web page of a game, encoded like the web app's own gamePath. */
fun gameUrl(serverUrl: String, emulatorId: String, gameKey: String): String =
    "${serverUrl.trimEnd('/')}/games/${encodeSegment(emulatorId)}/${encodeKey(gameKey)}"

private enum class Pane { Emulators, Games }

/** Whether the touch that is in progress began on the pane that was not driven; its tap only focuses. */
private class TouchMemo { var fromInactive = false }

/** Runs [block] when a pointer goes down anywhere in this element, before the rows see it; it consumes nothing. */
@Composable
private fun Modifier.onPress(block: () -> Unit): Modifier {
    val current by rememberUpdatedState(block)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) current()
            }
        }
    }
}

/**
 * The "This device" tab: this device's emulators on the left, the focused emulator's games on the right (GAME,
 * NEWEST ON, HERE) and, under them, the deliveries waiting for that emulator to close. The D-pad drives the
 * emulators; Right or A moves into the games, where A opens one, and Left or B comes back.
 */
@Composable
fun DeviceScreen(ui: DeviceUi, top: @Composable RowScope.() -> Unit, actions: DeviceActions) {
    var pane by remember { mutableStateOf(Pane.Emulators) }
    var emuAt by remember { mutableIntStateOf(0) }
    var gameAt by remember { mutableIntStateOf(0) }
    val touch = remember { TouchMemo() }

    val focused = ui.panes.getOrNull(emuAt.coerceAtMost(ui.panes.lastIndex))
    val games = if (ui.signedIn) focused?.games.orEmpty() else emptyList()
    val inGames = pane == Pane.Games && games.isNotEmpty()

    fun enterGames() { if (games.isNotEmpty()) pane = Pane.Games }
    fun openEmulator() = if (ui.signedIn) enterGames() else actions.setup()
    fun openGame(g: GameLine) { focused?.let { actions.openGame(it.id, g.key) } }

    val hints = listOf(
        Hint(Action.A, if (ui.signedIn) "Open" else "Sign in") {
            when {
                !ui.signedIn -> actions.setup()
                inGames -> games.getOrNull(gameAt)?.let(::openGame)
                else -> enterGames()
            }
        },
        Hint(Action.X, "Sync now", actions.syncNow),
        Hint(Action.Y, "Emulator settings") { focused?.let { actions.settings(it.id) } },
        Hint(Action.B, "Back") { if (inGames) pane = Pane.Emulators else actions.back() },
    )

    ConsoleScaffold(
        top = top,
        hints = hints,
        onAction = { a ->
            when (a) {
                Action.Right -> { if (!inGames && ui.signedIn) enterGames(); true }
                Action.Left -> { pane = Pane.Emulators; true }
                else -> false
            }
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val portrait = maxWidth < 600.dp
            val emuWidth = emulatorsWidth(maxWidth)
            val emulators: @Composable (Modifier) -> Unit = { m ->
                Column(m.onPress { touch.fromInactive = inGames; pane = Pane.Emulators }) {
                    BasicText("Emulators", Modifier.padding(top = 4.dp, bottom = 12.dp), style = EmuType.title.copy(color = LocalEmu.current.ink))
                    CursorList(
                        ui.panes,
                        key = { it.id },
                        selected = emuAt,
                        onSelect = { emuAt = it; gameAt = 0; touch.fromInactive = false },
                        onOpen = { if (touch.fromInactive) touch.fromInactive = false else openEmulator() },
                        modifier = Modifier.weight(1f),
                        active = !inGames,
                    ) { p, _ ->
                        Cell(p.name, Modifier.weight(1f), style = EmuType.rowPrimary.copy(fontSize = 16.sp))
                        Cell(p.state, tone = p.tone, style = EmuType.meta)
                    }
                }
            }
            val gamesPane: @Composable (Modifier) -> Unit = { m ->
                Column(m.onPress { touch.fromInactive = !inGames; if (games.isNotEmpty()) pane = Pane.Games }) {
                    GamesPane(
                        ui, focused, games, gameAt, inGames,
                        onSelect = { gameAt = it; touch.fromInactive = false },
                        onOpen = { g -> if (touch.fromInactive) touch.fromInactive = false else openGame(g) },
                    )
                }
            }
            if (portrait) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    emulators(Modifier.fillMaxWidth().weight(1f))
                    gamesPane(Modifier.fillMaxWidth().weight(1f))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    emulators(Modifier.width(emuWidth).fillMaxHeight())
                    gamesPane(Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
        // Registered after the lists, so it sees every action first: a key press ends any touch in progress.
        OnAction { touch.fromInactive = false; false }
    }
}

@Composable
private fun ColumnScope.GamesPane(
    ui: DeviceUi,
    emu: EmuPane?,
    games: List<GameLine>,
    selected: Int,
    active: Boolean,
    onSelect: (Int) -> Unit,
    onOpen: (GameLine) -> Unit,
) {
    val c = LocalEmu.current
    if (emu == null) return
    val heading = if (ui.signedIn && ui.loaded) "${emu.name} · ${games.size} ${if (games.size == 1) "game" else "games"}" else emu.name
    BasicText(heading, Modifier.padding(top = 4.dp, bottom = 12.dp), style = EmuType.section.copy(color = c.ink2))
    when {
        !ui.signedIn -> EmptyLine("Sign in to see your games.")
        games.isNotEmpty() -> {
            Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val h = EmuType.header.copy(color = c.ink3)
                BasicText("GAME", Modifier.weight(1f), style = h)
                BasicText("NEWEST ON", Modifier.width(NEWEST_W), style = h)
                BasicText("HERE", Modifier.width(HERE_W), style = h)
            }
            CursorList(games, key = { it.key }, selected = selected, onSelect = onSelect, onOpen = onOpen,
                modifier = Modifier.weight(1f, fill = false), active = active) { g, _ ->
                Cell(g.name, Modifier.weight(1f), style = EmuType.rowPrimary)
                Cell(g.newestOn, Modifier.width(NEWEST_W), tone = g.newestTone, style = EmuType.meta)
                Cell(g.here, Modifier.width(HERE_W), tone = g.tone, style = EmuType.meta)
            }
        }
        ui.error != null && !ui.loaded -> ErrorLine(ui.error, Modifier.padding(horizontal = 12.dp))
        ui.loaded -> EmptyLine("No games here yet. Play one and it shows up after the next sync.")
    }
    for (r in emu.received) {
        BasicText(r.text, Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp).widthIn(max = 460.dp),
            style = EmuType.meta.copy(color = c.ink))
    }
    for (w in emu.waiting) {
        BasicText(
            buildAnnotatedString {
                withStyle(SpanStyle(color = c.wait, fontWeight = FontWeight.SemiBold)) { append(w.close) }
                append(" ")
                append(w.rest)
            },
            Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp).widthIn(max = 460.dp),
            style = EmuType.meta.copy(color = c.ink2),
        )
    }
}

private val NEWEST_W = 96.dp
private val HERE_W = 192.dp   // the longest copy, "two saves · choose in Conflicts", whole even in SemiBold on the cursor row

/** What the games table needs to stay readable: row padding, column gaps, NEWEST ON, HERE and a 200 dp GAME. */
private val GAMES_MIN = 24.dp + 28.dp + NEWEST_W + HERE_W + 200.dp

/**
 * The emulators pane takes what the games table leaves, from 220 dp (the longest name and state) up to the mockup's
 * 380 dp. The Thor and the Odin 3 are 832 dp wide, not the mockup's 960, so a fixed 380 starved the GAME column.
 */
internal fun emulatorsWidth(content: Dp): Dp = (content - 28.dp - GAMES_MIN).coerceIn(220.dp, 380.dp)
