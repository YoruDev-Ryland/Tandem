package dev.emusync.app.modules

import dev.emusync.app.fs.Fs

object DirSearch {
    /** Breadth-first: the shallowest directory under [start] (inclusive, up to [maxDepth] levels down) satisfying [pred]. */
    fun find(fs: Fs, start: String, maxDepth: Int, pred: (String) -> Boolean): String? {
        var level = listOf(start.trimEnd('/'))
        for (depth in 0..maxDepth) {
            for (dir in level) if (try { pred(dir) } catch (_: Exception) { false }) return dir
            if (depth == maxDepth) break
            level = level.flatMap { dir ->
                try { fs.list(dir).filter { it.isDir }.map { it.path } } catch (_: Exception) { emptyList() }
            }
            if (level.isEmpty()) break
        }
        return null
    }

    private val sdRe = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")

    /** `<storageRoot>/XXXX-XXXX` volumes. */
    fun sdVolumes(fs: Fs, storageRoot: String): List<String> =
        try { fs.list(storageRoot).filter { it.isDir && sdRe.matches(it.path.substringAfterLast('/')) }.map { it.path } } catch (_: Exception) { emptyList() }
}
