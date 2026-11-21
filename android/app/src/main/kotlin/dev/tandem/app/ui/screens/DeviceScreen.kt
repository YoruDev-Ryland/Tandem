package dev.tandem.app.ui.screens

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tandem.app.api.encodeKey
import dev.tandem.app.api.encodeSegment
import dev.tandem.app.service.AppGraph
import dev.tandem.app.service.SyncService
import dev.tandem.app.sync.StateStore
import dev.tandem.app.ui.feedback.Cue
import dev.tandem.app.ui.feedback.LocalCueSink
import dev.tandem.app.ui.input.Action
import dev.tandem.app.ui.input.OnAction
import dev.tandem.app.ui.kit.Cell
import dev.tandem.app.ui.kit.ConsoleScaffold
import dev.tandem.app.ui.kit.CursorList
import dev.tandem.app.ui.kit.EmptyLine
import dev.tandem.app.ui.kit.EmuButton
import dev.tandem.app.ui.kit.ErrorLine
import dev.tandem.app.ui.kit.Hint
import dev.tandem.app.ui.kit.MARK_W
import dev.tandem.app.ui.kit.PRESENCE_W
import dev.tandem.app.ui.kit.PresenceMark
import dev.tandem.app.ui.kit.SystemMark
import dev.tandem.app.ui.kit.Tone
import dev.tandem.app.ui.theme.EmuType
import dev.tandem.app.ui.theme.LocalEmu

/** What the tab asks its host to do. */
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

    val hints = if (ui.revoked) listOf(Hint(Action.A, "Sign in again", actions.pairAgain), Hint(Action.B, "Back", actions.back)) else listOf(
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
    ) {
        // Removed on the server: nothing here syncs any more, so the only thing to show is that, and the way back.
        if (ui.revoked) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                BasicText(REVOKED_LINE, Modifier.padding(top = 4.dp, bottom = 8.dp), style = EmuType.title.copy(color = LocalEmu.current.fault))
                BasicText(REVOKED_REST, Modifier.widthIn(max = 560.dp).padding(bottom = 16.dp), style = EmuType.body.copy(color = LocalEmu.current.ink2))
                EmuButton("Sign in again", primary = true, glyph = Action.A, onClick = actions.pairAgain)
            }
            return@ConsoleScaffold
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val portrait = maxWidth < 600.dp
            val emuWidth = emulatorsWidth(maxWidth, presenceColumns(ui))
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
                        parked = inGames,
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
        // Right goes into the games, Left comes back; where there is nowhere to go, the press pushes back.
        val cues = LocalCueSink.current
        OnAction { a ->
            when (a) {
                Action.Right -> { if (!inGames && ui.signedIn && games.isNotEmpty()) enterGames() else cues?.cue(Cue.Reject); true }
                Action.Left -> { if (inGames) pane = Pane.Emulators else cues?.cue(Cue.Reject); true }
                else -> false
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
            val matrix = presenceColumns(ui) > 0
            Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val h = EmuType.header.copy(color = c.ink3)
                BasicText("GAME", Modifier.padding(start = MARK_W + 14.dp).weight(1f), style = h)
                // One column per other device, headed by its name: the presence marks say what each one holds.
                if (matrix) for (d in ui.devices) {
                    BasicText(d.name.uppercase(), Modifier.width(PRESENCE_W), style = h, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    BasicText("NEWEST ON", Modifier.width(NEWEST_W), style = h)
                }
                BasicText("HERE", Modifier.width(HERE_W), style = h)
            }
            CursorList(games, key = { it.key }, selected = selected, onSelect = onSelect, onOpen = onOpen,
                modifier = Modifier.weight(1f, fill = false), active = active) { g, _ ->
                SystemMark(g.system)
                Cell(g.name, Modifier.weight(1f), style = EmuType.rowPrimary)
                if (matrix) for (p in g.presence) PresenceMark(p.presence, p.description)
                else Cell(g.newestOn, Modifier.width(NEWEST_W), tone = g.newestTone, style = EmuType.meta)
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

/** At most this many other devices get a presence column each; with more, NEWEST ON names the newest one instead. */
internal const val MAX_PRESENCE = 3

/** How many presence columns the games table shows: one per other device, none when there are none or too many. */
internal fun presenceColumns(ui: DeviceUi): Int = ui.devices.size.takeIf { it in 1..MAX_PRESENCE } ?: 0

/**
 * What the games table needs to stay readable: row padding, the column gaps, the system mark, a presence column per
 * other device (or NEWEST ON), HERE and a 180 dp GAME.
 */
private fun gamesMin(presence: Int): Dp {
    val middle = if (presence > 0) PRESENCE_W * presence else NEWEST_W
    val gaps = 14.dp * (2 + maxOf(1, presence))
    return 24.dp + gaps + MARK_W + middle + HERE_W + 180.dp
}

/**
 * The emulators pane takes what the games table leaves, from 220 dp (the longest name and state) up to the mockup's
 * 380 dp. The Thor and the Odin 3 are 832 dp wide, not the mockup's 960, so a fixed 380 starved the GAME column.
 */
internal fun emulatorsWidth(content: Dp, presence: Int = 1): Dp = (content - 28.dp - gamesMin(presence)).coerceIn(220.dp, 380.dp)
