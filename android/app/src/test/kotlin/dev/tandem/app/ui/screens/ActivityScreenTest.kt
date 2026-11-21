package dev.tandem.app.ui.screens

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.tandem.app.ui.kit.key
import dev.tandem.app.ui.theme.EmuTheme
import dev.tandem.app.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private fun entry(id: Long, msg: String = "Uploaded save $id", fault: Boolean = false) = ActivityEntry(id, "5 min ago", msg, fault)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class ActivityScreenTest {
    @get:Rule val compose = createComposeRule()

    private class Calls { var older = 0; var refresh = 0; var back = 0 }

    private fun show(ui: ActivityUi, log: List<String> = emptyList(), calls: Calls = Calls()) {
        compose.setContent {
            EmuTheme(ThemeMode.Dark) {
                ActivityScreen(ui, log, top = {}, actions = ActivityActions({ calls.older++ }, { calls.refresh++ }, { calls.back++ }))
            }
        }
    }

    private fun ui(vararg e: ActivityEntry, older: Boolean = false, loading: Boolean = false, olderError: Boolean = false) =
        ActivityUi(signedIn = true, entries = e.toList(), loaded = true, hasOlder = older, loadingOlder = loading, olderError = olderError)

    @Test fun rowsShowTimeThenMessage() {
        show(ui(entry(2, "Uploaded Pokémon Sword"), entry(1)))
        compose.onNodeWithText("Uploaded Pokémon Sword").assertIsDisplayed()
        compose.onNodeWithText("Uploaded save 1").assertIsDisplayed()
    }

    @Test fun loadOlderIsTheLastRowAndAOnItAsks() {
        val calls = Calls()
        show(ui(entry(2), entry(1), older = true), calls = calls)
        compose.onAllNodesWithText("Load older").assertCountEquals(2)   // the row and its hint
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN); compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(1, calls.older)
    }

    @Test fun aOnAnActivityRowDoesNotLoadOlder() {
        val calls = Calls()
        show(ui(entry(2), older = true), calls = calls)
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(0, calls.older)
    }

    @Test fun whileLoadingTheRowSaysSoAndAIsIgnored() {
        val calls = Calls()
        show(ui(entry(1), older = true, loading = true), calls = calls)
        compose.onNodeWithText("Loading…").assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN); compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(0, calls.older)
    }

    @Test fun nothingOlderIsTextNotARow() {
        show(ui(entry(1)))
        compose.onNodeWithText("Nothing older").assertIsDisplayed()
        compose.onNodeWithText("Load older").assertDoesNotExist()
    }

    @Test fun aFailedOlderPageKeepsTheRowsAndShowsTheErrorUnderThem() {
        show(ui(entry(1), older = true, olderError = true))
        compose.onNodeWithText("Uploaded save 1").assertIsDisplayed()
        compose.onNodeWithText("Couldn't load older activity. Try again.").assertIsDisplayed()
    }

    @Test fun offlineSaysWhenTheCachedPageWasCurrent() {
        show(ui(entry(1)).copy(offline = true, offlineNote = "Offline · last updated 14:05"))
        compose.onNodeWithText("Offline · last updated 14:05").assertIsDisplayed()
        compose.onNodeWithText("Uploaded save 1").assertIsDisplayed()
    }

    @Test fun emptyAndSignedOutSayWhy() {
        show(ActivityUi(signedIn = false))
        compose.onNodeWithText("Sign in to see this device's activity.").assertIsDisplayed()
    }

    @Test fun theLogSubTabShowsTheNewestLineFirst() {
        show(ui(entry(1)), log = listOf("10:00:00.000 oldest", "10:00:01.000 middle", "10:00:02.000 newest"))
        compose.key(KeyEvent.KEYCODE_BUTTON_R2)
        val top = { t: String -> compose.onNodeWithText(t, substring = true).getUnclippedBoundsInRoot().top }
        assertTrue(top("newest") < top("middle"))
        assertTrue(top("middle") < top("oldest"))
    }

    @Test fun logSubTabCanBeReachedByTouch() {
        show(ui(entry(1)), log = listOf("10:00:00.000 only line"))
        compose.onNodeWithText("Log").performClick()
        compose.onNodeWithText("10:00:00.000 only line").assertIsDisplayed()
    }

    @Test fun xRefreshesAndBGoesBack() {
        val calls = Calls()
        show(ui(entry(1)), calls = calls)
        compose.key(KeyEvent.KEYCODE_BUTTON_X); compose.key(KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(1, calls.refresh); assertEquals(1, calls.back)
    }
}

