package dev.emusync.app.fs

import android.os.DeadObjectException
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import dev.emusync.app.shell.ErrCodec
import dev.emusync.app.shell.IShellService
import dev.emusync.app.shell.IWatchListener
import kotlinx.serialization.json.Json
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/** Fs over the Shizuku shell service. Binder failures become FsUnavailableException. */
class RemoteFs(private val service: () -> IShellService) : Fs {
    constructor(service: IShellService) : this({ service })

    private val json = Json
    private val pageSize = 500

    private inline fun <T> call(block: (IShellService) -> T): T {
        val s = try {
            service()
        } catch (e: IllegalStateException) {
            throw FsUnavailableException("shell service unavailable: ${e.message}")
        }
        try {
            return block(s)
        } catch (e: UnsupportedOperationException) {
            val (code, msg) = ErrCodec.decode(e) ?: throw e
            throw when (code) {
                ErrCodec.NOT_FOUND -> FileNotFoundException(msg)
                ErrCodec.PROBE -> FsUnavailableException("probe failed: $msg")
                else -> IOException(msg)
            }
        } catch (e: DeadObjectException) {
            throw FsUnavailableException("shell service died: ${e.message}")
        } catch (e: RemoteException) {
            throw FsUnavailableException("shell service error: ${e.message}")
        }
    }

    override fun list(dir: String): List<FileEntry> = call { s ->
        val out = ArrayList<FileEntry>()
        while (true) {
            val page: List<FileEntry> = json.decodeFromString(s.listJson(dir, out.size, pageSize))
            out.addAll(page)
            if (page.size < pageSize) break
        }
        out
    }

    override fun walk(dir: String): List<FileEntry> = call { s ->
        val out = ArrayList<FileEntry>()
        var after = ""
        while (true) {
            val page: List<FileEntry> = json.decodeFromString(s.walkJson(dir, after, pageSize))
            out.addAll(page)
            if (page.size < pageSize) break
            after = page.last().path
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
        // Reliable pipe: if the source fails mid-stream the write end is closed WITH an error, so the
        // service's checkError() aborts before renaming a truncated tmp over the target.
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        var writeError: Throwable? = null
        // The binder call blocks until the service has consumed everything, so the writer runs concurrently.
        val writer = Thread {
            try {
                val out = ParcelFileDescriptor.AutoCloseOutputStream(writeEnd)
                data.copyTo(out)
                out.close()
            } catch (t: Throwable) {
                writeError = t
                runCatching { writeEnd.closeWithError(t.message ?: "source stream failed") }
            }
        }
        writer.start()
        try {
            s.writeAtomic(path, readEnd)
        } finally {
            readEnd.close()
            writer.join()
        }
        writeError?.let { throw IOException("write stream failed", it) }
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
