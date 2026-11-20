package dev.emusync.app.ui.setup.steps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.emusync.app.service.ServiceSnapshot
import dev.emusync.app.ui.input.Action
import dev.emusync.app.ui.kit.Cell
import dev.emusync.app.ui.kit.CursorList
import dev.emusync.app.ui.kit.EmptyLine
import dev.emusync.app.ui.kit.Hint
import dev.emusync.app.ui.kit.Tone
import dev.emusync.app.ui.screens.PathSheet
import dev.emusync.app.ui.screens.SettingsUi
import dev.emusync.app.ui.screens.VariantLine
import dev.emusync.app.ui.setup.SetupFrame
import dev.emusync.app.ui.setup.SetupStep
import dev.emusync.app.ui.setup.SetupViewModel
import dev.emusync.app.ui.setup.StepNote
import dev.emusync.app.ui.setup.StepSub
import dev.emusync.app.ui.setup.StepTitle
import dev.emusync.app.ui.state.TopStatus
import dev.emusync.app.ui.theme.EmuType

private const val AZAHAR = "azahar"

/** A row of step 3. */
private sealed interface EmuRow {
    val key: String

    /** An installed variant; [found] is what detection says of the emulator, on its active variant's row. */
    data class Variant(val line: VariantLine, val several: Boolean, val found: Pair<String, Tone>?) : EmuRow {
        override val key = "v:${line.emulatorId}:${line.pkg}"
    }

    /** Azahar is installed, but its data folder was not found. */
    data object AzaharFolder : EmuRow { override val key = "azahar" }

    data class RomDirs(val dirs: List<String>) : EmuRow { override val key = "roms" }
}

private fun rows(ui: SettingsUi, snap: ServiceSnapshot): List<EmuRow> {
    val installed = ui.variants.filter { it.pkg != null }
    val counts = snap.emulators.associate { it.detected.emulatorId to it.itemCount }
    val out = mutableListOf<EmuRow>()
    for ((id, lines) in installed.groupBy { it.emulatorId }) {
        val found = when {
            !snap.live -> "looking…" to Tone.Ink3
            id in counts -> (counts.getValue(id).let { if (it == 0) "found" else if (it == 1) "1 save" else "$it saves" }) to Tone.Ink2
            id == AZAHAR -> "folder not found" to Tone.Wait
            else -> "not found" to Tone.Ink3
        }
        for (l in lines) out += EmuRow.Variant(l, lines.size > 1, if (l.active) found else null)
        if (id == AZAHAR && snap.live && id !in counts) out += EmuRow.AzaharFolder
    }
    out += EmuRow.RomDirs(ui.romDirs)
    return out
}

/** A folder asked for as a path, in the Settings sheet. */
private data class Ask(val title: String, val detail: String, val confirm: String, val initial: String, val submit: (String) -> String?)

/**
 * Step 3, emulators found: each installed emulator with what detection found. Where an emulator has several variants
 * installed, A makes the one under the cursor the active one (through Settings, so its sync state is forgotten the
 * same way). Azahar's folder and the ROM folders are asked for here when they are missing. X moves on.
 */
@Composable
internal fun EmulatorsStep(vm: SetupViewModel, status: TopStatus, later: (() -> Unit)?) {
    val ui by vm.settings.ui.collectAsState()
    val snap by vm.snapshot.collectAsState()
    LaunchedEffect(Unit) { vm.settings.show() }   // an emulator may have been installed while away
    val list = rows(ui, snap)
    var at by rememberSaveable { mutableIntStateOf(0) }
    val row = list.getOrNull(at.coerceIn(0, maxOf(0, list.lastIndex)))

    var ask by remember { mutableStateOf<Ask?>(null) }
    val text = remember { TextFieldState() }
    var askError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(text) { snapshotFlow { text.text.toString() }.collect { askError = null } }
    fun open(a: Ask) { text.setTextAndPlaceCursorAtEnd(a.initial); askError = null; ask = a }
    fun submit() {
        val a = ask ?: return
        val e = a.submit(text.text.toString())
        if (e == null) ask = null else askError = e
    }

    fun activate(r: EmuRow) {
        when (r) {
            is EmuRow.Variant -> if (r.line.pkg != null && !r.line.active) vm.chooseVariant(r.line.emulatorId, r.line.pkg)
            EmuRow.AzaharFolder -> open(Ask(
                "Azahar folder", "The full path of Azahar's user folder, the one with sdmc and config in it.", "Save",
                ui.azaharRoot, vm::setAzaharFolder,
            ))
            is EmuRow.RomDirs -> open(Ask(
                "Add a ROM folder",
                "The full path of a folder with your games, such as /sdcard/Roms. EmuSync looks in it for the games each " +
                    "emulator can play.",
                "Add", "", vm::addRomDir,
            ))
        }
    }

    val open = ask
    val hints = if (open != null) {
        listOf(Hint(Action.A, open.confirm, ::submit), Hint(Action.B, "Cancel") { ask = null })
    } else {
        listOfNotNull(
            when (row) {
                is EmuRow.Variant -> if (row.several && !row.line.active) Hint(Action.A, "Use this one") { activate(row) } else null
                EmuRow.AzaharFolder -> Hint(Action.A, "Set folder") { activate(row) }
                is EmuRow.RomDirs -> Hint(Action.A, "Add a folder") { activate(row) }
                null -> null
            },
            Hint(Action.X, "Continue", vm::emulatorsDone),
        )
    }
    val note = if (open == null && row is EmuRow.Variant && row.several) "Changing the variant restarts sync" else null

    SetupFrame(status, SetupStep.Emulators, hints, if (open == null) vm::back else null, note, later = if (open == null) later else null) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                StepTitle("Emulators found")
                StepSub("EmuSync syncs the saves of each emulator here. Where one has several versions installed, choose the one you play.")
                if (list.none { it is EmuRow.Variant }) {
                    EmptyLine("No supported emulator is installed. Install one, then set up again from Settings.")
                }
                CursorList(
                    list, key = { it.key }, selected = at, onSelect = { at = it }, onOpen = ::activate,
                    modifier = Modifier.weight(1f, fill = false), active = open == null,
                ) { r, _ ->
                    when (r) {
                        is EmuRow.Variant -> {
                            Cell(if (r.line.first) r.line.emulatorName else "", Modifier.width(110.dp), style = EmuType.rowPrimary)
                            Cell(r.line.label, Modifier.width(140.dp), style = EmuType.body)
                            Cell(r.line.pkg.orEmpty(), Modifier.weight(1f), tone = Tone.Ink3, style = EmuType.mono)
                            val meta = listOfNotNull(if (r.several && r.line.active) "active" else null, r.found?.first).joinToString(" · ")
                            if (meta.isNotEmpty()) Cell(meta, tone = r.found?.second ?: Tone.Ink2, style = EmuType.meta)
                        }
                        EmuRow.AzaharFolder -> {
                            Cell("Azahar folder", Modifier.width(250.dp + 14.dp), style = EmuType.rowPrimary)
                            Cell("Not found where Azahar usually keeps it", Modifier.weight(1f), tone = Tone.Ink2, style = EmuType.meta)
                        }
                        is EmuRow.RomDirs -> {
                            Cell("ROM folders", Modifier.width(250.dp + 14.dp), style = EmuType.rowPrimary)
                            if (r.dirs.isEmpty()) Cell("None yet", Modifier.weight(1f), tone = Tone.Ink3, style = EmuType.meta)
                            else Cell(r.dirs.joinToString(", "), Modifier.weight(1f), tone = Tone.Ink2, style = EmuType.mono)
                        }
                    }
                }
                StepNote("ROM folders let EmuSync tell which games each emulator can play here.")
            }
            if (open != null) PathSheet(open.title, open.detail, open.confirm, text, askError, ::submit) { ask = null }
        }
    }
}
