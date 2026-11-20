package dev.emusync.app.shell

import android.content.Context
import android.os.ParcelFileDescriptor
import dev.emusync.app.fs.FileEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.NoSuchFileException
import kotlin.system.exitProcess

/** Runs in the Shizuku UserService process (uid 2000). */
class ShellService() : IShellService.Stub() {
    @Suppress("unused") constructor(context: Context) : this()

    private val json = Json
    private var watcher: Watcher? = null

    /** Checked exceptions must not escape onTransact; map them to ErrCodec-coded exceptions. */
    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: UnsupportedOperationException) {
        throw e
    } catch (e: FileNotFoundException) {
        throw ErrCodec.encode(ErrCodec.NOT_FOUND, e.message)
    } catch (e: NoSuchFileException) {
        throw ErrCodec.encode(ErrCodec.NOT_FOUND, e.message)
    } catch (e: ProbeException) {
        throw ErrCodec.encode(ErrCodec.PROBE, e.message)
    } catch (e: IOException) {
        throw ErrCodec.encode(ErrCodec.IO, e.message)
    }

    override fun destroy() {
        watcher?.stop()
        exitProcess(0)
    }

    override fun listJson(dir: String, offset: Int, limit: Int): String =
        guard { json.encodeToString(ShellFsCore.listPage(dir, offset, limit)) }

    override fun walkJson(dir: String, afterPath: String, limit: Int): String =
        guard { json.encodeToString(ShellFsCore.walkPage(dir, afterPath, limit)) }

    override fun statJson(path: String): String =
        guard { ShellFsCore.stat(path)?.let { json.encodeToString<FileEntry>(it) } ?: "" }

    override fun openRead(path: String): ParcelFileDescriptor =
        guard { ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY) }

    override fun writeAtomic(path: String, src: ParcelFileDescriptor) = guard {
        ParcelFileDescriptor.AutoCloseInputStream(src).use { ins ->
            // checkError() throws if the app side closed the pipe with an error (truncated source),
            // which aborts before the rename so the target stays intact.
            ShellFsCore.writeAtomic(path, ins) { src.checkError() }
        }
    }

    override fun delete(path: String): Boolean = guard { ShellFsCore.delete(path) }

    override fun copy(src: String, dst: String) = guard { ShellFsCore.copy(src, dst) }

    override fun isProcessAlive(pkg: String): Boolean = guard { ProcessProbe.isProcessAlive(pkg) }

    override fun installedPackagesCsv(): String = guard { ProcessProbe.installedPackages().joinToString(",") }

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
