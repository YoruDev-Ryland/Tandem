package dev.emusync.app.modules

import dev.emusync.app.fs.Fs
import dev.emusync.app.model.InstalledGame
import dev.emusync.app.model.ItemKind

data class Detected(val emulatorId: String, val pkg: String, val version: String, val dataRoot: String, val extraRoots: Map<String, String> = emptyMap())
data class LocalItem(
    val kind: ItemKind, val gameKey: String, val slot: String, val core: String,
    val baseDir: String, val files: List<String>, val displayName: String? = null,
)  // files relative to baseDir
data class ConfigBundleFiles(val files: Map<String, String>)   // bundle path ("data/retroarch.cfg") -> absolute path
data class DeviceSettings(val romDirs: List<String>, val overrides: Map<String, String>)  // overrides e.g. "azahar.root", "eden.package"

class ModuleException(msg: String) : Exception(msg)

object Paths {
    /** Maps a leading /sdcard/ to /storage/emulated/0/. */
    fun canonical(p: String): String = when {
        p == "/sdcard" -> "/storage/emulated/0"
        p.startsWith("/sdcard/") -> "/storage/emulated/0/" + p.removePrefix("/sdcard/")
        else -> p
    }

    fun requireSafeSegment(s: String) {
        if (s.isEmpty() || s == "." || s == ".." || s.any { it == '/' || it == '\\' || it == '\u0000' })
            throw ModuleException("unsafe path segment: '${s.replace('\u0000', '?')}'")
    }

    fun requireSafeRelative(p: String) {
        if (p.startsWith("/")) throw ModuleException("absolute path not allowed: '$p'")
        if (p.contains('\u0000')) throw ModuleException("unsafe path")
        p.split('/').forEach { requireSafeSegment(it) }
    }
}

/**
 * Every package whose processes mean this emulator is running: all known variants (installed or not, since
 * detection picks only the first installed one and variants can share a data folder) plus the detected package.
 */
fun EmulatorModule.processPackages(d: Detected?): List<String> = (packages + listOfNotNull(d?.pkg)).distinct()

/** True when any process of any of the module's packages is alive (see [processPackages]). Fails closed (throws). */
fun Fs.isEmulatorRunning(m: EmulatorModule, d: Detected?): Boolean = isAnyProcessAlive(m.processPackages(d))

interface EmulatorModule {
    val emulatorId: String
    /** Known packages in preferred order: detection uses the first installed one; every one counts as running. */
    val packages: List<String>
    /** First segments a delivered config bundle path may start with: "data" maps to dataRoot, others to extraRoots. */
    val configRoots: Set<String> get() = setOf("data")
    fun detect(fs: Fs, settings: DeviceSettings): Detected?
    fun saveItems(fs: Fs, d: Detected): List<LocalItem>
    fun watchDirs(fs: Fs, d: Detected): List<String>
    fun installedGames(fs: Fs, d: Detected, settings: DeviceSettings): List<InstalledGame>
    fun configBundle(fs: Fs, d: Detected): ConfigBundleFiles
    fun targetDirFor(fs: Fs, d: Detected, kind: ItemKind, gameKey: String, slot: String, core: String): String  // where a delivered item is written
    fun cores(fs: Fs, d: Detected): List<String>? = null
}
