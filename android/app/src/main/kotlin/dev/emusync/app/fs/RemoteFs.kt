package dev.emusync.app.fs

import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import dev.emusync.app.shell.IShellService
import dev.emusync.app.shell.IWatchListener
import kotlinx.serialization.json.Json
import java.io.InputStream

/** Fs over the Shizuku shell service. Binder failures become FsUnavailableException. */
class RemoteFs(private val service: () -> IShellService) : Fs {
    constructor(service: IShellService) : this({ service })

    private val json = Json
    private val pageSize = 2000

    private inline fun <T> call(block: (IShellService) -> T): T = try {
        block(service())
    } catch (e: DeadObjectException) {
        throw FsUnavailableException("shell service died: ${e.message}")
    } catch (e: RemoteException) {
        throw FsUnavailableException("shell service error: ${e.message}")
    } catch (e: IllegalStateException) {
        throw FsUnavailableException("shell service unavailable: ${e.message}")
    }

    override fun list(dir: String): List<FileEntry> =
        call { json.decodeFromString(it.listJson(dir)) }

    override fun walk(dir: String): List<FileEntry> = call { s ->
        val out = ArrayList<FileEntry>()
        var offset = 0
        while (true) {
            val page: List<FileEntry> = json.decodeFromString(s.walkJson(dir, offset, pageSize))
            out.addAll(page)
            if (page.size < pageSize) break
            offset += page.size
        }
        out
    }

    override fun stat(path: String): FileEntry? = call { s ->
        s.statJson(path).takeIf { it.isNotEmpty() }?.let { json.decodeFromString<FileEntry>(it) }
    }

    override fun exists(path: String) = stat(path) != null

    override fun openRead(path: String): InputStream = call {
        ParcelFileDescriptor.AutoCloseInputStream(it.openRead(path))
    }

    override fun writeAtomic(path: String, data: InputStream) = call { s ->
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        var writeError: Throwable? = null
        // Writer thread feeds the pipe while the service reads it; the binder call below blocks
        // until the service has consumed everything, so they must run concurrently.
        val writer = Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { data.copyTo(it) }
            } catch (t: Throwable) {
                writeError = t
            }
        }
        writer.start()
        try {
            s.writeAtomic(path, readEnd)
        } finally {
            readEnd.close()
            writer.join()
        }
        writeError?.let { throw java.io.IOException("write stream failed", it) }
        Unit
    }

    override fun delete(path: String) = call { it.delete(path) }
    override fun copy(src: String, dst: String) = call { it.copy(src, dst) }
    override fun isProcessAlive(pkg: String) = call { it.isProcessAlive(pkg) }

    override fun installedPackages(): Set<String> = call {
        it.installedPackagesCsv().split(',').filter(String::isNotBlank).toSet()
    }

    fun watch(dirs: List<String>, onChanged: (dir: String, name: String) -> Unit) = call { s ->
        s.watch(dirs.toTypedArray(), object : IWatchListener.Stub() {
            override fun onChanged(dir: String, name: String) = onChanged(dir, name)
        })
    }

    fun unwatchAll() = call { it.unwatchAll() }
}
