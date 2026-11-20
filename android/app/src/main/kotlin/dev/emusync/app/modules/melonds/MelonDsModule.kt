package dev.emusync.app.modules.melonds

import dev.emusync.app.fs.Fs
import dev.emusync.app.model.InstalledGame
import dev.emusync.app.model.ItemKind
import dev.emusync.app.modules.*

/** [internalRoot] replaces /storage/emulated/0, [storageRoot] replaces /storage (for tests). */
class MelonDsModule(
    private val internalRoot: String = "/storage/emulated/0",
    private val storageRoot: String = "/storage",
) : EmulatorModule {
    private fun rooted(p: String): String {
        val c = Paths.canonical(p)
        return if (internalRoot != "/storage/emulated/0" && c.startsWith("/storage/emulated/0")) internalRoot + c.removePrefix("/storage/emulated/0") else c
    }

    override val emulatorId = "melonds"
    override val packages = listOf("me.magnum.melonds")

    private val romExts = setOf("nds", "dsi", "zip", "7z")
    private val stateRe = Regex("^(.+)\\.ml([0-8])$", RegexOption.IGNORE_CASE)
    private val sdRe = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")
    private val keyPrefix = "nds/"
    private val romKey = "rom:"

    override fun detect(fs: Fs, settings: DeviceSettings): Detected? {
        val pkg = packages.firstOrNull { it in fs.installedPackages() } ?: return null
        val dirs = LinkedHashSet<String>()
        dirs += rooted("/sdcard/Games/nds")
        for (e in fs.list(storageRoot)) {
            if (e.isDir && sdRe.matches(e.path.substringAfterLast('/'))) dirs += "${e.path}/Games/nds"
        }
        for (r in settings.romDirs) {
            val c = rooted(r).trimEnd('/')
            if (c.endsWith("nds", ignoreCase = true)) dirs += c
        }
        val extra = LinkedHashMap<String, String>()
        extra["INTERNAL"] = internalRoot
        extra["export"] = rooted("/sdcard/EmuSync/melonds-export")
        dirs.forEachIndexed { i, dir -> extra["$romKey$i"] = dir }
        return Detected(emulatorId, pkg, "", rooted("/sdcard/Android/data/$pkg/files"), extra)
    }

    private fun romDirs(d: Detected): List<String> =
        d.extraRoots.filterKeys { it.startsWith(romKey) }.entries.sortedBy { it.key.removePrefix(romKey).toIntOrNull() ?: 0 }.map { it.value }

    private fun name(p: String) = p.substringAfterLast('/')
    private fun ext(n: String) = n.substringAfterLast('.', "").lowercase()

    private fun romFiles(fs: Fs, dir: String): List<String> = try {
        fs.list(dir).filter { !it.isDir && ext(name(it.path)) in romExts }.map { name(it.path) }
    } catch (_: Exception) { emptyList() }

    override fun saveItems(fs: Fs, d: Detected): List<LocalItem> {
        val out = LinkedHashMap<Triple<ItemKind, String, String>, LocalItem>()
        fun scan(dir: String) {
            val entries = try { fs.list(dir) } catch (_: Exception) { return }
            for (e in entries) {
                try {
                    if (e.isDir) continue
                    val n = name(e.path)
                    val kind: ItemKind; val base: String; val slot: String
                    val m = stateRe.matchEntire(n)
                    when {
                        n.endsWith(".sav", ignoreCase = true) && n.length > 4 -> { kind = ItemKind.Save; base = n.dropLast(4); slot = "" }
                        m != null -> { kind = ItemKind.State; base = m.groupValues[1]; slot = m.groupValues[2] }
                        else -> continue
                    }
                    out.putIfAbsent(Triple(kind, base, slot), LocalItem(kind, keyPrefix + base, slot, "", dir, listOf(n), base))
                } catch (_: Exception) { continue }
            }
        }
        romDirs(d).forEach(::scan)
        scan("${d.dataRoot}/saves")
        scan("${d.dataRoot}/savestates")
        return out.values.sortedWith(compareBy({ it.kind }, { it.gameKey }, { it.slot }))
    }

    override fun watchDirs(fs: Fs, d: Detected): List<String> =
        romDirs(d) + "${d.dataRoot}/saves" + "${d.dataRoot}/savestates"

    override fun installedGames(fs: Fs, d: Detected, settings: DeviceSettings): List<InstalledGame> {
        val found = sortedMapOf<String, String>()
        for (dir in romDirs(d)) for (n in romFiles(fs, dir)) {
            val base = n.substringBeforeLast('.')
            found.putIfAbsent(keyPrefix + base, base)
        }
        return found.map { (k, v) -> InstalledGame(k, v) }
    }

    override fun configBundle(fs: Fs, d: Detected): ConfigBundleFiles {
        val out = sortedMapOf<String, String>()
        val root = d.extraRoots["export"] ?: return ConfigBundleFiles(out)
        for (f in try { fs.walk(root) } catch (_: Exception) { emptyList() }) {
            if (f.isDir) continue
            out["export/" + f.path.removePrefix("$root/")] = f.path
        }
        return ConfigBundleFiles(out)
    }

    override fun targetDirFor(fs: Fs, d: Detected, kind: ItemKind, gameKey: String, slot: String, core: String): String {
        if (!gameKey.startsWith(keyPrefix)) throw ModuleException("invalid melonDS game key: '$gameKey'")
        val base = gameKey.removePrefix(keyPrefix)
        Paths.requireSafeSegment(base)
        if (kind == ItemKind.State && (slot.length != 1 || slot[0] !in '0'..'8')) throw ModuleException("invalid melonDS state slot: '$slot'")
        for (dir in romDirs(d)) {
            if (romFiles(fs, dir).any { it.substringBeforeLast('.').equals(base, ignoreCase = true) }) return dir
        }
        throw ModuleException("melonDS ROM not installed for '$gameKey'")
    }
}
