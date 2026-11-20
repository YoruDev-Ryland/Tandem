package dev.emusync.app.shell

/** Pure path bookkeeping for the Watcher: which directories are watched, with a cap. */
class WatchTree(private val cap: Int = 4000) {
    private val paths = HashSet<String>()

    val size: Int get() = paths.size
    fun contains(path: String) = path in paths

    /** True if newly added; false if already present or the cap is reached. */
    fun add(path: String): Boolean = path !in paths && paths.size < cap && paths.add(path)

    val isFull: Boolean get() = paths.size >= cap

    /** Removes [path] and all descendants; returns the removed paths. */
    fun removeSubtree(path: String): List<String> {
        val prefix = path.trimEnd('/') + "/"
        val gone = paths.filter { it == path || it.startsWith(prefix) }
        paths.removeAll(gone.toSet())
        return gone
    }

    fun clear() = paths.clear()
}
