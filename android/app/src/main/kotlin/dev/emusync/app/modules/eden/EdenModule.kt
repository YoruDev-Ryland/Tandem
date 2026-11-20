package dev.emusync.app.modules.eden

import dev.emusync.app.fs.FsUnavailableException
import dev.emusync.app.fs.Fs
import dev.emusync.app.model.InstalledGame
import dev.emusync.app.model.ItemKind
import dev.emusync.app.modules.*

/** [internalRoot] replaces /storage/emulated/0 (for tests). */
class EdenModule(private val internalRoot: String = "/storage/emulated/0") : EmulatorModule {
    private fun rooted(p: String): String {
        val c = Paths.canonical(p)
        return if (internalRoot != "/storage/emulated/0" && c.startsWith("/storage/emulated/0")) internalRoot + c.removePrefix("/storage/emulated/0") else c
    }

    override val emulatorId = "eden"

    override val displayName = "Eden"
    override val packages = listOf("dev.eden.eden_emulator", "dev.eden.eden_emulator.combo", "dev.igawa6.edenduo")

    private val hex16 = Regex("[0-9A-Fa-f]{16}")
    private val hex32 = Regex("[0-9A-Fa-f]{32}")
    private val titleInName = Regex("\\[(0100[0-9A-Fa-f]{12})\\]")
    private val userId = "0000000000000000"

    override fun detect(fs: Fs, settings: DeviceSettings): Detected? {
        val installed = fs.installedPackages()
        val pkg = settings.overrides["eden.package"]?.takeIf { it in installed }
            ?: packages.firstOrNull { it in installed } ?: return null
        val root = rooted("/sdcard/Android/data/$pkg/files")
        val cfg = readConfig(fs, root)
        return Detected(emulatorId, pkg, "", root, mapOf("saveRoot" to saveRoot(cfg, root), "nand" to nand(cfg, root), "INTERNAL" to internalRoot))
    }

    /** Parsed Qt ini: plain keys, the first non-empty value wins, flattened; keeps "Paths\x" and bare "x" forms. */
    internal fun readConfig(fs: Fs, dataRoot: String): Map<String, String> {
        val p = "$dataRoot/config/config.ini"
        if (!fs.exists(p)) return emptyMap()
        val out = LinkedHashMap<String, String>()
        fs.openRead(p).bufferedReader().useLines { lines ->
            for (l in lines) {
                val i = l.indexOf('=')
                if (i <= 0 || l.startsWith("[") || l.startsWith(";")) continue
                val k = l.substring(0, i)
                if (k.endsWith("\\default")) continue
                val v = l.substring(i + 1).trim().removeSurrounding("\"")
                if (out[k].isNullOrEmpty()) out[k] = v
            }
        }
        return out
    }

    private fun cfgPath(cfg: Map<String, String>, key: String): String? =
        (cfg["Paths\\$key"]?.takeIf { it.isNotEmpty() } ?: cfg[key]?.takeIf { it.isNotEmpty() })?.let { rooted(it) }

    internal fun nand(cfg: Map<String, String>, root: String) = cfgPath(cfg, "nand_directory") ?: "$root/nand"
    internal fun saveRoot(cfg: Map<String, String>, root: String) =
        (cfgPath(cfg, "save_directory") ?: nand(cfg, root)) + "/user/save"

    private fun saveRootOf(d: Detected) = d.extraRoots["saveRoot"] ?: (d.dataRoot + "/nand/user/save")
    private fun nandOf(d: Detected) = d.extraRoots["nand"] ?: (d.dataRoot + "/nand")

    private fun profileDirs(fs: Fs, d: Detected): List<String> =
        fs.list("${saveRootOf(d)}/$userId").filter { it.isDir && hex32.matches(it.path.substringAfterLast('/')) }.map { it.path }

    /** Profile dir with the newest file mtime, or null. */
    internal fun activeProfile(fs: Fs, d: Detected): String? =
        profileDirs(fs, d).maxByOrNull { dir ->
            fs.walk(dir).maxOfOrNull { it.mtimeMillis } ?: 0L
        }

    override fun saveItems(fs: Fs, d: Detected): List<LocalItem> {
        val prof = activeProfile(fs, d) ?: return emptyList()
        return fs.list(prof).filter { it.isDir && hex16.matches(it.path.substringAfterLast('/')) }.map {
            val dir = it.path
            val files = fs.walk(dir).map { f -> f.path.removePrefix("$dir/") }.sorted()
            LocalItem(ItemKind.Save, dir.substringAfterLast('/').uppercase(), "", "", dir, files)
        }.sortedBy { it.gameKey }
    }

    override fun watchDirs(fs: Fs, d: Detected): List<String> =
        listOfNotNull(activeProfile(fs, d), saveRootOf(d)).distinct()

    override fun installedGames(fs: Fs, d: Detected, settings: DeviceSettings): List<InstalledGame> {
        val found = LinkedHashMap<String, String?>()
        fun add(id: String, name: String?) {
            val k = normalizeTitleId(id)
            if (found[k] == null) found[k] = name
        }
        val cfg = readConfig(fs, d.dataRoot)
        val dirs = LinkedHashSet<String>()
        cfg.filterKeys { Regex("Paths\\\\gamedirs\\\\\\d+\\\\path").matches(it) }.values
            .mapNotNullTo(dirs) { ContentUris.toPath(it, d.extraRoots) }
        settings.romDirs.mapTo(dirs) { Paths.canonical(it) }
        for (dir in dirs) {
            for (f in fs.walk(dir)) {
                val name = f.path.substringAfterLast('/')
                val stem = name.substringBeforeLast('.', name)
                val m = titleInName.find(name)
                if (m != null) {
                    add(m.groupValues[1], stem.replace(titleInName, "").trim().ifEmpty { null })
                } else if (name.endsWith(".nsp", true)) {
                    ticketTitleId(fs, f.path)?.let { add(it, stem) }
                }
            }
        }
        saveItems(fs, d).forEach { add(it.gameKey, null) }
        return found.map { (k, v) -> InstalledGame(k, v) }.sortedBy { it.gameKey }
    }

    /** Maps update (low 12 bits 0x800) and DLC (non-zero low bits) title ids to the base application id. */
    internal fun normalizeTitleId(id: String): String {
        val v = id.toULongOrNull(16) ?: return id.uppercase()
        if (v < 0x1000UL) return id
        val low = v and 0xFFFUL
        val base = when {
            low == 0x800UL -> v - 0x800UL
            low != 0UL -> (v and 0xFFFUL.inv()) - 0x1000UL
            else -> v
        }
        return base.toString(16).uppercase().padStart(16, '0')
    }

    /** Reads only the PFS0 header; returns base TitleID from the first .tik entry name. */
    internal fun ticketTitleId(fs: Fs, path: String): String? = try {
        fs.openRead(path).use { ins ->
            val head = ins.readNBytes(16)
            if (head.size < 16 || String(head, 0, 4, Charsets.US_ASCII) != "PFS0") return null
            fun u32(o: Int) = (head[o].toLong() and 0xFF) or ((head[o + 1].toLong() and 0xFF) shl 8) or
                ((head[o + 2].toLong() and 0xFF) shl 16) or ((head[o + 3].toLong() and 0xFF) shl 24)
            val count = u32(4); val strSize = u32(8)
            if (count !in 0..65536 || strSize !in 0..(4L shl 20)) return null
            val rest = ins.readNBytes((count * 0x18 + strSize).toInt())
            if (rest.size < count * 0x18 + strSize) return null
            val strs = rest.copyOfRange((count * 0x18).toInt(), rest.size)
            val names = String(strs, Charsets.ISO_8859_1).split('\u0000')
            val tik = names.firstOrNull { it.endsWith(".tik", true) && it.length >= 16 && hex16.matches(it.take(16)) }
            tik?.take(16)?.let { (it.take(13) + "000").uppercase() }
        }
    } catch (fu: FsUnavailableException) { throw fu } catch (_: Exception) { null }

    override fun configBundle(fs: Fs, d: Detected): ConfigBundleFiles {
        val cfg = "${d.dataRoot}/config"
        val out = sortedMapOf<String, String>()
        if (fs.exists("$cfg/config.ini")) out["data/config/config.ini"] = "$cfg/config.ini"
        for (sub in listOf("custom", "input")) {
            fs.list("$cfg/$sub").filter { !it.isDir && it.path.endsWith(".ini") }.forEach {
                out["data/config/$sub/${it.path.substringAfterLast('/')}"] = it.path
            }
        }
        return ConfigBundleFiles(out)
    }

    override fun targetDirFor(fs: Fs, d: Detected, kind: ItemKind, gameKey: String, slot: String, core: String): String {
        if (!hex16.matches(gameKey)) throw ModuleException("invalid Eden title id: '$gameKey'")
        Paths.requireSafeSegment(gameKey)
        val base = "${saveRootOf(d)}/$userId"
        val profile = activeProfile(fs, d)?.substringAfterLast('/') ?: avatarProfile(fs, d)
            ?: throw ModuleException("no Eden profile yet; open Eden once")
        Paths.requireSafeSegment(profile)
        val existing = fs.list("$base/$profile").firstOrNull { it.isDir && it.path.substringAfterLast('/').equals(gameKey, true) }
        return existing?.path ?: "$base/$profile/${gameKey.uppercase()}"
    }

    private fun avatarProfile(fs: Fs, d: Detected): String? =
        fs.list("${nandOf(d)}/system/save/8000000000000010/su/avators").asSequence()
            .map { it.path.substringAfterLast('/').substringBeforeLast('.') }
            .firstOrNull { hex32.matches(it) }?.uppercase()
}
