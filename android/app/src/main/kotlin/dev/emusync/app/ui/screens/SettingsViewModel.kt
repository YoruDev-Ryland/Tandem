package dev.emusync.app.ui.screens

import dev.emusync.app.modules.DeviceSettings
import dev.emusync.app.modules.EmulatorModule
import dev.emusync.app.modules.Paths
import dev.emusync.app.modules.activePackage
import dev.emusync.app.sync.DeviceSettingsData
import dev.emusync.app.sync.StateStore
import dev.emusync.app.ui.theme.ThemeMode
import dev.emusync.app.ui.theme.setting
import dev.emusync.app.ui.theme.themeModeOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class SettingsSection(val label: String) {
    Sync("Sync"), Emulators("Emulators"), Appearance("Appearance"), Account("Account"), Diagnostics("Diagnostics")
}

/** One row of Settings › Emulators: an installed variant of an emulator, or the emulator with none ([pkg] null). */
data class VariantLine(
    val emulatorId: String,
    val emulatorName: String,
    val pkg: String?,
    val label: String,
    val active: Boolean,
    /** The emulator's first row, which shows its name. */
    val first: Boolean,
)

/** Asks the Emulators section to put its cursor on [emulatorId]; [seq] makes each request new. */
data class EmulatorFocus(val emulatorId: String, val seq: Int)

data class SettingsUi(
    val section: SettingsSection = SettingsSection.Sync,
    val autoApply: Boolean = false,
    val romDirs: List<String> = emptyList(),
    val azaharRoot: String = "",
    val ppssppMemstick: String = "",
    val variants: List<VariantLine> = emptyList(),
    val focus: EmulatorFocus? = null,
    val theme: ThemeMode = ThemeMode.System,
    val signedIn: Boolean = false,
    val serverUrl: String? = null,
    val deviceName: String? = null,
)

const val AZAHAR_ROOT = "azahar.root"
const val PPSSPP_MEMSTICK = "ppsspp.memstick"

/**
 * The Settings tab over the [StateStore]. Every change is one store write; a change that alters what sync does
 * (auto-apply, ROM folders, folder overrides, the active variant) then [reload]s the service while signed in. The
 * theme is the app's own and never restarts sync. [installed] says which emulator packages are on the device; it
 * is read again on [show], since an emulator may have been installed while the app was away.
 */
class SettingsViewModel(
    private val store: StateStore,
    private val modules: List<EmulatorModule>,
    private val installed: () -> Set<String>,
    private val label: (String) -> String?,
    deviceName: StateFlow<String?>,
    private val reload: () -> Unit,
    private val stopSync: () -> Unit,
    private val afterSignOut: () -> Unit,
    scope: CoroutineScope,
) {
    private val section = MutableStateFlow(SettingsSection.Sync)
    private val focus = MutableStateFlow<EmulatorFocus?>(null)
    private val packages = MutableStateFlow(installed())
    private var focusSeq = 0

    val ui: StateFlow<SettingsUi> =
        combine(store.changes, deviceName, section, focus, packages) { _, name, sec, f, inst -> build(name, sec, f, inst) }
            .stateIn(scope, SharingStarted.Eagerly, build(deviceName.value, section.value, focus.value, packages.value))

    private fun build(name: String?, sec: SettingsSection, f: EmulatorFocus?, inst: Set<String>): SettingsUi {
        val st = store.settings
        val signedIn = store.deviceKey != null
        return SettingsUi(
            section = sec,
            autoApply = st.autoApply,
            romDirs = st.romDirs,
            azaharRoot = st.overrides[AZAHAR_ROOT].orEmpty(),
            ppssppMemstick = st.overrides[PPSSPP_MEMSTICK].orEmpty(),
            variants = variantLines(st, inst),
            focus = f,
            theme = themeModeOf(st.theme),
            signedIn = signedIn,
            serverUrl = store.serverUrl,
            deviceName = if (signedIn) name else null,
        )
    }

    private fun variantLines(st: DeviceSettingsData, inst: Set<String>): List<VariantLine> {
        val ds = st.toModule()
        return modules.flatMap { m ->
            // A variant chosen elsewhere (the diagnostics screen) may be a package the module doesn't list.
            val have = (m.packages + listOfNotNull(st.variants[m.emulatorId])).distinct().filter { it in inst }
            if (have.isEmpty()) return@flatMap listOf(VariantLine(m.emulatorId, m.displayName, null, "Not installed", false, true))
            val active = m.activePackage(inst, ds)
            have.mapIndexed { i, p -> VariantLine(m.emulatorId, m.displayName, p, label(p) ?: m.displayName, p == active, i == 0) }
        }
    }

    /** The tab is on screen: look at the installed packages again. */
    fun show() { packages.value = installed() }

    fun select(s: SettingsSection) { section.value = s }

    /** Opens Settings › Emulators on [emulatorId] (Y on This device). */
    fun focusEmulator(emulatorId: String) {
        section.value = SettingsSection.Emulators
        focus.value = EmulatorFocus(emulatorId, ++focusSeq)
    }

    /** The Emulators section applied the focus request: it must not be applied again when the tab comes back. */
    fun consumeFocus() { focus.value = null }

    private fun change(f: (DeviceSettingsData) -> DeviceSettingsData) {
        store.updateSettings(f)
        if (store.deviceKey != null) reload()
    }

    fun setAutoApply(on: Boolean) {
        if (store.settings.autoApply != on) change { it.copy(autoApply = on) }
    }

    /** Adds a ROM folder, or returns why it can't be added. Paths are compared as the modules read them. */
    fun addRomDir(path: String): String? {
        val p = path.trim().trimEnd('/').ifEmpty { if (path.isBlank()) "" else "/" }
        if (p.isEmpty()) return "Enter a folder path."
        if (!p.startsWith("/")) return "Enter a full path, starting with /."
        val want = Paths.canonical(p)
        // A whole volume would have every module walk all of storage for games.
        if (want == "/" || want == "/storage" || want == "/storage/emulated" || want == "/storage/emulated/0" ||
            SD_ROOT.matches(want)
        ) return "Choose the folder your games are in, not the whole storage."
        if (store.settings.romDirs.any { Paths.canonical(it.trimEnd('/')) == want }) return "That folder is already in the list."
        change { it.copy(romDirs = it.romDirs + p) }
        return null
    }

    fun removeRomDir(path: String) {
        if (path in store.settings.romDirs) change { it.copy(romDirs = it.romDirs - path) }
    }

    /** Sets a folder override; an empty [value] goes back to the emulator's own folder. */
    fun setOverride(key: String, value: String) {
        val v = value.trim()
        val cur = store.settings.overrides[key]
        if (v == cur.orEmpty()) return
        change { it.copy(overrides = if (v.isEmpty()) it.overrides - key else it.overrides + (key to v)) }
    }

    /**
     * Makes [pkg] the active variant of [emulatorId] and restarts sync (the running session holds the settings it
     * started with). If detection now picks another package, the emulator's sync state is forgotten in the same
     * write: it describes the old variant's folder, so the new folder's first scan uploads with no base and the
     * server reports a conflict rather than taking an older save as the newest. No file is touched.
     */
    fun chooseVariant(emulatorId: String, pkg: String) {
        val m = modules.firstOrNull { it.emulatorId == emulatorId } ?: return
        val inst = packages.value
        if (pkg !in inst) return
        val st = store.settings
        if (st.variants[emulatorId] == pkg) return
        val before = m.activePackage(inst, st.toModule())
        store.setVariant(emulatorId, pkg, forgetState = before != pkg)
        if (store.deviceKey != null) reload()
    }

    fun setTheme(mode: ThemeMode) {
        if (store.settings.theme != mode.setting) store.updateSettings { it.copy(theme = mode.setting) }
    }

    /** Forgets this device's key, id and sync state (the server URL stays), stops sync and clears server data. */
    fun signOut() {
        store.signOut()
        stopSync()
        afterSignOut()
    }
}

/** The root of a removable volume, such as /storage/1A2B-3C4D. */
private val SD_ROOT = Regex("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")

private fun DeviceSettingsData.toModule() = DeviceSettings(romDirs, overrides, variants)
