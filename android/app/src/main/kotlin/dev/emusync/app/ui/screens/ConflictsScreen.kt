package dev.emusync.app.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.emusync.app.model.BulkFirstSyncRequest
import dev.emusync.app.model.ResolveConflictRequest
import dev.emusync.app.ui.input.Action
import dev.emusync.app.ui.input.ActionLayer
import dev.emusync.app.ui.input.OnAction
import dev.emusync.app.ui.kit.Cell
import dev.emusync.app.ui.kit.ConsoleScaffold
import dev.emusync.app.ui.kit.CursorList
import dev.emusync.app.ui.kit.EmptyLine
import dev.emusync.app.ui.kit.EmuButton
import dev.emusync.app.ui.kit.ErrorLine
import dev.emusync.app.ui.kit.Fact
import dev.emusync.app.ui.kit.Facts
import dev.emusync.app.ui.kit.Hint
import dev.emusync.app.ui.kit.Tone
import dev.emusync.app.ui.theme.EmuType
import dev.emusync.app.ui.theme.LocalEmu

/** What the tab asks its host to do. */
class ConflictsActions(
    val resolve: (itemId: String, req: ResolveConflictRequest) -> Unit = { _, _ -> },
    val bulk: (calls: List<BulkFirstSyncRequest>) -> Unit = {},
    val back: () -> Unit = {},
)

/**
 * The Conflicts tab: the conflicts this device is part of on the left, the focused one on the right with both saves
 * side by side. A keeps this device's save, X the other device's, Y keeps both (the other as a named copy). With two
 * or more first-sync conflicts, L2 and R2 offer the same choice for all of them, after a confirm sheet.
 */
@Composable
fun ConflictsScreen(
    ui: ConflictsUi,
    top: @Composable RowScope.() -> Unit,
    actions: ConflictsActions,
    now: () -> Long = { SystemClock.uptimeMillis() },
) {
    // The cursor is the focused game, by item id; its index is only where it was, for when that game goes away.
    var focusId by remember { mutableStateOf<String?>(null) }
    var lastAt by remember { mutableIntStateOf(0) }
    // The sheet holds the choice as it was when it opened: a refresh underneath does not change what it sends.
    var sheet by remember { mutableStateOf<BulkChoice?>(null) }
    val seen = remember { Seen() }

    val at = ui.entries.indexOfFirst { it.itemId == focusId }.takeIf { it >= 0 } ?: lastAt.coerceIn(0, maxOf(0, ui.entries.lastIndex))
    val focused = ui.entries.getOrNull(at)
    val bulkOther = ui.bulkMine?.let { ui.bulkOther[focused?.otherId] ?: ui.bulkOther.values.firstOrNull() }

    // When the cursor lands on a game by itself (the decided one left the list), that game must be on screen for a
    // moment before A, X or Y decide it, so a quick second press never decides a game nobody has looked at.
    SideEffect { seen.show(focused?.itemId, now) }
    fun decide(req: (ConflictEntry) -> ResolveConflictRequest) {
        val e = focused ?: return
        if (!seen.settled(e.itemId, now)) return
        actions.resolve(e.itemId, req(e))
    }
    fun select(i: Int) {
        ui.entries.getOrNull(i)?.let { focusId = it.itemId; seen.chosen(it.itemId) }
        lastAt = i
    }
    fun keepMine() = decide { it.keepMine }
    fun confirm() { sheet?.let { actions.bulk(it.calls) }; sheet = null }

    val open = sheet
    val hints = when {
        open != null -> listOf(Hint(Action.A, open.confirm, ::confirm), Hint(Action.B, "Cancel") { sheet = null })
        focused != null -> listOf(
            Hint(Action.A, "Keep ${focused.me.replaceFirstChar { it.lowercase() }}'s", ::keepMine),
            Hint(Action.X, "Keep ${focused.other}'s") { decide { it.keepOther } },
            Hint(Action.Y, "Keep both") { decide { it.keepBoth } },
            Hint(Action.B, "Back", actions.back),
        )
        else -> listOf(Hint(Action.B, "Back", actions.back))
    }
    val status = when {
        focused == null -> null
        ui.busy -> "Sending your choice"
        focused.running -> "${focused.emulator} is open: your choice is applied when it closes"
        else -> "Applied right away"
    }

    ConsoleScaffold(
        top = top,
        hints = hints,
        status = status,
        onAction = { a ->
            when (a) {
                Action.L2 -> { ui.bulkMine?.let { sheet = it }; true }
                Action.R2 -> { bulkOther?.let { sheet = it }; true }
                else -> false
            }
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val portrait = maxWidth < 600.dp
            val list: @Composable (Modifier) -> Unit = { m ->
                Column(m) {
                    ListPane(ui, at, ::select, ui.bulkMine, bulkOther) { sheet = it }
                }
            }
            val detail: @Composable (Modifier) -> Unit = { m ->
                if (focused != null) Column(m) { DetailPane(focused, ui.failure, portrait) }
            }
            if (portrait) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    list(Modifier.fillMaxWidth().weight(1f))
                    detail(Modifier.fillMaxWidth().weight(1f))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    list(Modifier.width(300.dp).fillMaxHeight())
                    detail(Modifier.weight(1f).fillMaxHeight())
                }
            }
            if (open != null) ConfirmSheet(open, ::confirm) { sheet = null }
        }
        // A keeps this device's save rather than "opening" the row: the list's own A does nothing, so a tap on a row
        // never decides. In a layer of its own so it comes before the list however late the list (re)enters the
        // screen; the sheet's layer attaches later still, so the sheet comes before this.
        ActionLayer { OnAction { a -> if (a == Action.A && focused != null) { keepMine(); true } else false } }
    }
}

/** How long a game the cursor moved to by itself is shown before a button decides it. */
private const val SETTLE_MS = 500L

/** When the focused game came on screen, and whether the user put the cursor there. */
private class Seen {
    private var id: String? = null
    private var since = Long.MIN_VALUE
    private var picked: String? = null

    /** The user moved the cursor to [itemId]: they are looking at it. */
    fun chosen(itemId: String) { picked = itemId }

    fun show(itemId: String?, now: () -> Long) {
        if (itemId == id) return
        // The first game shown, and a game the user picked, can be decided at once; one that slid under the cursor waits.
        since = if (id == null || itemId == picked) Long.MIN_VALUE else now()
        id = itemId
        picked = null
    }

    fun settled(itemId: String, now: () -> Long): Boolean = when {
        itemId != id -> itemId == picked   // not drawn yet: only a game the user just picked
        else -> since == Long.MIN_VALUE || now() - since >= SETTLE_MS
    }
}

@Composable
private fun ColumnScope.ListPane(
    ui: ConflictsUi,
    at: Int,
    onSelect: (Int) -> Unit,
    bulkMine: BulkChoice?,
    bulkOther: BulkChoice?,
    onBulk: (BulkChoice) -> Unit,
) {
    val c = LocalEmu.current
    BasicText("Conflicts", Modifier.padding(top = 4.dp, bottom = 12.dp), style = EmuType.title.copy(color = c.ink))
    when {
        !ui.signedIn -> EmptyLine("Sign in to see conflicts.")
        ui.entries.isNotEmpty() -> {
            // A tap on the cursor row does nothing: choices are made with the buttons, never by touching a game.
            CursorList(ui.entries, key = { it.itemId }, selected = at, onSelect = onSelect, onOpen = {},
                modifier = Modifier.weight(1f, fill = false)) { e, _ ->
                Cell(e.game, Modifier.weight(1f), style = EmuType.rowPrimary)
                Cell(e.meta, tone = Tone.Ink2, style = EmuType.meta)
            }
            if (ui.entries.size == 1) {
                BasicText(
                    "Only this game differs. Every other save matched or synced on its own.",
                    Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp),
                    style = EmuType.meta.copy(color = c.ink3),
                )
            }
            if (bulkMine != null) {
                Column(Modifier.padding(top = 16.dp)) {
                    EmuButton(bulkMine.label, glyph = Action.L2, onClick = { onBulk(bulkMine) })
                    if (bulkOther != null) EmuButton(bulkOther.label, glyph = Action.R2, onClick = { onBulk(bulkOther) })
                }
            }
        }
        ui.error != null && !ui.loaded -> ErrorLine(ui.error, Modifier.padding(horizontal = 12.dp))
        ui.loaded -> EmptyLine("Nothing to decide.")
    }
}

@Composable
private fun DetailPane(e: ConflictEntry, failure: Throwable?, portrait: Boolean) {
    val c = LocalEmu.current
    Column(
        Modifier
            .fillMaxSize()
            .then(
                if (portrait) Modifier
                else Modifier.drawBehind { drawLine(c.rule2, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1.dp.toPx()) }
                    .padding(start = 24.dp),
            ),
    ) {
        BasicText("Two different saves", Modifier.padding(top = 4.dp, bottom = 6.dp), style = EmuType.title.copy(color = c.fault))
        BasicText(e.subtitle, Modifier.padding(bottom = 14.dp).widthIn(max = 560.dp), style = EmuType.body.copy(color = c.ink2))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            for (col in e.columns) {
                Column(Modifier.weight(1f)) {
                    BasicText(col.title, Modifier.padding(top = 4.dp, bottom = 10.dp),
                        style = EmuType.section.copy(color = c.ink, fontSize = 17.sp))
                    Facts(listOf(Fact("Received", col.received), Fact("Size", col.size, mono = true)), Modifier.padding(bottom = 14.dp))
                }
            }
        }
        BasicText("Whatever you choose, the other copy stays in history.", style = EmuType.meta.copy(color = c.ink3))
        if (failure != null) ErrorLine(failure, Modifier.padding(top = 14.dp))
    }
}

/**
 * Confirms a bulk choice. It sits in its own input layer and takes every button while open (A confirms, B cancels),
 * and it covers the screen to touch, so nothing underneath can be chosen around it.
 */
@Composable
private fun ConfirmSheet(choice: BulkChoice, confirm: () -> Unit, cancel: () -> Unit) {
    val c = LocalEmu.current
    ActionLayer {
        OnAction { a ->
            when (a) {
                Action.A -> confirm()
                Action.B -> cancel()
                else -> {}
            }
            true
        }
        Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(c.bg)
                    .drawBehind { drawLine(c.rule2, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
                    .padding(top = 20.dp, bottom = 16.dp, start = 12.dp, end = 12.dp),
            ) {
                BasicText(choice.question, style = EmuType.section.copy(color = c.ink, fontSize = 20.sp))
                BasicText(choice.detail, Modifier.padding(top = 6.dp, bottom = 12.dp).widthIn(max = 620.dp),
                    style = EmuType.body.copy(color = c.ink2))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EmuButton(choice.confirm, primary = true, glyph = Action.A, onClick = confirm)
                    EmuButton("Cancel", glyph = Action.B, onClick = cancel)
                }
            }
        }
    }
}
