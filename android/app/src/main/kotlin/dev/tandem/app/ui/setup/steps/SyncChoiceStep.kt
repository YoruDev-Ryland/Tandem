package dev.tandem.app.ui.setup.steps

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tandem.app.ui.input.Action
import dev.tandem.app.ui.kit.EmptyLine
import dev.tandem.app.ui.kit.ErrorLine
import dev.tandem.app.ui.kit.Hint
import dev.tandem.app.ui.kit.describeConfigError
import dev.tandem.app.ui.setup.Loading
import dev.tandem.app.ui.setup.OptionText
import dev.tandem.app.ui.setup.Options
import dev.tandem.app.ui.setup.SetupFrame
import dev.tandem.app.ui.setup.SetupStep
import dev.tandem.app.ui.setup.SetupViewModel
import dev.tandem.app.ui.setup.StepSub
import dev.tandem.app.ui.setup.StepTitle
import dev.tandem.app.ui.setup.configOptions
import dev.tandem.app.ui.setup.ConfigChoice
import dev.tandem.app.ui.setup.ConfigOption
import dev.tandem.app.ui.screens.Seen
import dev.tandem.app.ui.state.TopStatus

const val SYNC_CHOICE_SUB =
    "Saves always sync. This is about settings, controls and shaders only. Folder paths stay as they are on each device."

/**
 * Step 4, how each one syncs: one screen per emulator found, asking how its settings sync here (join a group, copy
 * another device's once, or only back up this device's). A sends the choice and moves to the next emulator; a
 * refusal is shown under the option, in config words.
 */
@Composable
internal fun SyncChoiceStep(vm: SetupViewModel, status: TopStatus, later: (() -> Unit)?, now: () -> Long) {
    val emus by vm.syncEmulators.collectAsState()
    val at by vm.emulatorAt.collectAsState()
    val overview by vm.overview.collectAsState()
    val choices by vm.choices.collectAsState()
    val choosing by vm.choosing.collectAsState()
    val failed by vm.choiceError.collectAsState()
    val list = emus
    // Each emulator's screen after the first is on screen for a moment before A chooses on it, so presses meant for
    // one emulator never choose for the next (the settle rule of the first-sync review).
    val seen = remember { Seen() }

    when {
        list == null -> SetupFrame(status, SetupStep.SyncChoice, emptyList(), vm::back, later = later) {
            StepTitle("How each one syncs")
            EmptyLine("Looking for emulators on this device…")
        }
        list.isEmpty() -> SetupFrame(status, SetupStep.SyncChoice, listOf(Hint(Action.A, "Continue", vm::syncChoicesDone)), vm::back, later = later) {
            StepTitle("How each one syncs")
            EmptyLine("No emulators were found here, so there are no settings to choose.")
        }
        else -> {
            val i = at.coerceIn(0, list.lastIndex)
            val emu = list[i]
            val name = vm.emulatorName(emu)
            val side = "$name · ${i + 1} of ${list.size}\nNext: ${list.getOrNull(i + 1)?.let(vm.emulatorName) ?: SetupStep.FirstSync.label}"
            val data = overview.value
            val note = if (choosing != null) "Saving your choice" else "Change any of this later on the web, under Configs"
            if (data == null) {
                val err = overview.error
                val hints = if (err != null) listOf(Hint(Action.A, "Try again", vm::loadConfigs)) else emptyList()
                SetupFrame(status, SetupStep.SyncChoice, hints, vm::back, note, side, later) {
                    StepTitle("$name settings")
                    StepSub(SYNC_CHOICE_SUB)
                    if (err != null) ErrorLine(err, Modifier.padding(horizontal = 12.dp)) else Loading()
                }
                return
            }
            val me = vm.deviceId.orEmpty()
            val options = configOptions(data.emulators.firstOrNull { it.emulatorId == emu }, me)
            val chosen = choices[emu]
            // The cursor starts on the earlier choice, per emulator, else on "Only back up", which changes nothing here.
            // Kept by option key, so a reload of the server's groups (after a choice) never moves it to another option.
            val ownAt = options.indexOfFirst { it.choice == ConfigChoice.Own }.coerceAtLeast(0)
            var cursorKey by rememberSaveable(emu) {
                mutableStateOf(options.firstOrNull { it.key == chosen }?.key ?: options.getOrNull(ownAt)?.key.orEmpty())
            }
            val cursor = options.indexOfFirst { it.key == cursorKey }.takeIf { it >= 0 } ?: ownAt
            SideEffect { seen.show(emu, now) }
            fun choose(o: ConfigOption) { if (seen.settled(emu, now)) vm.choose(emu, o) }
            val pick = options.getOrNull(cursor.coerceIn(0, options.lastIndex))
            val hints = if (choosing == null && pick != null) listOf(Hint(Action.A, "Choose") { choose(pick) }) else emptyList()
            SetupFrame(status, SetupStep.SyncChoice, hints, vm::back, note, side, later) {
                StepTitle("$name settings")
                StepSub(SYNC_CHOICE_SUB)
                Options(
                    options, cursor, { i -> options.getOrNull(i)?.let { cursorKey = it.key } }, ::choose,
                    text = { OptionText(it.title, it.detail, if (it.key == chosen) "Your choice" else null) },
                    enabled = choosing == null,
                    below = { o ->
                        val f = failed
                        if (f != null && f.first == "$emu/${o.key}") {
                            ErrorLine(f.second, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), text = ::describeConfigError)
                        }
                    },
                )
            }
        }
    }
}
