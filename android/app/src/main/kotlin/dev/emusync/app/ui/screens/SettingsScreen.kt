package dev.emusync.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.emusync.app.ui.input.Action
import dev.emusync.app.ui.input.ActionLayer
import dev.emusync.app.ui.input.OnAction
import dev.emusync.app.ui.input.textEntry
import dev.emusync.app.ui.kit.BumperTabs
import dev.emusync.app.ui.kit.Cell
import dev.emusync.app.ui.kit.ConsoleScaffold
import dev.emusync.app.ui.kit.CursorList
import dev.emusync.app.ui.kit.EmuButton
import dev.emusync.app.ui.kit.EmuRadius
import dev.emusync.app.ui.kit.Fact
import dev.emusync.app.ui.kit.Facts
import dev.emusync.app.ui.kit.Hint
import dev.emusync.app.ui.kit.Tone
import dev.emusync.app.ui.theme.EmuType
import dev.emusync.app.ui.theme.LocalEmu
import dev.emusync.app.ui.theme.ThemeMode

/** What the tab asks its host to do. */
class SettingsActions(
    val select: (SettingsSection) -> Unit = {},
    val setAutoApply: (Boolean) -> Unit = {},
    /** Returns why the folder can't be added, or null when it was. */
    val addRomDir: (String) -> String? = { null },
    val removeRomDir: (String) -> Unit = {},
    val setOverride: (key: String, value: String) -> Unit = { _, _ -> },
    val chooseVariant: (emulatorId: String, pkg: String) -> Unit = { _, _ -> },
    val consumeFocus: () -> Unit = {},
    val setTheme: (ThemeMode) -> Unit = {},
    val signOut: () -> Unit = {},
    val diagnostics: () -> Unit = {},
    /** Opens the setup flow (Account › Set up again). */
    val setUp: () -> Unit = {},
    val back: () -> Unit = {},
)

fun SettingsViewModel.actions(diagnostics: () -> Unit, setUp: () -> Unit, back: () -> Unit) = SettingsActions(
    select = ::select, setAutoApply = ::setAutoApply, addRomDir = ::addRomDir, removeRomDir = ::removeRomDir,
    setOverride = ::setOverride, chooseVariant = ::chooseVariant, consumeFocus = ::consumeFocus, setTheme = ::setTheme, signOut = ::signOut,
    diagnostics = diagnostics, setUp = setUp, back = back,
)

const val AUTO_APPLY_COPY = "Apply newer saves automatically when the emulator is closed"
const val REMOVE_ROM_COPY = "Games found only in this folder stop receiving saves on this device."
const val SIGN_OUT_COPY = "This device stops syncing until you sign in again. Nothing on it is deleted."

/** A row of Settings › Sync. */
private sealed interface SyncRow {
    val key: String
    data class AutoApply(val on: Boolean) : SyncRow { override val key = "auto" }
    data class RomDir(val path: String) : SyncRow { override val key = "rom:$path" }
    data object AddRomDir : SyncRow { override val key = "add" }
    data class Override(val id: String, val label: String, val value: String, val default: String) : SyncRow {
        override val key = "override:$id"
    }
}

private fun syncRows(ui: SettingsUi): List<SyncRow> =
    listOf<SyncRow>(SyncRow.AutoApply(ui.autoApply)) +
        ui.romDirs.map { SyncRow.RomDir(it) } +
        SyncRow.AddRomDir +
        SyncRow.Override(AZAHAR_ROOT, "Azahar folder", ui.azaharRoot, "Azahar's own folder") +
        SyncRow.Override(PPSSPP_MEMSTICK, "PPSSPP memstick", ui.ppssppMemstick, "PPSSPP's own folder")

/** An open sheet. Each takes every button while open: A confirms, B cancels. */
private sealed interface Sheet {
    data object SignOut : Sheet
    data class RemoveRomDir(val path: String) : Sheet
    data class Path(val title: String, val detail: String, val confirm: String, val initial: String, val submit: (String) -> String?) : Sheet
}

private val THEMES = listOf(
    ThemeMode.System to "Follows this device's setting",
    ThemeMode.Light to "",
    ThemeMode.Dark to "",
)

/**
 * The Settings tab. Its sections are sub-tabs (L2/R2): Sync, Emulators (which installed variant each emulator syncs),
 * Appearance, Account and Diagnostics. Every section is a cursor list or a single action, with its buttons in the
 * hint bar; editing a folder and signing out open a sheet over the section.
 */
@Composable
fun SettingsScreen(ui: SettingsUi, top: @Composable RowScope.() -> Unit, actions: SettingsActions) {
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var syncAt by remember { mutableIntStateOf(0) }
    var emuAt by remember { mutableIntStateOf(0) }
    var themeAt by remember { mutableIntStateOf(ui.theme.ordinal) }

    // Y on This device: the cursor goes to that emulator's first row.
    LaunchedEffect(ui.focus) {
        val f = ui.focus ?: return@LaunchedEffect
        ui.variants.indexOfFirst { it.emulatorId == f.emulatorId }.takeIf { it >= 0 }?.let { emuAt = it }
        actions.consumeFocus()   // once: coming back to the tab later starts where the tab always starts
    }

    val rows = syncRows(ui)
    val syncRow = rows.getOrNull(syncAt.coerceIn(0, rows.lastIndex))
    val variant = ui.variants.getOrNull(emuAt.coerceIn(0, maxOf(0, ui.variants.lastIndex)))

    // The open folder sheet's text, here so the hint bar's A submits the same text as the sheet's own button.
    val pathText = remember { TextFieldState() }
    var pathError by remember { mutableStateOf<String?>(null) }
    fun openPath(p: Sheet.Path) { pathText.setTextAndPlaceCursorAtEnd(p.initial); pathError = null; sheet = p }
    // Typing clears a refusal shown under the field.
    LaunchedEffect(pathText) { snapshotFlow { pathText.text.toString() }.collect { pathError = null } }
    fun openSync(r: SyncRow) {
        when (r) {
            is SyncRow.AutoApply -> actions.setAutoApply(!r.on)
            is SyncRow.RomDir -> sheet = Sheet.RemoveRomDir(r.path)
            SyncRow.AddRomDir -> openPath(Sheet.Path(
                "Add a ROM folder",
                "The full path of a folder with your games, such as /sdcard/Roms. EmuSync looks in it for the games " +
                    "each emulator can play.",
                "Add", "", actions.addRomDir,
            ))
            is SyncRow.Override -> openPath(Sheet.Path(
                r.label, "Leave it empty to use ${r.default}.", "Save", r.value,
            ) { actions.setOverride(r.id, it); null })
        }
    }
    fun openVariant(v: VariantLine) { if (v.pkg != null && !v.active) actions.chooseVariant(v.emulatorId, v.pkg) }
    fun chooseTheme(i: Int) = actions.setTheme(THEMES[i].first)

    fun submitPath() {
        val p = sheet as? Sheet.Path ?: return
        val e = p.submit(pathText.text.toString())
        if (e == null) sheet = null else pathError = e
    }
    fun confirmSignOut() { sheet = null; actions.signOut() }
    val cancel = Hint(Action.B, "Cancel") { sheet = null }
    val keep = Hint(Action.B, "Keep") { sheet = null }
    fun confirmRemove(path: String) { sheet = null; actions.removeRomDir(path) }

    val back = Hint(Action.B, "Back", actions.back)
    val open = sheet
    val hints = when (open) {
        Sheet.SignOut -> listOf(Hint(Action.A, "Sign out", ::confirmSignOut), cancel)
        is Sheet.Path -> listOf(Hint(Action.A, open.confirm, ::submitPath), cancel)
        is Sheet.RemoveRomDir -> listOf(Hint(Action.A, "Remove") { confirmRemove(open.path) }, keep)
        null -> when (ui.section) {
            SettingsSection.Sync -> when (syncRow) {
                is SyncRow.AutoApply -> listOf(Hint(Action.A, if (syncRow.on) "Turn off" else "Turn on") { openSync(syncRow) }, back)
                is SyncRow.RomDir -> listOf(Hint(Action.X, "Remove") { openSync(syncRow) }, back)
                SyncRow.AddRomDir -> listOf(Hint(Action.A, "Add") { openSync(SyncRow.AddRomDir) }, back)
                is SyncRow.Override -> listOf(Hint(Action.A, "Change") { openSync(syncRow) }, back)
                null -> listOf(back)
            }
            SettingsSection.Emulators ->
                if (variant?.pkg != null && !variant.active) listOf(Hint(Action.A, "Use this one") { openVariant(variant) }, back)
                else listOf(back)
            SettingsSection.Appearance ->
                if (THEMES[themeAt.coerceIn(0, THEMES.lastIndex)].first != ui.theme) {
                    listOf(Hint(Action.A, "Use this one") { chooseTheme(themeAt) }, back)
                } else listOf(back)
            SettingsSection.Account ->
                if (ui.signedIn) listOf(Hint(Action.A, "Sign out") { sheet = Sheet.SignOut }, Hint(Action.X, SET_UP_AGAIN, actions.setUp), back)
                else listOf(Hint(Action.A, SET_UP, actions.setUp), back)
            SettingsSection.Diagnostics -> listOf(Hint(Action.A, "Open", actions.diagnostics), back)
        }
    }
    val status = when (ui.section) {
        SettingsSection.Emulators -> "Changing the variant restarts sync"
        else -> null
    }

    ConsoleScaffold(
        top = top,
        tabs = {
            BumperTabs(SettingsSection.entries.map { it.label }, ui.section.ordinal, { actions.select(SettingsSection.entries[it]) }, level = 2)
        },
        hints = hints,
        status = if (open == null) status else null,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
                when (ui.section) {
                    SettingsSection.Sync -> CursorList(
                        rows, key = { it.key }, selected = syncAt, onSelect = { syncAt = it }, onOpen = ::openSync,
                        modifier = Modifier.weight(1f, fill = false),
                    ) { r, _ -> SyncCells(r) }
                    SettingsSection.Emulators -> {
                        CursorList(
                            ui.variants, key = { "${it.emulatorId}:${it.pkg}" }, selected = emuAt, onSelect = { emuAt = it },
                            onOpen = ::openVariant, modifier = Modifier.weight(1f, fill = false),
                        ) { v, _ -> VariantCells(v) }
                        Note("Each emulator syncs the saves of its active variant.")
                    }
                    SettingsSection.Appearance -> CursorList(
                        THEMES, key = { it.first }, selected = themeAt, onSelect = { themeAt = it },
                        onOpen = { chooseTheme(THEMES.indexOf(it)) }, modifier = Modifier.weight(1f, fill = false),
                    ) { (mode, meta), _ ->
                        Cell(mode.name, Modifier.width(160.dp), style = EmuType.rowPrimary)
                        Cell(meta, Modifier.weight(1f), tone = Tone.Ink2, style = EmuType.meta)
                        if (mode == ui.theme) Cell("active", tone = Tone.Ink2, style = EmuType.meta)
                    }
                    SettingsSection.Account -> Account(ui, actions.setUp) { sheet = Sheet.SignOut }
                    SettingsSection.Diagnostics -> Diagnostics(actions.diagnostics)
                }
            }
            when (open) {
                Sheet.SignOut -> SheetFrame("Sign out?", SIGN_OUT_COPY, "Sign out", ::confirmSignOut, cancel.onClick) {}
                is Sheet.Path -> PathSheet(open.title, open.detail, open.confirm, pathText, pathError, ::submitPath, cancel.onClick)
                is Sheet.RemoveRomDir -> SheetFrame(
                    "Remove ${open.path}?", REMOVE_ROM_COPY, "Remove", { confirmRemove(open.path) }, keep.onClick,
                    cancelLabel = "Keep",
                ) {}
                null -> {}
            }
        }
    }
}

@Composable
private fun RowScope.SyncCells(r: SyncRow) {
    when (r) {
        is SyncRow.AutoApply -> {
            Cell(AUTO_APPLY_COPY, Modifier.weight(1f), style = EmuType.rowPrimary)
            Cell(if (r.on) "On" else "Off", tone = Tone.Ink2, style = EmuType.meta)
        }
        is SyncRow.RomDir -> {
            Cell("ROM folder", Modifier.width(160.dp), style = EmuType.rowPrimary)
            Cell(r.path, Modifier.weight(1f), tone = Tone.Ink2, style = EmuType.mono)
        }
        SyncRow.AddRomDir -> Cell("Add a ROM folder", Modifier.weight(1f), tone = Tone.Ink2, style = EmuType.rowPrimary)
        is SyncRow.Override -> {
            Cell(r.label, Modifier.width(160.dp), style = EmuType.rowPrimary)
            if (r.value.isEmpty()) Cell(r.default, Modifier.weight(1f), tone = Tone.Ink3, style = EmuType.meta)
            else Cell(r.value, Modifier.weight(1f), tone = Tone.Ink2, style = EmuType.mono)
        }
    }
}

@Composable
private fun RowScope.VariantCells(v: VariantLine) {
    Cell(if (v.first) v.emulatorName else "", Modifier.width(120.dp), style = EmuType.rowPrimary)
    if (v.pkg == null) {
        Cell(v.label, Modifier.weight(1f), tone = Tone.Ink3, style = EmuType.meta)
        return
    }
    Cell(v.label, Modifier.width(160.dp), style = EmuType.body)
    Cell(v.pkg, Modifier.weight(1f), tone = Tone.Ink3, style = EmuType.mono)
    if (v.active) Cell("active", tone = Tone.Ink2, style = EmuType.meta)
}

@Composable
private fun Note(text: String) {
    BasicText(text, Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp), style = EmuType.meta.copy(color = LocalEmu.current.ink3))
}

const val SET_UP_AGAIN = "Set up again"
const val SET_UP = "Set up this device"

@Composable
private fun Account(ui: SettingsUi, setUp: () -> Unit, signOut: () -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Facts(
            listOf(
                Fact("Server", ui.serverUrl ?: "Not set", mono = ui.serverUrl != null),
                Fact("This device", if (ui.signedIn) ui.deviceName ?: "Signed in" else "Not signed in"),
            ),
            Modifier.padding(bottom = 16.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.signedIn) {
                EmuButton("Sign out", glyph = Action.A, onClick = signOut)
                EmuButton(SET_UP_AGAIN, glyph = Action.X, onClick = setUp)
            } else {
                EmuButton(SET_UP, glyph = Action.A, onClick = setUp)
            }
        }
    }
}

@Composable
private fun Diagnostics(open: () -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        BasicText(
            "The sync log, Shizuku and the emulators as the service sees them, for when something doesn't sync.",
            Modifier.padding(bottom = 16.dp).widthIn(max = 620.dp),
            style = EmuType.body.copy(color = LocalEmu.current.ink2),
        )
        EmuButton("Open diagnostics", glyph = Action.A, onClick = open)
    }
}

/** A folder path entered as text: the paths are read through Shizuku, not the system folder picker. */
@Composable
internal fun PathSheet(
    title: String,
    detail: String,
    confirmLabel: String,
    text: TextFieldState,
    error: String?,
    submit: () -> Unit,
    cancel: () -> Unit,
) {
    val c = LocalEmu.current
    val focus = remember { FocusRequester() }
    SheetFrame(title, detail, confirmLabel, confirm = submit, cancel = cancel) {
        BasicTextField(
            text,
            Modifier
                .testTag("settings-field")
                .semantics { contentDescription = title }
                .textEntry()
                .focusRequester(focus)
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .height(40.dp)
                .border(1.5.dp, c.rule2, EmuRadius)
                .padding(horizontal = 12.dp),
            textStyle = EmuType.mono.copy(color = c.ink),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            onKeyboardAction = { submit() },
            cursorBrush = SolidColor(c.ink),
            decorator = { inner -> Box(contentAlignment = Alignment.CenterStart) { inner() } },
        )
        if (error != null) BasicText(error, Modifier.padding(top = 8.dp), style = EmuType.meta.copy(color = c.fault))
    }
    LaunchedEffect(title) { focus.requestFocus() }
}

/**
 * A sheet along the bottom, in its own input layer: it takes every button while open (A confirms, B cancels), and it
 * covers the section to touch, so nothing underneath can be chosen around it.
 */
@Composable
private fun SheetFrame(
    title: String,
    detail: String,
    confirmLabel: String,
    confirm: () -> Unit,
    cancel: () -> Unit,
    cancelLabel: String = "Cancel",
    content: @Composable () -> Unit,
) {
    val c = LocalEmu.current
    ActionLayer {
        OnAction { a ->
            when (a) {
                Action.A -> confirm()
                Action.B -> cancel()
                else -> {}
            }
            true
        }
        Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(c.bg)
                    .drawBehind { drawLine(c.rule2, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx()) }
                    .padding(top = 20.dp, bottom = 16.dp, start = 12.dp, end = 12.dp),
            ) {
                BasicText(title, style = EmuType.section.copy(color = c.ink, fontSize = 20.sp))
                BasicText(detail, Modifier.padding(top = 6.dp, bottom = 12.dp).widthIn(max = 620.dp), style = EmuType.body.copy(color = c.ink2))
                content()
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EmuButton(confirmLabel, primary = true, glyph = Action.A, onClick = confirm)
                    EmuButton(cancelLabel, glyph = Action.B, onClick = cancel)
                }
            }
        }
    }
}
