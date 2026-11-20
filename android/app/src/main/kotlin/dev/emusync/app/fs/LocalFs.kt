package dev.emusync.app.fs

import dev.emusync.app.shell.ShellFsCore
import java.io.File
import java.io.InputStream

/** Fs over ShellFsCore directly, for JVM tests. Process/package probes are settable fakes. */
class LocalFs : Fs {
    var alive: MutableSet<String> = mutableSetOf()
    var packages: MutableSet<String> = mutableSetOf()

    override fun list(dir: String) = ShellFsCore.list(dir)
    override fun walk(dir: String) = ShellFsCore.walk(dir)
    override fun stat(path: String) = ShellFsCore.stat(path)
    override fun exists(path: String) = ShellFsCore.exists(path)
    override fun openRead(path: String): InputStream = File(path).inputStream()
    override fun writeAtomic(path: String, data: InputStream) = ShellFsCore.writeAtomic(path, data)
    override fun delete(path: String) = ShellFsCore.delete(path)
    override fun copy(src: String, dst: String) = ShellFsCore.copy(src, dst)
    override fun isProcessAlive(pkg: String) = pkg in alive
    override fun installedPackages(): Set<String> = packages.toSet()
}
