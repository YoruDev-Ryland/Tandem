package dev.tandem.app

import android.content.ActivityNotFoundException
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.tandem.app.service.AppGraph
import dev.tandem.app.service.LogBuffer
import dev.tandem.app.service.SyncService
import dev.tandem.app.service.Watchdog
import dev.tandem.app.ui.AppViewModel
import dev.tandem.app.ui.kit.TopBar
import dev.tandem.app.ui.screens.ActivityActions
import dev.tandem.app.ui.screens.ActivityScreen
import dev.tandem.app.ui.screens.ConflictsActions
import dev.tandem.app.ui.screens.ConflictsScreen
import dev.tandem.app.ui.screens.DeviceActions
import dev.tandem.app.ui.screens.DeviceScreen
import dev.tandem.app.ui.screens.SettingsScreen
import dev.tandem.app.ui.screens.actions
import dev.tandem.app.ui.screens.gameUrl
import dev.tandem.app.ui.screens.syncNow
import dev.tandem.app.ui.setup.SetupFlow
import dev.tandem.app.sync.StateStore
import dev.tandem.app.ui.theme.EmuColors
import dev.tandem.app.ui.theme.EmuTheme
import dev.tandem.app.ui.theme.ThemeMode
import dev.tandem.app.ui.theme.isDark
import dev.tandem.app.ui.theme.themeModeOf

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private const val DEBUG_ACTIVITY = "dev.tandem.app.debug.DebugActivity"
private val TABS = listOf("This device", "Activity", "Conflicts", "Settings")
private const val TAB_DEVICE = 0
private const val TAB_ACTIVITY = 1
private const val TAB_CONFLICTS = 2
private const val TAB_SETTINGS = 3

/** The launcher: the console UI with its page tabs. */
class MainActivity : ComponentActivity() {
    private val vm by lazy { ViewModelProvider(this)[AppViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = AppGraph.store(this)
        // Before the first frame, so a Light choice on a dark system (or the reverse) never flashes the other colours.
        applyWindowTheme(themeModeOf(store.settings.theme).let { it == ThemeMode.Dark || (it == ThemeMode.System && systemDark()) })
        setContent { StoredTheme(store, onDark = ::applyWindowTheme) { TandemRoot(vm, this) } }
    }

    override fun onStart() {
        super.onStart()
        // The only in-app start: sync runs whenever this device is signed in. Starting a running service is a no-op.
        // Removed on the server, it stays off until a sign-in. The watchdog restarts sync if something kills it later.
        if (AppGraph.store(this).canSync) {
            SyncService.tryStart(this)
            Watchdog.schedule(this)
        }
        vm.server.setVisible(true)
        vm.server.refresh()   // the key or server may have changed while we were away (setup, debug screen)
    }

    override fun onStop() {
        vm.server.setVisible(false)
        super.onStop()
    }

    private fun systemDark() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    /** The window under Compose and the system bar icons, in the app's theme rather than the system's. */
    private fun applyWindowTheme(dark: Boolean) {
        window.setBackgroundDrawable(ColorDrawable((if (dark) EmuColors.Dark else EmuColors.Light).bg.toArgb()))
        val bars = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        enableEdgeToEdge(bars, bars)
    }

    /** Opens the diagnostics screen; null in release builds, which don't contain it (it lives in src/debug). */
    internal fun diagnostics(): (() -> Unit)? = if (!BuildConfig.DEBUG) null else ({
        try { startActivity(Intent().setClassName(this, DEBUG_ACTIVITY)) } catch (_: ActivityNotFoundException) { }
    })

    internal fun view(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: ActivityNotFoundException) { }
    }
}

@Composable
private fun TandemRoot(vm: AppViewModel, activity: MainActivity) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_DEVICE) }
    val status by vm.state.top.collectAsStateWithLifecycle()
    val setupActive by vm.setup.active.collectAsStateWithLifecycle()
    // Shizuku help is a later task; until then the fix opens Shizuku itself, when it is installed.
    val shizuku = remember { activity.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) }

    // Signed out at launch, or opened from This device or Settings: the setup flow instead of the tabs.
    if (setupActive) {
        // The system back (gesture or key outside the scaffold) is B too: it never leaves the app mid-setup.
        BackHandler { vm.setup.back() }
        SetupFlow(vm.setup, status, openShizuku = shizuku?.let { i -> { activity.startActivity(i) } }, onDone = { tab = TAB_DEVICE },
            diagnostics = activity.diagnostics(),
        )
        return
    }

    val device by vm.device.ui.collectAsStateWithLifecycle()
    val activityUi by vm.activity.ui.collectAsStateWithLifecycle()
    val log by LogBuffer.lines.collectAsStateWithLifecycle()
    val conflictsUi by vm.conflicts.ui.collectAsStateWithLifecycle()
    val settingsUi by vm.settings.ui.collectAsStateWithLifecycle()
    val openConflicts = conflictsUi.entries.size   // the ones this device is part of, as the tab lists them

    val top: @Composable RowScope.() -> Unit = {
        TopBar(
            TABS, tab, { tab = it }, status,
            onShizukuFix = shizuku?.let { i -> { activity.startActivity(i) } },
            badge = { if (it == TAB_CONFLICTS && openConflicts > 0 && tab != TAB_CONFLICTS) "$openConflicts" else null },
        )
    }

    when (tab) {
        TAB_DEVICE -> DeviceScreen(
            device, top,
            DeviceActions(
                openGame = { emu, key -> vm.serverUrl?.let { activity.view(gameUrl(it, emu, key)) } },
                syncNow = { syncNow(activity) },
                // Y: Settings › Emulators with the cursor on this emulator.
                settings = { id -> vm.settings.focusEmulator(id); tab = TAB_SETTINGS },
                setup = { vm.setup.start(exitable = true) },
                // Removed on the server: the old key works nowhere, so sign out (no file is touched) and sign in again.
                pairAgain = { vm.settings.signOut(); vm.setup.start(exitable = true) },
                back = { activity.onBackPressedDispatcher.onBackPressed() },
            ),
        )
        TAB_ACTIVITY -> {
            // Visible: fetch now, and refresh on nudges until the tab changes.
            DisposableEffect(Unit) { vm.activity.show(); onDispose { vm.activity.hide() } }
            ActivityScreen(
                activityUi, log, top,
                ActivityActions(loadOlder = vm.activity::loadOlder, refresh = vm.activity::refresh, back = { tab = TAB_DEVICE }),
            )
        }
        TAB_CONFLICTS -> ConflictsScreen(
            conflictsUi, top,
            ConflictsActions(resolve = vm.conflicts::resolve, bulk = vm.conflicts::bulk, back = { tab = TAB_DEVICE }),
        )
        else -> {
            LaunchedEffect(Unit) { vm.settings.show() }   // emulators may have been installed while away
            SettingsScreen(
                settingsUi, top,
                vm.settings.actions(
                    diagnostics = activity.diagnostics(),
                    setUp = { vm.setup.start(exitable = true) },
                    back = { tab = TAB_DEVICE },
                ),
            )
        }
    }
}

/**
 * The app's theme as Settings › Appearance stores it, following every change. [onDark] hears which colours are in
 * use after each change, for what Compose does not draw (the window background, the system bar icons).
 */
@Composable
internal fun StoredTheme(store: StateStore, onDark: (Boolean) -> Unit = {}, content: @Composable () -> Unit) {
    val changes by store.changes.collectAsState()
    val mode = remember(changes) { themeModeOf(store.settings.theme) }
    val dark = mode.isDark()
    LaunchedEffect(dark) { onDark(dark) }   // only when it changes: the store changes often while syncing
    EmuTheme(mode, content)
}
