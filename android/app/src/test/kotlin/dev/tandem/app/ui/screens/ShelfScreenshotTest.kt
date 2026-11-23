package dev.tandem.app.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import dev.tandem.app.fs.ShizukuState
import dev.tandem.app.ui.kit.Chrome
import dev.tandem.app.ui.kit.TopBar
import dev.tandem.app.ui.kit.key
import dev.tandem.app.ui.state.ServerConn
import dev.tandem.app.ui.state.ShelfUi
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

/**
 * The Library tab at the devices' size (832×468 dp, xhdpi), light and dark: the mockup's shelf with the cursor on a
 * waiting game, a conflict on RetroArch's mixed shelf, an empty emulator, and four devices. It always checks that each
 * composes; with TANDEM_SHOT_DIR set it writes robo-library-*.png there. TANDEM_SHELF_ART may name a local cover image
 * for the focused game, to compare with the mockup by eye; TANDEM_SPINE_ART may name a local folder of
 * `<key with / as _>.spine.png` and `.cover.png` images for the spines. Neither is ever committed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = DEVICE_SIZE)
class ShelfScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun waitingLight() = shoot(ThemeMode.Light, "robo-library-waiting-light.png", shelfSample(), rights = 4)
    @Test fun waitingDark() = shoot(ThemeMode.Dark, "robo-library-waiting-dark.png", shelfSample(), rights = 4)
    @Test fun conflictLight() = shoot(ThemeMode.Light, "robo-library-conflict-light.png", shelfSample(), downs = 1)
    @Test fun conflictDark() = shoot(ThemeMode.Dark, "robo-library-conflict-dark.png", shelfSample(), downs = 1)
    /** A shelf longer than the screen, the cursor well into it: the card stays whole with a spine either side. */
    @Test fun scrolledLight() = shoot(ThemeMode.Light, "robo-library-scrolled-light.png", long(), downs = 1, rights = 14)
    @Test fun scrolledDark() = shoot(ThemeMode.Dark, "robo-library-scrolled-dark.png", long(), downs = 1, rights = 14)
    @Test fun emptyLight() = shoot(ThemeMode.Light, "robo-library-empty-light.png", shelfSample(), downs = 2)
    @Test fun emptyDark() = shoot(ThemeMode.Dark, "robo-library-empty-dark.png", shelfSample(), downs = 2)
    @Test fun fourDevicesLight() = shoot(ThemeMode.Light, "robo-library-four-light.png", four(), downs = 3, rights = 1)
    @Test fun fourDevicesDark() = shoot(ThemeMode.Dark, "robo-library-four-dark.png", four(), downs = 3, rights = 1)

    /** Dolphin's shelf with real spines (where TANDEM_SPINE_ART has them) and RetroArch's with built ones. */
    @Test fun spinesLight() = shoot(ThemeMode.Light, "robo-library-spines-light.png", spines())
    @Test fun spinesDark() = shoot(ThemeMode.Dark, "robo-library-spines-dark.png", spines())
    @Test fun builtLight() = shoot(ThemeMode.Light, "robo-library-built-light.png", spines(), downs = 1)

    private fun spines() = shelfSample(games = dolphinGames.map { it.copy(spine = true) } + retroarchGames)

    private fun four() = shelfSample(games = dolphinGames + azaharGames)

    private fun long() = shelfSample(games = dolphinGames + retroarchGames + listOf(
        "Advance Wars", "Golden Sun", "Kirby & The Amazing Mirror", "Mario Kart Super Circuit", "Metroid Fusion",
        "Minish Cap", "Mother 3", "Pokemon - FireRed Version", "Super Mario Advance 4", "Wario Land 4", "Zelda - A Link to the Past",
        "F-Zero - Maximum Velocity",
    ).map { shelfGame("retroarch", "gba/$it", it, dev.tandem.app.model.DeviceGameState.Newest, dev.tandem.app.model.DeviceGameState.Behind) })

    private fun shoot(mode: ThemeMode, name: String, ui: ShelfUi, downs: Int = 0, rights: Int = 0) {
        val status = TopStatus("Odin3", ServerConn.Connected, ShizukuState.Ready, signedIn = true)
        val art: ImageBitmap? = System.getenv("TANDEM_SHELF_ART")?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
        compose.setContent {
            EmuTheme(mode) {
                Chrome {
                    ShelfScreen(ui, top = {
                        TopBar(listOf("Library", "Activity", "Conflicts", "Settings"), 0, {}, status, {}, badge = { if (it == 2) "1" else null })
                    }, actions = DeviceActions(), art = { _, k -> if (k == "GSXE69") art else null }, spineArt = { s -> localSpineArt(s) })
                }
            }
        }
        repeat(downs) { compose.key(KeyEvent.KEYCODE_DPAD_DOWN) }
        repeat(rights) { compose.key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val dir = System.getenv("TANDEM_SHOT_DIR") ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val spineDir = System.getenv("TANDEM_SPINE_ART")?.let(::File)

    private fun localSpineArt(s: dev.tandem.app.ui.state.Spine): SpineImages {
        val dir = spineDir ?: return SpineImages()
        fun img(kind: String) = File(dir, s.gameKey.replace('/', '_') + ".$kind.png").takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
        return SpineImages(img("spine"), img("cover"))
    }
}
