package dev.tandem.app.shell

import dev.tandem.app.fs.FileEntry
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** All java.io file logic; runs inside the Shizuku service and directly on the JVM in tests. */
object ShellFsCore {
    const val TMP_SUFFIX = ".tandem-tmp"

    fun isTmp(name: String) = name.endsWith(TMP_SUFFIX)

    private fun entry(f: File) = FileEntry(f.path, f.isDirectory, if (f.isDirectory) 0L else f.length(), f.lastModified())

    fun list(dir: String): List<FileEntry> =
        File(dir).listFiles()?.filter { !isTmp(it.name) }?.map(::entry)?.sortedBy { it.path } ?: emptyList()

    /** Offset-based page over the sorted non-recursive listing. */
    fun listPage(dir: String, offset: Int, limit: Int): List<FileEntry> =
        list(dir).drop(offset.coerceAtLeast(0)).take(limit.coerceAtLeast(0))

    fun walk(dir: String): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        fun rec(d: File) {
            val children = d.listFiles() ?: return
            for (c in children) {
                if (c.isDirectory) rec(c) else if (!isTmp(c.name)) out.add(entry(c))
            }
        }
        rec(File(dir))
        out.sortBy { it.path }
        return out
    }

    /** Stable-cursor page: sorted files whose path is strictly greater than [afterPath] ("" = start). */
    fun walkPage(dir: String, afterPath: String, limit: Int): List<FileEntry> =
        walk(dir).filter { it.path > afterPath }.take(limit.coerceAtLeast(0))

    fun stat(path: String): FileEntry? = File(path).takeIf { it.exists() }?.let(::entry)

    fun exists(path: String): Boolean = File(path).exists()

    /**
     * Writes to `<path>.tandem-tmp`, fsyncs, calls [beforeCommit] (may throw to abort), then atomically
     * renames over the target. On any failure the tmp is removed and the target is left untouched.
     */
    fun writeAtomic(path: String, data: InputStream, beforeCommit: () -> Unit = {}) {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File(path + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                data.copyTo(out)
                out.flush()
                out.fd.sync()
            }
            beforeCommit()
            Files.move(tmp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun delete(path: String): Boolean {
        val f = File(path)
        return if (f.isDirectory) f.deleteRecursively() else f.delete()
    }

    fun copy(src: String, dst: String) {
        File(src).inputStream().use { writeAtomic(dst, it) }
    }
}
