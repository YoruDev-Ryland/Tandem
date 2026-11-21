package dev.tandem.app.ui.feedback

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.staticCompositionLocalOf
import dev.tandem.app.ui.input.Action

/**
 * What a button press felt like, for haptics and the optional UI sounds: the cursor or a tab moved ([Move]), A or a
 * secondary action did something ([Confirm]), B went back ([Back]), or nothing could happen: the list's end, an
 * action this screen doesn't offer ([Reject]).
 */
enum class Cue { Move, Confirm, Back, Reject }

/** Plays cues; the activity's player turns them into haptics and sounds by the user's settings. */
fun interface CuePlayer {
    fun play(cue: Cue)
}

/**
 * The cue for one routed press. [explicit] is what a handler said while it ran (a list at its end says [Cue.Reject];
 * one that moved says [Cue.Move]); otherwise the action and whether anyone took it decide. A held direction repeats:
 * it ticks with every step but never buzzes against the end of a list again and again ([repeat]). B that nobody took
 * leaves the screen through the system, which has its own feel, so it gets none.
 */
fun cueFor(action: Action, handled: Boolean, explicit: Cue?, repeat: Boolean): Cue? {
    if (repeat && (explicit == Cue.Reject || (!handled && explicit == null))) return null
    if (explicit != null) return explicit
    if (!handled) return if (action == Action.B) null else Cue.Reject
    return when (action) {
        Action.A, Action.X, Action.Y -> Cue.Confirm
        Action.B -> Cue.Back
        Action.Up, Action.Down, Action.Left, Action.Right, Action.L1, Action.R1, Action.L2, Action.R2 -> Cue.Move
    }
}

/**
 * The haptic for a cue: a light clock tick per cursor step, a firmer confirm, the platform's reject. Played through
 * View.performHapticFeedback without FLAG_IGNORE_GLOBAL_SETTING, so the system's touch-feedback setting still rules.
 */
fun hapticFor(cue: Cue): Int = when (cue) {
    Cue.Move -> HapticFeedbackConstants.CLOCK_TICK
    Cue.Back -> HapticFeedbackConstants.CLOCK_TICK
    Cue.Confirm -> HapticFeedbackConstants.CONFIRM
    Cue.Reject -> HapticFeedbackConstants.REJECT
}

/**
 * Collects cues around routed presses. A handler that knows better than the default (a list at its end) calls [cue]
 * while it runs; [routed] then plays one cue for the whole press. A cue outside a press (a tap on a row) plays at once.
 */
class CueSink(private val player: () -> CuePlayer) {
    private var depth = 0
    private var explicit: Cue? = null

    fun cue(c: Cue) {
        if (depth == 0) player().play(c) else if (explicit == null || c == Cue.Reject) explicit = c
    }

    fun routed(action: Action, repeat: Boolean, route: () -> Boolean): Boolean {
        val outer = explicit
        depth++
        explicit = null
        val handled = try { route() } finally { depth-- }
        val said = explicit
        explicit = outer
        cueFor(action, handled, said, repeat)?.let { player().play(it) }
        return handled
    }
}

/** The scaffold's sink; null outside one (and in tests that don't care), where handlers just skip their cues. */
val LocalCueSink = staticCompositionLocalOf<CueSink?> { null }

/** The player the scaffold plays through: silent unless the activity provides its haptics and sounds. */
val LocalCuePlayer = staticCompositionLocalOf { CuePlayer {} }
