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
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import dev.emusync.app.ui.kit.key
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.robolectric.annotation.GraphicsMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Thor and the Odin 3 are 1920×1080 px at 369 dpi: 832×468 dp, not the 960×540 of the mockups, and the system
 * bars take some of the height. The games table must still leave the game name room to be read.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
    }

    @Test fun theLongestHereCopyIsNeverCutOnTheCursorRowOrOff() {
        val games = listOf(
            game("bd", "Pokémon Brilliant Diamond", DeviceGameState.Differs),
            game("0100ABF008968000", "Pokémon Sword", DeviceGameState.Waiting),
            game("ra", "Final Fantasy VI", DeviceGameState.Waiting, emu = "retroarch"),
        )
        val ui = deviceUi(sampleEmulators, Loaded(games, Instant.EPOCH), emptyList(), "dev-thor", signedIn = true)
        val status = TopStatus("AYN Thor", ServerConn.Connected, ShizukuState.Ready, signedIn = true)
        compose.setContent {
            EmuTheme(ThemeMode.Dark) {
                DeviceScreen(ui, top = { TopBar(listOf("This device", "Activity", "Conflicts", "Settings"), 0, {}, status, {}) }, actions = DeviceActions())
            }
        }
        val fault = "two saves · choose in Conflicts"
        assertWhole(fault)                                   // regular weight
        compose.key(KeyEvent.KEYCODE_DPAD_RIGHT)              // the cursor on Brilliant Diamond: SemiBold
        assertWhole(fault)
        compose.key(KeyEvent.KEYCODE_DPAD_LEFT)
        repeat(3) { compose.key(KeyEvent.KEYCODE_DPAD_DOWN) }  // RetroArch
        assertWhole("waiting for RetroArch to close")
        compose.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertWhole("waiting for RetroArch to close")
    }

    private fun assertWhole(text: String) {
        val node = compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val l = layouts.single()
        assertFalse("\"$text\" is cut: needs ${l.multiParagraph.intrinsics.maxIntrinsicWidth} px, has ${l.size.width} px", l.isLineEllipsized(0) || l.hasVisualOverflow)
    }
}
