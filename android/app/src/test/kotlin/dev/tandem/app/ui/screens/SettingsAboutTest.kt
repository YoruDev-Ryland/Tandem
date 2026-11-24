package dev.tandem.app.ui.screens

import android.view.KeyEvent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.tandem.app.StoredTheme
import dev.tandem.app.modules.ModuleRegistry
import dev.tandem.app.sync.StateStore
import dev.tandem.app.ui.kit.Tone
import dev.tandem.app.ui.kit.key
import dev.tandem.app.update.UpdatePhase
import dev.tandem.app.update.UpdateUi
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class UpdateLineTest {
    private val now = Instant.parse("2026-11-23T12:00:00Z")
    private fun line(ui: UpdateUi) = updateLine(ui, now, ZoneOffset.UTC)

    @Test fun eachPhaseReadsAsOneLine() {
        val twoHoursAgo = now.minusSeconds(2 * 3600).toEpochMilli()
        assertEquals("Up to date · checked 2 h ago" to Tone.Ink2, line(UpdateUi(UpdatePhase.UpToDate, lastCheckAt = twoHoursAgo)))
        assertEquals("Up to date · checked just now" to Tone.Ink2, line(UpdateUi(UpdatePhase.UpToDate, lastCheckAt = now.toEpochMilli())))
        assertEquals("Downloading 0.3.0…" to Tone.Ink2, line(UpdateUi(UpdatePhase.Downloading("0.3.0"))))
        assertEquals("Checking for updates…" to Tone.Ink2, line(UpdateUi(UpdatePhase.Checking)))
        assertEquals("Not checked yet" to Tone.Ink3, line(UpdateUi(UpdatePhase.Unchecked)))
        assertEquals("Updates are off in debug builds" to Tone.Ink3, line(UpdateUi(UpdatePhase.Off)))
    }

    @Test fun aReadyUpdateIsWaitingInAmber() {
        assertEquals("0.3.0 is ready: it installs when sync is idle" to Tone.Wait,
            line(UpdateUi(UpdatePhase.Ready("0.3.0"), installQueued = true, canInstall = true)))
        assertEquals("0.3.0 is ready to install" to Tone.Wait,
            line(UpdateUi(UpdatePhase.Ready("0.3.0"), autoInstall = false, canInstall = true)))
        assertEquals("Installing 0.3.0…" to Tone.Wait, line(UpdateUi(UpdatePhase.Installing("0.3.0"))))
    }

    @Test fun aFailureIsAProblemInRed() {
        assertEquals("Couldn't check for updates: GitHub answered 403" to Tone.Fault,
            line(UpdateUi(UpdatePhase.Failed("Couldn't check for updates: GitHub answered 403"))))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class SettingsAboutTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    @After fun stop() = scope.cancel()

    private val update = MutableStateFlow(UpdateUi(UpdatePhase.UpToDate, lastCheckAt = System.currentTimeMillis()))
    private val did = mutableListOf<String>()

    private fun show() {
        val store = StateStore(File(tmp.root, "state.json")).apply { serverUrl = "http://nas:5100"; deviceKey = "k"; deviceId = "d" }
        val vm = SettingsViewModel(
            store, ModuleRegistry.default().all(), installed = { emptySet() }, label = { null },
            deviceName = MutableStateFlow("Thor"), reload = {}, stopSync = {}, afterSignOut = {}, scope = scope,
            appVersion = "0.2.0",
            updates = UpdateControls(update, check = { did += "check" }, install = { did += "install" },
                setAutoInstall = { did += "auto $it"; update.value = update.value.copy(autoInstall = it) }),
        )
        vm.select(SettingsSection.About)
        compose.setContent {
            StoredTheme(store) {
                val ui by vm.ui.collectAsState()
                SettingsScreen(ui, top = {}, actions = vm.actions(null, {}, {}) { "licence text" })
            }
        }
    }

    @Test fun theVersionAndUpdateStateShareALineAndAChecksForUpdates() {
        show()
        compose.onNodeWithText("0.2.0").assertIsDisplayed()
        compose.onNodeWithText("Up to date · checked just now").assertIsDisplayed()
        compose.onNodeWithText("Check now").assertIsDisplayed()   // in the hint bar
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("check"), did)
    }

    @Test fun aReadyUpdateOffersInstallNowOnX() {
        update.value = UpdateUi(UpdatePhase.Ready("0.3.0"), installQueued = false, autoInstall = false, canInstall = true)
        show()
        compose.onNodeWithText("0.3.0 is ready to install").assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_BUTTON_X)
        assertEquals(listOf("install"), did)
    }

    @Test fun noInstallNowWithoutAReadyUpdate() {
        show()
        compose.onNodeWithText("Install now").assertDoesNotExist()
        compose.key(KeyEvent.KEYCODE_BUTTON_X)
        assertEquals(emptyList<String>(), did)
    }

    @Test fun theAutomaticInstallSwitchTurnsOffAndOn() {
        show()
        compose.onNodeWithText(AUTO_UPDATE_COPY).assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Turn off").assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        compose.waitForIdle()
        compose.onNodeWithText("Turn on").assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("auto false", "auto true"), did)
    }

    @Test fun aDebugBuildSaysUpdatesAreOffAndOffersNoUpdateActions() {
        update.value = UpdateUi(UpdatePhase.Off)
        show()
        compose.onNodeWithText("Updates are off in debug builds").assertIsDisplayed()
        compose.onNodeWithText("Check now").assertDoesNotExist()
        compose.onNodeWithText(AUTO_UPDATE_COPY).assertDoesNotExist()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)   // nothing to do on the version row
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)  // the next row is the licences
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("licence text").assertIsDisplayed()
        assertEquals(emptyList<String>(), did)
    }

    @Test fun nothingToCheckWhileAChecksRunning() {
        update.value = UpdateUi(UpdatePhase.Downloading("0.3.0"))
        show()
        compose.onNodeWithText("Downloading 0.3.0…").assertIsDisplayed()
        compose.onNodeWithText("Check now").assertDoesNotExist()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(emptyList<String>(), did)
    }

    @Test fun touchWorksToo() {
        update.value = UpdateUi(UpdatePhase.Ready("0.3.0"), canInstall = true, installQueued = true)
        show()
        compose.onNodeWithText("0.3.0 is ready: it installs when sync is idle").performClick()   // the cursor row: opens it
        assertEquals(listOf("check"), did)
    }
}
