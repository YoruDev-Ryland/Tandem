package dev.emusync.app.ui.kit

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.performSemanticsAction
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

    @Test fun aButtonWithAHintRunsOnlyTheHintAndTheScreenGetsTheRest() {
        val screen = mutableListOf<Action>()
        // The screen claims nothing: X must still run once (the hint), never also reach the screen.
        compose.host(hints, onAction = { screen += it; false }) {}
        compose.key(KeyEvent.KEYCODE_BUTTON_X)
        compose.key(KeyEvent.KEYCODE_BUTTON_B)
        compose.key(KeyEvent.KEYCODE_BUTTON_L2)
        assertEquals(listOf("Sync now", "Back"), clicked)
        assertEquals(listOf(Action.L2), screen)
    }

    @Test fun kitControlsNeverTakeKeyboardFocus() {
        var page = 0
        compose.host(hints) {
            // A gamepad or keyboard puts Android in keyboard input mode, where clickables are focusable by default.
            val mode = LocalInputModeManager.current
            SideEffect { mode.requestInputMode(InputMode.Keyboard) }
            Column {
                BumperTabs(listOf("One", "Two"), page, { page = it })
                CursorList(listOf("a", "b"), key = { it }, selected = 0, onSelect = {}, onOpen = {}, active = false) { s, _ -> BasicText(s) }
                EmuButton("Keep both") {}
            }
        }
        // TAB and unhandled arrows are Compose's focus navigation; none of it may land on a kit control.
        repeat(6) { compose.key(KeyEvent.KEYCODE_TAB) }
        compose.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onAllNodes(isFocused()).assertCountEquals(0)
    }

    @Test fun kitControlsKeepTheirAccessibilityClick() {
        var opened = ""
        var tab = 0
        var button = 0
        compose.host(hints) {
            Column {
                BumperTabs(listOf("One", "Two"), tab, { tab = it })
                CursorList(listOf("a", "b"), key = { it }, selected = 0, onSelect = {}, onOpen = { opened = it }) { s, _ -> BasicText(s) }
                EmuButton("Keep both") { button++ }
            }
        }
        compose.onNodeWithText("Sync now").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Two").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("a").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Keep both").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("Sync now"), clicked)
        assertEquals(1, tab)
        assertEquals("a", opened)
        assertEquals(1, button)
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
