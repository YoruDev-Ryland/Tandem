package dev.tandem.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import dev.tandem.app.fs.ShizukuState
import dev.tandem.app.ui.kit.Chrome
import dev.tandem.app.ui.kit.TopBar
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

/**
 * Settings › Sync in both themes and Settings › Emulators in dark. With TANDEM_SHOT_DIR set it writes
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

    private fun ui(section: SettingsSection, update: dev.tandem.app.update.UpdateUi = dev.tandem.app.update.UpdateUi()) = SettingsUi(
        update = update,
        appVersion = "0.2.0",
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

    @Test fun syncDark() = shoot(ThemeMode.Dark, SettingsSection.Sync, "settings-dark.png", ADD_ROM_DIR)

    @Test fun syncLight() = shoot(ThemeMode.Light, SettingsSection.Sync, "settings-light.png", ADD_ROM_DIR)

    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceSyncDark() = shoot(ThemeMode.Dark, SettingsSection.Sync, "robo-settings-sync-dark.png", ADD_ROM_DIR)
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceSyncLight() = shoot(ThemeMode.Light, SettingsSection.Sync, "robo-settings-sync-light.png", ADD_ROM_DIR)
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceAppearanceDark() = shoot(ThemeMode.Dark, SettingsSection.Appearance, "robo-settings-appearance-dark.png", HAPTICS_COPY)
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceAppearanceLight() = shoot(ThemeMode.Light, SettingsSection.Appearance, "robo-settings-appearance-light.png", HAPTICS_COPY)

    private val ready = dev.tandem.app.update.UpdateUi(dev.tandem.app.update.UpdatePhase.Ready("0.3.0"), installQueued = true, canInstall = true)
    private val failed = dev.tandem.app.update.UpdateUi(dev.tandem.app.update.UpdatePhase.Failed("0.3.0 wasn't installed: it is signed with another key"))
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceAboutDark() =
        shoot(ThemeMode.Dark, SettingsSection.About, "robo-settings-about-dark.png", "0.3.0 is ready: it installs when sync is idle", ready)
    @Config(qualifiers = DEVICE_SIZE) @Test fun deviceAboutLight() =
        shoot(ThemeMode.Light, SettingsSection.About, "robo-settings-about-light.png", AUTO_UPDATE_COPY, failed)

    @Test fun emulatorsDark() = shoot(ThemeMode.Dark, SettingsSection.Emulators, "settings-emulators-dark.png", "dev.igawa6.edenduo")

    private fun shoot(mode: ThemeMode, section: SettingsSection, name: String, expect: String, update: dev.tandem.app.update.UpdateUi = dev.tandem.app.update.UpdateUi()) {
        val status = TopStatus("Thor", ServerConn.Connected, ShizukuState.Ready, signedIn = true)
        compose.setContent {
            EmuTheme(mode) {
                Chrome {
                    SettingsScreen(ui(section, update), top = {
                        TopBar(listOf("This device", "Activity", "Conflicts", "Settings"), 3, {}, status, {})
                    }, actions = SettingsActions())
                }
            }
        }
        compose.onNodeWithText(expect).assertExists()
        val dir = System.getenv("TANDEM_SHOT_DIR") ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
