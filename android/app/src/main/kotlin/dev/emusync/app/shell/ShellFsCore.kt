package dev.emusync.app.shell

import dev.emusync.app.fs.FileEntry
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** All java.io file logic; runs inside the Shizuku service and directly on the JVM in tests. */
object ShellFsCore {
    private fun entry(f: File) = FileEntry(f.path, f.isDirectory, if (f.isDirectory) 0L else f.length(), f.lastModified())

    fun list(dir: String): List<FileEntry> =
        File(dir).listFiles()?.map(::entry)?.sortedBy { it.path } ?: emptyList()

    fun walk(dir: String): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        fun rec(d: File) {
            val children = d.listFiles() ?: return
            for (c in children) if (c.isDirectory) rec(c) else out.add(entry(c))
        }
        rec(File(dir))
        out.sortBy { it.path }
        return out
    }

    fun stat(path: String): FileEntry? = File(path).takeIf { it.exists() }?.let(::entry)

    fun exists(path: String): Boolean = File(path).exists()

    fun writeAtomic(path: String, data: InputStream) {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File(path + ".emusync-tmp")
        try {
            FileOutputStream(tmp).use { out ->
                data.copyTo(out)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                if (!tmp.renameTo(target)) throw java.io.IOException("rename failed: $path")
            }
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
