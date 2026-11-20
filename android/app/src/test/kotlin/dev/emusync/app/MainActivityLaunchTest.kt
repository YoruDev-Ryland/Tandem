package dev.emusync.app

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import dev.emusync.app.ui.kit.key
import dev.emusync.app.ui.screens.AUTO_APPLY_COPY
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class MainActivityLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launchesAndShowsTheWordmark() {
        compose.onNodeWithText("EmuSync").assertIsDisplayed()
    }

    @Test fun opensOnThisDeviceAndTheBumpersSwitchTabs() {
        compose.onNodeWithText("Emulators").assertIsDisplayed()
        compose.onNodeWithText("Signed out").assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_BUTTON_R1)
        compose.key(KeyEvent.KEYCODE_BUTTON_R1)
        compose.key(KeyEvent.KEYCODE_BUTTON_R1)
        compose.onNodeWithText(AUTO_APPLY_COPY).assertIsDisplayed()   // Settings › Sync
        compose.onAllNodesWithText("Settings").assertCountEquals(1)   // the page tab; sections are sub-tabs
        compose.key(KeyEvent.KEYCODE_BUTTON_Y)   // no hint here: nothing happens
        compose.onNodeWithText(AUTO_APPLY_COPY).assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_BUTTON_B)   // B goes back to This device
        compose.onNodeWithText(AUTO_APPLY_COPY).assertDoesNotExist()
        compose.onNodeWithText("Emulators").assertIsDisplayed()
    }

    @Test fun yOnThisDeviceOpensSettingsEmulatorsOnThatEmulator() {
        compose.key(KeyEvent.KEYCODE_BUTTON_Y)   // the cursor is on the first emulator, Eden
        compose.onNodeWithText("Changing the variant restarts sync").assertIsDisplayed()   // Settings › Emulators
        compose.onNode(hasText("Eden") and isSelected(), useUnmergedTree = false).assertIsDisplayed()
    }
}
