package dev.tandem.app.ui.screens

import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import dev.tandem.app.fs.ShizukuState
import dev.tandem.app.model.DeviceGameState.Newest
import dev.tandem.app.service.SyncService
import dev.tandem.app.sync.StateStore
import dev.tandem.app.ui.feedback.Cue
import dev.tandem.app.ui.feedback.CuePlayer
import dev.tandem.app.ui.feedback.LocalCuePlayer
import dev.tandem.app.ui.feedback.Notice as SuccessNotice
import dev.tandem.app.ui.kit.LocalNotice
import dev.tandem.app.ui.kit.LocalReadout
import dev.tandem.app.ui.kit.LocalStatusLine
import dev.tandem.app.ui.kit.Readout
import dev.tandem.app.ui.kit.TopBar
import dev.tandem.app.ui.kit.key
import dev.tandem.app.ui.state.Loaded
import dev.tandem.app.ui.state.ServerConn
import dev.tandem.app.ui.state.ShelfUi
import dev.tandem.app.ui.state.TopStatus
import dev.tandem.app.ui.theme.EmuTheme
import dev.tandem.app.ui.theme.ThemeMode
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private fun status(server: ServerConn = ServerConn.Connected, shizuku: ShizukuState = ShizukuState.Ready) =
    TopStatus("Odin3", server, shizuku, signedIn = server != ServerConn.SignedOut)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = DEVICE_SIZE)
class ShelfScreenTest {
    @get:Rule val compose = createComposeRule()

    private val cues = mutableListOf<Cue>()

    private fun show(ui: ShelfUi, actions: DeviceActions = DeviceActions(), status: TopStatus = status(), fix: (() -> Unit)? = {}) {
        compose.setContent {
            EmuTheme(ThemeMode.Dark) {
                CompositionLocalProvider(LocalCuePlayer provides CuePlayer { cues += it }) {
                    ShelfScreen(ui, top = { TopBar(listOf("Library", "Activity", "Conflicts", "Settings"), 0, {}, status, fix) }, actions = actions)
                }
            }
        }
    }

    private fun cardTitle(): String = compose.onNodeWithTag("card-title", useUnmergedTree = true).fetchSemanticsNode()
        .config[SemanticsProperties.Text].joinToString { it.text }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun layout(tag: String): TextLayoutResult {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val out = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(out)
        return out.single()
    }

    @Test fun opens_on_the_first_emulator_with_games_and_names_the_tab_library() {
        show(shelfSample())
        compose.onNodeWithText("Library").assertExists()
        compose.onNodeWithText("Dolphin").assertExists()
        compose.onNodeWithText("· Open · 1 waiting").assertExists()
        compose.onNodeWithText("9 games").assertExists()
        assertEquals("Medal of Honor: Rising Sun", cardTitle())   // the shelf's order: by name
    }

    @Test fun right_moves_cursor_and_card_shows_next_title() {
        show(shelfSample())
        compose.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("Metroid Prime", cardTitle())
        compose.onNodeWithTag("spine:GR8E69", useUnmergedTree = true).assertExists()   // the last one is a spine again
        assertEquals(Cue.Move, cues.last())
    }

    @Test fun left_at_start_does_not_wrap() {
        show(shelfSample())
        compose.key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("Medal of Honor: Rising Sun", cardTitle())
        assertEquals(Cue.Reject, cues.last())
        repeat(8) { compose.key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("The Legend of Zelda: The Wind Waker", cardTitle())
        compose.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("The Legend of Zelda: The Wind Waker", cardTitle())
        assertEquals(Cue.Reject, cues.last())
    }

    @Test fun down_switches_emulator_and_restores_cursor() {
        show(shelfSample())
        repeat(3) { compose.key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("Skies of Arcadia Legends", cardTitle())
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Final Fantasy IV Advance", cardTitle())   // RetroArch: the conflict first
        compose.onNodeWithText("10 games").assertExists()
        compose.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.key(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals("Skies of Arcadia Legends", cardTitle())
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals("Castlevania - Aria of Sorrow", cardTitle())
    }

    @Test fun tapping_a_spine_moves_the_cursor_and_tapping_the_card_opens_it() {
        val opened = mutableListOf<Pair<String, String>>()
        show(shelfSample(), DeviceActions(openGame = { e, k -> opened += e to k }))
        compose.onNodeWithTag("spine:GM8E01", useUnmergedTree = true).performClick()
        assertEquals("Metroid Prime", cardTitle())
        assertEquals(emptyList<Pair<String, String>>(), opened)
        compose.onNodeWithTag("card").performClick()
        assertEquals(listOf("dolphin" to "GM8E01"), opened)
    }

    @Test fun tapping_an_emulator_switches_to_it() {
        show(shelfSample())
        compose.onNodeWithText("RetroArch").performClick()
        assertEquals("Final Fantasy IV Advance", cardTitle())
    }

    @Test fun a_opens_focused_game() {
        val opened = mutableListOf<Pair<String, String>>()
        show(shelfSample(), DeviceActions(openGame = { e, k -> opened += e to k }))
        repeat(4) { compose.key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("dolphin" to "GSXE69"), opened)
    }

    @Test fun y_opens_focused_emulator_settings() {
        val asked = mutableListOf<String>()
        show(shelfSample(), DeviceActions(settings = { asked += it }))
        compose.key(KeyEvent.KEYCODE_BUTTON_Y)
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.key(KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(listOf("dolphin", "retroarch"), asked)
    }

    @Test fun rail_shows_a_mark_under_every_spine() {
        val ui = shelfSample()
        show(ui)
        val shelf = ui.shelves.first { it.tab.id == "dolphin" }
        assertEquals(2, ui.rail.size)
        compose.onNodeWithContentDescription("Odin3").assertExists()      // the gutter labels
        compose.onNodeWithContentDescription("AYN Thor").assertExists()
        // Every spine on screen has one mark per rail row (the Dolphin shelf fits the screen whole).
        val expected = shelf.spines.flatMap { s -> s.marks.map { it.description } }.groupingBy { it }.eachCount()
        for ((d, n) in expected) compose.onAllNodesWithContentDescription(d).assertCountEquals(n)
        assertEquals(shelf.spines.size * ui.rail.size, expected.values.sum())
        // And each mark sits under its own spine.
        val spine = bounds("spine:GM8E01")
        val mark = compose.onAllNodesWithContentDescription("AYN Thor has an older save").fetchSemanticsNodes()
            .first { it.boundsInRoot.center.x in spine.left..spine.right }.boundsInRoot
        assertTrue(mark.top > spine.bottom)
        assertEquals(spine.center.x, mark.center.x, 1.5f)
    }

    @Test fun long_and_unknown_titles_stay_inside_spine() {
        val long = "The Legend of Zelda: A Link Between Worlds and Another Very Long Subtitle"   // 70 characters
        val games = listOf(
            shelfGame("eden", "0100000000000000", "A first game", Newest, Newest),
            shelfGame("eden", "0100AAAAAAAAA000", long, Newest, Newest),
            shelfGame("eden", "0100AC20128AB000", null, Newest, Newest),
        )
        show(shelfSample(games, emptyList()))
        for (key in listOf("0100AAAAAAAAA000", "0100AC20128AB000")) {
            val spine = bounds("spine:$key")
            val text = bounds("spine-title:$key")
            assertTrue("$key: $text not in $spine", text.left >= spine.left - 0.5f && text.right <= spine.right + 0.5f)
            assertTrue("$key: $text not in $spine", text.top >= spine.top - 0.5f && text.bottom <= spine.bottom + 0.5f)
        }
        assertTrue(layout("spine-title:0100AAAAAAAAA000").isLineEllipsized(0))
        compose.onNodeWithText("0100AC20128AB000").assertExists()   // a raw key, as the name
    }

    @Test fun card_title_wraps_to_two_lines_max() {
        val long = "The Legend of Zelda: A Link Between Worlds and Another Very Long Subtitle"
        show(shelfSample(listOf(shelfGame("eden", "k", long, Newest, Newest)), emptyList()))
        val l = layout("card-title")
        assertEquals(2, l.lineCount)
        assertTrue(l.isLineEllipsized(1))
        val card = bounds("card")
        val title = bounds("card-title")
        assertTrue(title.right <= card.right && title.bottom <= card.bottom)
    }

    @Test fun notice_line_shows_more_count() {
        val two = listOf(ssxWaiting, ssxWaiting.copy(deliveryId = "d2", gameKey = "GM8E01", displayName = "Metroid Prime"))
        show(shelfSample(waiting = two))
        compose.onNode(hasText("Close Dolphin to get the newer SSX 3 save from AYN Thor.", substring = true)).assertExists()
        compose.onNodeWithText("+1 more").assertExists()
    }

    @Test fun nothing_pending_leaves_the_notice_line_empty() {
        show(shelfSample(waiting = emptyList(), games = dolphinGames.filter { it.gameKey != "GSXE69" }))
        compose.onNode(hasText("Close Dolphin", substring = true)).assertDoesNotExist()
        compose.onNode(hasText("more", substring = true)).assertDoesNotExist()
    }

    @Test fun empty_emulator_shows_empty_line() {
        show(shelfSample())
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)   // melonDS: not set up, no games
        compose.onNodeWithText("No games for melonDS yet.").assertExists()
        compose.onNodeWithText("· not set up").assertExists()
        compose.onNodeWithTag("card").assertDoesNotExist()
    }

    @Test fun no_art_card_shows_the_system_code() {
        show(shelfSample())
        compose.onNodeWithTag("no-art", useUnmergedTree = true).assertExists()
    }

    @Test fun the_art_slot_fills_the_cover() {
        val asked = mutableListOf<String>()
        compose.setContent {
            EmuTheme(ThemeMode.Light) {
                ShelfScreen(shelfSample(), top = {}, actions = DeviceActions(), art = { _, k ->
                    asked += k
                    androidx.compose.ui.graphics.ImageBitmap(4, 4)
                })
            }
        }
        compose.onNodeWithContentDescription("Cover of Medal of Honor: Rising Sun").assertExists()
        compose.onNodeWithTag("no-art", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("GR8E69", asked.last())
    }

    @Test fun the_cursor_clamps_when_its_game_disappears() {
        val state = mutableStateOf(shelfSample())
        compose.setContent { EmuTheme(ThemeMode.Dark) { ShelfScreen(state.value, top = {}, actions = DeviceActions()) } }
        repeat(8) { compose.key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("The Legend of Zelda: The Wind Waker", cardTitle())
        state.value = shelfSample(games = dolphinGames.filter { it.gameKey != "GZLE01" } + retroarchGames)
        compose.waitForIdle()
        assertEquals("The Legend of Zelda: Skyward Sword", cardTitle())
        state.value = shelfSample(games = retroarchGames)
        compose.waitForIdle()
        compose.onNodeWithText("No games for Dolphin yet.").assertExists()
    }

    // ---- what the old tab tested, kept for the shelf ----

    @Test fun signedOutAsksToSignInAndAGoesToSetup() {
        var setup = 0
        val ui = shelfUi(shelfEmulators, Loaded(), emptyList(), SHELF_ODIN, signedIn = false)
        show(ui, DeviceActions(setup = { setup++ }), status(ServerConn.SignedOut))
        compose.onNodeWithText("Signed out").assertExists()
        compose.onNodeWithText("Sign in to see your games.").assertExists()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(1, setup)
    }

    @Test fun aRevokedDeviceSaysSoAndASignsInAgain() {
        var again = 0
        show(shelfSample().copy(revoked = true), DeviceActions(pairAgain = { again++ }), status(ServerConn.Revoked))
        compose.onNodeWithText(REVOKED_LINE).assertExists()
        compose.onNodeWithText("Removed from server").assertExists()
        compose.onNodeWithTag("shelf").assertDoesNotExist()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(1, again)
    }

    @Test fun theStatusAreaSaysWhenThisDeviceLastSyncedAndASuccessLineStandsIn() {
        compose.setContent {
            EmuTheme(ThemeMode.Dark) {
                CompositionLocalProvider(LocalStatusLine provides "Synced 5 min ago") {
                    ShelfScreen(shelfSample(), top = {}, actions = DeviceActions())
                }
            }
        }
        compose.onNodeWithText("Synced 5 min ago").assertExists()
    }

    @Test fun aSuccessLineStandsInForTheStatus() {
        compose.setContent {
            EmuTheme(ThemeMode.Dark) {
                CompositionLocalProvider(LocalStatusLine provides "Synced 5 min ago", LocalNotice provides SuccessNotice(1, "SSX 3 from AYN Thor is in place.")) {
                    ShelfScreen(shelfSample(), top = {}, actions = DeviceActions())
                }
            }
        }
        compose.onNodeWithText("SSX 3 from AYN Thor is in place.").assertExists()
        compose.onNodeWithText("Synced 5 min ago").assertDoesNotExist()
    }

    @Test fun syncedAgoTicksWithTheClockItIsGiven() {
        val at = Instant.parse("2026-11-21T11:55:00Z")
        assertEquals("Synced just now", syncedAgo(at, at.plusSeconds(30), ZoneOffset.UTC))
        assertEquals("Synced 5 min ago", syncedAgo(at, Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC))
        assertEquals("Synced 2 h ago", syncedAgo(at, Instant.parse("2026-11-21T14:00:00Z"), ZoneOffset.UTC))
        assertEquals("Synced Nov 21, 11:55", syncedAgo(at, Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC))
        assertEquals(null, syncedAgo(null, at, ZoneOffset.UTC))
    }

    @Test fun shizukuOffShowsTapToFixAndTheTapFixesIt() {
        var fixed = 0
        show(shelfSample(), status = status(shizuku = ShizukuState.NotRunning), fix = { fixed++ })
        compose.onNodeWithText("Shizuku is off: tap to fix").performClick()
        assertEquals(1, fixed)
    }

    @Test fun theTopBarShowsTheWordmarkClockAndBattery() {
        compose.setContent {
            EmuTheme(ThemeMode.Dark) {
                CompositionLocalProvider(LocalReadout provides Readout("14:05", 82, charging = true)) {
                    ShelfScreen(shelfSample(), top = { TopBar(listOf("Library"), 0, {}, status(), {}) }, actions = DeviceActions())
                }
            }
        }
        compose.onNodeWithText("TANDEM").assertExists()
        compose.onNodeWithContentDescription("14:05, battery 82 percent, charging").assertExists()
    }

    private fun store(key: String?) = StateStore(File.createTempFile("state", ".json").apply { delete(); deleteOnExit() })
        .also { it.deviceKey = key }

    @Test fun xSendsScanThenApply() {
        val app = RuntimeEnvironment.getApplication()
        val signedIn = store("k")
        show(shelfSample(), DeviceActions(syncNow = { syncNow(app, signedIn) }))
        compose.key(KeyEvent.KEYCODE_BUTTON_X)
        val shadow = shadowOf(app)
        assertEquals(SyncService.ACTION_SCAN, shadow.nextStartedService?.action)
        assertEquals(SyncService.ACTION_APPLY, shadow.nextStartedService?.action)
    }

    @Test fun syncNowWhileSignedOutDoesNotStartTheService() {
        val app = RuntimeEnvironment.getApplication()
        syncNow(app, store(null))
        assertEquals(null, shadowOf(app).nextStartedService)
    }

    @Test fun gameUrlEncodesEachSegment() {
        assertEquals(
            "http://h:5100/games/retro%20arch/snes/Mario%20%26%20Luigi%3F",
            gameUrl("http://h:5100/", "retro arch", "snes/Mario & Luigi?"),
        )
    }

    @Test fun waitingCardSaysSoInAmberWords() {
        show(shelfSample())
        repeat(4) { compose.key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals("SSX 3", cardTitle())
        compose.onNodeWithText("Waiting for Dolphin to close.").assertExists()
        compose.onNodeWithText("newest · 2 h ago", useUnmergedTree = true).assertExists()
        assertFalse(cues.contains(Cue.Reject))
    }

}
