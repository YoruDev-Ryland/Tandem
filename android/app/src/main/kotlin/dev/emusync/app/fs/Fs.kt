package dev.emusync.app.fs

import kotlinx.serialization.Serializable

@Serializable
data class FileEntry(val path: String, val isDir: Boolean, val size: Long, val mtimeMillis: Long)

class FsUnavailableException(msg: String) : Exception(msg)

interface Fs {
    fun list(dir: String): List<FileEntry>
    fun walk(dir: String): List<FileEntry>
    fun stat(path: String): FileEntry?
    fun exists(path: String): Boolean
    fun openRead(path: String): java.io.InputStream
    fun writeAtomic(path: String, data: java.io.InputStream)
    fun delete(path: String): Boolean
    fun copy(src: String, dst: String)
    fun isProcessAlive(pkg: String): Boolean
    fun installedPackages(): Set<String>
}
