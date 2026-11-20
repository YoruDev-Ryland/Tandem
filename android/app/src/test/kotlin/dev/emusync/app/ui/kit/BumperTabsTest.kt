package dev.emusync.app.ui.kit

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BumperTabsTest {
    @get:Rule val compose = createComposeRule()

    private val pages = listOf("This device", "Activity", "Conflicts", "Settings")
    private var active by mutableIntStateOf(0)
    private var conflicts: Int? = 1

    private fun show(level: Int = 1) = compose.host {
        BumperTabs(pages, active, onChange = { active = it }, level = level, badge = { i -> if (i == 2) conflicts?.toString() else null })
    }

    @Test fun r1AdvancesAndL1GoesBackStoppingAtTheEnds() {
        show()
        compose.key(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(1, active)
        compose.onNodeWithText("Activity").assertIsSelected()
        compose.key(KeyEvent.KEYCODE_BUTTON_L1)
        compose.key(KeyEvent.KEYCODE_BUTTON_L1)
        assertEquals(0, active)
        repeat(5) { compose.key(KeyEvent.KEYCODE_BUTTON_R1) }
        assertEquals(3, active)
    }

    @Test fun levelTwoListensToL2AndR2Only() {
        show(level = 2)
        compose.key(KeyEvent.KEYCODE_BUTTON_R1)
        assertEquals(0, active)
        compose.key(KeyEvent.KEYCODE_BUTTON_R2)
        assertEquals(1, active)
        compose.key(KeyEvent.KEYCODE_BUTTON_L2)
        assertEquals(0, active)
    }

    @Test fun theBadgeShowsOnlyWhenNonNull() {
        show()
        compose.onNodeWithText("Conflicts 1").assertExists()
        compose.onAllNodesWithText("Activity 1", substring = false).assertCountEquals(0)
        compose.onNodeWithText("Activity").assertExists()
    }

    @Test fun noBadgeMeansThePlainLabel() {
        conflicts = null
        show()
        compose.onNodeWithText("Conflicts").assertExists()
        compose.onAllNodesWithText("Conflicts", substring = true).assertCountEquals(1)
    }

    @Test fun tappingATabSwitchesToIt() {
        show()
        compose.onNodeWithText("Settings").performClick()
        assertEquals(3, active)
    }
}
