package dev.emusync.app.modules.dolphin

import dev.emusync.app.fs.Fs
import dev.emusync.app.model.InstalledGame
import dev.emusync.app.model.ItemKind
import dev.emusync.app.modules.*

/** [internalRoot] replaces /storage/emulated/0 (for tests). */
class DolphinModule(private val internalRoot: String = "/storage/emulated/0") : EmulatorModule {
    private fun rooted(p: String): String {
        val c = Paths.canonical(p)
        return if (internalRoot != "/storage/emulated/0" && c.startsWith("/storage/emulated/0")) internalRoot + c.removePrefix("/storage/emulated/0") else c
    }

    override val emulatorId = "dolphin"
    override val packages = listOf("org.dolphinemu.dolphinemu")

    private val regions = listOf("USA", "EUR", "JAP")
    private val gameIdRe = Regex("^[A-Z0-9]{6}$")
    private val wiiKeyRe = Regex("^[A-Za-z0-9]{4}$")
    private val hex8 = Regex("[0-9A-Fa-f]{8}")
    private val stateRe = Regex("^(.+)\\.s(\\d{2})$")
    private val romExts = setOf("iso", "gcm", "ciso", "wbfs", "rvz", "wia")

    override fun detect(fs: Fs, settings: DeviceSettings): Detected? {
        val pkg = packages.firstOrNull { it in fs.installedPackages() } ?: return null
        return Detected(emulatorId, pkg, "", rooted("/sdcard/Android/data/$pkg/files"), mapOf("INTERNAL" to internalRoot))
    }

    private fun cardDir(d: Detected, region: String) = "${d.dataRoot}/GC/$region/Card A"
    private fun wiiTitles(d: Detected) = "${d.dataRoot}/Wii/title/00010000"

    override fun saveItems(fs: Fs, d: Detected): List<LocalItem> {
        val out = ArrayList<LocalItem>()
        for (region in regions) {
            val dir = cardDir(d, region)
            for (e in fs.list(dir)) {
                val name = e.path.substringAfterLast('/')
                if (e.isDir || !name.endsWith(".gci", true) || e.size < 0x40) continue
                val head = try { fs.openRead(e.path).use { it.readNBytes(0x28) } } catch (_: java.io.IOException) { continue }
                if (head.size < 0x28) continue
                val key = String(head, 0, 6, Charsets.US_ASCII)
                if (!gameIdRe.matches(key)) continue
                val slot = String(head, 8, 0x20, Charsets.ISO_8859_1).trimEnd('\u0000').trim('\u0000')
                try { Paths.requireSafeSegment(slot) } catch (_: ModuleException) { continue }
                out += LocalItem(ItemKind.Save, key, slot, "", dir, listOf(name))
            }
        }
        for (t in fs.list(wiiTitles(d))) {
            val hex = t.path.substringAfterLast('/')
            if (!t.isDir || !hex8.matches(hex)) continue
            val key = wiiKey(hex) ?: continue
            val data = "${t.path}/data"
            val files = fs.walk(data).filter { !it.isDir }.map { it.path.removePrefix("$data/") }.sorted()
            if (files.isEmpty()) continue
            out += LocalItem(ItemKind.Save, key, "", "", data, files)
        }
        for (e in fs.list("${d.dataRoot}/StateSaves")) {
            if (e.isDir) continue
            val name = e.path.substringAfterLast('/')
            val m = stateRe.matchEntire(name) ?: continue
            val nn = m.groupValues[2].toInt()
            if (nn !in 1..99) continue
            out += LocalItem(ItemKind.State, m.groupValues[1], m.groupValues[2], "", "${d.dataRoot}/StateSaves", listOf(name))
        }
        return out.sortedWith(compareBy({ it.kind }, { it.gameKey }, { it.slot }))
    }

    /** 8-hex title-low decoded as 4 ASCII chars. TODO: owner-device check whether a "W" suffix is wanted. */
    private fun wiiKey(hex: String): String? {
        val s = String(ByteArray(4) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }, Charsets.ISO_8859_1)
        return s.takeIf { wiiKeyRe.matches(it) }
    }

    override fun watchDirs(fs: Fs, d: Detected): List<String> =
        regions.map { cardDir(d, it) } + wiiTitles(d) + "${d.dataRoot}/StateSaves"

    /** ISOPath0..N values from a Dolphin.ini [General] section. */
    internal fun isoPaths(ini: String): List<String> {
        var section = ""
        val out = ArrayList<Pair<Int, String>>()
        for (raw in ini.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith("[")) { section = l.removePrefix("[").substringBefore(']'); continue }
            if (section != "General") continue
            val i = l.indexOf('=')
            if (i <= 0) continue
            val m = Regex("^ISOPath(\\d+)$").matchEntire(l.substring(0, i).trim()) ?: continue
            out += m.groupValues[1].toInt() to l.substring(i + 1).trim()
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    override fun installedGames(fs: Fs, d: Detected, settings: DeviceSettings): List<InstalledGame> {
        val found = LinkedHashMap<String, String?>()
        val ini = "${d.dataRoot}/Config/Dolphin.ini"
        val dirs = LinkedHashSet<String>()
        if (fs.exists(ini)) {
            val text = fs.openRead(ini).bufferedReader().use { it.readText() }
            for (p in isoPaths(text)) {
                val dir = if (p.startsWith("content://")) ContentUris.toPath(p, d.extraRoots) else Paths.canonical(p)
                if (dir != null) dirs += dir
            }
        }
        settings.romDirs.mapTo(dirs) { Paths.canonical(it) }
        for (dir in dirs) for (f in fs.walk(dir)) {
            if (f.isDir) continue
            val name = f.path.substringAfterLast('/')
            if (name.substringAfterLast('.', "").lowercase() !in romExts) continue
            val id = DiscId.read(fs, f.path) ?: continue
            if (found[id] == null) found[id] = name.substringBeforeLast('.').replace(Regex("\\s*\\([A-Z0-9]{6}\\)"), "").trim().ifEmpty { null }
        }
        saveItems(fs, d).filter { it.kind == ItemKind.Save && gameIdRe.matches(it.gameKey) }.forEach { if (it.gameKey !in found) found[it.gameKey] = null }
        return found.map { (k, v) -> InstalledGame(k, v) }.sortedBy { it.gameKey }
    }

    override fun configBundle(fs: Fs, d: Detected): ConfigBundleFiles {
        val out = sortedMapOf<String, String>()
        for (sub in listOf("Config", "GameSettings")) {
            fs.list("${d.dataRoot}/$sub").filter { !it.isDir && it.path.endsWith(".ini") }.forEach {
                out["data/$sub/${it.path.substringAfterLast('/')}"] = it.path
            }
        }
        return ConfigBundleFiles(out)
    }

    override fun targetDirFor(fs: Fs, d: Detected, kind: ItemKind, gameKey: String, slot: String, core: String): String {
        Paths.requireSafeSegment(gameKey)
        if (kind == ItemKind.State) return "${d.dataRoot}/StateSaves"
        return when {
            gameIdRe.matches(gameKey) -> {
                val region = when (gameKey[3]) {
                    'E' -> "USA"
                    'J', 'K' -> "JAP"
                    'P', 'D', 'F', 'S', 'I', 'H', 'X', 'Y', 'U', 'Z' -> "EUR"
                    else -> "USA"
                }
                existingRegion(fs, d, gameKey, slot)?.let { cardDir(d, it) } ?: cardDir(d, region)
            }
            wiiKeyRe.matches(gameKey) -> {
                val hex = gameKey.toByteArray(Charsets.ISO_8859_1).joinToString("") { "%02x".format(it) }
                val existing = fs.list(wiiTitles(d)).firstOrNull { it.isDir && it.path.substringAfterLast('/').equals(hex, true) }
                "${existing?.path ?: "${wiiTitles(d)}/$hex"}/data"
            }
            else -> throw ModuleException("invalid Dolphin game key: '$gameKey'")
        }
    }

    /** Region folder already holding a GCI with this gameKey and slot, if any. */
    private fun existingRegion(fs: Fs, d: Detected, gameKey: String, slot: String): String? {
        if (slot.isEmpty()) return null
        for (region in regions) for (e in fs.list(cardDir(d, region))) {
            if (e.isDir || !e.path.endsWith(".gci", true) || e.size < 0x40) continue
            val head = try { fs.openRead(e.path).use { it.readNBytes(0x28) } } catch (_: java.io.IOException) { continue }
            if (head.size < 0x28) continue
            if (String(head, 0, 6, Charsets.US_ASCII) == gameKey &&
                String(head, 8, 0x20, Charsets.ISO_8859_1).trim('\u0000') == slot) return region
        }
        return null
    }
}
