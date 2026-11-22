package dev.tandem.app.ui.kit

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tandem.app.api.ApiException
import dev.tandem.app.ui.input.Action
import dev.tandem.app.ui.theme.EmuTheme
import dev.tandem.app.ui.theme.EmuType
import dev.tandem.app.ui.theme.LocalEmu
import dev.tandem.app.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders a sample console screen built only from the kit, in both themes. It always checks that the screen
 * composes; with TANDEM_SHOT_DIR set it also writes kit-dark.png and kit-light.png there for a visual review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class KitScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dark() = shoot(ThemeMode.Dark, "kit-dark.png")

    @Test fun light() = shoot(ThemeMode.Light, "kit-light.png")

    private fun shoot(mode: ThemeMode, name: String) {
        compose.setContent { EmuTheme(mode) { Sample() } }
        compose.onNodeWithText("Emulators").assertExists()
        val dir = System.getenv("TANDEM_SHOT_DIR") ?: return
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

private data class Emu(val name: String, val state: String, val tone: Tone)

private val emulators = listOf(
    Emu("Eden", "Open · 1 waiting", Tone.Wait),
    Emu("Azahar", "3 saves", Tone.Ink2),
    Emu("melonDS", "1 save", Tone.Ink2),
    Emu("RetroArch", "config group", Tone.Ink2),
    Emu("Dolphin", "not set up", Tone.Ink3),
)

@Composable
private fun Sample() {
    val c = LocalEmu.current
    var page by remember { mutableIntStateOf(0) }
    var at by remember { mutableIntStateOf(0) }
    val noop = {}
    ConsoleScaffold(
        top = {
            Wordmark()
            BumperTabs(listOf("This device", "Activity", "Conflicts", "Settings"), page, { page = it }, badge = { if (it == 2) "1" else null })
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                BasicText("Thor", style = EmuType.meta.copy(fontWeight = FontWeight.SemiBold, color = c.ink))
                SystemReadout(Readout("14:05", 82))
            }
        },
        hints = listOf(
            Hint(Action.A, "Open", noop),
            Hint(Action.X, "Sync now", noop),
            Hint(Action.Y, "Emulator settings", noop),
            Hint(Action.B, "Back", noop),
        ),
        status = "Synced 2 min ago",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.width(380.dp).fillMaxHeight()) {
                BasicText("Emulators", Modifier.padding(top = 4.dp, bottom = 12.dp), style = EmuType.title.copy(color = c.ink))
                CursorList(emulators, key = { it.name }, selected = at, onSelect = { at = it }, onOpen = {}) { e, _ ->
                    Cell(e.name, Modifier.weight(1f), style = EmuType.rowPrimary.copy(fontSize = 16.sp))
                    Cell(e.state, tone = e.tone, style = EmuType.meta)
                }
            }
            Column(Modifier.weight(1f)) {
                BasicText("Eden · 15 games", Modifier.padding(top = 4.dp, bottom = 12.dp), style = EmuType.section.copy(color = c.ink2))
                Facts(
                    listOf(
                        Fact("Changed", "Nov 19, 21:48"),
                        Fact("Size", "1.0 MB · 3 files", mono = true),
                        Fact("Newest on", "Odin 3"),
                    ),
                    Modifier.padding(horizontal = 12.dp),
                )
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    EmuButton("Keep this Thor's", primary = true, glyph = Action.A, onClick = noop)
                    EmuButton("Keep Odin 3's", glyph = Action.X, onClick = noop)
                    EmuButton("Keep both", enabled = false, onClick = noop)
                }
                EmptyLine("No games here yet. Play one and it shows up after the next sync.")
                ErrorLine(ApiException(429, "rate_limited", null), Modifier.padding(horizontal = 12.dp))
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
