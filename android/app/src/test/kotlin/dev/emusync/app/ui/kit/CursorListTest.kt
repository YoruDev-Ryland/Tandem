package dev.emusync.app.ui.kit

import android.view.KeyEvent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CursorListTest {
    @get:Rule val compose = createComposeRule()

    private var items by mutableStateOf(listOf("Eden", "Azahar", "melonDS", "RetroArch", "Dolphin"))
    private var selected by mutableIntStateOf(0)
    private val opened = mutableListOf<String>()

    private fun show() = compose.host {
        CursorList(items = items, key = { it }, selected = selected, onSelect = { selected = it }, onOpen = { opened += it }) { item, _ ->
            BasicText(item)
        }
    }

    @Test fun dpadDownMovesTheSelectionAndAOpensIt() {
        show()
        compose.onNodeWithText("Eden").assertIsSelected()
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(1, selected)
        compose.onNodeWithText("Azahar").assertIsSelected()
        compose.onNodeWithText("Eden").assertIsNotSelected()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("Azahar"), opened)
    }

    @Test fun movementStopsAtEitherEnd() {
        show()
        compose.key(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(0, selected)
        repeat(7) { compose.key(KeyEvent.KEYCODE_DPAD_DOWN) }
        assertEquals(4, selected)
    }

    @Test fun aHeldDpadKeepsMovingButAHeldADoesNotReopen() {
        show()
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN, repeats = 2)
        assertEquals(3, selected)
        compose.key(KeyEvent.KEYCODE_BUTTON_A, repeats = 3)
        assertEquals(listOf("RetroArch"), opened)
    }

    @Test fun enterAndDpadCenterOpenToo() {
        show()
        compose.key(KeyEvent.KEYCODE_ENTER)
        compose.key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(listOf("Eden", "Eden"), opened)
    }

    @Test fun tappingARowSelectsItAndTappingTheSelectedRowOpensIt() {
        show()
        compose.onNodeWithText("melonDS").performClick()
        assertEquals(2, selected)
        assertEquals(emptyList<String>(), opened)
        compose.onNodeWithText("melonDS").performClick()
        assertEquals(listOf("melonDS"), opened)
    }

    @Test fun theCursorFollowsItsItemWhenRowsArriveAbove() {
        show()
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN) // melonDS
        items = listOf("Vita3K", "PPSSPP") + items
        compose.waitForIdle()
        assertEquals(4, selected)
        compose.onNodeWithText("melonDS").assertIsSelected()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("melonDS"), opened)
    }

    @Test fun theCursorClampsWhenItsItemGoes() {
        show()
        repeat(4) { compose.key(KeyEvent.KEYCODE_DPAD_DOWN) } // Dolphin, the last
        items = items - "Dolphin"
        compose.waitForIdle()
        assertEquals(3, selected)
        compose.onNodeWithText("RetroArch").assertIsSelected()
    }

    @Test fun reconcileKeepsTheKeyOrClamps() {
        val before = listOf<Any>("a", "b", "c")
        assertEquals(2, reconcileCursor(before, "b", listOf("x", "a", "b", "c"), 1))
        assertEquals(1, reconcileCursor(before, "b", listOf("a", "c"), 1))
        assertEquals(1, reconcileCursor(before, "c", listOf("a", "b"), 2))
        // Unchanged rows: the caller's index wins (that is how the cursor moves).
        assertEquals(2, reconcileCursor(before, "a", before, 2))
        // First composition: nothing to follow yet.
        assertEquals(1, reconcileCursor(null, null, before, 1))
        assertEquals(2, reconcileCursor(null, null, before, 9))
    }

    @Test fun theCursorScrollsALongListToKeepItsRowInView() {
        items = List(60) { "Game $it" }
        compose.host {
            CursorList(items, key = { it }, selected = selected, onSelect = { selected = it }, onOpen = {}, modifier = Modifier.height(200.dp)) { item, _ ->
                BasicText(item)
            }
        }
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN, repeats = 29)
        assertEquals(30, selected)
        compose.onNodeWithText("Game 30").assertIsDisplayed().assertIsSelected()
        repeat(25) { compose.key(KeyEvent.KEYCODE_DPAD_UP) }
        compose.onNodeWithText("Game 5").assertIsDisplayed().assertIsSelected()
    }
}
