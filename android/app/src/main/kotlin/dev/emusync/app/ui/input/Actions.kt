package dev.emusync.app.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf

/** What a gamepad button means to the UI. Keys map to these in [keyToAction]; touch reaches the same handlers. */
enum class Action { Up, Down, Left, Right, A, B, X, Y, L1, R1, L2, R2 }

/**
 * The handler stack behind ConsoleScaffold. Handlers register while they are on screen and are offered an action
 * newest first, so a sheet or dialog that appears later sees B before the screen under it. A handler returns true
 * when it consumed the action; a modal one returns true for everything.
 */
class ActionRouter {
    private val stack = mutableListOf<(Action) -> Boolean>()

    /** Text fields that have focus (see Modifier.textEntry); while any does, keys stay with the field. */
    internal var textFocus = 0

    /** Puts [handler] on top of the stack. Returns its removal. */
    fun register(handler: (Action) -> Boolean): () -> Unit {
        stack.add(handler)
        return { stack.remove(handler) }
    }

    /** Offers [action] to the handlers newest first; true if one consumed it. */
    fun dispatch(action: Action): Boolean {
        // A copy: a handler may close its own sheet (and unregister) while it runs.
        for (h in stack.toList().asReversed()) if (h(action)) return true
        return false
    }
}

val LocalActionRouter = staticCompositionLocalOf<ActionRouter?> { null }

/**
 * Receives actions while this composable is on screen and [enabled]. Later registrations come first, so put the
 * call inside the dialog or sheet it belongs to. It registers once, when it enters the screen: toggling [enabled]
 * keeps its place, so a list that fills in under an open sheet does not jump above it.
 */
@Composable
fun OnAction(enabled: Boolean = true, handler: (Action) -> Boolean) {
    val router = LocalActionRouter.current ?: return
    val current by rememberUpdatedState(handler)
    val on by rememberUpdatedState(enabled)
    DisposableEffect(router) {
        val remove = router.register { on && current(it) }
        onDispose { remove() }
    }
}

/**
 * Marks a text field. While it has focus the scaffold leaves keys to it (the D-pad moves the caret, ENTER is the IME
 * action); only B (BUTTON_B, BACK, ESCAPE) is still routed, so a sheet with a field can be closed.
 */
fun Modifier.textEntry(): Modifier = this then TextEntryElement

private data object TextEntryElement : ModifierNodeElement<TextEntryNode>() {
    override fun create() = TextEntryNode()
    override fun update(node: TextEntryNode) {}
}

private class TextEntryNode : Modifier.Node(), FocusEventModifierNode, CompositionLocalConsumerModifierNode {
    /** The router this field counted itself into, while it has focus. */
    private var counted: ActionRouter? = null

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.hasFocus && counted == null) {
            counted = currentValueOf(LocalActionRouter)?.also { it.textFocus++ }
        } else if (!focusState.hasFocus) {
            release()
        }
    }

    override fun onDetach() = release()

    private fun release() {
        counted?.let { it.textFocus-- }
        counted = null
    }
}
