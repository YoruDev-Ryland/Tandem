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
import androidx.test.core.app.ApplicationProvider
import dev.emusync.app.service.AppGraph
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class MainActivityLaunchTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    /** Signed in before the activity starts (except for the signedOut… tests), signed out again after. */
    @get:Rule val rules: RuleChain = RuleChain.outerRule(TestRule { base, d ->
        object : Statement() {
            override fun evaluate() {
                val store = AppGraph.store(ApplicationProvider.getApplicationContext())
                if (!d.methodName.startsWith("signedOut")) {
                    store.serverUrl = "http://127.0.0.1:1"
                    store.deviceKey = "test-key"
                    store.deviceId = "dev-test"
                }
                try { base.evaluate() } finally { store.deviceKey = null; store.deviceId = null }
            }
        }
    }).around(compose)

    @Test fun launchesAndShowsTheWordmark() {
        compose.onNodeWithText("EmuSync").assertIsDisplayed()
    }

    @Test fun signedOutItOpensOnSetupAndBStaysThere() {
        compose.onNodeWithText("Set up this device").assertIsDisplayed()
        compose.onNodeWithText("Which server?").assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_BUTTON_B)   // nothing before the first step at launch
        compose.onNodeWithText("Which server?").assertIsDisplayed()
    }

    @Test fun opensOnThisDeviceAndTheBumpersSwitchTabs() {
        compose.onNodeWithText("Emulators").assertIsDisplayed()
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
