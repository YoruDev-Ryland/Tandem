package dev.emusync.app.ui.setup.steps

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.emusync.app.ui.input.Action
import dev.emusync.app.ui.kit.EmptyLine
import dev.emusync.app.ui.kit.ErrorLine
import dev.emusync.app.ui.kit.Hint
import dev.emusync.app.ui.kit.describeConfigError
import dev.emusync.app.ui.setup.Loading
import dev.emusync.app.ui.setup.OptionText
import dev.emusync.app.ui.setup.Options
import dev.emusync.app.ui.setup.SetupFrame
import dev.emusync.app.ui.setup.SetupStep
import dev.emusync.app.ui.setup.SetupViewModel
import dev.emusync.app.ui.setup.StepSub
import dev.emusync.app.ui.setup.StepTitle
import dev.emusync.app.ui.setup.configOptions
import dev.emusync.app.ui.state.TopStatus

const val SYNC_CHOICE_SUB =
    "Saves always sync. This is about settings, controls and shaders only. Folder paths stay as they are on each device."

/**
 * Step 4, how each one syncs: one screen per emulator found, asking how its settings sync here (join a group, copy
 * another device's once, or only back up this device's). A sends the choice and moves to the next emulator; a
 * refusal is shown under the option, in config words.
 */
@Composable
internal fun SyncChoiceStep(vm: SetupViewModel, status: TopStatus) {
    val emus by vm.syncEmulators.collectAsState()
    val at by vm.emulatorAt.collectAsState()
    val overview by vm.overview.collectAsState()
    val choices by vm.choices.collectAsState()
    val choosing by vm.choosing.collectAsState()
    val failed by vm.choiceError.collectAsState()
    val list = emus

    when {
        list == null -> SetupFrame(status, SetupStep.SyncChoice, emptyList(), vm::back) {
            StepTitle("How each one syncs")
            EmptyLine("Looking for emulators on this device…")
        }
        list.isEmpty() -> SetupFrame(status, SetupStep.SyncChoice, listOf(Hint(Action.A, "Continue", vm::syncChoicesDone)), vm::back) {
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
                SetupFrame(status, SetupStep.SyncChoice, hints, vm::back, note, side) {
                    StepTitle("$name settings")
                    StepSub(SYNC_CHOICE_SUB)
                    if (err != null) ErrorLine(err, Modifier.padding(horizontal = 12.dp)) else Loading()
                }
                return
            }
            val me = vm.deviceId.orEmpty()
            val options = configOptions(data.emulators.firstOrNull { it.emulatorId == emu }, me)
            val chosen = choices[emu]
            // The cursor starts on the earlier choice, per emulator.
            var cursor by rememberSaveable(emu) { mutableIntStateOf(options.indexOfFirst { it.key == chosen }.coerceAtLeast(0)) }
            val pick = options.getOrNull(cursor.coerceIn(0, options.lastIndex))
            val hints = if (choosing == null && pick != null) listOf(Hint(Action.A, "Choose") { vm.choose(emu, pick) }) else emptyList()
            SetupFrame(status, SetupStep.SyncChoice, hints, vm::back, note, side) {
                StepTitle("$name settings")
                StepSub(SYNC_CHOICE_SUB)
                Options(
                    options, cursor, { cursor = it }, { vm.choose(emu, it) },
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
