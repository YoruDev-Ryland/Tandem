package dev.emusync.app.ui.screens

import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.emusync.app.fs.ShizukuState
import dev.emusync.app.model.DeviceGameState
import dev.emusync.app.ui.kit.TopBar
import dev.emusync.app.ui.state.Loaded
import dev.emusync.app.ui.state.ServerConn
import dev.emusync.app.ui.state.TopStatus
import dev.emusync.app.ui.theme.EmuTheme
import dev.emusync.app.ui.theme.ThemeMode
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Thor and the Odin 3 are 1920×1080 px at 369 dpi: 832×468 dp, not the 960×540 of the mockups, and the system
 * bars take some of the height. The games table must still leave the game name room to be read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w832dp-h420dp-land-xhdpi")
class DeviceScreenSizeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onAThorSizedScreenTheGameColumnStaysReadable() {
        val games = listOf(
            game("bd", "Pokémon Brilliant Diamond", DeviceGameState.Differs),
            game("0100ABF008968000", "Pokémon Sword", DeviceGameState.Waiting),
        )
        val ui = deviceUi(sampleEmulators, Loaded(games, Instant.EPOCH), listOf(swordWaiting), "dev-thor", signedIn = true)
        val status = TopStatus("AYN Thor", ServerConn.Connected, ShizukuState.Ready, signedIn = true)
        compose.setContent {
            EmuTheme(ThemeMode.Dark) {
                DeviceScreen(ui, top = { TopBar(listOf("This device", "Activity", "Conflicts", "Settings"), 0, {}, status, {}) }, actions = DeviceActions())
            }
        }
        compose.onNodeWithText("GAME", useUnmergedTree = true).assertWidthIsAtLeast(160.dp)
        compose.onNodeWithText("Pokémon Brilliant Diamond", useUnmergedTree = true).assertWidthIsAtLeast(160.dp)
        // The longest HERE copy fits its column whole.
        compose.onNodeWithText("two saves · choose in Conflicts", useUnmergedTree = true).assertWidthIsAtLeast(150.dp)
    }
}
