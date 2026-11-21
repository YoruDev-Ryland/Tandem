package dev.tandem.app.ui.setup.steps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tandem.app.fs.ShizukuState
import dev.tandem.app.model.ResolveConflictRequest
import dev.tandem.app.ui.input.Action
import dev.tandem.app.ui.input.ActionLayer
import dev.tandem.app.ui.kit.rememberSheetChoice
import dev.tandem.app.ui.input.OnAction
import dev.tandem.app.ui.kit.Cell
import dev.tandem.app.ui.kit.CursorList
import dev.tandem.app.ui.kit.EmuButton
import dev.tandem.app.ui.kit.ErrorLine
import dev.tandem.app.ui.kit.Hint
import dev.tandem.app.ui.kit.Tone
import dev.tandem.app.ui.screens.BulkChoice
import dev.tandem.app.ui.screens.ConfirmSheet
import dev.tandem.app.ui.screens.ConflictEntry
import dev.tandem.app.ui.screens.Seen
import dev.tandem.app.ui.setup.FirstSyncPhase
import dev.tandem.app.ui.setup.Loading
import dev.tandem.app.ui.setup.STILL_SCANNING
import dev.tandem.app.ui.setup.describeSetupError
import androidx.compose.foundation.text.BasicText
import dev.tandem.app.ui.theme.LocalEmu
import dev.tandem.app.ui.setup.SetupFrame
import dev.tandem.app.ui.setup.SetupStep
import dev.tandem.app.ui.setup.SetupViewModel
import dev.tandem.app.ui.setup.StepNote
import dev.tandem.app.ui.setup.StepSub
import dev.tandem.app.ui.setup.StepTitle
import dev.tandem.app.ui.setup.ToggleLine
import dev.tandem.app.ui.screens.AUTO_APPLY_COPY
import dev.tandem.app.ui.state.TopStatus
import dev.tandem.app.ui.theme.EmuType

const val ALL_SET = "All set."
const val AUTO_APPLY_OFF_NOTE = "Saves from your other devices then wait until you press Sync now on This device."

/** "Use Odin 3's", or "Use the earlier save" when the other save is this device's own. */
private fun useOther(e: ConflictEntry) = if (e.otherId == null) "Use ${e.other}" else "Use ${e.other}'s"

/**
 * Step 5, first sync: a scan of this device, then its first-sync conflicts. With none it is all set; otherwise each
 * game is a row decided with A (this device's save) or X (the other's), and L2/R2 decide them all after a confirm,
 * with the Conflicts tab's model, bulk plans and settle guard. Y leaves the rest for the Conflicts tab.
 */
@Composable
internal fun FirstSyncStep(vm: SetupViewModel, status: TopStatus, onDone: () -> Unit, later: (() -> Unit)?, now: () -> Long) {
    val phase by vm.phase.collectAsState()
    val error by vm.firstSyncError.collectAsState()
    val shizuku by vm.shizuku.collectAsState()
    val slow by vm.scanSlow.collectAsState()
    val autoApply by vm.autoApply.collectAsState()
    fun done() { vm.finish(); onDone() }
    // X flips auto-apply on every screen of the step but the review, where X picks the other device's save.
    val toggle = Hint(Action.X, if (autoApply) "Turn off" else "Turn on") { vm.setAutoApply(!autoApply) }
    val line: @Composable () -> Unit = {
        ToggleLine(AUTO_APPLY_COPY, autoApply, { vm.setAutoApply(!autoApply) }, Modifier.padding(top = 18.dp))
        if (!autoApply) StepNote(AUTO_APPLY_OFF_NOTE)
    }

    when (phase) {
        FirstSyncPhase.Scanning -> SetupFrame(
            status, SetupStep.FirstSync, listOf(toggle), vm::back,
            note = if (shizuku != ShizukuState.Ready) "Shizuku is off: the scan waits for it" else "Scanning this device",
            later = later,
        ) {
            StepTitle("First sync")
            StepSub("Checking every save on this device against your other devices.")
            if (slow) {
                BasicText(STILL_SCANNING, Modifier.padding(vertical = 12.dp), style = EmuType.body.copy(color = LocalEmu.current.ink))
                StepNote("A first scan of many games can take a while. Finish later, and the Conflicts tab will list anything to decide.")
            } else {
                Loading()
            }
            line()
        }
        FirstSyncPhase.Failed -> SetupFrame(
            status, SetupStep.FirstSync, listOf(Hint(Action.A, "Try again", vm::retryFirstSync), toggle), vm::back, later = later,
        ) {
            StepTitle("First sync")
            error?.let { ErrorLine(it, text = ::describeSetupError) }
            line()
        }
        FirstSyncPhase.Done -> SetupFrame(status, SetupStep.FirstSync, listOf(Hint(Action.A, "Go to This device", ::done), toggle), vm::back) {
            StepTitle(ALL_SET)
            StepSub("Every save on this device matched or synced on its own. Tandem keeps them in sync from here.")
            line()
        }
        FirstSyncPhase.Review -> Review(vm, status, later ?: ::done, now)
    }
}

@Composable
private fun Review(vm: SetupViewModel, status: TopStatus, done: () -> Unit, now: () -> Long) {
    val ui by vm.review.ui.collectAsState()
    val error by vm.firstSyncError.collectAsState()
    var focusId by remember { mutableStateOf<String?>(null) }
    var lastAt by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<BulkChoice?>(null) }
    val seen = remember { Seen() }

    val at = ui.entries.indexOfFirst { it.itemId == focusId }.takeIf { it >= 0 } ?: lastAt.coerceIn(0, maxOf(0, ui.entries.lastIndex))
    val focused = ui.entries.getOrNull(at)
    val bulkOther = ui.bulkMine?.let { ui.bulkOther[focused?.otherId] ?: ui.bulkOther.values.firstOrNull() }

    // A game that slid under the cursor after a choice is shown for a moment before a button decides it.
    SideEffect { seen.show(focused?.itemId, now) }
    fun decide(req: (ConflictEntry) -> ResolveConflictRequest) {
        val e = focused ?: return
        if (!seen.settled(e.itemId, now)) return
        vm.review.resolve(e.itemId, req(e))
    }
    fun select(i: Int) {
        ui.entries.getOrNull(i)?.let { focusId = it.itemId; seen.chosen(it.itemId) }
        lastAt = i
    }
    fun mine() = decide { it.keepMine }
    fun confirm() { sheet?.let { vm.review.bulk(it.calls) }; sheet = null }

    val open = sheet
    val choice = rememberSheetChoice(open, now)   // opens on Cancel, as on the Conflicts tab
    val hints = when {
        open != null -> listOf(choice.hint(open.confirm, "Cancel", ::confirm) { sheet = null }, Hint(Action.B, "Cancel") { sheet = null })
        focused != null -> listOf(
            Hint(Action.A, "Use this device's", ::mine),
            Hint(Action.X, useOther(focused)) { decide { it.keepOther } },
            Hint(Action.Y, "Finish later", done),
        )
        else -> listOf(Hint(Action.Y, "Finish later", done))
    }
    val note = when {
        focused == null -> null
        ui.busy -> "Sending your choice"
        focused.running -> "${focused.emulator} is open: your choice is applied when it closes"
        else -> "Applied right away"
    }

    SetupFrame(
        status, SetupStep.FirstSync, hints, if (open == null) vm::back else null, note,
        onAction = { a ->
            when (a) {
                Action.L2 -> { ui.bulkMine?.let { sheet = it }; true }
                Action.R2 -> { bulkOther?.let { sheet = it }; true }
                else -> false
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                StepTitle("Choose which saves to keep")
                StepSub(
                    "This device and another had different saves for these games before they first synced. Both copies are " +
                        "safe on the server, whichever you choose.",
                )
                if (ui.entries.isEmpty()) Loading()
                // A tap on the cursor row does nothing: choices are made with the buttons, never by touching a game.
                CursorList(
                    ui.entries, key = { it.itemId }, selected = at, onSelect = ::select, onOpen = {},
                    modifier = Modifier.weight(1f, fill = false), active = open == null,
                ) { e, _ ->
                    Cell(e.game, Modifier.weight(1f), style = EmuType.rowPrimary)
                    Cell(e.emulator, Modifier.width(110.dp), tone = Tone.Ink2, style = EmuType.meta)
                    Cell("also on ${e.other}", Modifier.width(150.dp), tone = Tone.Ink2, style = EmuType.meta)
                }
                ui.bulkMine?.let { mine ->
                    Column(Modifier.padding(top = 12.dp)) {
                        EmuButton(mine.label, glyph = Action.L2, onClick = { sheet = mine })
                        bulkOther?.let { o -> EmuButton(o.label, glyph = Action.R2, onClick = { sheet = o }) }
                    }
                }
                (ui.failure ?: error)?.let { ErrorLine(it, Modifier.padding(top = 12.dp)) }
                StepNote("Whatever you choose, the other copy stays in history. Games left here wait in the Conflicts tab.")
            }
            if (open != null) ConfirmSheet(open, choice, ::confirm) { sheet = null }
        }
        // A uses this device's save rather than "opening" the row; in its own layer so it comes before the list.
        ActionLayer { OnAction(enabled = open == null) { a -> if (a == Action.A && focused != null) { mine(); true } else false } }
    }
}
