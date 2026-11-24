package dev.tandem.app.modules.melonds

import java.io.InputStream
import dev.tandem.app.fs.FsUnavailableException
import dev.tandem.app.fs.Fs
import dev.tandem.app.model.InstalledGame
import dev.tandem.app.model.ItemKind
import dev.tandem.app.modules.*

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

    override val displayName = "melonDS"
    override val packages = listOf("me.magnum.melonds")
    override val configRoots = setOf("export")

    private val romExts = setOf("nds", "dsi", "zip", "7z")
    private val stateRe = Regex("^(.+)\\.ml([0-8])$", RegexOption.IGNORE_CASE)
    private val sdRe = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")
    private val keyPrefix = "nds/"
    private val romKey = "rom:"

    override fun detect(fs: Fs, settings: DeviceSettings): Detected? {
        val pkg = activePackage(fs.installedPackages(), settings) ?: return null
        val dirs = LinkedHashSet<String>()
        dirs += rooted("/sdcard/Games/nds")
        for (e in try { fs.list(storageRoot) } catch (fu: FsUnavailableException) { throw fu } catch (_: Exception) { emptyList() }) {
            if (e.isDir && sdRe.matches(e.path.substringAfterLast('/'))) dirs += "${e.path}/Games/nds"
        }
        for (r in settings.romDirs) {
            val c = rooted(r).trimEnd('/')
            if (c.endsWith("nds", ignoreCase = true)) dirs += c
        }
        val extra = LinkedHashMap<String, String>()
        extra["INTERNAL"] = internalRoot
        extra["export"] = rooted("/sdcard/Tandem/melonds-export")
        dirs.forEachIndexed { i, dir -> extra["$romKey$i"] = dir }
        return Detected(emulatorId, pkg, "", rooted("/sdcard/Android/data/$pkg/files"), extra)
    }

    private fun romDirs(d: Detected): List<String> =
        d.extraRoots.filterKeys { it.startsWith(romKey) }.entries.sortedBy { it.key.removePrefix(romKey).toIntOrNull() ?: 0 }.map { it.value }

    private fun name(p: String) = p.substringAfterLast('/')
    private fun ext(n: String) = n.substringAfterLast('.', "").lowercase()

    private fun romFiles(fs: Fs, dir: String): List<String> = try {
        fs.list(dir).filter { !it.isDir && ext(name(it.path)) in romExts }.map { name(it.path) }
    } catch (fu: FsUnavailableException) { throw fu } catch (_: Exception) { emptyList() }

    override fun saveItems(fs: Fs, d: Detected): List<LocalItem> {
        val out = LinkedHashMap<Triple<ItemKind, String, String>, LocalItem>()
        fun scan(dir: String) {
            val entries = try { fs.list(dir) } catch (fu: FsUnavailableException) { throw fu } catch (_: Exception) { return }
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
                } catch (fu: FsUnavailableException) { throw fu } catch (_: Exception) { continue }
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
        val found = sortedMapOf<String, Pair<String, String?>>()
        for (dir in romDirs(d)) for (n in romFiles(fs, dir)) {
            val base = n.substringBeforeLast('.')
            if (keyPrefix + base in found) continue
            val code = try { fs.openRead("$dir/$n").use { romGameCode(ext(n), it) } } catch (fu: FsUnavailableException) { throw fu } catch (_: Exception) { null }
            found[keyPrefix + base] = base to code
        }
        return found.map { (k, v) -> InstalledGame(k, v.first, gameId = v.second) }
    }

    override fun configBundle(fs: Fs, d: Detected): ConfigBundleFiles {
        val out = sortedMapOf<String, String>()
        val root = d.extraRoots["export"] ?: return ConfigBundleFiles(out)
        for (f in try { fs.walk(root) } catch (fu: FsUnavailableException) { throw fu } catch (_: Exception) { emptyList() }) {
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

    companion object {
        /** The game code of a ROM file by its extension: an .nds/.dsi header, or the first .nds/.dsi inside a .zip; null for anything else (.7z isn't read). */
        fun romGameCode(ext: String, input: InputStream): String? = when (ext) {
            "nds", "dsi" -> gameCode(input)
            "zip" -> java.util.zip.ZipInputStream(input).let { z ->
                var e = z.nextEntry
                var seen = 0
                while (e != null && seen++ < 64) {
                    val n = e.name.lowercase()
                    if (!e.isDirectory && (n.endsWith(".nds") || n.endsWith(".dsi"))) return@let gameCode(z)
                    e = z.nextEntry
                }
                null
            }
            else -> null
        }

        /** The DS header's 4-character game code (offset 0x0C), the GameTDB id; null unless it is 4 of A-Z, 0-9. */
        fun gameCode(input: InputStream): String? {
            val h = input.readNBytes(0x10)
            if (h.size < 0x10) return null
            val code = String(h, 0x0C, 4, Charsets.ISO_8859_1)
            return code.takeIf { c -> c.all { it in 'A'..'Z' || it in '0'..'9' } }
        }
    }
}
