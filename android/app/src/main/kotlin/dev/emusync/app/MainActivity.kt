package dev.emusync.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.emusync.app.debug.DebugActivity
import dev.emusync.app.ui.AppViewModel
import dev.emusync.app.ui.input.Action
import dev.emusync.app.ui.kit.ConsoleScaffold
import dev.emusync.app.ui.kit.Hint
import dev.emusync.app.ui.kit.TopBar
import dev.emusync.app.ui.screens.DeviceActions
import dev.emusync.app.ui.screens.DeviceScreen
import dev.emusync.app.ui.screens.gameUrl
import dev.emusync.app.ui.screens.syncNow
import dev.emusync.app.ui.theme.EmuTheme
import dev.emusync.app.ui.theme.EmuType
import dev.emusync.app.ui.theme.LocalEmu

private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
private val TABS = listOf("This device", "Activity", "Conflicts", "Settings")
private const val TAB_DEVICE = 0
private const val TAB_CONFLICTS = 2
private const val TAB_SETTINGS = 3

/** The launcher: the console UI with its page tabs. */
class MainActivity : ComponentActivity() {
    private val vm by lazy { ViewModelProvider(this)[AppViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { EmuTheme { EmuSyncRoot(vm, this) } }
    }

    override fun onStart() {
        super.onStart()
        vm.server.setVisible(true)
        vm.server.refresh()   // the key or server may have changed while we were away (setup, debug screen)
    }

    override fun onStop() {
        vm.server.setVisible(false)
        super.onStop()
    }

    internal fun view(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: ActivityNotFoundException) { }
    }
}

@Composable
private fun EmuSyncRoot(vm: AppViewModel, activity: MainActivity) {
    var tab by rememberSaveable { mutableIntStateOf(TAB_DEVICE) }
    val status by vm.state.top.collectAsStateWithLifecycle()
    val conflicts by vm.server.conflicts.collectAsStateWithLifecycle()
    val device by vm.device.ui.collectAsStateWithLifecycle()
    val openConflicts = conflicts.value?.size ?: 0
    // Shizuku help is a later task; until then the fix opens Shizuku itself, when it is installed.
    val shizuku = remember { activity.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) }

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
                // Settings will scroll to the emulator once it exists (Task 9); for now it only switches tab.
                settings = { tab = TAB_SETTINGS },
                // Setup is a later task; the debug screen holds sign-in until then.
                setup = { activity.startActivity(Intent(activity, DebugActivity::class.java)) },
                back = { activity.onBackPressedDispatcher.onBackPressed() },
            ),
        )
        else -> PlaceholderTab(TABS[tab], top) { tab = TAB_DEVICE }
    }
}

/** A tab whose screen arrives in a later task: its name and nothing else. B goes back to This device. */
@Composable
private fun PlaceholderTab(name: String, top: @Composable RowScope.() -> Unit, back: () -> Unit) {
    ConsoleScaffold(top = top, hints = listOf(Hint(Action.B, "Back", back))) {
        BasicText(name, Modifier.padding(top = 4.dp), style = EmuType.title.copy(color = LocalEmu.current.ink))
    }
}
