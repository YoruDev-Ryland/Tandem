package dev.emusync.app.modules.retroarch

import dev.emusync.app.fs.Fs
import dev.emusync.app.model.InstalledGame
import dev.emusync.app.model.ItemKind
import dev.emusync.app.modules.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** [internalRoot] replaces /storage/emulated/0 (for tests). */
class RetroArchModule(private val internalRoot: String = "/storage/emulated/0") : EmulatorModule {
    private fun rooted(p: String): String {
        val c = Paths.canonical(p)
        return if (internalRoot != "/storage/emulated/0" && c.startsWith("/storage/emulated/0")) internalRoot + c.removePrefix("/storage/emulated/0") else c
    }

    override val emulatorId = "retroarch"
    override val packages = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")
    override val configRoots = setOf("data", "config")

    private val saveExts = setOf("srm", "sav", "rtc", "mcr", "eep", "fla", "dsv", "bin")
    private val romExts = setOf("gba", "gbc", "gb", "nes", "sfc", "smc", "md", "gen", "n64", "z64", "nds", "zip", "7z", "chd", "cue", "iso", "pbp")
    private val stateRe = Regex("^(.+)\\.state(?:(\\d+)|\\.auto)?$")
    private val maxPlaylist = 16L * 1024 * 1024
    private val archiveSep = Regex("\\.(zip|7z|rar)#", RegexOption.IGNORE_CASE)
    private val maxConfigFile = 1_000_000L
    private val keyPrefix = "retroarch/"

    private class Dirs(val saves: String, val states: String, val config: String, val playlists: String,
                       val sortSaves: Boolean, val sortStates: Boolean)

    private fun cfgPath(d: Detected) = "${d.dataRoot}/retroarch.cfg"

    private fun probeRoot(fs: Fs, pkg: String): String? =
        listOf("/sdcard/RetroArch", "/sdcard/Android/media/$pkg/RetroArch", "/sdcard/Android/data/$pkg/files/RetroArch")
            .map { rooted(it) }.firstOrNull { fs.exists(it) }

    private fun readCfg(fs: Fs, d: Detected): RaCfg? {
        val p = cfgPath(d)
        if (!fs.exists(p)) return null
        return RaCfg.parse(fs.openRead(p).bufferedReader().use { it.readText() })
    }

    private fun dirs(fs: Fs, d: Detected): Dirs? {
        val cfg = readCfg(fs, d)
        val probed = probeRoot(fs, d.pkg)
        if (cfg == null && probed == null) return null
        val base = probed ?: rooted("/sdcard/RetroArch")
        val c = cfg ?: RaCfg.EMPTY
        fun dir(key: String, sub: String) = c.path(key, base)?.let { rooted(it).trimEnd('/') } ?: "$base/$sub"
        val config = dir("rgui_config_directory", "config")
        return Dirs(dir("savefile_directory", "saves"), dir("savestate_directory", "states"), config,
            dir("playlist_directory", "playlists"), c.bool("sort_savefiles_enable"), c.bool("sort_savestates_enable"))
    }

    override fun detect(fs: Fs, settings: DeviceSettings): Detected? {
        val installed = fs.installedPackages()
        val pkg = packages.firstOrNull { it in installed } ?: return null
        val dataRoot = rooted("/sdcard/Android/data/$pkg/files")
        val extra = LinkedHashMap<String, String>()
        extra["INTERNAL"] = internalRoot
        val base = Detected(emulatorId, pkg, "", dataRoot, extra)
        if (!fs.exists(cfgPath(base))) extra["cfgMissing"] = "1"
        dirs(fs, base)?.let { extra["config"] = it.config }
        return base.copy(extraRoots = extra)
    }

    private fun name(p: String) = p.substringAfterLast('/')

    override fun saveItems(fs: Fs, d: Detected): List<LocalItem> {
        val dirs = dirs(fs, d) ?: return emptyList()
        val out = ArrayList<LocalItem>()
        for (f in fs.walk(dirs.saves)) {
            if (f.isDir) continue
            val n = name(f.path)
            val ext = n.substringAfterLast('.', "").lowercase()
            if (ext !in saveExts) continue
            val base = n.substringBeforeLast('.')
            if (base.isEmpty()) continue
            out += LocalItem(ItemKind.Save, keyPrefix + base, ext, coreOf(f.path, dirs.saves), f.path.substringBeforeLast('/'), listOf(n))
        }
        for (f in fs.walk(dirs.states)) {
            if (f.isDir) continue
            val n = name(f.path)
            val m = stateRe.matchEntire(n) ?: continue
            val slot = if (m.groups[2] != null) m.groupValues[2] else if (n.endsWith(".auto")) "auto" else ""
            out += LocalItem(ItemKind.State, keyPrefix + m.groupValues[1], slot, coreOf(f.path, dirs.states), f.path.substringBeforeLast('/'), listOf(n))
        }
        return out.sortedWith(compareBy({ it.kind }, { it.gameKey }, { it.slot }, { it.core }))
    }

    /** First folder below [dir], or "" for files directly in it. */
    private fun coreOf(path: String, dir: String): String {
        val rel = path.removePrefix("$dir/")
        return if (rel.contains('/')) rel.substringBefore('/') else ""
    }

    override fun watchDirs(fs: Fs, d: Detected): List<String> {
        val dirs = dirs(fs, d) ?: return emptyList()
        return listOf(dirs.saves, dirs.states)
    }

    private class PlaylistEntry(val path: String, val label: String?, val core: String?)

    private fun playlists(fs: Fs, d: Detected): List<PlaylistEntry> {
        val dir = dirs(fs, d)?.playlists ?: return emptyList()
        val out = ArrayList<PlaylistEntry>()
        for (sub in listOf(dir, "$dir/builtin")) {
            for (e in fs.list(sub).sortedBy { it.path }) {
                if (e.isDir || !e.path.endsWith(".lpl", true)) continue
                if (e.size > maxPlaylist) { System.err.println("RetroArch: skipping oversized playlist ${e.path} (${e.size} bytes)"); continue }
                try {
                    val text = fs.openRead(e.path).bufferedReader().use { it.readText() }
                    val items = Json.parseToJsonElement(text).jsonObject["items"]?.jsonArray ?: continue
                    for (it in items) {
                        val o = it as? JsonObject ?: continue
                        val path = o["path"]?.jsonPrimitive?.contentOrNull ?: continue
                        out += PlaylistEntry(path, o["label"]?.jsonPrimitive?.contentOrNull, o["core_path"]?.jsonPrimitive?.contentOrNull)
                    }
                } catch (_: Exception) { /* malformed playlist: skip */ }
            }
        }
        return out
    }

    private fun contentBase(path: String): String {
        val sep = archiveSep.findAll(path).lastOrNull()
        val inner = if (sep != null) path.substring(sep.range.last + 1) else path
        return name(inner.replace('\\', '/')).substringBeforeLast('.')
    }

    override fun installedGames(fs: Fs, d: Detected, settings: DeviceSettings): List<InstalledGame> {
        val found = LinkedHashMap<String, String?>()
        for (p in playlists(fs, d)) {
            val b = contentBase(p.path)
            if (b.isEmpty()) continue
            val k = keyPrefix + b
            if (found[k] == null) found[k] = p.label?.ifEmpty { null }
        }
        for (dir in settings.romDirs.map { Paths.canonical(it) }) for (f in fs.walk(dir)) {
            if (f.isDir) continue
            val n = name(f.path)
            if (n.substringAfterLast('.', "").lowercase() !in romExts) continue
            val b = n.substringBeforeLast('.')
            if (b.isNotEmpty() && (keyPrefix + b) !in found) found[keyPrefix + b] = null
        }
        return found.map { (k, v) -> InstalledGame(k, v) }.sortedBy { it.gameKey }
    }

    override fun cores(fs: Fs, d: Detected): List<String> =
        playlists(fs, d).mapNotNull { it.core }
            .filter { it.isNotEmpty() && it != "DETECT" }
            .map { name(it).removeSuffix(".so").removeSuffix("_libretro_android") }
            .filter { it.isNotEmpty() }.distinct().sorted()

    override fun configBundle(fs: Fs, d: Detected): ConfigBundleFiles {
        val out = sortedMapOf<String, String>()
        if (fs.exists(cfgPath(d))) out["data/retroarch.cfg"] = cfgPath(d)
        val dirs = dirs(fs, d) ?: return ConfigBundleFiles(out)
        for (f in fs.walk(dirs.config)) {
            if (f.isDir || f.size > maxConfigFile) continue
            out["config/" + f.path.removePrefix(dirs.config + "/")] = f.path
        }
        return ConfigBundleFiles(out)
    }

    override fun targetDirFor(fs: Fs, d: Detected, kind: ItemKind, gameKey: String, slot: String, core: String): String {
        if (!gameKey.startsWith(keyPrefix)) throw ModuleException("invalid RetroArch game key: '$gameKey'")
        val base = gameKey.removePrefix(keyPrefix)
        Paths.requireSafeSegment(base)
        if (core.isNotEmpty()) Paths.requireSafeSegment(core)
        val dirs = dirs(fs, d) ?: throw ModuleException("RetroArch directories not found")
        val (root, sorted, fileName) = if (kind == ItemKind.State) {
            if (slot != "" && slot != "auto" && !slot.all { it in '0'..'9' }) throw ModuleException("invalid RetroArch state slot: '$slot'")
            val suffix = when (slot) { "" -> "state"; "auto" -> "state.auto"; else -> "state$slot" }
            Triple(dirs.states, dirs.sortStates, "$base.$suffix")
        } else {
            Paths.requireSafeSegment(slot)
            Triple(dirs.saves, dirs.sortSaves, "$base.$slot")
        }
        val preferred = if (sorted && core.isNotEmpty()) "$root/$core" else root
        val matches = fs.walk(root).filter { !it.isDir && name(it.path).equals(fileName, true) }
        if (matches.any { it.path.substringBeforeLast('/') == preferred }) return preferred
        val existing = matches.firstOrNull()
        return existing?.path?.substringBeforeLast('/') ?: preferred
    }
}
