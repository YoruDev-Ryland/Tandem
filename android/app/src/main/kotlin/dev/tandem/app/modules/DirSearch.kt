package dev.tandem.app.modules

import dev.tandem.app.fs.FsUnavailableException
import dev.tandem.app.fs.Fs

object DirSearch {
    private val skipNames = setOf("Android", "DCIM")

    /**
     * Known locations first (first match wins), then a bounded breadth-first walk under [walkStarts]
     * (up to [maxDepth] levels, skipping Android/, DCIM and dot-dirs). Among walk matches the highest [rank] wins
     * (ties: shallowest, then first found).
     */
    fun locate(fs: Fs, known: List<String>, walkStarts: List<String>, maxDepth: Int,
               rank: (String) -> Long = { 0L }, pred: (String) -> Boolean): String? {
        fun ok(dir: String) = try { pred(dir) } catch (fu: FsUnavailableException) { throw fu } catch (_: Exception) { false }
        for (k in known) if (ok(k.trimEnd('/'))) return k.trimEnd('/')
        var best: Pair<Long, String>? = null
        val seen = HashSet<String>()
        for (start in walkStarts) {
            var level = listOf(start.trimEnd('/'))
            for (depth in 0..maxDepth) {
                for (dir in level) {
                    if (!seen.add(dir) || !ok(dir)) continue
                    val r = try { rank(dir) } catch (fu: FsUnavailableException) { throw fu } catch (_: Exception) { 0L }
                    if (best == null || r > best.first) best = r to dir
                }
                if (depth == maxDepth) break
                level = level.flatMap { dir ->
                    try {
                        fs.list(dir).filter { it.isDir }.map { it.path }
                            .filter { val n = it.substringAfterLast('/'); n !in skipNames && !n.startsWith(".") }
                    } catch (fu: FsUnavailableException) { throw fu } catch (_: Exception) { emptyList() }
                }
                if (level.isEmpty()) break
            }
        }
        return best?.second
    }

    private val sdRe = Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$")

    /** `<storageRoot>/XXXX-XXXX` volumes. */
    fun sdVolumes(fs: Fs, storageRoot: String): List<String> =
        try { fs.list(storageRoot).filter { it.isDir && sdRe.matches(it.path.substringAfterLast('/')) }.map { it.path } } catch (fu: FsUnavailableException) { throw fu } catch (_: Exception) { emptyList() }
}
