package dev.emusync.app.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.emusync.app.ui.input.Action
import dev.emusync.app.ui.input.ActionRouter
import dev.emusync.app.ui.input.KeyRouter
import dev.emusync.app.ui.input.LocalActionRouter
import dev.emusync.app.ui.theme.LocalEmu

/**
 * The console screen: a 52 dp top bar ([top]), an optional sub-tab line ([tabs], usually level-2 BumperTabs), the
 * [content] with 28 dp sides, and the [HintBar] with [status] and [hints].
 *
 * It owns the gamepad. A root onPreviewKeyEvent maps keys to actions and offers each one, in order, to:
 * 1. the handlers registered with OnAction inside it, newest first, so a sheet shown later gets B before the list
 *    under it (CursorList and BumperTabs register themselves);
 * 2. [onAction], the screen's own handler;
 * 3. the hint for that button, as if it were tapped.
 * Keys nobody takes pass on (an unhandled BACK leaves the app). Sheets and dialogs must draw inside [content], not
 * in a separate window, so their keys come through here.
 */
@Composable
fun ConsoleScaffold(
    top: @Composable RowScope.() -> Unit,
    tabs: (@Composable () -> Unit)? = null,
    hints: List<Hint>,
    status: String? = null,
    onAction: (Action) -> Boolean = { false },
    content: @Composable () -> Unit,
) {
    val c = LocalEmu.current
    val router = remember { ActionRouter() }
    val screen by rememberUpdatedState(onAction)
    val shown by rememberUpdatedState(hints)
    val keys = remember(router) {
        KeyRouter { a ->
            router.dispatch(a) || screen(a) || shown.firstOrNull { it.action == a }?.let { it.onClick(); true } ?: false
        }
    }
    // The root holds focus so key events have somewhere to go; it takes it back whenever nothing inside has it.
    val focus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(hasFocus) { if (!hasFocus) focus.requestFocus() }

    CompositionLocalProvider(LocalActionRouter provides router) {
        Column(
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .onPreviewKeyEvent { e ->
                    val down = when (e.type) {
                        KeyEventType.KeyDown -> true
                        KeyEventType.KeyUp -> false
                        else -> return@onPreviewKeyEvent false
                    }
                    val n = e.nativeKeyEvent
                    keys.onKey(n.keyCode, down, n.repeatCount, inTextField = router.textFocus > 0)
                }
                .onFocusChanged { hasFocus = it.hasFocus }
                .focusRequester(focus)
                .focusTarget()
                .safeDrawingPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                content = top,
            )
            if (tabs != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp), contentAlignment = Alignment.CenterStart) { tabs() }
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 4.dp)) { content() }
            HintBar(status, hints)
        }
    }
}
