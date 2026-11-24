package dev.tandem.app

import android.content.ActivityNotFoundException
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.tandem.app.ui.feedback.AndroidCuePlayer
import dev.tandem.app.ui.feedback.CueSettings
import dev.tandem.app.ui.feedback.LocalCuePlayer
import dev.tandem.app.ui.kit.LocalNotice
import dev.tandem.app.ui.kit.LocalReadout
import dev.tandem.app.ui.kit.LocalStatusLine
import dev.tandem.app.ui.kit.LocalTabEntrance
import dev.tandem.app.ui.kit.Readout
import dev.tandem.app.ui.kit.TabEntrance
import dev.tandem.app.ui.kit.readoutTime
import dev.tandem.app.ui.screens.syncedAgo
import dev.tandem.app.ui.state.ServerConn
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sign
import kotlinx.coroutines.delay
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
import dev.tandem.app.ui.screens.ShelfScreen
import dev.tandem.app.ui.art.prefetchSpines
import dev.tandem.app.ui.art.shelfArt
import dev.tandem.app.ui.art.spineImages
import dev.tandem.app.ui.screens.SettingsScreen
import dev.tandem.app.ui.screens.actions
import dev.tandem.app.ui.screens.gameUrl
import dev.tandem.app.ui.screens.licenceText
import dev.tandem.app.ui.state.PermissionRequest
import android.Manifest
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
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
private const val REQ_NOTIFICATIONS = 7
private val TABS = listOf("Library", "Activity", "Conflicts", "Settings")
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
        setContent { StoredTheme(store, onDark = ::applyWindowTheme) { Console(this, store) { TandemRoot(vm, this) } } }
        immersive()
        // Setup and Settings ask for notifications and the battery exemption; the prompts need this activity.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.permissions.requests.collect(::ask) }
        }
    }

    /** Bumped on every resume: what depends on other apps (Shizuku installed meanwhile) is read again. */
    internal var resumes by mutableIntStateOf(0)
        private set

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()   // a swipe showed the bars; they go again once the app has the window back
    }

    /**
     * Takes the whole screen like a console menu: the status and navigation bars are hidden, and a swipe from the edge
     * shows them for a moment.
     */
    private fun immersive() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    internal val cues: AndroidCuePlayer by lazy {
        val store = AppGraph.store(this)
        AndroidCuePlayer(this, { window?.decorView }) { store.settings.let { CueSettings(it.haptics, it.sounds) } }
    }

    override fun onDestroy() {
        cues.release()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        resumes++
        vm.permissions.refresh()   // a prompt (or the system settings) may have changed them
    }

    private fun ask(r: PermissionRequest) {
        when (r) {
            PermissionRequest.Notifications -> requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            PermissionRequest.Battery -> try {
                @Suppress("BatteryLife")   // a sync service that must outlive the screen is what the exemption is for
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            } catch (_: ActivityNotFoundException) {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: ActivityNotFoundException) { }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        vm.permissions.refresh()
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
    val notice by vm.notices.current.collectAsStateWithLifecycle()
    val now = LocalNow.current
    // The tab that was showing, so the new one can arrive from the side it lies on.
    var shownTab by remember { mutableIntStateOf(tab) }
    val slide = remember { animationsOn(activity) }
    val entrance = remember(tab) { TabEntrance(direction = (tab - shownTab).sign, slide = slide) }
    SideEffect { shownTab = tab }
    val setupActive by vm.setup.active.collectAsStateWithLifecycle()
    // Opens Shizuku when it is installed (null: not installed; setup then explains and links its download page).
    val shizuku = remember(activity.resumes) { activity.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) }

    // Signed out at launch, or opened from This device or Settings: the setup flow instead of the tabs.
    if (setupActive) {
        // The system back (gesture or key outside the scaffold) is B too: it never leaves the app mid-setup.
        BackHandler { vm.setup.back() }
        CompositionLocalProvider(LocalNotice provides notice) {
            SetupFlow(vm.setup, status, openShizuku = shizuku?.let { i -> { activity.startActivity(i) } }, onDone = { tab = TAB_DEVICE },
                diagnostics = activity.diagnostics(), openUrl = activity::view,
            )
        }
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

    // Every tab's hint bar says how long ago this device synced, ticking on its own; a success line stands in for it.
    val synced = if (status.signedIn && status.server != ServerConn.Revoked) syncedAgo(status.lastSyncedAt, now) else null
    CompositionLocalProvider(LocalStatusLine provides synced, LocalNotice provides notice, LocalTabEntrance provides entrance) {
    when (tab) {
        TAB_DEVICE -> ShelfScreen(
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
            art = { emu, key -> shelfArt(vm.art, device, emu, key) },
            artNow = { emu, key -> vm.art.inMemory(emu, key) },
            spineArt = { s -> spineImages(vm.art, s) },
            prefetch = { spines, first, last -> prefetchSpines(vm.art, spines, first, last) },
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
                    licences = { licenceText { p -> runCatching { activity.assets.open(p).bufferedReader().use { it.readText() } }.getOrNull() } },
                ),
            )
        }
    }
    }
}

/** Whether the system's animations are on (Remove animations, or an animator scale of 0, turns them off). */
private fun animationsOn(context: android.content.Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/** The clock the top bar and the hint bar's synced line read; it ticks every 30 s, on the half minute. */
val LocalNow = compositionLocalOf<Instant> { Instant.now() }

/**
 * The console's live parts around [content]: a clock that ticks every 30 s ([LocalNow]), the top bar's time and battery
 * ([LocalReadout]) and the haptics and sounds ([LocalCuePlayer]), which follow Settings › Appearance.
 */
@Composable
private fun Console(activity: MainActivity, store: StateStore, content: @Composable () -> Unit) {
    val now by produceState(Instant.now()) {
        while (true) {
            delay(30_000 - System.currentTimeMillis() % 30_000)
            value = Instant.now()
        }
    }
    val resumes = activity.resumes
    val battery = remember(now, resumes) { batteryLevel(activity) }
    val readout = Readout(readoutTime(now, ZoneId.systemDefault(), DateFormat.is24HourFormat(activity)), battery?.first, battery?.second == true)
    val changes by store.changes.collectAsState()
    val sounds = remember(changes) { store.settings.sounds }
    LaunchedEffect(sounds) { activity.cues.prepare(sounds) }
    CompositionLocalProvider(LocalNow provides now, LocalReadout provides readout, LocalCuePlayer provides activity.cues, content = content)
}

/** The battery's level in percent and whether it is charging, from the sticky battery broadcast; null without one. */
private fun batteryLevel(context: android.content.Context): Pair<Int, Boolean>? {
    val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return (level * 100 / scale) to charging
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
