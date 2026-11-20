package dev.emusync.app.ui.screens

import android.view.KeyEvent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onNodeWithTag
import dev.emusync.app.StoredTheme
import dev.emusync.app.fs.ShizukuState
import dev.emusync.app.model.GameRow
import dev.emusync.app.modules.ModuleRegistry
import dev.emusync.app.service.AppGraph
import dev.emusync.app.sync.ConfigState
import dev.emusync.app.sync.ItemState
import dev.emusync.app.sync.StateStore
import dev.emusync.app.ui.kit.TopBar
import dev.emusync.app.ui.kit.key
import dev.emusync.app.ui.state.AppState
import dev.emusync.app.ui.state.Loaded
import dev.emusync.app.ui.state.ServerView
import dev.emusync.app.ui.theme.EmuColors
import dev.emusync.app.ui.theme.LocalEmu
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val EDEN = "dev.eden.eden_emulator"
private const val DUO = "dev.igawa6.edenduo"

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var reloads = 0
    private var refreshed = 0
    private var stops = 0
    private var installed = setOf(EDEN, DUO, "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold")

    @After fun stop() = scope.cancel()

    private fun store(signedIn: Boolean = true) = StateStore(File(tmp.root, "state.json")).apply {
        serverUrl = "http://nas:5100"
        if (signedIn) { deviceKey = "secret-key"; deviceId = "dev-thor" }
    }

    private fun vm(store: StateStore) = SettingsViewModel(
        store, ModuleRegistry.default().all(), installed = { installed }, label = { null },
        deviceName = MutableStateFlow("Thor"), reload = { reloads++ },
        stopSync = { stops++ }, afterSignOut = { refreshed++ }, scope = scope,
    )

    private class NoServer : ServerView {
        override val deviceName = MutableStateFlow<String?>("Thor")
        override val configGroups = MutableStateFlow<Map<String, String>>(emptyMap())
        override val games = MutableStateFlow<Loaded<List<GameRow>>>(Loaded())
    }

    /** The tab as MainActivity shows it: the stored theme around it and the real top bar over it. */
    private fun show(store: StateStore, vm: SettingsViewModel, colors: (EmuColors) -> Unit = {}) {
        val app = AppState(AppGraph, store, MutableStateFlow(ShizukuState.Ready), scope, NoServer())
        compose.setContent {
            StoredTheme(store) {
                colors(LocalEmu.current)
                val ui by vm.ui.collectAsState()
                val status by app.top.collectAsState()
                SettingsScreen(ui, top = { TopBar(listOf("Settings"), 0, {}, status, null) }, actions = vm.actions({}, {}))
            }
        }
    }

    private fun r2(times: Int) = repeat(times) { compose.key(KeyEvent.KEYCODE_BUTTON_R2) }

    @Test fun choosingLightRecomposesWithTheLightColours() {
        val st = store().apply { settings = settings.copy(theme = "dark") }
        var colors: EmuColors? = null
        show(st, vm(st)) { colors = it }
        assertSame(EmuColors.Dark, colors)
        r2(2)   // Sync › Emulators › Appearance
        compose.onNodeWithText("Light").assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_DPAD_UP)   // the cursor starts on the theme in use (Dark)
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertSame(EmuColors.Light, colors)
        assertEquals("light", st.settings.theme)
        assertEquals(0, reloads)   // colours are the app's own: sync is not restarted
    }

    @Test fun signOutClearsTheKeyAndTheTopBarSaysSignedOut() {
        val st = store().apply {
            put("eden|Save|0100ABF008968000||", ItemState("v1", "m", "m"))
            putConfigState("eden", ConfigState("b", "c1", "m", "m"))
        }
        show(st, vm(st))
        compose.onNodeWithText("Signed out").assertDoesNotExist()
        r2(3)   // › Account
        compose.onNodeWithText("http://nas:5100").assertIsDisplayed()
        compose.onNodeWithText("secret-key", substring = true).assertDoesNotExist()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        compose.onNodeWithText("This device stops syncing until you sign in again. Nothing on it is deleted.").assertIsDisplayed()
        assertEquals("secret-key", st.deviceKey)   // only the confirm signs out
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertNull(st.deviceKey)
        assertNull(st.deviceId)
        assertEquals("http://nas:5100", st.serverUrl)
        // What this device knew of the server goes with the key; the save files are not touched.
        assertNull(st.get("eden|Save|0100ABF008968000||"))
        assertNull(st.configState("eden"))
        assertEquals(1, stops)     // sync stops rather than restarting signed out
        assertEquals(0, reloads)
        assertEquals(1, refreshed)
        compose.onNodeWithText("Signed out").assertIsDisplayed()
    }

    @Test fun cancellingTheSignOutSheetKeepsTheDeviceSignedIn() {
        val st = store()
        show(st, vm(st))
        r2(3)
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        compose.key(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithText("This device stops syncing until you sign in again. Nothing on it is deleted.").assertDoesNotExist()
        assertEquals("secret-key", st.deviceKey)
        assertEquals(0, reloads)
        assertEquals(0, stops)
    }

    @Test fun choosingAnInstalledVariantMakesItActiveAndReloadsSync() {
        val st = store()
        val vm = vm(st)
        show(st, vm)
        r2(1)   // › Emulators
        compose.onNodeWithText(DUO).assertIsDisplayed()
        assertTrue(vm.ui.value.variants.single { it.pkg == EDEN }.active)
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)   // Eden's second variant
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(DUO, st.settings.variants["eden"])
        assertEquals(1, reloads)
        assertTrue(vm.ui.value.variants.single { it.pkg == DUO }.active)
        assertFalse(vm.ui.value.variants.single { it.pkg == EDEN }.active)
        compose.key(KeyEvent.KEYCODE_BUTTON_A)   // already active: nothing to restart
        assertEquals(1, reloads)
    }

    @Test fun emulatorSettingsFromThisDeviceOpenOnThatEmulator() {
        val st = store()
        val vm = vm(st)
        vm.focusEmulator("ppsspp")
        show(st, vm)
        compose.onNodeWithText("org.ppsspp.ppssppgold").assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)   // from PPSSPP's first variant to its second
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals("org.ppsspp.ppssppgold", st.settings.variants["ppsspp"])
    }

    @Test fun autoApplyToggleSavesAndReloads() {
        val st = store()
        show(st, vm(st))
        compose.onNodeWithText("Apply newer saves automatically when the emulator is closed").assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertTrue(st.settings.autoApply)
        assertEquals(1, reloads)
    }

    @Test fun addingARomFolderByTouch() {
        val st = store()
        show(st, vm(st))
        compose.onNodeWithText("Add a ROM folder").performClick().performClick()
        compose.onNodeWithTag("settings-field").performTextReplacement("/sdcard/Roms")
        compose.onNodeWithTag("settings-field").performImeAction()
        assertEquals(listOf("/sdcard/Roms"), st.settings.romDirs)
        compose.onNodeWithText("/sdcard/Roms").assertIsDisplayed()
    }

    @Test fun aDuplicateRomFolderIsRefusedInTheSheet() {
        val st = store().apply { settings = settings.copy(romDirs = listOf("/sdcard/Roms")) }
        show(st, vm(st))
        compose.onNodeWithText("Add a ROM folder").performClick().performClick()
        compose.onNodeWithTag("settings-field").performTextReplacement("/storage/emulated/0/Roms/")
        compose.onNodeWithTag("settings-field").performImeAction()
        compose.onNodeWithText("That folder is already in the list.").assertIsDisplayed()
        assertEquals(listOf("/sdcard/Roms"), st.settings.romDirs)
    }

    @Test fun removingARomFolderAsksFirstAndBKeepsIt() {
        val st = store().apply { settings = settings.copy(romDirs = listOf("/sdcard/Roms")) }
        show(st, vm(st))
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)   // the folder's row
        compose.key(KeyEvent.KEYCODE_BUTTON_X)
        compose.onNodeWithText("Remove /sdcard/Roms?").assertIsDisplayed()
        compose.onNodeWithText("Games found only in this folder stop receiving saves on this device.").assertIsDisplayed()
        compose.key(KeyEvent.KEYCODE_BUTTON_B)
        compose.onNodeWithText("Remove /sdcard/Roms?").assertDoesNotExist()
        assertEquals(listOf("/sdcard/Roms"), st.settings.romDirs)
        compose.key(KeyEvent.KEYCODE_BUTTON_X)
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(emptyList<String>(), st.settings.romDirs)
        assertEquals(1, reloads)
    }

    @Test fun theFocusFromThisDeviceIsAppliedOnceNotEachTimeTheTabReturns() {
        val st = store()
        val vm = vm(st)
        vm.focusEmulator("ppsspp")
        var visible by mutableStateOf(true)
        compose.setContent {
            StoredTheme(st) {
                val ui by vm.ui.collectAsState()
                if (visible) SettingsScreen(ui, top = {}, actions = vm.actions({}, {}))
            }
        }
        compose.onNodeWithText("org.ppsspp.ppssppgold").assertIsDisplayed()
        assertNull(vm.ui.value.focus)
        visible = false; compose.waitForIdle()
        visible = true; compose.waitForIdle()
        // Back on the tab the cursor starts at the top (Eden), not on PPSSPP again.
        compose.key(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.key(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(DUO, st.settings.variants["eden"])
        assertNull(st.settings.variants["ppsspp"])
    }
}

/** The model on its own: validation, what reloads sync and what does not. */
class SettingsViewModelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var reloads = 0
    private var installed = setOf(EDEN)

    @After fun stop() = scope.cancel()

    private fun setup(signedIn: Boolean = true): Pair<StateStore, SettingsViewModel> {
        val st = StateStore(File(tmp.root, "state.json")).apply { if (signedIn) deviceKey = "k" }
        return st to SettingsViewModel(
            st, ModuleRegistry.default().all(), installed = { installed }, label = { if (it == EDEN) "Eden" else null },
            deviceName = MutableStateFlow(null), reload = { reloads++ }, stopSync = {}, afterSignOut = {}, scope = scope,
        )
    }

    @Test fun romFoldersAreValidatedAddedAndRemoved() {
        val (st, vm) = setup()
        assertEquals("Enter a folder path.", vm.addRomDir("  "))
        assertEquals("Enter a full path, starting with /.", vm.addRomDir("Roms"))
        assertNull(vm.addRomDir(" /sdcard/Roms/ "))
        assertEquals("That folder is already in the list.", vm.addRomDir("/storage/emulated/0/Roms"))
        assertEquals(listOf("/sdcard/Roms"), st.settings.romDirs)
        vm.removeRomDir("/sdcard/Roms")
        assertEquals(emptyList<String>(), st.settings.romDirs)
        assertEquals(2, reloads)
    }

    @Test fun overridesAreSetAndAnEmptyValueClearsThem() {
        val (st, vm) = setup()
        vm.setOverride("azahar.root", " /sdcard/Azahar ")
        assertEquals("/sdcard/Azahar", st.settings.overrides["azahar.root"])
        assertEquals("/sdcard/Azahar", vm.ui.value.azaharRoot)
        vm.setOverride("azahar.root", "")
        assertFalse("azahar.root" in st.settings.overrides)
        assertEquals(2, reloads)
    }

    @Test fun settingsChangedWhileSignedOutDoNotStartSync() {
        val (st, vm) = setup(signedIn = false)
        vm.setAutoApply(true)
        assertTrue(st.settings.autoApply)
        assertEquals(0, reloads)
    }

    @Test fun eachModuleIsListedWithItsInstalledVariantsOrNotInstalled() {
        val (_, vm) = setup()
        val rows = vm.ui.value.variants
        assertEquals(listOf(VariantLine("eden", "Eden", EDEN, "Eden", active = true, first = true)), rows.filter { it.emulatorId == "eden" })
        val dolphin = rows.single { it.emulatorId == "dolphin" }
        assertNull(dolphin.pkg)
        assertEquals("Not installed", dolphin.label)
        assertEquals(ModuleRegistry.default().all().size, rows.size)
    }

    @Test fun theStorageRootsAreRefusedAsRomFolders() {
        val (st, vm) = setup()
        val refusal = "Choose the folder your games are in, not the whole storage."
        for (p in listOf("/", "/sdcard", "/sdcard/", "/storage/emulated/0", "/storage", "/storage/emulated", "/storage/1A2B-3C4D")) {
            assertEquals(p, refusal, vm.addRomDir(p))
        }
        assertNull(vm.addRomDir("/storage/1A2B-3C4D/Roms"))
        assertEquals(listOf("/storage/1A2B-3C4D/Roms"), st.settings.romDirs)
    }

    @Test fun changingTheActiveVariantForgetsOnlyThatEmulatorsSyncState() {
        installed = setOf(EDEN, DUO)
        val (st, vm) = setup()
        st.put("eden|Save|0100ABF008968000||", ItemState("v1", "m", "m"))
        st.putConfigState("eden", ConfigState("b", "c1", "m", "m"))
        st.put("dolphin|Save|GALE01||", ItemState("v9", "m", "m"))
        st.putConfigState("dolphin", ConfigState("b2", "c2", "m", "m"))
        vm.chooseVariant("eden", DUO)
        assertEquals(DUO, st.settings.variants["eden"])
        assertNull(st.get("eden|Save|0100ABF008968000||"))
        assertNull(st.configState("eden"))
        assertEquals("v9", st.get("dolphin|Save|GALE01||")?.knownVersionId)
        assertEquals("c2", st.configState("dolphin")?.knownVersionId)
        assertEquals(1, reloads)
    }

    @Test fun savingTheDefaultVariantKeepsStateButStillReloads() {
        installed = setOf(EDEN, DUO)
        val (st, vm) = setup()
        st.put("eden|Save|0100ABF008968000||", ItemState("v1", "m", "m"))
        vm.chooseVariant("eden", EDEN)   // already detected by preference: same folder, same state
        assertEquals(EDEN, st.settings.variants["eden"])
        assertEquals("v1", st.get("eden|Save|0100ABF008968000||")?.knownVersionId)
        assertEquals(1, reloads)   // the running session holds the settings it started with
    }
}
