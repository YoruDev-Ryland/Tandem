package dev.emusync.app.shell

import android.content.Context
import android.os.ParcelFileDescriptor
import dev.emusync.app.fs.FileEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

/** Runs in the Shizuku UserService process (uid 2000). */
class ShellService() : IShellService.Stub() {
    @Suppress("unused") constructor(context: Context) : this()

    private val json = Json
    private var watcher: Watcher? = null

    override fun destroy() {
        watcher?.stop()
        exitProcess(0)
    }

    override fun listJson(dir: String): String = json.encodeToString(ShellFsCore.list(dir))

    override fun walkJson(dir: String, offset: Int, limit: Int): String {
        val all = ShellFsCore.walk(dir)
        val from = offset.coerceIn(0, all.size)
        val to = (from.toLong() + limit.coerceAtLeast(0)).coerceAtMost(all.size.toLong()).toInt()
        return json.encodeToString(all.subList(from, to))
    }

    override fun statJson(path: String): String =
        ShellFsCore.stat(path)?.let { json.encodeToString<FileEntry>(it) } ?: ""

    override fun openRead(path: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun writeAtomic(path: String, src: ParcelFileDescriptor) {
        ParcelFileDescriptor.AutoCloseInputStream(src).use { ShellFsCore.writeAtomic(path, it) }
    }

    override fun delete(path: String): Boolean = ShellFsCore.delete(path)

    override fun copy(src: String, dst: String) = ShellFsCore.copy(src, dst)

    override fun isProcessAlive(pkg: String): Boolean = ProcessProbe.isProcessAlive(pkg)

    override fun installedPackagesCsv(): String = ProcessProbe.installedPackages().joinToString(",")

    @Synchronized
    override fun watch(dirs: Array<String>, l: IWatchListener) {
        watcher?.stop()
        val w = Watcher { dir, name -> runCatching { l.onChanged(dir, name) } }
        dirs.forEach { w.watchTree(it) }
        watcher = w
    }

    @Synchronized
    override fun unwatchAll() {
        watcher?.stop()
        watcher = null
    }
}
