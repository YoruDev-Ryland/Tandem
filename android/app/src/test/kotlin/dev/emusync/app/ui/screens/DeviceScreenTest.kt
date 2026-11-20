package dev.emusync.app.ui.screens

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.robolectric.RuntimeEnvironment
import dev.emusync.app.fs.ShizukuState
import dev.emusync.app.model.DeviceGameState
import dev.emusync.app.model.GameDeviceState
import dev.emusync.app.model.GameRow
import dev.emusync.app.service.SyncService
import dev.emusync.app.sync.WaitingDelivery
import dev.emusync.app.ui.kit.Tone
import dev.emusync.app.ui.kit.TopBar
import dev.emusync.app.ui.kit.key
import dev.emusync.app.ui.state.EmuRowState
import dev.emusync.app.ui.state.EmulatorRowUi
import dev.emusync.app.ui.state.Loaded
import dev.emusync.app.ui.state.ServerConn
import dev.emusync.app.ui.state.TopStatus
import dev.emusync.app.ui.theme.EmuTheme
import dev.emusync.app.ui.theme.ThemeMode
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

private const val HERE = "dev-thor"

internal fun game(key: String, name: String, state: DeviceGameState, emu: String = "eden", newestOn: String? = "Odin 3") = GameRow(
    emulatorId = emu, gameKey = key, displayName = name, installedOn = listOf(HERE, "dev-odin"), stateCount = 0,
    openConflict = state == DeviceGameState.Differs,
    devices = listOf(GameDeviceState(HERE, "Thor", state), GameDeviceState("dev-odin", "Odin 3", DeviceGameState.Newest)),
    newestOnDeviceName = newestOn,
)

internal val sampleEmulators = listOf(
    EmulatorRowUi("eden", "Eden", EmuRowState.Open(1), 3),
    EmulatorRowUi("azahar", "Azahar", EmuRowState.Saves(3), 3),
    EmulatorRowUi("melonds", "melonDS", EmuRowState.Saves(1), 1),
    EmulatorRowUi("retroarch", "RetroArch", EmuRowState.ConfigGroup("Handhelds"), 0),
    EmulatorRowUi("dolphin", "Dolphin", EmuRowState.NotSetUp, 0),
)

internal val swordWaiting = WaitingDelivery("d1", "eden", "Eden", "0100ABF008968000", "Pokémon Sword", null)

private fun ui(games: List<GameRow>, waiting: List<WaitingDelivery> = emptyList(), signedIn: Boolean = true) =
    deviceUi(sampleEmulators, if (signedIn) Loaded(games, Instant.EPOCH) else Loaded(), waiting, HERE, signedIn)

private fun status(server: ServerConn = ServerConn.Connected, shizuku: ShizukuState = ShizukuState.Ready, lastSync: Instant? = null) =
    TopStatus("Thor", server, shizuku, signedIn = server != ServerConn.SignedOut, lastSync = lastSync)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class DeviceScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun show(ui: DeviceUi, status: TopStatus = status(), actions: DeviceActions = DeviceActions(), fix: (() -> Unit)? = {}) {
        compose.setContent {
            EmuTheme(ThemeMode.Dark) {
                DeviceScreen(ui, top = {
                    TopBar(listOf("This device", "Activity", "Conflicts", "Settings"), 0, {}, status, fix, zone = ZoneOffset.UTC)
                }, actions = actions)
            }
        }
    }

    @Test fun edenOpenWithOneWaitingShowsTheCountAndTheCloseLine() {
        show(ui(listOf(game("0100ABF008968000", "Pokémon Sword", DeviceGameState.Waiting)), listOf(swordWaiting)))
        compose.onNodeWithText("Open · 1 waiting").assertExists()
        compose.onNodeWithText("waiting for Eden to close").assertExists()
        compose.onNodeWithText(
            "Close Eden to get the newer Pokémon Sword save from Odin 3. Nothing is written while Eden is open.",
        ).assertExists()
    }

    @Test fun theCloseLineShowsWhenOnlyTheServerSaysWaiting() {
        show(ui(listOf(game("k1", "Xenoblade", DeviceGameState.Waiting, newestOn = "Odin 3"))))
        compose.onNodeWithText("Close Eden to get the newer Xenoblade save from Odin 3. Nothing is written while Eden is open.").assertExists()
    }

    @Test fun aLocalWaitingDeliveryWithoutAGameRowComesFromAnotherDevice() {
        val pane = ui(emptyList(), listOf(swordWaiting)).panes.first { it.id == "eden" }
        assertEquals(listOf(WaitLine("Eden", "Pokémon Sword", "another device")), pane.waiting)
        assertEquals("Open · 1 waiting", pane.state)
        assertEquals(Tone.Wait, pane.tone)
    }

    @Test fun aDiffersGameShowsTheFaultCopy() {
        val u = ui(listOf(game("bd", "Pokémon Brilliant Diamond", DeviceGameState.Differs)))
        show(u)
        compose.onNodeWithText("two saves · choose in Conflicts").assertExists()
        val line = u.panes.first { it.id == "eden" }.games.single()
        assertEquals(Tone.Fault, line.tone)
    }

    @Test fun everyHereStateHasItsCopy() {
        val states = DeviceGameState.entries.filter { it != DeviceGameState.NotInstalled }
        val u = ui(states.map { game(it.name, it.name, it) })
        val here = u.panes.first { it.id == "eden" }.games.associate { it.key to (it.here to it.tone) }
        assertEquals("this is the newest" to Tone.Ink2, here["Newest"])
        assertEquals("up to date" to Tone.Ink2, here["HasIt"])
        assertEquals("waiting for Eden to close" to Tone.Wait, here["Waiting"])
        assertEquals("on its way" to Tone.Ink2, here["Pending"])
        assertEquals("older save" to Tone.Ink2, here["Behind"])
        assertEquals("two saves · choose in Conflicts" to Tone.Fault, here["Differs"])
    }

    @Test fun beforeTheFirstScanAnEmulatorIsLookingInInk3() {
        val pane = deviceUi(listOf(EmulatorRowUi("azahar", "Azahar", EmuRowState.Looking, 0)), Loaded(), emptyList(), HERE, true).panes.single()
        assertEquals("looking…" to Tone.Ink3, pane.state to pane.tone)
    }

    @Test fun aGameWithNoNewestDeviceShowsADashLikeTheWeb() {
        val line = ui(listOf(game("k", "K", DeviceGameState.HasIt, newestOn = null))).panes.first { it.id == "eden" }.games.single()
        assertEquals("—", line.newestOn)
        assertEquals(Tone.Ink3, line.newestTone)
    }

    @Test fun emulatorStatesUseTheirCopy() {
        val panes = ui(emptyList()).panes.associate { it.id to (it.state to it.tone) }
        assertEquals(listOf("eden", "azahar", "melonds", "retroarch", "dolphin"), ui(emptyList()).panes.map { it.id })
        assertEquals("3 saves" to Tone.Ink2, panes["azahar"])
        assertEquals("1 save" to Tone.Ink2, panes["melonds"])
        assertEquals("config group" to Tone.Ink2, panes["retroarch"])
        assertEquals("not set up" to Tone.Ink3, panes["dolphin"])
    }

    @Test fun theRightPaneCountsTheFocusedEmulatorsGames() {
        show(ui(listOf(game("a", "A", DeviceGameState.Newest), game("b", "B", DeviceGameState.HasIt), game("c", "C", DeviceGameState.Newest, emu = "azahar"))))
        compose.onNodeWithText("Eden · 2 games").assertExists()
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Azahar · 1 game").assertExists()
    }

    @Test fun shizukuOffShowsTapToFixAndTheTapFixesIt() {
        var fixed = 0
        show(ui(emptyList()), status(shizuku = ShizukuState.NotRunning), fix = { fixed++ })
        compose.onNodeWithText("Shizuku is off: tap to fix").performClick()
        assertEquals(1, fixed)
    }

    @Test fun shizukuReadyShowsOn() {
        show(ui(emptyList()))
        compose.onNodeWithText("Shizuku on").assertExists()
        compose.onNodeWithText("Server connected").assertExists()
        compose.onNodeWithText("Thor").assertExists()
    }

    @Test fun offlineShowsTheLastUpdateTime() {
        show(ui(emptyList()), status(server = ServerConn.Offline, lastSync = Instant.parse("2026-11-19T21:48:00Z")))
        compose.onNodeWithText("Offline · last updated 21:48").assertExists()
    }

    @Test fun signedOutAsksToSignInAndAGoesToSetup() {
        var setup = 0
        show(ui(emptyList(), signedIn = false), status(server = ServerConn.SignedOut), DeviceActions(setup = { setup++ }))
        compose.onNodeWithText("Signed out").assertExists()
        compose.onNodeWithText("Sign in to see your games.").assertExists()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(1, setup)
        compose.onNodeWithText("Sign in").performClick()
        assertEquals(2, setup)
    }

    @Test fun xSendsScanThenApply() {
        val app = RuntimeEnvironment.getApplication()
        show(ui(emptyList()), actions = DeviceActions(syncNow = { syncNow(app) }))
        compose.key(KeyEvent.KEYCODE_BUTTON_X)
        val shadow = shadowOf(app)
        assertEquals(SyncService.ACTION_SCAN, shadow.nextStartedService?.action)
        assertEquals(SyncService.ACTION_APPLY, shadow.nextStartedService?.action)
    }

    @Test fun rightThenAOpensTheFocusedGame() {
        val opened = mutableListOf<Pair<String, String>>()
        show(ui(listOf(game("a", "A", DeviceGameState.Newest), game("b", "B", DeviceGameState.Newest))),
            actions = DeviceActions(openGame = { e, k -> opened += e to k }))
        compose.key(KeyEvent.KEYCODE_BUTTON_A)   // into the games pane
        assertEquals(emptyList<Pair<String, String>>(), opened)
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("eden" to "b"), opened)
        compose.key(KeyEvent.KEYCODE_BUTTON_B)   // back to the emulators
        compose.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("eden" to "b", "eden" to "b"), opened)
    }

    @Test fun tappingAGameInTheInactivePaneOnlyFocusesIt() {
        val opened = mutableListOf<String>()
        show(ui(listOf(game("a", "Alpha", DeviceGameState.Newest))), actions = DeviceActions(openGame = { _, k -> opened += k }))
        compose.onNodeWithText("Alpha").performClick()
        assertEquals(emptyList<String>(), opened)
        compose.onNodeWithText("Alpha").performClick()
        assertEquals(listOf("a"), opened)
    }

    @Test fun yOpensSettingsForTheFocusedEmulator() {
        val asked = mutableListOf<String>()
        show(ui(emptyList()), actions = DeviceActions(settings = { asked += it }))
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.key(KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(listOf("azahar"), asked)
    }

    @Test fun gameUrlEncodesEachSegment() {
        assertEquals(
            "http://h:5100/games/retro%20arch/snes/Mario%20%26%20Luigi%3F",
            gameUrl("http://h:5100/", "retro arch", "snes/Mario & Luigi?"),
        )
    }
}
