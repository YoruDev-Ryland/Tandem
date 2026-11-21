package dev.tandem.app.ui.setup.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.tandem.app.fs.ShizukuState
import dev.tandem.app.ui.input.Action
import dev.tandem.app.ui.kit.Hint
import dev.tandem.app.ui.setup.SetupFrame
import dev.tandem.app.ui.setup.SetupStep
import dev.tandem.app.ui.setup.SetupViewModel
import dev.tandem.app.ui.setup.StepNote
import dev.tandem.app.ui.setup.StepSub
import dev.tandem.app.ui.setup.StepTitle
import dev.tandem.app.ui.state.TopStatus
import dev.tandem.app.ui.theme.EmuType
import dev.tandem.app.ui.theme.LocalEmu

const val SHIZUKU_DOWNLOAD = "https://shizuku.rikka.app/download/"
const val SHIZUKU_START = "Open Shizuku and start it (Wireless debugging or root)."
const val SHIZUKU_ALLOW = "Allow Tandem when Shizuku asks."
const val CONTINUE_WITHOUT_SHIZUKU = "Continue without Shizuku"
const val SHIZUKU_REBOOT_NOTE =
    "Without root, Shizuku stops whenever the device restarts. Start it again the same way after each reboot; " +
        "Tandem says \"Shizuku is off\" until you do."

/** Installing Shizuku: it isn't on this device. */
val SHIZUKU_INSTALL_STEPS = listOf(
    "Tandem reads and writes the emulators' folders through Shizuku, a free app. It isn't installed here yet.",
    "Open its download page and install it (from Google Play or the GitHub release).",
    "Come back here. Setup carries on once Shizuku is running.",
)

/** Starting Shizuku over wireless debugging, the way that needs no root or PC. */
val SHIZUKU_START_STEPS = listOf(
    SHIZUKU_START,
    "No root: turn on Developer options (Settings › About › tap Build number 7 times), then Wireless debugging in them.",
    "In Shizuku choose Pairing, and enter the pairing code that Wireless debugging › Pair device with pairing code shows.",
    "Tap Start in Shizuku, then come back here. Setup carries on by itself.",
)

/**
 * Step 2, Shizuku: its state and what to do about it, numbered. Not installed ([openShizuku] null), A opens its
 * download page; installed but stopped, the wireless-debugging steps and A opens it; without permission A asks for it.
 * Once Shizuku is ready the flow moves on by itself (unless the user came back here to look).
 */
@Composable
internal fun ShizukuStep(
    vm: SetupViewModel,
    status: TopStatus,
    openShizuku: (() -> Unit)?,
    later: (() -> Unit)?,
    openUrl: (String) -> Unit = {},
) {
    val state by vm.shizuku.collectAsState()
    val installed = openShizuku != null
    val (title, steps, hint) = when {
        state == ShizukuState.NotRunning && !installed -> Triple(
            "Install Shizuku", SHIZUKU_INSTALL_STEPS, Hint(Action.A, "Open the download page") { openUrl(SHIZUKU_DOWNLOAD) },
        )
        state == ShizukuState.NotRunning -> Triple("Start Shizuku", SHIZUKU_START_STEPS, openShizuku?.let { Hint(Action.A, "Open Shizuku", it) })
        state == ShizukuState.NoPermission -> Triple(
            "Allow Tandem in Shizuku",
            listOf(SHIZUKU_ALLOW, "Setup carries on by itself once it is allowed."),
            Hint(Action.A, "Ask Shizuku", vm::requestShizuku),
        )
        else -> Triple("Shizuku is on", emptyList(), Hint(Action.A, "Continue", vm::continueShizuku))
    }
    // Without Shizuku setup still goes on: sync waits until it is ready, and This device says so.
    val skip = if (state != ShizukuState.Ready) Hint(Action.X, CONTINUE_WITHOUT_SHIZUKU, vm::skipShizuku) else null
    SetupFrame(status, SetupStep.Shizuku, listOfNotNull(hint, skip), vm::back, later = later) {
        StepTitle(title)
        StepSub("Tandem reads and writes saves in each emulator's own folders through Shizuku.")
        Numbered(steps)
        if (state != ShizukuState.Ready) StepNote(SHIZUKU_REBOOT_NOTE)
    }
}

@Composable
private fun Numbered(items: List<String>) {
    val c = LocalEmu.current
    Column(Modifier.widthIn(max = 560.dp)) {
        items.forEachIndexed { i, text ->
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText("${i + 1}", Modifier.width(20.dp), style = EmuType.section.copy(fontSize = 15.sp, color = c.ink2, textAlign = TextAlign.End))
                BasicText(text, style = EmuType.body.copy(fontSize = 15.sp, color = c.ink))
            }
        }
    }
}
