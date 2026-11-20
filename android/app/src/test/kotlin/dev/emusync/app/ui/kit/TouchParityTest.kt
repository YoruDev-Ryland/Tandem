package dev.emusync.app.ui.kit

import android.view.KeyEvent
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.emusync.app.ui.input.Action
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TouchParityTest {
    @get:Rule val compose = createComposeRule()

    private val clicked = mutableListOf<String>()
    private val hints = listOf(
        Hint(Action.A, "Open") { clicked += "Open" },
        Hint(Action.X, "Sync now") { clicked += "Sync now" },
        Hint(Action.Y, "Emulator settings") { clicked += "Emulator settings" },
        Hint(Action.B, "Back") { clicked += "Back" },
    )

    @Test fun everyHintIsAClickableControlThatRunsItsAction() {
        compose.host(hints) {}
        for (h in hints) {
            val node = compose.onNode(hasText(h.label) and hasClickAction())
            node.assertHasClickAction().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            node.performClick()
        }
        assertEquals(hints.map { it.label }, clicked)
    }

    @Test fun aHintsAccessibleTextIsItsLabel() {
        compose.host(hints) {}
        // The glyph is decoration: the control reads as "Sync now", not "X Sync now".
        compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString("Sync now"))) and hasClickAction())
            .assertExists()
    }

    @Test fun aButtonNothingElseHandledRunsItsHint() {
        compose.host(hints) {}
        compose.key(KeyEvent.KEYCODE_BUTTON_X)
        compose.key(KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(listOf("Sync now", "Emulator settings"), clicked)
    }

    @Test fun theScreenHandlerComesBeforeTheHintFallback() {
        val screen = mutableListOf<Action>()
        compose.host(hints, onAction = { screen += it; it == Action.X }) {}
        compose.key(KeyEvent.KEYCODE_BUTTON_X)
        compose.key(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(listOf(Action.X, Action.B), screen)
        assertEquals(listOf("Back"), clicked)
    }

    @Test fun emuButtonIsATouchTargetThatClicks() {
        var n = 0
        compose.host { EmuButton("Keep both", glyph = Action.Y) { n++ } }
        compose.onNodeWithText("Keep both").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, n)
    }

    @Test fun aDisabledEmuButtonDoesNothing() {
        var n = 0
        compose.host { EmuButton("Keep both", enabled = false) { n++ } }
        compose.onNodeWithText("Keep both").performClick()
        assertEquals(0, n)
    }
}
