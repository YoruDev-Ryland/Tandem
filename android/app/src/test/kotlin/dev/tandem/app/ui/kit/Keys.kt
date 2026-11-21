package dev.tandem.app.ui.kit

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyPress
import dev.tandem.app.ui.theme.EmuTheme
import dev.tandem.app.ui.theme.ThemeMode

/** Presses and releases a hardware key at the compose root, with [repeats] auto-repeat downs in between. */
fun ComposeContentTestRule.key(code: Int, repeats: Int = 0) {
    keyDown(code, 0)
    for (n in 1..repeats) keyDown(code, n)
    onRoot().performKeyPress(KeyEvent(NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_UP, code, 0)))
    waitForIdle()
}

fun ComposeContentTestRule.keyDown(code: Int, repeat: Int = 0) {
    onRoot().performKeyPress(KeyEvent(NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_DOWN, code, repeat)))
    waitForIdle()
}

/** A scaffold around [content], which owns the key routing the kit relies on. */
fun ComposeContentTestRule.host(hints: List<Hint> = emptyList(), onAction: (dev.tandem.app.ui.input.Action) -> Boolean = { false }, content: @Composable () -> Unit) {
    setContent {
        EmuTheme(ThemeMode.Dark) {
            ConsoleScaffold(top = {}, hints = hints, status = null, onAction = onAction, content = content)
        }
    }
}
