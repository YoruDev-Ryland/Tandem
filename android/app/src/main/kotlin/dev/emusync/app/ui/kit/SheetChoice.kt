package dev.emusync.app.ui.kit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.emusync.app.ui.input.Action

/** How long a confirm sheet is on screen before its confirm button acts. */
const val SHEET_SETTLE_MS = 500L

/**
 * The cursor of a sheet that confirms a destructive or overwriting choice (sign out, remove a folder, a choice for
 * every game). It opens on the safe button, so a second press of the button that opened the sheet cancels it; Left
 * and Right move it between confirm (left) and cancel (right). The confirm acts only once the sheet has been on
 * screen for [SHEET_SETTLE_MS], so a quick double press never confirms what nobody has read.
 */
class SheetChoice(private val now: () -> Long) {
    private val shownAt = now()

    /** Whether the cursor is on the confirm button. */
    var onConfirm by mutableStateOf(false)
        private set

    /** Left and Right move the cursor; true when [a] was one of them. */
    fun move(a: Action): Boolean {
        when (a) {
            Action.Left -> onConfirm = true
            Action.Right -> onConfirm = false
            else -> return false
        }
        return true
    }

    /** A: whatever the cursor's button does. */
    fun press(confirm: () -> Unit, cancel: () -> Unit) = if (onConfirm) confirmIfSettled(confirm) else cancel()

    /** The confirm button itself (A on it, or a tap): acts only once the sheet has settled on screen. */
    fun confirmIfSettled(confirm: () -> Unit) {
        if (now() - shownAt >= SHEET_SETTLE_MS) confirm()
    }

    /** The hint bar's A: named after the cursor's button. */
    fun hint(confirmLabel: String, cancelLabel: String, confirm: () -> Unit, cancel: () -> Unit) =
        Hint(Action.A, if (onConfirm) confirmLabel else cancelLabel) { press(confirm, cancel) }

    /** The sheet's own key handling: every button is taken while it is open. */
    fun onAction(a: Action, confirm: () -> Unit, cancel: () -> Unit): Boolean {
        when (a) {
            Action.A -> press(confirm, cancel)
            Action.B -> cancel()
            else -> move(a)
        }
        return true
    }
}

/** A new [SheetChoice] for each sheet that opens ([sheet] is its identity). */
@Composable
fun rememberSheetChoice(sheet: Any?, now: () -> Long): SheetChoice = remember(sheet) { SheetChoice(now) }

/**
 * A confirm sheet's buttons, confirm then cancel. The cursor's button is the filled one and carries the A glyph;
 * cancel shows B while the cursor is on confirm.
 */
@Composable
fun SheetButtons(choice: SheetChoice, confirmLabel: String, cancelLabel: String, confirm: () -> Unit, cancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        EmuButton(confirmLabel, primary = choice.onConfirm, glyph = if (choice.onConfirm) Action.A else null) { choice.confirmIfSettled(confirm) }
        EmuButton(cancelLabel, primary = !choice.onConfirm, glyph = if (choice.onConfirm) Action.B else Action.A, onClick = cancel)
    }
}
