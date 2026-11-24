package dev.tandem.app.modules.retroarch

import dev.tandem.app.fs.FsUnavailableException
import dev.tandem.app.fs.Fs
import dev.tandem.app.model.InstalledGame
import dev.tandem.app.model.ItemKind
import dev.tandem.app.modules.*
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

    override val displayName = "RetroArch"
    override val packages = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32")
    override val configRoots = setOf("data", "config")

    private val saveExts = setOf("srm", "sav", "rtc", "mcr", "eep", "fla", "dsv", "bin")
    private val romExts = setOf("gba", "gbc", "gb", "nes", "sfc", "smc", "md", "gen", "n64", "z64", "nds", "zip", "7z", "chd", "cue", "iso", "pbp")
    private val stateRe = Regex("^(.+)\\.state(?:(\\d+)|\\.auto)?$")
    private val maxPlaylist = 16L * 1024 * 1024
    private val archiveSep = Regex("\\.(zip|7z|rar)#", RegexOption.IGNORE_CASE)
    private val maxConfigFile = 1_000_000L
    private val keyPrefix = "retroarch/"
    private val coreFolder = Regex("\\(([^()]+)\\)\\s*$")   // "Nintendo - Game Boy Advance (mGBA)" -> mGBA

    private class Dirs(val saves: String, val states: String, val config: String, val playlists: String, val system: String,
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
            dir("playlist_directory", "playlists"), dir("system_directory", "system"), c.bool("sort_savefiles_enable"), c.bool("sort_savestates_enable"))
    }

    /**
     * BIOS and system files go to RetroArch's system folder: retroarch.cfg's `system_directory` when it names one,
     * else `system` under the RetroArch folder when that exists. Otherwise null: the core would look elsewhere.
     */
    override fun vaultTarget(fs: Fs, d: Detected, category: String, name: String): String? {
        if (category != "bios" && category != "system") return null
        val dirs = dirs(fs, d) ?: return null
        val configured = readCfg(fs, d)?.get("system_directory") != null
        return dirs.system.takeIf { configured || fs.exists(it) }
    }

    override fun detect(fs: Fs, settings: DeviceSettings): Detected? {
        val installed = fs.installedPackages()
        val pkg = activePackage(installed, settings) ?: return null
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

    /**
     * [core] is the entry's core_path, [coreName] its core_name; DETECT entries take the playlist's defaults. [system]
     * is the game's system label, from the entry's db_name, the playlist's own name, the core name or the file's
     * extension, in that order.
     */
    private class PlaylistEntry(val path: String, val label: String?, val core: String?, val coreName: String? = null, val system: String? = null)

    private fun playlists(fs: Fs, d: Detected): List<PlaylistEntry> {
        val dir = dirs(fs, d)?.playlists ?: return emptyList()
        val out = ArrayList<PlaylistEntry>()
        for (sub in listOf(dir, "$dir/builtin")) {
            for (e in fs.list(sub).sortedBy { it.path }) {
                if (e.isDir || !e.path.endsWith(".lpl", true)) continue
                if (e.size > maxPlaylist) { System.err.println("RetroArch: skipping oversized playlist ${e.path} (${e.size} bytes)"); continue }
                try {
                    val text = fs.openRead(e.path).bufferedReader().use { it.readText() }
                    val root = Json.parseToJsonElement(text).jsonObject
                    val items = root["items"]?.jsonArray ?: continue
                    fun str(o: JsonObject, k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    val defaultName = str(root, "default_core_name")
                    for (it in items) {
                        val o = it as? JsonObject ?: continue
                        val path = str(o, "path") ?: continue
                        val coreName = str(o, "core_name")?.takeIf { n -> n.isNotEmpty() && n != "DETECT" } ?: defaultName
                        val system = RetroArchSystems.fromDb(str(o, "db_name")) ?: RetroArchSystems.fromDb(name(e.path))
                            ?: RetroArchSystems.fromCoreName(coreName) ?: RetroArchSystems.fromExtension(contentName(path))
                        out += PlaylistEntry(path, str(o, "label"), str(o, "core_path"), coreName, system)
                    }
                } catch (fu: FsUnavailableException) { throw fu } catch (_: Exception) { /* malformed playlist: skip */ }
            }
        }
        return out
    }

    /** The content's file name: inside an archive, the entry after '#'. */
    private fun contentName(path: String): String {
        val sep = archiveSep.findAll(path).lastOrNull()
        val inner = if (sep != null) path.substring(sep.range.last + 1) else path
        return name(inner.replace('\\', '/'))
    }

    private fun contentBase(path: String): String = contentName(path).substringBeforeLast('.')

    override fun installedGames(fs: Fs, d: Detected, settings: DeviceSettings): List<InstalledGame> {
        val found = LinkedHashMap<String, String?>()
        val systems = HashMap<String, String>()
        // Archives a playlist opens inside ("x.zip#inner.gba"): RetroArch names their saves after the inner file, so
        // the archive itself is not a second game when the ROM folders are walked.
        // A history entry for the bare archive (opened once from the file browser) is not a second game either.
        val entries = playlists(fs, d)
        val opened = entries.mapNotNullTo(HashSet()) { p ->
            archiveSep.findAll(p.path).lastOrNull()?.let { Paths.canonical(p.path.substring(0, it.range.last).replace('\\', '/')) }
        }
        for (p in entries) {
            if (Paths.canonical(p.path.replace('\\', '/')) in opened) continue
            val b = contentBase(p.path)
            if (b.isEmpty()) continue
            val k = keyPrefix + b
            if (found[k] == null) found[k] = p.label?.ifEmpty { null }
            if (p.system != null) systems.putIfAbsent(k, p.system)
        }
        for (dir in settings.romDirs.map { Paths.canonical(it) }) for (f in fs.walk(dir)) {
            if (f.isDir || Paths.canonical(f.path) in opened) continue
            val n = name(f.path)
            if (n.substringAfterLast('.', "").lowercase() !in romExts) continue
            val b = n.substringBeforeLast('.')
            if (b.isEmpty()) continue
            if ((keyPrefix + b) !in found) found[keyPrefix + b] = null
            RetroArchSystems.fromExtension(n)?.let { systems.putIfAbsent(keyPrefix + b, it) }
        }
        return found.map { (k, v) -> InstalledGame(k, v, system = systems[k]) }.sortedBy { it.gameKey }
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
        val files = fs.walk(root).filter { !it.isDir }
        val matches = files.filter { name(it.path).equals(fileName, true) }
        // A state only works with the core that made it, and its key includes that core: it keeps its own core
        // folder. A save's key has no core, and this device may use another core for the game.
        val preferred = when {
            !sorted -> root
            kind == ItemKind.State -> if (core.isNotEmpty()) "$root/$core" else root
            else -> localCoreDir(fs, d, root, base, files)
        }
        if (matches.any { it.path.substringBeforeLast('/') == preferred }) return preferred
        val existing = matches.firstOrNull()
        return existing?.path?.substringBeforeLast('/') ?: preferred
    }

    /**
     * Where a sorted save (never a state) for [base] goes on this device: (1) the EXISTING core folder matching the
     * core this device's playlists give the game (an entry's core or its playlist's default; by the core_name's
     * "(…)" suffix, then the core_path stem); (2) the core folder under [root] holding the most files; (3) [root]
     * itself (unsorted). Never creates a folder from a display name: RetroArch sorts by library_name, which the
     * display name often differs from ("Snes9x - Current" vs "Snes9x").
     */
    private fun localCoreDir(fs: Fs, d: Detected, root: String, base: String, files: List<dev.tandem.app.fs.FileEntry>): String {
        val counts = files.mapNotNull { f -> f.path.removePrefix("$root/").takeIf { '/' in it }?.substringBefore('/') }
            .groupingBy { it }.eachCount()
        fun existing(n: String) = norm(n).takeIf { it.isNotEmpty() }?.let { w -> counts.keys.firstOrNull { norm(it) == w } }
        for (e in playlists(fs, d)) {
            if (!contentBase(e.path).equals(base, true)) continue
            val libName = e.coreName?.let { coreFolder.find(it)?.groupValues?.get(1)?.trim() }?.takeIf { it.isNotEmpty() }
            val stem = e.core?.takeIf { it.isNotEmpty() && it != "DETECT" }
                ?.let { name(it).removeSuffix(".so").removeSuffix("_libretro_android").removeSuffix("_libretro") }
            val folder = libName?.let { existing(it) } ?: stem?.let { existing(it) } ?: continue
            if (runCatching { Paths.requireSafeSegment(folder) }.isSuccess) return "$root/$folder"
        }
        val common = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()
        return common?.let { "$root/${it.key}" } ?: root
    }

    /** Core folder / core name for matching: lowercase, without " - Current"/_libretro/_android suffixes or punctuation. */
    private fun norm(n: String): String {
        var s = n.trim().lowercase()
        for (suffix in listOf(" - current", "_libretro_android", "_libretro", "_android")) s = s.removeSuffix(suffix)
        return s.filter { it.isLetterOrDigit() }
    }
}
