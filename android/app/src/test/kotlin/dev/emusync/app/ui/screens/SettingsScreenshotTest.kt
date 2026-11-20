package dev.emusync.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import dev.emusync.app.fs.ShizukuState
import dev.emusync.app.ui.kit.TopBar
import dev.emusync.app.ui.state.ServerConn
import dev.emusync.app.ui.state.TopStatus
import dev.emusync.app.ui.theme.EmuTheme
import dev.emusync.app.ui.theme.ThemeMode
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Sync in both themes and Settings › Emulators in dark. With EMUSYNC_SHOT_DIR set it writes
 * settings-dark.png, settings-light.png and settings-emulators-dark.png there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class SettingsScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val variants = listOf(
        VariantLine("eden", "Eden", "dev.eden.eden_emulator", "Eden", active = false, first = true),
        VariantLine("eden", "Eden", "dev.igawa6.edenduo", "Eden Duo", active = true, first = false),
        VariantLine("dolphin", "Dolphin", "org.dolphinemu.dolphinemu", "Dolphin", active = true, first = true),
        VariantLine("retroarch", "RetroArch", "com.retroarch.aarch64", "RetroArch", active = true, first = true),
        VariantLine("melonds", "melonDS", "me.magnum.melonds", "melonDS", active = true, first = true),
        VariantLine("azahar", "Azahar", null, "Not installed", active = false, first = true),
        VariantLine("ppsspp", "PPSSPP", "org.ppsspp.ppsspp", "PPSSPP", active = true, first = true),
        VariantLine("ppsspp", "PPSSPP", "org.ppsspp.ppssppgold", "PPSSPP Gold", active = false, first = false),
        VariantLine("vita3k", "Vita3K", null, "Not installed", active = false, first = true),
    )

    private fun ui(section: SettingsSection) = SettingsUi(
        section = section,
        autoApply = true,
        romDirs = listOf("/sdcard/Roms", "/storage/1A2B-3C4D/Games"),
        ppssppMemstick = "/sdcard/PSP",
        variants = variants,
        theme = ThemeMode.System,
        signedIn = true,
        serverUrl = "http://nas.local:5100",
        deviceName = "Thor",
    )

    @Test fun syncDark() = shoot(ThemeMode.Dark, SettingsSection.Sync, "settings-dark.png", "Add a ROM folder")

    @Test fun syncLight() = shoot(ThemeMode.Light, SettingsSection.Sync, "settings-light.png", "Add a ROM folder")

    @Test fun emulatorsDark() = shoot(ThemeMode.Dark, SettingsSection.Emulators, "settings-emulators-dark.png", "dev.igawa6.edenduo")

    private fun shoot(mode: ThemeMode, section: SettingsSection, name: String, expect: String) {
        val status = TopStatus("Thor", ServerConn.Connected, ShizukuState.Ready, signedIn = true)
        compose.setContent {
            EmuTheme(mode) {
                SettingsScreen(ui(section), top = {
                    TopBar(listOf("This device", "Activity", "Conflicts", "Settings"), 3, {}, status, {})
                }, actions = SettingsActions())
            }
        }
        compose.onNodeWithText(expect).assertExists()
        val dir = System.getenv("EMUSYNC_SHOT_DIR") ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
