package dev.emusync.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MainActivityLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun launchesAndShowsTheWordmark() {
        compose.onNodeWithText("EmuSync").assertIsDisplayed()
    }
}
