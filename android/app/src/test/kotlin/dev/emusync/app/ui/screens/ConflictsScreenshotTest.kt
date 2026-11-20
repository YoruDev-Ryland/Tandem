package dev.emusync.app.ui.screens

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import dev.emusync.app.fs.ShizukuState
import dev.emusync.app.model.ConflictRow
import dev.emusync.app.ui.kit.TopBar
import dev.emusync.app.ui.kit.key
import dev.emusync.app.ui.state.Loaded
import dev.emusync.app.ui.state.ServerConn
import dev.emusync.app.ui.state.TopStatus
import dev.emusync.app.ui.theme.EmuTheme
import dev.emusync.app.ui.theme.ThemeMode
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The mockup's Conflicts tab (app-setup-conflicts.html): dark with Brilliant Diamond while Eden is open; light with
 * three first-sync conflicts and the bulk choices; dark with the bulk confirm sheet open. With EMUSYNC_SHOT_DIR set
 * it writes conflicts-dark.png, conflicts-light.png and conflicts-sheet-dark.png there.
 */
/** The devices' size in dp: 1920x1080 px at 369 dpi. */
internal const val DEVICE_SIZE = "w832dp-h468dp-land-xhdpi"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class ConflictsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val one = listOf(conflict("bd", "Pokémon Brilliant Diamond"))
    private val three = listOf(
        conflict("bd", "Pokémon Brilliant Diamond"),
        conflict("pt", "Pokémon Platinum", emu = "azahar", thorHead = true),
        conflict("xc", "Xenoblade Chronicles X"),
    )

    @Test fun dark() = shoot(ThemeMode.Dark, "conflicts-dark.png", one, running = setOf("eden"))

    @Test fun light() = shoot(ThemeMode.Light, "conflicts-light.png", three)

    @Test fun sheetDark() = shoot(ThemeMode.Dark, "conflicts-sheet-dark.png", three, sheet = true)

    // The Thor's and the Odin 3's real size (1920x1080 px at 369 dpi), for design/screens/app/robo-*.png.
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceDark() = shoot(ThemeMode.Dark, "robo-conflicts-decide-dark.png", one, running = setOf("eden"))
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceLight() = shoot(ThemeMode.Light, "robo-conflicts-decide-light.png", three)
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceSheetDark() = shoot(ThemeMode.Dark, "robo-conflicts-sheet-dark.png", three, sheet = true)
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceSheetLight() = shoot(ThemeMode.Light, "robo-conflicts-sheet-light.png", three, sheet = true)

    private fun shoot(mode: ThemeMode, name: String, rows: List<ConflictRow>, running: Set<String> = emptySet(), sheet: Boolean = false) {
        val names = mapOf("eden" to "Eden", "azahar" to "Azahar")
        val ui = conflictsUi(Loaded(rows, Instant.EPOCH), "dev-thor", running, { names[it] ?: it }, signedIn = true, zone = ZoneOffset.UTC)
        val status = TopStatus("Thor", ServerConn.Connected, ShizukuState.Ready, signedIn = true)
        compose.setContent {
            EmuTheme(mode) {
                ConflictsScreen(ui, top = {
                    TopBar(listOf("This device", "Activity", "Conflicts", "Settings"), 2, {}, status, {}, badge = { null })
                }, actions = ConflictsActions())
            }
        }
        if (sheet) compose.key(KeyEvent.KEYCODE_BUTTON_L2)
        compose.onNodeWithText("Two different saves").assertExists()
        val dir = System.getenv("EMUSYNC_SHOT_DIR") ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
