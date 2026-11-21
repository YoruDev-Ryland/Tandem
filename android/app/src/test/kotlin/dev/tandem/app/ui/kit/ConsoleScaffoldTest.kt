package dev.tandem.app.ui.kit

import android.view.KeyEvent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import dev.tandem.app.ui.input.textEntry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.tandem.app.ui.input.Action
import dev.tandem.app.ui.input.ActionLayer
import dev.tandem.app.ui.input.OnAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConsoleScaffoldTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aSheetShownLaterGetsBBeforeTheListAndTheScreen() {
        val log = mutableListOf<String>()
        var sheet by mutableStateOf(false)
        compose.host(onAction = { log += "screen $it"; true }) {
            CursorList(listOf("a", "b"), key = { it }, selected = 0, onSelect = {}, onOpen = { log += "open $it" }) { s, _ -> BasicText(s) }
            if (sheet) ActionLayer { OnAction { a -> log += "sheet $a"; true } }
        }
        compose.key(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(listOf("screen B"), log)

        log.clear()
        sheet = true
        compose.waitForIdle()
        compose.key(KeyEvent.KEYCODE_BUTTON_B)
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("sheet B", "sheet A"), log)

        log.clear()
        sheet = false
        compose.waitForIdle()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("open a"), log)
    }

    @Test fun anInactiveListLeavesTheDpadAlone() {
        val log = mutableListOf<Action>()
        compose.host(onAction = { log += it; true }) {
            CursorList(listOf("a", "b"), key = { it }, selected = 0, onSelect = {}, onOpen = {}, active = false) { s, _ -> BasicText(s) }
        }
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(listOf(Action.Down), log)
    }

    @Test fun aFocusedTextFieldKeepsTheDpadButBStillRoutes() {
        val log = mutableListOf<String>()
        var selected = 0
        compose.host(onAction = { log += "screen $it"; true }) {
            Column {
                BasicTextField(rememberTextFieldState(), Modifier.testTag("field").textEntry())
                CursorList(listOf("a", "b"), key = { it }, selected = selected, onSelect = { selected = it }, onOpen = { log += "open $it" }) { s, _ -> BasicText(s) }
            }
        }
        compose.onNodeWithTag("field").requestFocus()
        compose.waitForIdle()
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.key(KeyEvent.KEYCODE_ENTER)
        assertEquals(0, selected)
        assertEquals(emptyList<String>(), log)
        compose.key(KeyEvent.KEYCODE_BACK)
        assertEquals(listOf("screen B"), log)
    }

    @Test fun aListThatFillsInUnderAnOpenSheetStaysUnderIt() {
        val log = mutableListOf<String>()
        var rows by mutableStateOf(emptyList<String>())
        compose.host {
            CursorList(rows, key = { it }, selected = 0, onSelect = {}, onOpen = { log += "open $it" }) { s, _ -> BasicText(s) }
            ActionLayer { OnAction { a -> log += "sheet $a"; true } }
        }
        rows = listOf("a", "b")
        compose.waitForIdle()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("sheet A"), log)
    }

    @Test fun aListThatEntersTheScreenUnderAnOpenSheetStaysUnderIt() {
        val log = mutableListOf<String>()
        var loaded by mutableStateOf(false)
        compose.host {
            // The sheet is open first; then a load swaps the empty line for a list, which registers after the sheet.
            ActionLayer { OnAction { a -> log += "sheet $a"; true } }
            if (loaded) {
                CursorList(listOf("a", "b"), key = { it }, selected = 0, onSelect = {}, onOpen = { log += "open $it" }) { s, _ -> BasicText(s) }
            } else {
                EmptyLine("Nothing yet")
            }
        }
        loaded = true
        compose.waitForIdle()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(listOf("sheet A", "sheet Down"), log)
    }

    @Test fun anInnerLayerComesBeforeItsParentAndClosingItHandsBack() {
        val log = mutableListOf<String>()
        var confirm by mutableStateOf(true)
        compose.host {
            ActionLayer {
                OnAction { a -> log += "sheet $a"; true }
                if (confirm) ActionLayer { OnAction { a -> log += "confirm $a"; a == Action.B } }
            }
        }
        compose.key(KeyEvent.KEYCODE_BUTTON_B)
        compose.key(KeyEvent.KEYCODE_BUTTON_X) // not taken by the confirm: falls to the sheet
        confirm = false
        compose.waitForIdle()
        compose.key(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(listOf("confirm B", "confirm X", "sheet X", "sheet B"), log)
    }
}
