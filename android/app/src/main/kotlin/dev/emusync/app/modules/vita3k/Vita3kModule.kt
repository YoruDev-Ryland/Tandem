package dev.emusync.app.modules.vita3k

import dev.emusync.app.fs.FsUnavailableException
import dev.emusync.app.fs.Fs
import dev.emusync.app.model.InstalledGame
import dev.emusync.app.model.ItemKind
import dev.emusync.app.modules.*

/** [internalRoot] replaces /storage/emulated/0 (for tests). */
class Vita3kModule(private val internalRoot: String = "/storage/emulated/0") : EmulatorModule {
    private fun rooted(p: String): String {
        val c = Paths.canonical(p)
        return if (internalRoot != "/storage/emulated/0" && c.startsWith("/storage/emulated/0")) internalRoot + c.removePrefix("/storage/emulated/0") else c
    }

    override val emulatorId = "vita3k"

    override val displayName = "Vita3K"
    override val packages = listOf("org.vita3k.emulator")

    private val titleRe = Regex("^[A-Z0-9]{9}$")

    override fun detect(fs: Fs, settings: DeviceSettings): Detected? {
        val pkg = packages.firstOrNull { it in fs.installedPackages() } ?: return null
        return Detected(emulatorId, pkg, "", rooted("/sdcard/Android/data/$pkg/files"), mapOf("INTERNAL" to internalRoot))
    }

    /** `pref-path` from config.yml, else `<root>/vita`. */
    private fun vitaFs(fs: Fs, d: Detected): String {
        val cfg = "${d.dataRoot}/config.yml"
        try {
            if (fs.exists(cfg)) {
                for (raw in fs.openRead(cfg).bufferedReader().use { it.readText() }.lineSequence()) {
                    val l = raw.trim()
                    if (!l.startsWith("pref-path:")) continue
                    val v = l.removePrefix("pref-path:").trim().trim('"', '\'').trimEnd('/')
                    safePrefPath(v)?.let { return it }
                }
            }
        } catch (fu: FsUnavailableException) { throw fu } catch (_: Exception) { }
        return "${d.dataRoot}/vita"
    }

    private val volumeRe = Regex("^/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}/.+")

    /** Only internal storage or an SD volume, and no `.`/`..` segments; anything else (synced input) is rejected. */
    private fun safePrefPath(v: String): String? {
        if (v.contains('\u0000') || v.split('/').any { it == ".." || it == "." }) return null
        val c = Paths.canonical(v)
        return if (c.startsWith("/storage/emulated/0/") && c.length > "/storage/emulated/0/".length || volumeRe.matches(c)) rooted(c) else null
    }

    private fun saveRoot(fs: Fs, d: Detected) = "${vitaFs(fs, d)}/ux0/user/00/savedata"
    private fun name(p: String) = p.substringAfterLast('/')

    override fun saveItems(fs: Fs, d: Detected): List<LocalItem> {
        val out = ArrayList<LocalItem>()
        for (e in fs.list(saveRoot(fs, d))) {
            try {
                val n = name(e.path)
                if (!e.isDir || !titleRe.matches(n)) continue
                val files = fs.walk(e.path).filter { !it.isDir }.map { it.path.removePrefix(e.path + "/") }.sorted()
                if (files.isEmpty()) continue
                out += LocalItem(ItemKind.Save, n, "", "", e.path, files)
            } catch (fu: FsUnavailableException) { throw fu } catch (_: Exception) { continue }
        }
        return out.sortedBy { it.gameKey }
    }

    override fun watchDirs(fs: Fs, d: Detected): List<String> = listOf(saveRoot(fs, d))

    override fun installedGames(fs: Fs, d: Detected, settings: DeviceSettings): List<InstalledGame> =
        fs.list("${vitaFs(fs, d)}/ux0/app").filter { it.isDir }.map { name(it.path) }
            .filter { titleRe.matches(it) }.sorted().map { InstalledGame(it) }

    override fun configBundle(fs: Fs, d: Detected): ConfigBundleFiles {
        val out = sortedMapOf<String, String>()
        if (fs.exists("${d.dataRoot}/config.yml")) out["data/config.yml"] = "${d.dataRoot}/config.yml"
        for (e in fs.list("${d.dataRoot}/config")) {
            if (!e.isDir && e.path.endsWith(".xml", true)) out["data/config/${name(e.path)}"] = e.path
        }
        return ConfigBundleFiles(out)
    }

    override fun targetDirFor(fs: Fs, d: Detected, kind: ItemKind, gameKey: String, slot: String, core: String): String {
        Paths.requireSafeSegment(gameKey)
        if (kind != ItemKind.Save) throw ModuleException("Vita3K has no synced item kind '$kind'")
        if (!titleRe.matches(gameKey)) throw ModuleException("invalid Vita3K title id: '$gameKey'")
        val root = saveRoot(fs, d)
        return fs.list(root).firstOrNull { it.isDir && name(it.path).equals(gameKey, true) }?.path ?: "$root/$gameKey"
    }
}
