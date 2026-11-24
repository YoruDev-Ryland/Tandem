package dev.tandem.app.ui.screens

import android.os.SystemClock

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
import dev.tandem.app.ui.feedback.Cue
import dev.tandem.app.ui.feedback.LocalCueSink
import dev.tandem.app.ui.input.Action
import dev.tandem.app.ui.input.ActionLayer
import dev.tandem.app.ui.input.OnAction
import dev.tandem.app.ui.input.textEntry
import dev.tandem.app.ui.kit.BumperTabs
import dev.tandem.app.ui.kit.Cell
import dev.tandem.app.ui.kit.ConsoleScaffold
import dev.tandem.app.ui.kit.CursorList
import dev.tandem.app.ui.kit.EmuButton
import dev.tandem.app.ui.kit.SheetButtons
import dev.tandem.app.ui.kit.SheetChoice
import dev.tandem.app.ui.kit.rememberSheetChoice
import dev.tandem.app.ui.kit.EmuRadius
import dev.tandem.app.ui.kit.Fact
import dev.tandem.app.ui.kit.Glyph
import dev.tandem.app.ui.kit.LocalRowFocused
import dev.tandem.app.ui.kit.Facts
import dev.tandem.app.ui.kit.Hint
import dev.tandem.app.ui.kit.Tone
import dev.tandem.app.ui.theme.EmuType
import dev.tandem.app.ui.theme.LocalEmu
import dev.tandem.app.ui.theme.ThemeMode
import dev.tandem.app.ui.state.INSECURE_URL_WARNING
import dev.tandem.app.ui.state.PermissionRequest
import dev.tandem.app.ui.state.ServerChange
import dev.tandem.app.ui.state.insecureUrl
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.launch
import dev.tandem.app.update.UpdatePhase
import dev.tandem.app.update.UpdateUi
import java.time.Instant
import java.time.ZoneId

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
    val setHaptics: (Boolean) -> Unit = {},
    val setSounds: (Boolean) -> Unit = {},
    val signOut: () -> Unit = {},
    /** Opens the diagnostics screen; null in release builds, which have no Diagnostics section. */
    val diagnostics: (() -> Unit)? = {},
    /** Opens the setup flow (Account › Set up again). */
    val setUp: () -> Unit = {},
    /** Shows the system prompt for notifications or the battery exemption. */
    val requestPermission: (PermissionRequest) -> Unit = {},
    /** Account › Change server address: checks the address, then switches (see SettingsViewModel.changeServer). */
    val changeServer: (String) -> Unit = {},
    val clearServerChange: () -> Unit = {},
    /** About › Open-source licences: the bundled font licences and the dependency list, as text. */
    val licences: () -> String = { "" },
    /** About › Check now, Install now and Install updates automatically (see SettingsViewModel). */
    val checkUpdate: () -> Unit = {},
    val installUpdate: () -> Unit = {},
    val setAutoInstall: (Boolean) -> Unit = {},
    val back: () -> Unit = {},
)

fun SettingsViewModel.actions(diagnostics: (() -> Unit)?, setUp: () -> Unit, back: () -> Unit, licences: () -> String = { "" }) = SettingsActions(
    select = ::select, setAutoApply = ::setAutoApply, addRomDir = ::addRomDir, removeRomDir = ::removeRomDir,
    setOverride = ::setOverride, chooseVariant = ::chooseVariant, consumeFocus = ::consumeFocus, setTheme = ::setTheme,
    setHaptics = ::setHaptics, setSounds = ::setSounds, signOut = ::signOut,
    diagnostics = diagnostics, setUp = setUp, requestPermission = ::requestPermission, changeServer = ::changeServer,
    clearServerChange = ::clearServerChange, licences = licences, checkUpdate = ::checkUpdate,
    installUpdate = ::installUpdate, setAutoInstall = ::setAutoInstall, back = back,
)

const val AUTO_APPLY_COPY = "Apply arriving saves automatically when the emulator is closed"
const val NOTIFICATIONS_COPY = "Notifications for conflicts and emulators to close"
const val BATTERY_COPY = "Run in the background without battery optimisation"
const val MELONDS_EXPORT_NOTE =
    "melonDS settings back up only when you export them: in melonDS, export them to /sdcard/Tandem/melonds-export. " +
        "On another device, import them from that folder in melonDS once they arrive."
const val CHANGE_SERVER = "Change server address"
const val CHANGE_SERVER_COPY =
    "The same Tandem server at a new address, such as a new domain or proxy. Tandem checks that it answers for this " +
        "device before it switches, and keeps everything it knows; nothing syncs again from scratch."
const val INSECURE_CONFIRM = "Press A again to switch anyway."
const val LICENCES = "Open-source licences"
const val AUTO_UPDATE_COPY = "Install updates automatically"
const val AUTO_UPDATE_DETAIL = "When sync is idle; otherwise Tandem asks first"
const val CHECK_NOW = "Check now"
const val INSTALL_NOW = "Install now"
const val REMOVE_ROM_COPY = "Games found only in this folder stop receiving saves on this device."
const val SIGN_OUT_COPY = "This device stops syncing until you sign in again. Nothing on it is deleted."

/** A row of Settings › Sync. */
private sealed interface SyncRow {
    val key: String
    data class AutoApply(val on: Boolean) : SyncRow { override val key = "auto" }
    data class Notifications(val on: Boolean) : SyncRow { override val key = "notifications" }
    data class Battery(val on: Boolean) : SyncRow { override val key = "battery" }
    data class RomDir(val path: String) : SyncRow { override val key = "rom:$path" }
    data object AddRomDir : SyncRow { override val key = "add" }
    data class Override(val id: String, val label: String, val value: String, val default: String) : SyncRow {
        override val key = "override:$id"
    }
}

private fun syncRows(ui: SettingsUi): List<SyncRow> =
    listOf(SyncRow.AutoApply(ui.autoApply), SyncRow.Notifications(ui.permissions.notifications), SyncRow.Battery(ui.permissions.battery)) +
        ui.romDirs.map { SyncRow.RomDir(it) } +
        SyncRow.AddRomDir +
        SyncRow.Override(AZAHAR_ROOT, "Azahar folder", ui.azaharRoot, "Azahar's own folder") +
        SyncRow.Override(PPSSPP_MEMSTICK, "PPSSPP memstick", ui.ppssppMemstick, "PPSSPP's own folder")

/** An open sheet. Each takes every button while open: A confirms, B cancels. */
private sealed interface Sheet {
    data object SignOut : Sheet
    data class RemoveRomDir(val path: String) : Sheet
    data class Path(val title: String, val detail: String, val confirm: String, val initial: String, val submit: (String) -> String?) : Sheet
    data object Server : Sheet
    data object Licences : Sheet
}

private val THEMES = listOf(
    ThemeMode.System to "Follows this device's setting",
    ThemeMode.Light to "",
    ThemeMode.Dark to "",
)

const val HAPTICS_COPY = "Haptics"
const val HAPTICS_DETAIL = "A tick as the cursor moves, when this device's touch feedback is on"
const val SOUNDS_COPY = "UI sounds"
const val SOUNDS_DETAIL = "Short, quiet clicks for move, open and back"

/** A row of Settings › Appearance: the three themes, then the two feedback switches. */
private sealed interface LookRow {
    val key: String
    data class Theme(val mode: ThemeMode, val meta: String) : LookRow { override val key = "theme:${mode.name}" }
    data class Haptics(val on: Boolean) : LookRow { override val key = "haptics" }
    data class Sounds(val on: Boolean) : LookRow { override val key = "sounds" }
}

private fun lookRows(ui: SettingsUi): List<LookRow> =
    THEMES.map { (m, meta) -> LookRow.Theme(m, meta) } + LookRow.Haptics(ui.haptics) + LookRow.Sounds(ui.sounds)

/** A row of Settings › About: the version with the update state, the automatic-install switch, the licences. */
private sealed interface AboutRow {
    val key: String
    data object Version : AboutRow { override val key = "version" }
    data class AutoInstall(val on: Boolean) : AboutRow { override val key = "auto-update" }
    data object Licences : AboutRow { override val key = "licences" }
}

/** A debug build doesn't update itself: no switch for it. */
private fun aboutRows(ui: SettingsUi): List<AboutRow> =
    listOfNotNull(AboutRow.Version, if (ui.update.phase == UpdatePhase.Off) null else AboutRow.AutoInstall(ui.update.autoInstall), AboutRow.Licences)

/** Check now is offered unless updates are off or a check, download or install is under way. */
private fun canCheck(u: UpdateUi) = when (u.phase) {
    UpdatePhase.Off, UpdatePhase.Checking, is UpdatePhase.Downloading, is UpdatePhase.Installing -> false
    else -> true
}

private fun canInstallNow(u: UpdateUi) = u.canInstall && u.phase !is UpdatePhase.Installing

/**
 * The updater's state in one line after the version, and its tone: amber while an update waits (for idle sync or
 * for the user), red when something failed.
 */
fun updateLine(u: UpdateUi, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Pair<String, Tone> = when (val p = u.phase) {
    UpdatePhase.Off -> "Updates are off in debug builds" to Tone.Ink3
    UpdatePhase.Unchecked -> "Not checked yet" to Tone.Ink3
    UpdatePhase.Checking -> "Checking for updates…" to Tone.Ink2
    UpdatePhase.UpToDate ->
        (u.lastCheckAt?.let { "Up to date · checked " + activityTime(Instant.ofEpochMilli(it).toString(), now, zone) } ?: "Up to date") to Tone.Ink2
    is UpdatePhase.Downloading -> "Downloading ${p.version}…" to Tone.Ink2
    is UpdatePhase.Ready ->
        (if (u.installQueued) "${p.version} is ready: it installs when sync is idle" else "${p.version} is ready to install") to Tone.Wait
    is UpdatePhase.Installing -> "Installing ${p.version}…" to Tone.Wait
    is UpdatePhase.Failed -> p.message to Tone.Fault
}

/**
 * The Settings tab. Its sections are sub-tabs (L2/R2): Sync, Emulators (which installed variant each emulator syncs),
 * Appearance, Account and Diagnostics. Every section is a cursor list or a single action, with its buttons in the
 * hint bar; editing a folder and signing out open a sheet over the section.
 */
@Composable
fun SettingsScreen(
    ui: SettingsUi,
    top: @Composable RowScope.() -> Unit,
    actions: SettingsActions,
    now: () -> Long = { SystemClock.uptimeMillis() },
    /** Wall-clock ms, for "checked 2 h ago". */
    wallClock: () -> Long = System::currentTimeMillis,
) {
    // A release build has no diagnostics screen, so no Diagnostics section either.
    val sections = SettingsSection.entries.filter { it != SettingsSection.Diagnostics || actions.diagnostics != null }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var syncAt by remember { mutableIntStateOf(0) }
    var emuAt by remember { mutableIntStateOf(0) }
    var themeAt by remember { mutableIntStateOf(ui.theme.ordinal) }
    var aboutAt by remember { mutableIntStateOf(0) }

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
            is SyncRow.Notifications -> if (!r.on) actions.requestPermission(PermissionRequest.Notifications)
            is SyncRow.Battery -> if (!r.on) actions.requestPermission(PermissionRequest.Battery)
            is SyncRow.RomDir -> sheet = Sheet.RemoveRomDir(r.path)
            SyncRow.AddRomDir -> openPath(Sheet.Path(
                "Add a ROM folder",
                "The full path of a folder with your games, such as /sdcard/Roms. Tandem looks in it for the games " +
                    "each emulator can play.",
                "Add", "", actions.addRomDir,
            ))
            is SyncRow.Override -> openPath(Sheet.Path(
                r.label, "Leave it empty to use ${r.default}.", "Save", r.value,
            ) { actions.setOverride(r.id, it); null })
        }
    }
    fun openVariant(v: VariantLine) { if (v.pkg != null && !v.active) actions.chooseVariant(v.emulatorId, v.pkg) }
    val looks = lookRows(ui)
    val look = looks.getOrNull(themeAt.coerceIn(0, looks.lastIndex))
    fun openLook(r: LookRow) {
        when (r) {
            is LookRow.Theme -> actions.setTheme(r.mode)
            is LookRow.Haptics -> actions.setHaptics(!r.on)
            is LookRow.Sounds -> actions.setSounds(!r.on)
        }
    }

    val abouts = aboutRows(ui)
    val about = abouts.getOrNull(aboutAt.coerceIn(0, abouts.lastIndex))
    fun openAbout(r: AboutRow) {
        when (r) {
            AboutRow.Version -> if (canCheck(ui.update)) actions.checkUpdate()
            is AboutRow.AutoInstall -> actions.setAutoInstall(!r.on)
            AboutRow.Licences -> sheet = Sheet.Licences
        }
    }

    fun submitPath() {
        val p = sheet as? Sheet.Path ?: return
        val e = p.submit(pathText.text.toString())
        if (e == null) sheet = null else pathError = e
    }
    fun confirmSignOut() { sheet = null; actions.signOut() }
    fun openServer() { pathText.setTextAndPlaceCursorAtEnd(ui.serverUrl.orEmpty()); actions.clearServerChange(); sheet = Sheet.Server }
    fun submitServer() = actions.changeServer(pathText.text.toString())
    fun closeServer() { sheet = null; actions.clearServerChange() }
    // A switch that went through closes the sheet; the Account facts show the new address.
    LaunchedEffect(ui.serverChange) { if (ui.serverChange is ServerChange.Done && sheet == Sheet.Server) closeServer() }
    val cancel = Hint(Action.B, "Cancel") { if (sheet == Sheet.Server) closeServer() else sheet = null }
    val keep = Hint(Action.B, "Keep") { sheet = null }
    fun confirmRemove(path: String) { sheet = null; actions.removeRomDir(path) }

    val back = Hint(Action.B, "Back", actions.back)
    val open = sheet
    // Sign out and Remove open on their safe button and confirm only after a moment on screen (SheetChoice).
    val choice = rememberSheetChoice(open, now)
    val hints = when (open) {
        Sheet.SignOut -> listOf(choice.hint("Sign out", "Cancel", ::confirmSignOut, cancel.onClick), cancel)
        is Sheet.Path -> listOf(Hint(Action.A, open.confirm, ::submitPath), cancel)
        is Sheet.RemoveRomDir -> listOf(choice.hint("Remove", "Keep", { confirmRemove(open.path) }, keep.onClick), keep)
        Sheet.Server -> listOfNotNull(
            if (ui.serverChange == ServerChange.Checking) null else Hint(Action.A, "Check and switch", ::submitServer), cancel,
        )
        Sheet.Licences -> listOf(Hint(Action.B, "Close") { sheet = null })
        null -> when (ui.section) {
            SettingsSection.Sync -> when (syncRow) {
                is SyncRow.AutoApply -> listOf(Hint(Action.A, if (syncRow.on) "Turn off" else "Turn on") { openSync(syncRow) }, back)
                is SyncRow.Notifications, is SyncRow.Battery ->
                    if (syncRow.isOn()) listOf(back) else listOf(Hint(Action.A, "Allow") { openSync(syncRow) }, back)
                is SyncRow.RomDir -> listOf(Hint(Action.X, "Remove") { openSync(syncRow) }, back)
                SyncRow.AddRomDir -> listOf(Hint(Action.A, "Add") { openSync(SyncRow.AddRomDir) }, back)
                is SyncRow.Override -> listOf(Hint(Action.A, "Change") { openSync(syncRow) }, back)
                null -> listOf(back)
            }
            SettingsSection.Emulators ->
                if (variant?.pkg != null && !variant.active) listOf(Hint(Action.A, "Use this one") { openVariant(variant) }, back)
                else listOf(back)
            SettingsSection.Appearance -> when (look) {
                is LookRow.Theme -> if (look.mode != ui.theme) listOf(Hint(Action.A, "Use this one") { openLook(look) }, back) else listOf(back)
                is LookRow.Haptics -> listOf(Hint(Action.A, if (look.on) "Turn off" else "Turn on") { openLook(look) }, back)
                is LookRow.Sounds -> listOf(Hint(Action.A, if (look.on) "Turn off" else "Turn on") { openLook(look) }, back)
                null -> listOf(back)
            }
            SettingsSection.Account ->
                if (ui.signedIn && !ui.revoked) listOf(
                    Hint(Action.A, "Sign out") { sheet = Sheet.SignOut }, Hint(Action.X, SET_UP_AGAIN, actions.setUp),
                    Hint(Action.Y, CHANGE_SERVER, ::openServer), back,
                )
                else if (ui.signedIn) listOf(Hint(Action.A, "Sign out") { sheet = Sheet.SignOut }, back)
                else listOf(Hint(Action.A, SET_UP, actions.setUp), back)
            SettingsSection.About -> when (about) {
                AboutRow.Version -> listOfNotNull(
                    if (canCheck(ui.update)) Hint(Action.A, CHECK_NOW, actions.checkUpdate) else null,
                    if (canInstallNow(ui.update)) Hint(Action.X, INSTALL_NOW, actions.installUpdate) else null,
                    back,
                )
                is AboutRow.AutoInstall -> listOf(Hint(Action.A, if (about.on) "Turn off" else "Turn on") { openAbout(about) }, back)
                AboutRow.Licences -> listOf(Hint(Action.A, "Open") { sheet = Sheet.Licences }, back)
                null -> listOf(back)
            }
            SettingsSection.Diagnostics -> listOfNotNull(actions.diagnostics?.let { Hint(Action.A, "Open", it) }, back)
        }
    }
    val status = when (ui.section) {
        SettingsSection.Emulators -> "Changing the variant restarts sync"
        else -> null
    }

    ConsoleScaffold(
        top = top,
        tabs = {
            BumperTabs(sections.map { it.label }, sections.indexOf(ui.section).coerceAtLeast(0), { actions.select(sections[it]) }, level = 2)
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
                        if (ui.variants.any { it.emulatorId == "melonds" && it.pkg != null }) Note(MELONDS_EXPORT_NOTE)
                    }
                    SettingsSection.Appearance -> CursorList(
                        looks, key = { it.key }, selected = themeAt, onSelect = { themeAt = it },
                        onOpen = ::openLook, modifier = Modifier.weight(1f, fill = false),
                    ) { r, _ ->
                        when (r) {
                            is LookRow.Theme -> {
                                Cell(r.mode.name, Modifier.width(160.dp), style = EmuType.rowPrimary)
                                Cell(r.meta, Modifier.weight(1f), tone = Tone.Ink2, style = EmuType.meta)
                                if (r.mode == ui.theme) Cell("active", tone = Tone.Ink2, style = EmuType.meta)
                            }
                            is LookRow.Haptics -> SwitchCells(HAPTICS_COPY, HAPTICS_DETAIL, r.on)
                            is LookRow.Sounds -> SwitchCells(SOUNDS_COPY, SOUNDS_DETAIL, r.on)
                        }
                    }
                    SettingsSection.Account -> Account(ui, actions.setUp, ::openServer) { sheet = Sheet.SignOut }
                    SettingsSection.About -> CursorList(
                        abouts, key = { it.key }, selected = aboutAt, onSelect = { aboutAt = it },
                        onOpen = ::openAbout, modifier = Modifier.weight(1f, fill = false),
                    ) { r, _ ->
                        when (r) {
                            AboutRow.Version -> {
                                val (text, tone) = updateLine(ui.update, Instant.ofEpochMilli(wallClock()))
                                Cell("Tandem", Modifier.width(120.dp), style = EmuType.rowPrimary)
                                // 120 + 146 + the row's 14 dp gap: the update line starts under the switch's detail.
                                Cell(ui.appVersion.ifEmpty { "—" }, Modifier.width(146.dp), tone = Tone.Ink2, style = EmuType.mono)
                                Cell(text, Modifier.weight(1f), tone = tone, style = EmuType.meta)
                            }
                            is AboutRow.AutoInstall -> {
                                Cell(AUTO_UPDATE_COPY, Modifier.width(280.dp), style = EmuType.rowPrimary)
                                Cell(AUTO_UPDATE_DETAIL, Modifier.weight(1f), tone = Tone.Ink2, style = EmuType.meta)
                                Cell(if (r.on) "On" else "Off", tone = Tone.Ink2, style = EmuType.meta)
                            }
                            AboutRow.Licences -> {
                                Cell("$LICENCES…", Modifier.weight(1f), style = EmuType.rowPrimary)
                                Glyph(Action.A, inverted = LocalRowFocused.current)
                                Cell("Open", tone = Tone.Ink2, style = EmuType.meta)
                            }
                        }
                    }
                    SettingsSection.Diagnostics -> actions.diagnostics?.let { Diagnostics(it) }
                }
            }
            when (open) {
                Sheet.Server -> ServerSheet(pathText, ui.serverChange, ::submitServer, ::closeServer)
                Sheet.Licences -> LicencesSheet(remember { actions.licences() }) { sheet = null }
                Sheet.SignOut -> SheetFrame("Sign out?", SIGN_OUT_COPY, "Sign out", ::confirmSignOut, cancel.onClick, choice = choice) {}
                is Sheet.Path -> PathSheet(open.title, open.detail, open.confirm, pathText, pathError, ::submitPath, cancel = cancel.onClick)
                is Sheet.RemoveRomDir -> SheetFrame(
                    "Remove ${open.path}?", REMOVE_ROM_COPY, "Remove", { confirmRemove(open.path) }, keep.onClick,
                    cancelLabel = "Keep", choice = choice,
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
        // Off is a problem for sync (alerts never arrive, the system kills it): the one place these rows use colour.
        is SyncRow.Notifications -> {
            Cell(NOTIFICATIONS_COPY, Modifier.weight(1f), style = EmuType.rowPrimary)
            Cell(if (r.on) "Allowed" else "Off", tone = if (r.on) Tone.Ink2 else Tone.Fault, style = EmuType.meta)
        }
        is SyncRow.Battery -> {
            Cell(BATTERY_COPY, Modifier.weight(1f), style = EmuType.rowPrimary)
            Cell(if (r.on) "Allowed" else "Optimised", tone = if (r.on) Tone.Ink2 else Tone.Wait, style = EmuType.meta)
        }
        is SyncRow.RomDir -> {
            Cell("ROM folder", Modifier.width(160.dp), style = EmuType.rowPrimary)
            Cell(r.path, Modifier.weight(1f), tone = Tone.Ink2, style = EmuType.mono)
        }
        // An action, not a heading: it reads as one (the ellipsis says a field opens) and shows its button.
        SyncRow.AddRomDir -> {
            Cell(ADD_ROM_DIR, Modifier.weight(1f), style = EmuType.rowPrimary)
            Glyph(Action.A, inverted = LocalRowFocused.current)
            Cell("Add", tone = Tone.Ink2, style = EmuType.meta)
        }
        is SyncRow.Override -> {
            Cell(r.label, Modifier.width(160.dp), style = EmuType.rowPrimary)
            if (r.value.isEmpty()) Cell(r.default, Modifier.weight(1f), tone = Tone.Ink3, style = EmuType.meta)
            else Cell(r.value, Modifier.weight(1f), tone = Tone.Ink2, style = EmuType.mono)
        }
    }
}

/** A switch row: what it is, what it does, then On or Off. */
@Composable
private fun RowScope.SwitchCells(label: String, detail: String, on: Boolean) {
    Cell(label, Modifier.width(160.dp), style = EmuType.rowPrimary)
    Cell(detail, Modifier.weight(1f), tone = Tone.Ink2, style = EmuType.meta)
    Cell(if (on) "On" else "Off", tone = Tone.Ink2, style = EmuType.meta)
}

const val ADD_ROM_DIR = "Add a ROM folder…"

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

private fun SyncRow.isOn() = when (this) {
    is SyncRow.Notifications -> on
    is SyncRow.Battery -> on
    else -> true
}

@Composable
private fun Account(ui: SettingsUi, setUp: () -> Unit, changeServer: () -> Unit, signOut: () -> Unit) {
    val c = LocalEmu.current
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Facts(
            listOf(
                Fact("Server", ui.serverUrl ?: "Not set", mono = ui.serverUrl != null),
                Fact("This device", when {
                    ui.revoked -> "Removed from the server"
                    ui.signedIn -> ui.deviceName ?: "Signed in"
                    else -> "Not signed in"
                }),
            ),
            Modifier.padding(bottom = if (insecureUrl(ui.serverUrl)) 8.dp else 16.dp),
        )
        if (insecureUrl(ui.serverUrl)) {
            BasicText(INSECURE_URL_WARNING, Modifier.padding(bottom = 16.dp).widthIn(max = 620.dp), style = EmuType.meta.copy(color = c.wait))
        }
        if (ui.revoked) {
            BasicText(REVOKED_REST, Modifier.padding(bottom = 16.dp).widthIn(max = 620.dp), style = EmuType.body.copy(color = c.ink2))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (ui.signedIn) {
                EmuButton("Sign out", glyph = Action.A, onClick = signOut)
                if (!ui.revoked) {
                    EmuButton(SET_UP_AGAIN, glyph = Action.X, onClick = setUp)
                    EmuButton(CHANGE_SERVER, glyph = Action.Y, onClick = changeServer)
                }
            } else {
                EmuButton(SET_UP, glyph = Action.A, onClick = setUp)
            }
        }
    }
}

/** Account › Change server address: the new address, checked against the server before anything changes. */
@Composable
private fun ServerSheet(text: TextFieldState, state: ServerChange, submit: () -> Unit, cancel: () -> Unit) {
    val error = (state as? ServerChange.Failed)?.message
    val confirming = state is ServerChange.ConfirmInsecure && state.url == dev.tandem.app.ui.setup.normalizeUrl(text.text.toString())
    val note = when {
        state == ServerChange.Checking -> "Checking the new address…" to false
        confirming -> "$INSECURE_URL_WARNING $INSECURE_CONFIRM" to true
        insecureUrl(text.text.toString()) -> INSECURE_URL_WARNING to true
        else -> null
    }
    PathSheet(CHANGE_SERVER, CHANGE_SERVER_COPY, if (confirming) "Switch anyway" else "Check and switch", text, error, submit,
        note = note, cancel = cancel)
}

/** About › Open-source licences, over the section: the text scrolls with Up and Down; B closes it. */
@Composable
private fun LicencesSheet(text: String, close: () -> Unit) {
    val c = LocalEmu.current
    val scroll = androidx.compose.foundation.rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    ActionLayer {
        OnAction { a ->
            when (a) {
                Action.B -> close()
                Action.Up -> scope.launch { scroll.animateScrollBy(-240f) }
                Action.Down -> scope.launch { scroll.animateScrollBy(240f) }
                else -> {}
            }
            true
        }
        Box(Modifier.fillMaxSize().background(c.bg).pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp)) {
                BasicText(LICENCES, Modifier.padding(bottom = 10.dp), style = EmuType.section.copy(color = c.ink, fontSize = 20.sp))
                BasicText(text, Modifier.widthIn(max = 720.dp), style = EmuType.mono.copy(color = c.ink2, fontSize = 12.sp))
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
    /** A line under the field that is not a refusal, and whether it is a warning (amber). */
    note: Pair<String, Boolean>? = null,
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
        else if (note != null) BasicText(note.first, Modifier.padding(top = 8.dp).widthIn(max = 620.dp), style = EmuType.meta.copy(color = if (note.second) c.wait else c.ink2))
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
    choice: SheetChoice? = null,
    content: @Composable () -> Unit,
) {
    val c = LocalEmu.current
    val cues = LocalCueSink.current
    ActionLayer {
        OnAction { a ->
            if (choice != null) return@OnAction choice.onAction(a, confirm, cancel)
            when (a) {
                Action.A -> confirm()
                Action.B -> cancel()
                else -> cues?.cue(Cue.Reject)   // the sheet takes every button; the others do nothing here
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
                if (choice != null) {
                    SheetButtons(choice, confirmLabel, cancelLabel, confirm, cancel, Modifier.padding(top = 12.dp))
                } else {
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        EmuButton(confirmLabel, primary = true, glyph = Action.A, onClick = confirm)
                        EmuButton(cancelLabel, glyph = Action.B, onClick = cancel)
                    }
                }
            }
        }
    }
}
