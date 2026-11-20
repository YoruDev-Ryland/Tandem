package dev.emusync.app.modules.azahar

import dev.emusync.app.fs.Fs
import dev.emusync.app.model.InstalledGame
import dev.emusync.app.model.ItemKind
import dev.emusync.app.modules.*
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** [internalRoot] replaces /storage/emulated/0 (for tests). */
class AzaharModule(
    private val internalRoot: String = "/storage/emulated/0",
    private val storageRoot: String = "/storage",
    private val now: () -> Long = System::currentTimeMillis,
) : EmulatorModule {
    private var cached: Pair<Long, String>? = null
    private val cacheMillis = 10 * 60 * 1000L

    private fun rooted(p: String): String {
        val c = Paths.canonical(p)
        return if (internalRoot != "/storage/emulated/0" && c.startsWith("/storage/emulated/0")) internalRoot + c.removePrefix("/storage/emulated/0") else c
    }

    override val emulatorId = "azahar"
    override val packages = listOf("org.azahar_emu.azahar", "io.github.lime3ds.android")
    override val configRoots = setOf("root")

    private val hex8 = Regex("^[0-9A-Fa-f]{8}$")
    private val hex16 = Regex("^[0-9A-Fa-f]{16}$")
    private val appHigh = "00040000"
    private val stateRe = Regex("^([0-9A-Fa-f]{16})\\.(\\d+)\\..+$")
    private val romExts = setOf("3ds", "cci", "cxi")
    private val zeroId = "0".repeat(32)

    override fun detect(fs: Fs, settings: DeviceSettings): Detected? {
        val pkg = packages.firstOrNull { it in fs.installedPackages() } ?: return null
        val root = settings.overrides["azahar.root"]?.takeIf { it.isNotBlank() }?.let { rooted(it).trimEnd('/') }
            ?: findRoot(fs, pkg)
            ?: return null
        return Detected(emulatorId, pkg, "", root, mapOf("INTERNAL" to internalRoot, "root" to root))
    }

    private fun isRoot(fs: Fs, dir: String) = fs.exists("$dir/config/config.ini") && fs.exists("$dir/sdmc")

    private fun findRoot(fs: Fs, pkg: String): String? {
        cached?.let { (t, r) -> if (now() - t < cacheMillis && isRoot(fs, r)) return r }
        val volumes = DirSearch.sdVolumes(fs, storageRoot)
        val known = (listOf(rooted("/sdcard")) + volumes).flatMap { listOf("$it/Games/3ds/user", "$it/Android/data/$pkg/files") }
        val found = DirSearch.locate(fs, known, listOf(rooted("/sdcard")) + volumes, 4,
            rank = { fs.stat("$it/sdmc")?.mtimeMillis ?: 0L }) { isRoot(fs, it) }
        if (found != null) cached = now() to found
        return found
    }

    private fun name(p: String) = p.substringAfterLast('/')

    /** `<root>/sdmc/Nintendo 3DS/<id0>/<id1>` of the most recently modified id0/id1 pair (title/ need not exist yet). */
    private fun idPair(fs: Fs, d: Detected): String? {
        val base = "${d.dataRoot}/sdmc/Nintendo 3DS"
        var best: Pair<Long, String>? = null
        for (id0 in fs.list(base)) {
            if (!id0.isDir) continue
            for (id1 in fs.list(id0.path)) {
                if (!id1.isDir) continue
                if (best == null || id1.mtimeMillis > best.first) best = id1.mtimeMillis to id1.path
            }
        }
        return best?.second
    }

    private fun titleRoot(fs: Fs, d: Detected): String? = idPair(fs, d)?.let { "$it/title" }

    private fun titleDirs(fs: Fs, d: Detected): List<Pair<String, String>> {   // (low8 as-is, absolute dir)
        val tr = titleRoot(fs, d) ?: return emptyList()
        return fs.list("$tr/$appHigh").filter { it.isDir && hex8.matches(name(it.path)) }.map { name(it.path) to it.path }
    }

    private fun key(low8: String) = appHigh + low8.uppercase()

    override fun saveItems(fs: Fs, d: Detected): List<LocalItem> {
        val out = ArrayList<LocalItem>()
        for ((low, dir) in titleDirs(fs, d)) {
            try {
                val data = "$dir/data"
                val files = fs.walk(data).filter { !it.isDir }.map { it.path.removePrefix("$data/") }.sorted()
                if (files.isNotEmpty()) out += LocalItem(ItemKind.Save, key(low), "", "", data, files)
            } catch (_: Exception) { continue }
        }
        for (e in fs.list("${d.dataRoot}/states")) {
            try {
                if (e.isDir) continue
                val m = stateRe.matchEntire(name(e.path)) ?: continue
                out += LocalItem(ItemKind.State, m.groupValues[1].uppercase(), m.groupValues[2], "", "${d.dataRoot}/states", listOf(name(e.path)))
            } catch (_: Exception) { continue }
        }
        return out.sortedWith(compareBy({ it.kind }, { it.gameKey }, { it.slot }))
    }

    override fun watchDirs(fs: Fs, d: Detected): List<String> =
        listOfNotNull(titleRoot(fs, d)?.let { "$it/$appHigh" }, "${d.dataRoot}/states")

    override fun installedGames(fs: Fs, d: Detected, settings: DeviceSettings): List<InstalledGame> {
        val found = sortedSetOf<String>()
        for ((low, dir) in titleDirs(fs, d)) if (fs.exists("$dir/content")) found += key(low)
        // TODO: parse .cia (TMD title id). A .cia counts as installed only if its title dir exists, which the
        // loop above already covers.
        val dirs = LinkedHashSet<String>()
        dirs += rooted("/sdcard/Games/3ds"); dirs += d.dataRoot.substringBeforeLast('/')
        settings.romDirs.mapTo(dirs) { rooted(it).trimEnd('/') }
        for (dir in dirs) for (e in try { fs.list(dir) } catch (_: Exception) { emptyList() }) {
            try {
                if (e.isDir || name(e.path).substringAfterLast('.', "").lowercase() !in romExts) continue
                val pid = fs.openRead(e.path).use { programId(it) } ?: continue
                val hex = "%016X".format(pid)
                if (hex.startsWith(appHigh)) found += hex
            } catch (_: Exception) { continue }
        }
        return found.map { InstalledGame(it) }
    }

    override fun configBundle(fs: Fs, d: Detected): ConfigBundleFiles {
        val out = sortedMapOf<String, String>()
        val cfg = "${d.dataRoot}/config/config.ini"
        if (fs.exists(cfg)) out["root/config/config.ini"] = cfg
        for (e in fs.list("${d.dataRoot}/config/custom")) {
            if (!e.isDir && e.path.endsWith(".ini", true)) out["root/config/custom/${name(e.path)}"] = e.path
        }
        return ConfigBundleFiles(out)
    }

    override fun targetDirFor(fs: Fs, d: Detected, kind: ItemKind, gameKey: String, slot: String, core: String): String {
        Paths.requireSafeSegment(gameKey)
        if (!hex16.matches(gameKey)) throw ModuleException("invalid Azahar title id: '$gameKey'")
        if (kind == ItemKind.State) {
            if (slot.isEmpty() || !slot.all { it in '0'..'9' }) throw ModuleException("invalid Azahar state slot: '$slot'")
            return "${d.dataRoot}/states"
        }
        if (!gameKey.startsWith(appHigh, ignoreCase = true)) throw ModuleException("unsupported Azahar title: '$gameKey'")
        val low = gameKey.substring(8)
        val tr = titleRoot(fs, d) ?: "${d.dataRoot}/sdmc/Nintendo 3DS/$zeroId/$zeroId/title"
        val existing = fs.list("$tr/$appHigh").firstOrNull { it.isDir && name(it.path).equals(low, true) }
        // Azahar writes title dirs as {:08x}
        return "${existing?.path ?: "$tr/$appHigh/${low.lowercase()}"}/data"
    }

    companion object {
        /**
         * Program ID (u64 LE at 0x118 of the NCCH header) of an NCCH (.cxi) or NCSD (.3ds/.cci) image, else null.
         * NCSD: magic "NCSD" at 0x100, first partition offset in 0x200-byte media units is the u32 at 0x120
         * (normally 0x20, i.e. 0x4000). Verified against azahar ncch_container.cpp.
         */
        fun programId(input: InputStream): Long? {
            val head = input.readNBytes(0x200)
            if (head.size < 0x200) return null
            val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
            return when (String(head, 0x100, 4, Charsets.ISO_8859_1)) {
                "NCCH" -> b.getLong(0x118)
                "NCSD" -> {
                    val off = (b.getInt(0x120).toLong() and 0xFFFFFFFFL) * 0x200
                    if (off < 0x200) return null
                    var skip = off - 0x200
                    while (skip > 0) { val n = input.skip(skip); if (n <= 0) return null; skip -= n }
                    val h = input.readNBytes(0x200)
                    if (h.size < 0x200 || String(h, 0x100, 4, Charsets.ISO_8859_1) != "NCCH") return null
                    ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getLong(0x118)
                }
                else -> null
            }
        }
    }
}
