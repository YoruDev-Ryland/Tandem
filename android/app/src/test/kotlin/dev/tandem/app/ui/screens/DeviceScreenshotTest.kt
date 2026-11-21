package dev.tandem.app.ui.screens

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import dev.tandem.app.fs.ShizukuState
import dev.tandem.app.model.DeviceGameState
import dev.tandem.app.ui.kit.TopBar
import dev.tandem.app.ui.kit.key
import dev.tandem.app.ui.state.Loaded
import dev.tandem.app.ui.state.ServerConn
import dev.tandem.app.ui.state.TopStatus
import dev.tandem.app.ui.theme.EmuTheme
import dev.tandem.app.ui.theme.ThemeMode
import java.io.File
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The mockup's two states of the tab (app-main.html): dark with the cursor on Eden, which waits on a save; light with
 * the cursor on melonDS. With TANDEM_SHOT_DIR set it writes device-dark.png and device-light.png there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class DeviceScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val games = listOf(
        game("0100ABF008968000", "Pokémon Sword", DeviceGameState.Waiting),
        game("bd", "Pokémon Brilliant Diamond", DeviceGameState.Differs),
        game("xc", "Xenoblade Chronicles X", DeviceGameState.Newest, newestOn = "Thor"),
        game("fr", "Pokémon FireRed", DeviceGameState.Newest, newestOn = "Thor"),
        game("pt", "Pokémon Platinum", DeviceGameState.HasIt, emu = "melonds"),
    )

    @Test fun dark() = shoot(ThemeMode.Dark, "device-dark.png", downs = 0)

    @Test fun light() = shoot(ThemeMode.Light, "device-light.png", downs = 2)

    private fun shoot(mode: ThemeMode, name: String, downs: Int) {
        val ui = deviceUi(sampleEmulators, Loaded(games, Instant.EPOCH), listOf(swordWaiting), "dev-thor", signedIn = true)
        val status = TopStatus("Thor", ServerConn.Connected, ShizukuState.Ready, signedIn = true)
        compose.setContent {
            EmuTheme(mode) {
                DeviceScreen(ui, top = {
                    TopBar(listOf("This device", "Activity", "Conflicts", "Settings"), 0, {}, status, {}, badge = { if (it == 2) "1" else null })
                }, actions = DeviceActions())
            }
        }
        repeat(downs) { compose.key(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNodeWithText("Emulators").assertExists()
        val dir = System.getenv("TANDEM_SHOT_DIR") ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
