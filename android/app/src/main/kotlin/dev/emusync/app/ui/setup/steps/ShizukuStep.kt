package dev.emusync.app.ui.setup.steps

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
import dev.emusync.app.fs.ShizukuState
import dev.emusync.app.ui.input.Action
import dev.emusync.app.ui.kit.Hint
import dev.emusync.app.ui.setup.SetupFrame
import dev.emusync.app.ui.setup.SetupStep
import dev.emusync.app.ui.setup.SetupViewModel
import dev.emusync.app.ui.setup.StepSub
import dev.emusync.app.ui.setup.StepTitle
import dev.emusync.app.ui.state.TopStatus
import dev.emusync.app.ui.theme.EmuType
import dev.emusync.app.ui.theme.LocalEmu

const val SHIZUKU_START = "Open Shizuku and start it (Wireless debugging or root)."
const val SHIZUKU_ALLOW = "Allow EmuSync when Shizuku asks."
const val CONTINUE_WITHOUT_SHIZUKU = "Continue without Shizuku"

/**
 * Step 2, Shizuku: its state and what to do about it, numbered. Without permission A asks for it; once Shizuku is
 * ready the flow moves on by itself (unless the user came back here to look).
 */
@Composable
internal fun ShizukuStep(vm: SetupViewModel, status: TopStatus, openShizuku: (() -> Unit)?, later: (() -> Unit)?) {
    val state by vm.shizuku.collectAsState()
    val (title, steps, hint) = when (state) {
        ShizukuState.NotRunning -> Triple(
            "Start Shizuku",
            listOf(SHIZUKU_START, "Come back here. Setup carries on by itself."),
            openShizuku?.let { Hint(Action.A, "Open Shizuku", it) },
        )
        ShizukuState.NoPermission -> Triple(
            "Allow EmuSync in Shizuku",
            listOf(SHIZUKU_ALLOW, "Setup carries on by itself once it is allowed."),
            Hint(Action.A, "Ask Shizuku", vm::requestShizuku),
        )
        ShizukuState.Ready -> Triple("Shizuku is on", emptyList(), Hint(Action.A, "Continue", vm::continueShizuku))
    }
    // Without Shizuku setup still goes on: sync waits until it is ready, and This device says so.
    val skip = if (state != ShizukuState.Ready) Hint(Action.X, CONTINUE_WITHOUT_SHIZUKU, vm::skipShizuku) else null
    SetupFrame(status, SetupStep.Shizuku, listOfNotNull(hint, skip), vm::back, later = later) {
        StepTitle(title)
        StepSub("EmuSync reads and writes saves in each emulator's own folders through Shizuku.")
        Numbered(steps)
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
