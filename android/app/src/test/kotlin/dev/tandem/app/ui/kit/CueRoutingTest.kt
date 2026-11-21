package dev.tandem.app.ui.kit

import android.view.KeyEvent
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.tandem.app.ui.feedback.Cue
import dev.tandem.app.ui.feedback.CuePlayer
import dev.tandem.app.ui.feedback.LocalCuePlayer
import dev.tandem.app.ui.input.Action
import dev.tandem.app.ui.theme.EmuTheme
import dev.tandem.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Each press through the scaffold plays one cue: a tick per step, a push-back at the end, confirm on A, back on B. */
@RunWith(RobolectricTestRunner::class)
class CueRoutingTest {
    @get:Rule val compose = createComposeRule()

    private val played = mutableListOf<Cue>()
    private var selected by mutableIntStateOf(0)

    private fun show() = compose.setContent {
        EmuTheme(ThemeMode.Dark) {
            CompositionLocalProvider(LocalCuePlayer provides CuePlayer { played += it }) {
                ConsoleScaffold(top = {}, hints = listOf(Hint(Action.B, "Back") {}), status = null) {
                    CursorList(listOf("Eden", "Azahar"), key = { it }, selected = selected, onSelect = { selected = it }, onOpen = {}) { s, _ ->
                        BasicText(s)
                    }
                }
            }
        }
    }

    @Test fun movesTickTheEndPushesBackAndButtonsConfirmOrGoBack() {
        show()
        compose.key(KeyEvent.KEYCODE_DPAD_UP)      // already at the top
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)    // the end
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        compose.key(KeyEvent.KEYCODE_BUTTON_B)
        compose.key(KeyEvent.KEYCODE_BUTTON_X)     // nothing here does X
        assertEquals(listOf(Cue.Reject, Cue.Move, Cue.Reject, Cue.Confirm, Cue.Back, Cue.Reject), played)
    }

    @Test fun holdingADirectionAtTheEndDoesNotBuzzOnEveryRepeat() {
        show()
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN, repeats = 4)   // one step, then repeats against the end
        assertEquals(listOf(Cue.Move), played)
    }
}
