package dev.tandem.app.ui.screens

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import dev.tandem.app.fs.ShizukuState
import dev.tandem.app.ui.kit.TopBar
import dev.tandem.app.ui.kit.key
import dev.tandem.app.ui.state.ServerConn
import dev.tandem.app.ui.state.TopStatus
import dev.tandem.app.ui.theme.EmuTheme
import dev.tandem.app.ui.theme.ThemeMode
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Dark shows the server activity with a conflict row and Load older; light shows the Log. With TANDEM_SHOT_DIR set it writes activity-dark.png and activity-light.png. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class ActivityScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val entries = listOf(
        ActivityEntry(9, "just now", "Uploaded Pokémon Sword (Eden)", false),
        ActivityEntry(8, "12 min ago", "Applied the newer Xenoblade Chronicles X save from Odin 3", false),
        ActivityEntry(7, "3 h ago", "Pokémon Brilliant Diamond has two saves waiting for a choice", true),
        ActivityEntry(6, "9 h ago", "Uploaded Pokémon FireRed (RetroArch)", false),
        ActivityEntry(5, "Nov 19, 22:14", "Joined the Handhelds config group for RetroArch", false),
        ActivityEntry(4, "Nov 19, 21:48", "Uploaded Pokémon Platinum (melonDS)", false),
    )
    private val log = listOf(
        "14:02:11.204 sync started", "14:02:11.388 scan eden: 3 games", "14:02:12.011 upload 0100ABF008968000 ok",
        "14:02:12.540 sse connected", "14:05:40.912 delivery waiting: eden", "14:05:41.100 eden is running; holding apply",
    )

    @Test fun dark() = shoot(ThemeMode.Dark, "activity-dark.png", logTab = false)
    @Test fun light() = shoot(ThemeMode.Light, "activity-light.png", logTab = true)

    private fun shoot(mode: ThemeMode, name: String, logTab: Boolean) {
        val ui = ActivityUi(signedIn = true, entries = entries, loaded = true, hasOlder = true)
        val status = TopStatus("Thor", ServerConn.Connected, ShizukuState.Ready, signedIn = true)
        compose.setContent {
            EmuTheme(mode) {
                ActivityScreen(ui, log, top = {
                    TopBar(listOf("This device", "Activity", "Conflicts", "Settings"), 1, {}, status, {})
                }, actions = ActivityActions())
            }
        }
        if (logTab) { compose.key(KeyEvent.KEYCODE_BUTTON_R2); compose.key(KeyEvent.KEYCODE_DPAD_DOWN) }
        else repeat(2) { compose.key(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNodeWithText(if (logTab) "Nothing logged yet." else "WHAT HAPPENED", substring = false).let { if (!logTab) it.assertExists() }
        val dir = System.getenv("TANDEM_SHOT_DIR") ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
