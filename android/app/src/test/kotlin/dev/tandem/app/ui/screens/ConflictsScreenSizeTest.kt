package dev.tandem.app.ui.screens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import dev.tandem.app.ui.state.Loaded
import dev.tandem.app.ui.theme.EmuTheme
import dev.tandem.app.ui.theme.ThemeMode
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The decide view on the devices' real size (832×468 dp): each side's facts stay on one line. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = DEVICE_SIZE)
class ConflictsScreenSizeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theReceivedTimeAndTheSizeFitOnOneLine() {
        val ui = conflictsUi(Loaded(listOf(conflict("bd", "Pokémon Brilliant Diamond")), Instant.EPOCH), "dev-thor", emptySet(),
            { "Eden" }, signedIn = true, zone = ZoneOffset.UTC)
        compose.setContent { EmuTheme(ThemeMode.Dark) { ConflictsScreen(ui, top = {}, actions = ConflictsActions()) } }
        for (text in listOf("Thu, Nov 19, 21:48", "1.0 MB · 3 files")) {
            val node = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().first()
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertEquals("\"$text\" wraps", 1, layouts.single().lineCount)
        }
    }
}
