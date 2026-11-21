package dev.tandem.app.ui.setup

import android.view.KeyEvent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.SavedStateHandle
import dev.tandem.app.api.Api
import dev.tandem.app.api.ApiException
import dev.tandem.app.fs.ShizukuState
import dev.tandem.app.model.ApplyConfigRequest
import dev.tandem.app.model.ApplyConfigResponse
import dev.tandem.app.model.ApplyMode
import dev.tandem.app.model.ConfigOverview
import dev.tandem.app.model.ConflictReason
import dev.tandem.app.model.ConflictRow
import dev.tandem.app.model.ConflictSide
import dev.tandem.app.model.DeviceKeyResponse
import dev.tandem.app.model.DeviceRef
import dev.tandem.app.model.EmulatorConfigRow
import dev.tandem.app.model.GroupRow
import dev.tandem.app.model.ItemKind
import dev.tandem.app.model.ResolveChoice
import dev.tandem.app.model.ResolveConflictRequest
import dev.tandem.app.model.ServerInfo
import dev.tandem.app.model.SoloRow
import dev.tandem.app.modules.Detected
import dev.tandem.app.modules.ModuleRegistry
import dev.tandem.app.service.EmulatorInfo
import dev.tandem.app.service.ServiceSnapshot
import dev.tandem.app.service.SyncService
import dev.tandem.app.sync.StateStore
import dev.tandem.app.ui.kit.key
import dev.tandem.app.ui.screens.SettingsViewModel
import dev.tandem.app.ui.state.ServerConn
import dev.tandem.app.ui.state.TopStatus
import dev.tandem.app.ui.theme.EmuTheme
import dev.tandem.app.ui.theme.ThemeMode
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val ME = "dev-me"
private const val EDEN = "dev.eden.eden_emulator"
private const val DUO = "dev.igawa6.edenduo"
private const val RA = "com.retroarch.aarch64"

private val unused: Api = Proxy.newProxyInstance(Api::class.java.classLoader, arrayOf(Api::class.java)) { _, m, _ ->
    throw UnsupportedOperationException(m.name)
} as Api

private fun info(id: String, pkg: String, items: Int = 0) = EmulatorInfo(Detected(id, pkg, "", "/data/$id"), items)

private fun side(device: String, name: String, head: Boolean) =
    ConflictSide("v-$device", device, name, "2026-11-19T21:48:00Z", 1024, 1, "abc", head)

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class SetupFlowTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store by lazy { StateStore(File(tmp.root, "state.json")) }
    private val modules = ModuleRegistry.default().all()
    private val shizuku = MutableStateFlow(ShizukuState.Ready)
    private val snapshot = MutableStateFlow(
        ServiceSnapshot(running = true, live = true, emulators = listOf(info("eden", EDEN, 3), info("retroarch", RA, 5))),
    )
    private val services = mutableListOf<String?>()
    private var asked = 0
    private var reloads = 0
    private var done = 0
    private var diagnostics = 0
    private var patience = 120_000L
    /** The flow's clock, in ms; tests move it by hand. */
    private var clock = 10_000L

    // The fake server.
    private var info = ServerInfo("Home server", 1, registrationOpen = false)
    private var redeem: () -> DeviceKeyResponse = { DeviceKeyResponse("u1", ME, "k1") }
    private var overview = ConfigOverview(emptyList())
    private var conflicts: List<ConflictRow> = emptyList()
    private var applyFails: Exception? = null
    private var joinFails: Exception? = null
    private val joined = mutableListOf<Pair<String, String>>()
    private val applied = mutableListOf<ApplyConfigRequest>()
    private val kept = mutableListOf<Pair<String, String>>()
    private val resolved = mutableListOf<Pair<String, ResolveConflictRequest>>()
    private val redeemed = mutableListOf<String>()

    private val server: Api = object : Api by unused {
        override suspend fun serverInfo() = info
        override suspend fun redeemPairing(code: String, deviceName: String): DeviceKeyResponse { redeemed += code; return redeem() }
        override suspend fun configs() = overview
        override suspend fun joinGroup(groupId: String, deviceId: String) { joinFails?.let { throw it }; joined += groupId to deviceId }
        override suspend fun applyConfig(req: ApplyConfigRequest): ApplyConfigResponse {
            applyFails?.let { throw it }
            applied += req
            return ApplyConfigResponse("b", "h", null)
        }
        override suspend fun keepOwn(deviceId: String, emulatorId: String) { kept += deviceId to emulatorId }
        override suspend fun conflicts() = conflicts
        override suspend fun resolveConflict(itemId: String, req: ResolveConflictRequest) { resolved += itemId to req }
    }

    private val settings by lazy {
        SettingsViewModel(
            store, modules, installed = { setOf(EDEN, DUO, RA) }, label = { if (it == DUO) "Eden Duo" else null },
            deviceName = MutableStateFlow("Thor"), reload = { reloads++ }, stopSync = {}, afterSignOut = {}, scope = scope,
        )
    }

    @After fun stop() = scope.cancel()

    private fun signedIn() {
        store.serverUrl = "http://nas:5100"
        store.deviceKey = "key"
        store.deviceId = ME
    }

    private fun vm(handle: SavedStateHandle = SavedStateHandle()) = SetupViewModel(
        handle = handle, store = store, modules = modules, anon = { server }, api = { server },
        shizuku = shizuku, askShizuku = { asked++ }, snapshot = snapshot, settings = settings,
        service = { services += it }, afterSignIn = {}, scope = scope, running = MutableStateFlow(emptySet()),
        emulatorName = { id -> modules.first { it.emulatorId == id }.displayName }, defaultDeviceName = "Thor", scanPatienceMillis = patience,
    )

    private var shown: MutableState<SetupViewModel>? = null

    /** Shows [vm]; a second call swaps the model under the same content, as a recreated activity would. */
    private fun show(vm: SetupViewModel) {
        shown?.let { it.value = vm; compose.waitForIdle(); return }
        val holder = mutableStateOf(vm).also { shown = it }
        val status = TopStatus("Thor", ServerConn.Connected, ShizukuState.Ready, signedIn = true)
        compose.setContent {
            EmuTheme(ThemeMode.Dark) {
                key(holder.value) { SetupFlow(holder.value, status, openShizuku = null, onDone = { done++ }, diagnostics = { diagnostics++ }, now = { clock }) }
            }
        }
    }

    private fun handleAt(step: SetupStep) = SavedStateHandle(mapOf("setup.active" to true, "setup.step" to step.name))

    // ---- step 1 ----

    @Test fun aRefusedPairingCodeIsShownInPlace() {
        redeem = { throw ApiException(400, "invalid_code", "bad code") }
        val vm = vm()
        show(vm)
        compose.onNodeWithTag("setup-url").performTextReplacement("http://nas:5100/")
        compose.onNodeWithText("Connect").performClick()
        compose.onNodeWithText("Sign in to Home server").assertIsDisplayed()
        compose.onNodeWithText("Email and password").assertIsDisplayed()
        compose.onNodeWithText("Create an account").assertDoesNotExist()   // registration is closed
        compose.key(KeyEvent.KEYCODE_BUTTON_A)   // the cursor starts on the pairing code
        compose.onNodeWithTag("setup-code").performTextReplacement("abcd1234")
        compose.onNodeWithText("Sign in").performClick()
        assertEquals(listOf("ABCD1234"), redeemed)
        compose.onNodeWithText("That code is not valid. Check it and try again.").assertIsDisplayed()
        assertEquals(SetupStep.Server, vm.step.value)
        assertNull(store.deviceKey)
    }

    @Test fun signingInStoresTheKeyForgetsEveryEmulatorAndRestartsSync() {
        shizuku.value = ShizukuState.NotRunning
        val vm = vm()
        show(vm)
        compose.onNodeWithTag("setup-url").performTextReplacement("http://nas:5100")
        compose.onNodeWithText("Connect").performClick()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithTag("setup-code").performTextReplacement("ABCD1234")
        compose.onNodeWithText("Sign in").performClick()
        assertEquals("k1", store.deviceKey)
        assertEquals(ME, store.deviceId)
        assertEquals("http://nas:5100", store.serverUrl)
        assertEquals(modules.map { it.emulatorId }.toSet(), store.pendingForget)
        assertTrue(SyncService.ACTION_RELOAD in services)
        assertEquals(SetupStep.Shizuku, vm.step.value)
    }

    @Test fun bOnTheFirstScreenStaysAtLaunchAndLeavesWhenOpenedFromSettings() {
        val vm = vm()
        show(vm)
        compose.key(KeyEvent.KEYCODE_BUTTON_B)
        assertTrue(vm.active.value)
        vm.start(exitable = true)
        compose.waitForIdle()
        compose.key(KeyEvent.KEYCODE_BUTTON_B)
        assertFalse(vm.active.value)
    }

    // ---- step 2 ----

    @Test fun shizukuWithoutPermissionAsksOnA() {
        signedIn()
        shizuku.value = ShizukuState.NoPermission
        val vm = vm(handleAt(SetupStep.Shizuku))
        show(vm)
        compose.onNodeWithText("Allow Tandem when Shizuku asks.").assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(1, asked)
    }

    @Test fun shizukuReadyMovesOnByItself() {
        signedIn()
        shizuku.value = ShizukuState.NotRunning
        val vm = vm(handleAt(SetupStep.Shizuku))
        show(vm)
        compose.onNodeWithText("Open Shizuku and start it (Wireless debugging or root).").assertIsDisplayed()
        shizuku.value = ShizukuState.Ready
        compose.waitForIdle()
        assertEquals(SetupStep.Emulators, vm.step.value)
    }

    // ---- step 3 ----

    @Test fun choosingAVariantWritesItTheWaySettingsDoes() {
        signedIn()
        val vm = vm(handleAt(SetupStep.Emulators))
        show(vm)
        compose.onNodeWithText("Eden Duo").assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)   // Eden is active; the next row is Eden Duo
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(DUO, store.settings.variants["eden"])
        assertTrue("eden" in store.pendingForget)   // the same forget protection as Settings
        assertEquals(1, reloads)
    }

    // ---- step 4 ----

    private fun configs() {
        overview = ConfigOverview(
            listOf(
                EmulatorConfigRow(
                    "eden",
                    groups = listOf(GroupRow("g1", "AYN handhelds", listOf(DeviceRef("dev-thor", "Thor")), "gv1", false)),
                    solo = listOf(
                        SoloRow(DeviceRef("dev-odin", "Odin 3"), "b1", "ov1", false),
                        SoloRow(DeviceRef(ME, "Me"), "b2", "mv1", false),
                        SoloRow(DeviceRef("dev-new", "New"), "b3", null, false),   // nothing backed up: nothing to copy
                    ),
                ),
                EmulatorConfigRow(
                    "retroarch",
                    groups = listOf(
                        GroupRow("g3", "Handhelds", listOf(DeviceRef(ME, "Me"), DeviceRef("dev-odin", "Odin 3")), "hv", false),
                        GroupRow("g4", "Living room", listOf(DeviceRef("dev-deck", "Deck")), "lv", false),
                    ),
                    solo = listOf(SoloRow(DeviceRef("dev-thor", "Thor"), "b4", "tv", false)),
                ),
            ),
        )
    }

    @Test fun syncChoicesMatchTheServerAndCallTheRightApi() {
        signedIn()
        configs()
        val vm = vm(handleAt(SetupStep.SyncChoice))
        show(vm)
        compose.onNodeWithText("Eden settings").assertIsDisplayed()
        compose.onNodeWithText("Join \"AYN handhelds\" with Thor").assertIsDisplayed()
        compose.onNodeWithText("Copy Odin 3's settings once").assertIsDisplayed()
        compose.onNodeWithText("Only back up this device's settings").assertIsDisplayed()
        compose.onNodeWithText("Copy Me's settings once").assertDoesNotExist()   // this device's own
        compose.onNodeWithText("Copy New's settings once").assertDoesNotExist()
        compose.key(KeyEvent.KEYCODE_DPAD_UP)    // from "Only back up", where the cursor starts
        compose.key(KeyEvent.KEYCODE_BUTTON_A)   // Copy Odin 3's
        assertEquals(listOf(ApplyConfigRequest(ME, "eden", "ov1", ApplyMode.Copy)), applied)
        assertTrue(SyncService.ACTION_APPLY in services)

        // RetroArch: this device follows "Handhelds", so that group is not offered to join, and nothing is copied over it.
        compose.onNodeWithText("RetroArch settings").assertIsDisplayed()
        compose.onNodeWithText("Join \"Handhelds\" with Odin 3").assertDoesNotExist()
        compose.onNodeWithText("Keep following \"Handhelds\"").assertIsDisplayed()
        compose.onNodeWithText("Copy Thor's settings once").assertDoesNotExist()
        compose.key(KeyEvent.KEYCODE_DPAD_UP)
        clock += 1_000                           // the next emulator's screen has been read
        compose.key(KeyEvent.KEYCODE_BUTTON_A)   // Join "Living room"
        assertEquals(listOf("g4" to ME), joined)
        assertEquals(SetupStep.FirstSync, vm.step.value)
    }

    @Test fun step4StartsOnOnlyBackUpSoAChangesNothing() {
        signedIn()
        configs()
        show(vm(handleAt(SetupStep.SyncChoice)))
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf(ME to "eden"), kept)
        assertTrue(applied.isEmpty())
        assertTrue(joined.isEmpty())
    }

    @Test fun aQuickSecondANeverChoosesForTheNextEmulator() {
        signedIn()
        configs()
        show(vm(handleAt(SetupStep.SyncChoice)))
        compose.key(KeyEvent.KEYCODE_DPAD_UP)
        compose.key(KeyEvent.KEYCODE_BUTTON_A)   // Eden: copy Odin 3's
        compose.onNodeWithText("RetroArch settings").assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)   // at once: RetroArch's screen has not been read yet
        assertTrue(kept.isEmpty())
        assertTrue(joined.isEmpty())
        clock += 1_000
        compose.key(KeyEvent.KEYCODE_BUTTON_A)   // now: "Only back up", where the cursor starts
        assertEquals(listOf(ME to "retroarch"), kept)
    }

    @Test fun aConfigErrorIsShownOnItsRowInConfigWords() {
        signedIn()
        configs()
        joinFails = ApiException(409, "already_in_group", "in a group")
        val vm = vm(handleAt(SetupStep.SyncChoice))
        show(vm)
        compose.key(KeyEvent.KEYCODE_DPAD_UP)
        compose.key(KeyEvent.KEYCODE_DPAD_UP)
        compose.key(KeyEvent.KEYCODE_BUTTON_A)   // Join "AYN handhelds"
        compose.onNodeWithText("That device already follows a group. Take it out of the group first.").assertIsDisplayed()
        compose.onNodeWithText("Eden settings").assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.key(KeyEvent.KEYCODE_BUTTON_A)   // Only back up
        assertEquals(listOf(ME to "eden"), kept)
        compose.onNodeWithText("RetroArch settings").assertIsDisplayed()
    }

    @Test fun recreatedOnStep4ItComesBackThereWithTheEarlierChoices() {
        signedIn()
        configs()
        val handle = handleAt(SetupStep.SyncChoice)
        val first = vm(handle)
        show(first)
        compose.key(KeyEvent.KEYCODE_DPAD_UP)
        compose.key(KeyEvent.KEYCODE_DPAD_UP)
        compose.key(KeyEvent.KEYCODE_BUTTON_A)   // Eden: join "AYN handhelds"
        compose.onNodeWithText("RetroArch settings").assertIsDisplayed()

        // Process death: only what the saved state holds comes back.
        val restored = vm(SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
        show(restored)
        assertEquals(SetupStep.SyncChoice, restored.step.value)
        compose.onNodeWithText("RetroArch settings").assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithText("Eden settings").assertIsDisplayed()
        compose.onNodeWithText("Your choice").assertIsDisplayed()
        assertEquals(listOf("g1" to ME), joined)   // shown, not sent again
    }

    // ---- step 5 ----

    @Test fun aFailedScanIsNeverAllSetAndRetriesTheScan() {
        signedIn()
        val vm = vm(handleAt(SetupStep.FirstSync))
        show(vm)
        assertEquals(1, services.count { it == SyncService.ACTION_SCAN })
        snapshot.value = snapshot.value.copy(failedScans = snapshot.value.failedScans + 1)
        compose.waitForIdle()
        compose.onNodeWithText("All set.").assertDoesNotExist()
        compose.onNodeWithText(SCAN_FAILED).assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)   // Try again: a new scan
        assertEquals(2, services.count { it == SyncService.ACTION_SCAN })
        compose.onNodeWithText(SCAN_FAILED).assertDoesNotExist()
        snapshot.value = snapshot.value.copy(scans = snapshot.value.scans + 1)
        compose.waitForIdle()
        compose.onNodeWithText("All set.").assertIsDisplayed()
    }

    @Test fun aLongScanSaysSoAndCanBeFinishedLater() {
        signedIn()
        patience = 0
        val vm = vm(handleAt(SetupStep.FirstSync))
        show(vm)
        compose.waitUntil(5_000) { compose.onAllNodesWithText(STILL_SCANNING).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("All set.").assertDoesNotExist()
        compose.key(KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(1, done)
        assertFalse(vm.active.value)
    }

    // ---- leaving early (signed in) ----

    @Test fun signedInWithoutShizukuYFinishesLater() {
        signedIn()
        shizuku.value = ShizukuState.NotRunning
        val vm = vm(handleAt(SetupStep.Shizuku))   // opened at launch: not exitable
        show(vm)
        compose.key(KeyEvent.KEYCODE_BUTTON_Y)
        assertEquals(1, done)
        assertFalse(vm.active.value)
    }

    @Test fun continueWithoutShizukuMovesToEmulators() {
        signedIn()
        shizuku.value = ShizukuState.NotRunning
        val vm = vm(handleAt(SetupStep.Shizuku))
        show(vm)
        compose.key(KeyEvent.KEYCODE_BUTTON_X)
        assertEquals(SetupStep.Emulators, vm.step.value)
    }

    @Test fun signedInBOnTheSignInSummaryLeavesEvenAtLaunch() {
        signedIn()
        val vm = vm(handleAt(SetupStep.Server))
        show(vm)
        compose.key(KeyEvent.KEYCODE_BUTTON_B)
        assertFalse(vm.active.value)
    }

    @Test fun diagnosticsOpensFromStep1() {
        val vm = vm()
        show(vm)
        compose.onNodeWithText("Diagnostics").performClick()
        assertEquals(1, diagnostics)
    }

    @Test fun firstSyncWithNothingToDecideIsAllSet() {
        signedIn()
        conflicts = listOf(
            ConflictRow("c1", "eden", "k1", "Concurrent game", ItemKind.Save, null, null, ConflictReason.Concurrent,
                listOf(side(ME, "Me", false), side("dev-odin", "Odin 3", true))),
            ConflictRow("c2", "eden", "k2", "Not mine", ItemKind.Save, null, null, ConflictReason.FirstSync,
                listOf(side("dev-thor", "Thor", false), side("dev-odin", "Odin 3", true))),
        )
        val vm = vm(handleAt(SetupStep.FirstSync))
        show(vm)
        assertTrue(SyncService.ACTION_SCAN in services)
        compose.onNodeWithText("All set.").assertDoesNotExist()   // the scan has not finished
        snapshot.value = snapshot.value.copy(scans = snapshot.value.scans + 1)
        compose.waitForIdle()
        compose.onNodeWithText("All set.").assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(1, done)
        assertFalse(vm.active.value)
    }

    @Test fun firstSyncReviewDecidesWithTheFaceButtons() {
        signedIn()
        conflicts = listOf(
            ConflictRow("c1", "eden", "k1", "Pokémon Brilliant Diamond", ItemKind.Save, null, null, ConflictReason.FirstSync,
                listOf(side(ME, "Me", false), side("dev-odin", "Odin 3", true))),
        )
        val vm = vm(handleAt(SetupStep.FirstSync))
        show(vm)
        snapshot.value = snapshot.value.copy(scans = snapshot.value.scans + 1)
        compose.waitForIdle()
        compose.onNodeWithText("Pokémon Brilliant Diamond").assertIsDisplayed()
        compose.onAllNodesWithText("Use Odin 3's")[0].assertExists()
        conflicts = emptyList()   // decided: the server stops listing it
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("c1" to ResolveConflictRequest(ResolveChoice.KeepVersion, "v-$ME", null)), resolved)
        compose.onNodeWithText("All set.").assertIsDisplayed()
    }
}
