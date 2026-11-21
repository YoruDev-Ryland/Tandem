package dev.tandem.app.ui.screens

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.tandem.app.ui.feedback.Notice
import dev.tandem.app.ui.kit.LocalNotice
import dev.tandem.app.ui.kit.LocalReadout
import dev.tandem.app.ui.kit.LocalStatusLine
import dev.tandem.app.ui.kit.Presence
import dev.tandem.app.ui.kit.Readout
import androidx.compose.ui.test.performClick
import org.robolectric.RuntimeEnvironment
import dev.tandem.app.fs.ShizukuState
import dev.tandem.app.model.DeviceGameState
import dev.tandem.app.model.GameDeviceState
import dev.tandem.app.model.GameRow
import dev.tandem.app.service.ReceivedAt
import dev.tandem.app.service.SyncService
import dev.tandem.app.sync.ReceivedDelivery
import dev.tandem.app.sync.StateStore
import java.io.File
import dev.tandem.app.sync.WaitingDelivery
import dev.tandem.app.ui.kit.Tone
import dev.tandem.app.ui.kit.TopBar
import dev.tandem.app.ui.kit.key
import dev.tandem.app.ui.state.EmuRowState
import dev.tandem.app.ui.state.EmulatorRowUi
import dev.tandem.app.ui.state.Loaded
import dev.tandem.app.ui.state.ServerConn
import dev.tandem.app.ui.state.TopStatus
import dev.tandem.app.ui.theme.EmuTheme
import dev.tandem.app.ui.theme.ThemeMode
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
                    TopBar(listOf("This device", "Activity", "Conflicts", "Settings"), 0, {}, status, fix)
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

    @Test fun aRevokedDeviceSaysSoAndASignsInAgain() {
        var again = 0
        show(ui(emptyList()).copy(revoked = true), status(ServerConn.Revoked), DeviceActions(pairAgain = { again++ }))
        compose.onNodeWithText(REVOKED_LINE).assertExists()
        compose.onNodeWithText("Removed from server").assertExists()   // the top bar
        compose.onNodeWithText("Emulators").assertDoesNotExist()
        compose.key(android.view.KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(1, again)
    }

    @Test fun theStatusAreaSaysWhenThisDeviceLastSynced() {
        compose.setContent {
            EmuTheme(ThemeMode.Dark) {
                CompositionLocalProvider(LocalStatusLine provides "Synced 5 min ago") {
                    DeviceScreen(ui(emptyList()), top = {}, actions = DeviceActions())
                }
            }
        }
        compose.onNodeWithText("Synced 5 min ago").assertExists()
    }

    @Test fun syncedAgoTicksWithTheClockItIsGiven() {
        val at = Instant.parse("2026-11-21T11:55:00Z")
        assertEquals("Synced just now", syncedAgo(at, at.plusSeconds(30), ZoneOffset.UTC))
        assertEquals("Synced 5 min ago", syncedAgo(at, Instant.parse("2026-11-21T12:00:00Z"), ZoneOffset.UTC))
        assertEquals("Synced 6 min ago", syncedAgo(at, Instant.parse("2026-11-21T12:01:30Z"), ZoneOffset.UTC))
        assertEquals("Synced 2 h ago", syncedAgo(at, Instant.parse("2026-11-21T14:00:00Z"), ZoneOffset.UTC))
        assertEquals("Synced Nov 21, 11:55", syncedAgo(at, Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC))
        assertEquals(null, syncedAgo(null, at, ZoneOffset.UTC))
    }

    @Test fun aSuccessLineStandsInForTheStatusWithItsWords() {
        compose.setContent {
            EmuTheme(ThemeMode.Dark) {
                CompositionLocalProvider(LocalStatusLine provides "Synced 5 min ago", LocalNotice provides Notice(1, "Pokémon Sword from Odin 3 is in place.")) {
                    DeviceScreen(ui(emptyList()), top = {}, actions = DeviceActions())
                }
            }
        }
        compose.onNodeWithText("Pokémon Sword from Odin 3 is in place.").assertExists()
        compose.onNodeWithText("Synced 5 min ago").assertDoesNotExist()
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

    @Test fun gamesCarryTheirSystemMarkAndAPresenceMarkPerOtherDevice() {
        val u = ui(listOf(game("0100ABF008968000", "Pokémon Sword", DeviceGameState.Waiting)))
        assertEquals(listOf(dev.tandem.app.ui.state.DeviceColumn("dev-odin", "Odin 3")), u.devices)
        val line = u.panes.first { it.id == "eden" }.games.single()
        assertEquals("SWITCH", line.system)
        assertEquals(listOf(PresenceCell(Presence.Newest, "Odin 3 has the newest save")), line.presence)
        show(u)
        compose.onNodeWithText("ODIN 3").assertExists()   // the column, headed by the device
        compose.onNodeWithContentDescription("Odin 3 has the newest save").assertExists()
        compose.onNodeWithContentDescription("System: SWITCH").assertExists()
    }

    @Test fun aRetroArchGameTakesItsSystemFromTheCoreAndItsNameWithoutThePrefix() {
        val row = game("retroarch/Pokemon - Emerald Version", "x", DeviceGameState.HasIt, emu = "retroarch").copy(displayName = null)
        val u = deviceUi(sampleEmulators, Loaded(listOf(row), Instant.EPOCH), emptyList(), HERE, true, core = { _, _ -> "mgba" })
        val line = u.panes.first { it.id == "retroarch" }.games.single()
        assertEquals("GBA", line.system)
        assertEquals("Pokemon - Emerald Version", line.name)
        val unknown = deviceUi(sampleEmulators, Loaded(listOf(row), Instant.EPOCH), emptyList(), HERE, true)
        assertEquals(null, unknown.panes.first { it.id == "retroarch" }.games.single().system)
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

    @Test fun whenAllIsWellTheTopBarOnlyNamesTheDevice() {
        show(ui(emptyList()))
        compose.onNodeWithText("Thor").assertExists()
        compose.onNodeWithText("Shizuku on").assertDoesNotExist()
        compose.onNodeWithText("Server connected").assertDoesNotExist()
        compose.onNodeWithText("Server unreachable").assertDoesNotExist()
    }

    @Test fun anUnreachableServerIsSaidInTheTopBar() {
        show(ui(emptyList()), status(server = ServerConn.Offline, lastSync = Instant.parse("2026-11-19T21:48:00Z")))
        compose.onNodeWithText("Server unreachable").assertExists()
    }

    @Test fun theTopBarShowsTheClockAndBatteryWhenGiven() {
        compose.setContent {
            EmuTheme(ThemeMode.Dark) {
                CompositionLocalProvider(LocalReadout provides Readout("14:05", 82, charging = true)) {
                    DeviceScreen(ui(emptyList()), top = { TopBar(listOf("This device"), 0, {}, status(), {}) }, actions = DeviceActions())
                }
            }
        }
        compose.onNodeWithContentDescription("14:05, battery 82 percent, charging").assertExists()
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

    private fun store(key: String?) = StateStore(File.createTempFile("state", ".json").apply { delete(); deleteOnExit() })
        .also { it.deviceKey = key }

    @Test fun xSendsScanThenApply() {
        val app = RuntimeEnvironment.getApplication()
        val signedIn = store("k")
        show(ui(emptyList()), actions = DeviceActions(syncNow = { syncNow(app, signedIn) }))
        compose.key(KeyEvent.KEYCODE_BUTTON_X)
        val shadow = shadowOf(app)
        assertEquals(SyncService.ACTION_SCAN, shadow.nextStartedService?.action)
        assertEquals(SyncService.ACTION_APPLY, shadow.nextStartedService?.action)
    }

    // ---- final review (d): Sync now never starts the service signed out ----

    @Test fun syncNowWhileSignedOutDoesNotStartTheService() {
        val app = RuntimeEnvironment.getApplication()
        syncNow(app, store(null))
        assertEquals(null, shadowOf(app).nextStartedService)
    }

    // ---- final review (b): "Received {game}" when a held delivery lands, until the next refresh ----

    private val landedAt = Instant.parse("2026-11-20T21:58:49Z")
    private fun sword(at: Instant = landedAt) = ReceivedAt(ReceivedDelivery("d1", "eden", "0100ABF008968000", "Pokémon Sword"), at)
    private fun uiAt(updatedAt: Instant, received: List<ReceivedAt>, games: List<GameRow> = listOf(game("0100ABF008968000", "Pokémon Sword", DeviceGameState.Pending))) =
        deviceUi(sampleEmulators, Loaded(games, updatedAt), emptyList(), HERE, true, received)

    @Test fun aReceivedLineAppearsAfterADeliveredEvent() {
        val u = uiAt(landedAt.minusSeconds(30), listOf(sword()))
        assertEquals(listOf(ReceivedLine("Pokémon Sword from Odin 3 is in place.")), u.panes.first { it.id == "eden" }.received)
        assertEquals(emptyList<ReceivedLine>(), u.panes.first { it.id == "azahar" }.received)
        show(u)
        compose.onNodeWithText("Pokémon Sword from Odin 3 is in place.").assertExists()
    }

    @Test fun aReceivedLineSurvivesTheRefreshThatRacesItAndGoesOnTheNextOne() {
        // The refresh the exit itself triggers may finish just after the apply: the line stays for one cycle.
        assertEquals(1, uiAt(landedAt.plusSeconds(2), listOf(sword())).panes.first { it.id == "eden" }.received.size)
        assertEquals(0, uiAt(landedAt.plusSeconds(60), listOf(sword())).panes.first { it.id == "eden" }.received.size)
    }

    @Test fun aReceivedConfigNamesTheEmulatorsSettings() {
        val cfg = ReceivedAt(ReceivedDelivery("d2", "eden", null, null), landedAt)
        assertEquals(listOf(ReceivedLine("Eden settings are in place.")), uiAt(landedAt, listOf(cfg)).panes.first { it.id == "eden" }.received)
    }

    @Test fun aReceivedGameWithoutATitleUsesTheServersName() {
        val r = ReceivedAt(ReceivedDelivery("d3", "eden", "0100ABF008968000", null), landedAt)
        assertEquals(listOf(ReceivedLine("Pokémon Sword from Odin 3 is in place.")), uiAt(landedAt, listOf(r)).panes.first { it.id == "eden" }.received)
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
