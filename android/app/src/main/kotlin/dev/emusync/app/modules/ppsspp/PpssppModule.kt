package dev.emusync.app.modules.ppsspp

import dev.emusync.app.fs.Fs
import dev.emusync.app.model.InstalledGame
import dev.emusync.app.model.ItemKind
import dev.emusync.app.modules.*

/** [internalRoot] replaces /storage/emulated/0, [storageRoot] replaces /storage (for tests). */
class PpssppModule(
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

    override val emulatorId = "ppsspp"
    override val packages = listOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold")
    override val configRoots = setOf("memstick")

    private val idRe = Regex("[A-Z]{4}\\d{5}")
    private val stateRe = Regex("^([A-Z]{4}\\d{5})_([^_]+)_(\\d+)\\.ppst$")
    private val romExts = setOf("iso", "cso", "pbp", "chd")

    override fun detect(fs: Fs, settings: DeviceSettings): Detected? {
        val pkg = packages.firstOrNull { it in fs.installedPackages() } ?: return null
        val ms = settings.overrides["ppsspp.memstick"]?.takeIf { it.isNotBlank() }?.let { rooted(it).trimEnd('/') }
            ?: findMemstick(fs, pkg)
            ?: rooted("/sdcard/Android/data/$pkg/files")
        return Detected(emulatorId, pkg, "", ms, mapOf("INTERNAL" to internalRoot, "memstick" to ms))
    }

    private fun isMemstick(fs: Fs, dir: String) = fs.exists("$dir/PSP/SYSTEM/ppsspp.ini")

    private fun findMemstick(fs: Fs, pkg: String): String? {
        cached?.let { (t, r) -> if (now() - t < cacheMillis && isMemstick(fs, r)) return r }
        val volumes = DirSearch.sdVolumes(fs, storageRoot)
        val known = listOf(rooted("/sdcard"), rooted("/sdcard/Android/data/$pkg/files")) +
            volumes.flatMap { listOf(it, "$it/Android/data/$pkg/files") }
        val found = DirSearch.locate(fs, known, listOf(rooted("/sdcard")) + volumes, 4,
            rank = { fs.stat("$it/PSP/SAVEDATA")?.mtimeMillis ?: 0L }) { isMemstick(fs, it) }
        if (found != null) cached = now() to found
        return found
    }

    private fun ms(d: Detected) = d.extraRoots["memstick"] ?: d.dataRoot
    private fun saveRoot(d: Detected) = "${ms(d)}/PSP/SAVEDATA"
    private fun stateDir(d: Detected) = "${ms(d)}/PSP/PPSSPP_STATE"
    private fun name(p: String) = p.substringAfterLast('/')

    override fun saveItems(fs: Fs, d: Detected): List<LocalItem> {
        val out = ArrayList<LocalItem>()
        for (e in fs.list(saveRoot(d))) {
            try {
                val n = name(e.path)
                if (!e.isDir || n.length < 9 || !idRe.matches(n.substring(0, 9))) continue
                val slot = n.substring(9)
                if (slot.isNotEmpty()) Paths.requireSafeSegment(slot)
                val files = fs.walk(e.path).filter { !it.isDir }.map { it.path.removePrefix(e.path + "/") }.sorted()
                if (files.isEmpty()) continue
                out += LocalItem(ItemKind.Save, n.substring(0, 9), slot, "", e.path, files)
            } catch (_: Exception) { continue }
        }
        val states = LinkedHashMap<Pair<String, String>, LocalItem>()
        for (e in fs.list(stateDir(d))) {
            try {
                if (e.isDir) continue
                val m = stateRe.matchEntire(name(e.path)) ?: continue
                states.putIfAbsent(m.groupValues[1] to m.groupValues[3],
                    LocalItem(ItemKind.State, m.groupValues[1], m.groupValues[3], "", stateDir(d), listOf(name(e.path))))
            } catch (_: Exception) { continue }
        }
        return (out + states.values).sortedWith(compareBy({ it.kind }, { it.gameKey }, { it.slot }))
    }

    override fun watchDirs(fs: Fs, d: Detected): List<String> = listOf(saveRoot(d), stateDir(d))

    override fun installedGames(fs: Fs, d: Detected, settings: DeviceSettings): List<InstalledGame> {
        val found = sortedSetOf<String>()
        saveItems(fs, d).filter { it.kind == ItemKind.Save }.mapTo(found) { it.gameKey }
        for (dir in settings.romDirs.map { rooted(it).trimEnd('/') }.distinct()) {
            for (e in try { fs.list(dir) } catch (_: Exception) { emptyList() }) {
                val n = name(e.path)
                if (e.isDir || n.substringAfterLast('.', "").lowercase() !in romExts) continue
                idRe.find(n)?.let { found += it.value }
            }
        }
        return found.map { InstalledGame(it) }
    }

    override fun configBundle(fs: Fs, d: Detected): ConfigBundleFiles {
        val out = sortedMapOf<String, String>()
        val sys = "${ms(d)}/PSP/SYSTEM"
        for (e in fs.list(sys)) {
            val n = name(e.path)
            if (e.isDir) continue
            if (n == "ppsspp.ini" || n == "controls.ini" || n.endsWith("_ppsspp.ini")) out["memstick/PSP/SYSTEM/$n"] = e.path
        }
        return ConfigBundleFiles(out)
    }

    override fun targetDirFor(fs: Fs, d: Detected, kind: ItemKind, gameKey: String, slot: String, core: String): String {
        Paths.requireSafeSegment(gameKey)
        if (!Regex("^[A-Z]{4}\\d{5}$").matches(gameKey)) throw ModuleException("invalid PPSSPP game id: '$gameKey'")
        if (kind == ItemKind.State) {
            if (slot.isEmpty() || !slot.all { it in '0'..'9' }) throw ModuleException("invalid PPSSPP state slot: '$slot'")
            return stateDir(d)
        }
        if (slot.isNotEmpty()) Paths.requireSafeSegment(slot)
        val want = gameKey + slot
        val existing = fs.list(saveRoot(d)).firstOrNull { it.isDir && name(it.path).equals(want, true) }
        return existing?.path ?: "${saveRoot(d)}/$want"
    }
}
