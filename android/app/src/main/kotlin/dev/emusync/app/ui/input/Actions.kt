package dev.emusync.app.ui.input

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
 * The handler tree behind ConsoleScaffold. An action goes to the deepest layer first: every [ActionLayer] inside
 * this one (newest first, each searched the same way) is offered it before this layer's own handlers, which are
 * then tried newest first. So a sheet drawn in its own layer always sees B before the screen under it, however late
 * a list on the screen entered composition. A handler returns true when it consumed the action; a modal one returns
 * true for everything.
 */
class ActionRouter private constructor(private val root: ActionRouter?) {
    constructor() : this(null)

    private val handlers = mutableListOf<(Action) -> Boolean>()
    private val layers = mutableListOf<ActionRouter>()

    /** Text fields that have focus (see Modifier.textEntry), counted once for the whole tree. */
    internal var textFocus: Int
        get() = root?.textFocus ?: ownTextFocus
        set(v) { if (root != null) root.textFocus = v else ownTextFocus = v }
    private var ownTextFocus = 0

    /** Puts [handler] on top of this layer's handlers. Returns its removal. */
    fun register(handler: (Action) -> Boolean): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    /** A layer for use under this one; it receives nothing until [attach]ed. */
    internal fun newLayer(): ActionRouter = ActionRouter(root ?: this)

    /** Puts [layer] above this layer's handlers (and above earlier layers). Returns its removal. */
    internal fun attach(layer: ActionRouter): () -> Unit {
        layers.add(layer)
        return { layers.remove(layer) }
    }

    /** Offers [action] to the deepest layers first, then this layer's handlers newest first; true if one took it. */
    fun dispatch(action: Action): Boolean {
        // Copies: a handler may close its own sheet (and unregister) while it runs.
        for (l in layers.toList().asReversed()) if (l.dispatch(action)) return true
        for (h in handlers.toList().asReversed()) if (h(action)) return true
        return false
    }
}

val LocalActionRouter = staticCompositionLocalOf<ActionRouter?> { null }

/**
 * A layer of input above the content around it: a sheet or dialog wraps its content in one, and every OnAction
 * inside (its own, and any list or tabs it holds) comes before the screen underneath. Draw it inside the
 * scaffold's content, not in a separate window.
 */
@Composable
fun ActionLayer(content: @Composable () -> Unit) {
    val parent = LocalActionRouter.current ?: return content()
    val layer = remember(parent) { parent.newLayer() }
    DisposableEffect(parent, layer) {
        val detach = parent.attach(layer)
        onDispose { detach() }
    }
    CompositionLocalProvider(LocalActionRouter provides layer, content = content)
}

/**
 * Receives actions while this composable is on screen and [enabled]. Within one [ActionLayer], later registrations
 * come first; a sheet's handlers belong in the sheet's own layer. It registers once, when it enters the screen, and
 * toggling [enabled] keeps its place.
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
