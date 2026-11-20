package dev.emusync.app.ui.input

import android.view.KeyEvent

/** The gamepad and keyboard mapping (plan Global Constraints: Input). Keys outside the table are not actions. */
fun keyToAction(keyCode: Int): Action? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_UP -> Action.Up
    KeyEvent.KEYCODE_DPAD_DOWN -> Action.Down
    KeyEvent.KEYCODE_DPAD_LEFT -> Action.Left
    KeyEvent.KEYCODE_DPAD_RIGHT -> Action.Right
    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> Action.A
    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> Action.B
    KeyEvent.KEYCODE_BUTTON_X -> Action.X
    KeyEvent.KEYCODE_BUTTON_Y -> Action.Y
    KeyEvent.KEYCODE_BUTTON_L1 -> Action.L1
    KeyEvent.KEYCODE_BUTTON_R1 -> Action.R1
    KeyEvent.KEYCODE_BUTTON_L2 -> Action.L2
    KeyEvent.KEYCODE_BUTTON_R2 -> Action.R2
    else -> null
}

/** Only movement repeats while held: holding A must not open twice, and holding R1 must not race through tabs. */
val Action.repeats: Boolean
    get() = this == Action.Up || this == Action.Down || this == Action.Left || this == Action.Right

/**
 * Turns key events into routed actions. It acts on key down; the matching key up (and any ignored repeats) of a key
 * it consumed are consumed too, so a BACK whose down opened nothing still reaches the activity, and one whose down
 * was handled does not.
 */
class KeyRouter(private val route: (Action) -> Boolean) {
    private val held = mutableSetOf<Int>()

    /**
     * @param inTextField a text field has focus: only B (BUTTON_B, BACK, ESCAPE) is routed, everything else is left
     *   to the field (D-pad moves the caret, ENTER is the IME action).
     * @return true if the event was consumed.
     */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int, inTextField: Boolean): Boolean {
        if (!down) return held.remove(keyCode)
        val action = keyToAction(keyCode) ?: return false
        if (inTextField && action != Action.B) return false
        if (repeatCount > 0 && !action.repeats) return keyCode in held
        val handled = route(action)
        if (handled) held += keyCode else held -= keyCode
        return handled
    }
}
