package dev.tandem.app.shell

import android.os.FileObserver
import android.util.Log
import java.io.File

/** Recursive directory watcher. Holds strong refs to observers (otherwise they get GC'd). */
class Watcher(private val onEvent: (dir: String, name: String) -> Unit) {
    private val tree = WatchTree(MAX_OBSERVERS)
    private val observers = HashMap<String, FileObserver>()
    private var capLogged = false

    companion object {
        const val MAX_OBSERVERS = 4000
        const val MASK = FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.CREATE or
            FileObserver.MOVED_TO or FileObserver.DELETE or FileObserver.MOVED_FROM
        private const val TAG = "TandemWatcher"
    }

    @Synchronized
    fun watchTree(root: String) {
        val f = File(root)
        if (!f.isDirectory) return
        addObserver(f)
        f.listFiles()?.filter { it.isDirectory }?.forEach { watchTree(it.path) }
    }

    @Synchronized
    private fun addObserver(dir: File) {
        val path = dir.path
        if (tree.contains(path)) return
        if (tree.isFull) {
            if (!capLogged) { capLogged = true; Log.w(TAG, "observer cap $MAX_OBSERVERS reached; not adding more") }
            return
        }
        if (!tree.add(path)) return
        val obs = object : FileObserver(dir, MASK) {
            override fun onEvent(event: Int, name: String?) {
                if (name == null) return
                handle(path, event and FileObserver.ALL_EVENTS, name)
            }
        }
        observers[path] = obs
        obs.startWatching()
    }

    @Synchronized
    private fun handle(dir: String, ev: Int, name: String) {
        val child = File(dir, name)
        if (ev and (FileObserver.CREATE or FileObserver.MOVED_TO) != 0 && child.isDirectory) {
            watchTree(child.path)
            emitExisting(child)
        } else if (ev and (FileObserver.DELETE or FileObserver.MOVED_FROM) != 0 && tree.contains(child.path)) {
            tree.removeSubtree(child.path).forEach { observers.remove(it)?.stopWatching() }
        }
        if (!ShellFsCore.isTmp(name)) onEvent(dir, name)
    }

    /** Emits an event for every file already inside a newly appeared directory. */
    private fun emitExisting(dir: File) {
        dir.listFiles()?.forEach { c ->
            if (c.isDirectory) emitExisting(c)
            else if (!ShellFsCore.isTmp(c.name)) onEvent(c.parent ?: dir.path, c.name)
        }
    }

    @Synchronized
    fun stop() {
        observers.values.forEach { it.stopWatching() }
        observers.clear()
        tree.clear()
        capLogged = false
    }
}
