package dev.tandem.app.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.tandem.app.ui.feedback.CueSink
import dev.tandem.app.ui.feedback.LocalCuePlayer
import dev.tandem.app.ui.feedback.LocalCueSink
import dev.tandem.app.ui.input.Action
import dev.tandem.app.ui.input.ActionRouter
import dev.tandem.app.ui.input.KeyRouter
import dev.tandem.app.ui.input.LocalActionRouter
import dev.tandem.app.ui.theme.LocalEmu

/**
 * The console screen: a 52 dp top bar ([top]), an optional sub-tab line ([tabs], usually level-2 BumperTabs), the
 * [content] with 28 dp sides, and the [HintBar] with [status] and [hints].
 *
 * It owns the gamepad. A root onPreviewKeyEvent maps keys to actions and offers each one, in order, to:
 * 1. the handlers registered with OnAction inside it, deepest ActionLayer first, so a sheet in its own layer gets B
 *    before the list under it (CursorList and BumperTabs register themselves);
 * 2. the hint for that button, as if it were tapped: a button with a hint runs only the hint;
 * 3. [onAction], the screen's own handler, for buttons without a hint.
 * Keys nobody takes pass on (an unhandled BACK leaves the app). Sheets and dialogs wrap their content in an
 * ActionLayer and draw inside [content], not in a separate window, so their keys come through here.
 *
 * Each routed press plays one cue (haptic, and sound when on) through [LocalCuePlayer]: see cueFor.
 *
 * When the page tab changed ([LocalTabEntrance]), the content arrives with a 120 ms fade and an 8 dp slide from the
 * side it came from; the bars stay put and the cursor never animates.
 *
 * Kit controls never take keyboard focus (all key input goes through this routing, and the cursor is the only
 * focus shown), but they keep their click semantics for TalkBack.
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
    val player by rememberUpdatedState(LocalCuePlayer.current)
    val sink = remember { CueSink { player } }
    val press = remember { PressMemo() }
    val keys = remember(router) {
        KeyRouter { a ->
            sink.routed(a, press.repeat) {
                val hint = shown.firstOrNull { it.action == a }
                when {
                    router.dispatch(a) -> true
                    // A button with a hint does exactly what the hint says, once; the screen never sees it as well.
                    hint != null -> { hint.onClick(); true }
                    else -> screen(a)
                }
            }
        }
    }
    val entrance = LocalTabEntrance.current
    val arrive = remember { Animatable(if (entrance.direction == 0) 1f else 0f) }
    LaunchedEffect(Unit) { if (arrive.value < 1f) arrive.animateTo(1f, tween(120, easing = LinearOutSlowInEasing)) }
    // The root holds focus so key events have somewhere to go; it takes it back whenever nothing inside has it.
    val focus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(hasFocus) { if (!hasFocus) focus.requestFocus() }

    CompositionLocalProvider(LocalActionRouter provides router, LocalCueSink provides sink) {
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
                    press.repeat = n.repeatCount > 0
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
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = arrive.value
                        if (entrance.slide) translationX = (1f - arrive.value) * entrance.direction * 8.dp.toPx()
                    }
                    .padding(start = 28.dp, end = 28.dp, top = 4.dp),
            ) { content() }
            HintBar(status, hints)
        }
    }
}

/** Whether the key being routed is a held key's repeat. */
private class PressMemo { var repeat = false }

/**
 * How a page arrives after a tab change: [direction] +1 from the right (the next tab), -1 from the left, 0 not at all
 * (the first screen). [slide] is false while the system's animations are off: then it only fades.
 */
@Immutable
data class TabEntrance(val direction: Int = 0, val slide: Boolean = true)

val LocalTabEntrance = compositionLocalOf { TabEntrance() }
