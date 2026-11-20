package dev.emusync.app.shell

import android.os.FileObserver
import android.util.Log
import java.io.File

/** Recursive directory watcher. Holds strong refs to observers (otherwise they get GC'd). */
class Watcher(private val onEvent: (dir: String, name: String) -> Unit) {
    private val observers = HashMap<String, FileObserver>()
    private var capLogged = false

    companion object {
        const val MAX_OBSERVERS = 4000
        const val MASK = FileObserver.MODIFY or FileObserver.CLOSE_WRITE or FileObserver.CREATE or
            FileObserver.MOVED_TO or FileObserver.DELETE or FileObserver.MOVED_FROM
        private const val TAG = "EmuSyncWatcher"
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
        if (observers.containsKey(path)) return
        if (observers.size >= MAX_OBSERVERS) {
            if (!capLogged) { capLogged = true; Log.w(TAG, "observer cap $MAX_OBSERVERS reached; not adding more") }
            return
        }
        val obs = object : FileObserver(dir, MASK) {
            override fun onEvent(event: Int, name: String?) {
                if (name == null) return
                if (event and CREATE != 0 && File(path, name).isDirectory) watchTree(File(path, name).path)
                onEvent(path, name)
            }
        }
        observers[path] = obs
        obs.startWatching()
    }

    @Synchronized
    fun stop() {
        observers.values.forEach { it.stopWatching() }
        observers.clear()
        capLogged = false
    }
}
