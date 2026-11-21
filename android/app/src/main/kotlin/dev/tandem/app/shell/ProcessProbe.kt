package dev.tandem.app.shell

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ProbeException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

/** Fails closed: any timeout, missing binary or bad exit code throws ProbeException. */
object ProcessProbe {
    private fun run(okExit: Set<Int>, vararg cmd: String): String {
        val p = try {
            ProcessBuilder(*cmd).redirectError(ProcessBuilder.Redirect.to(java.io.File("/dev/null"))).start()
        } catch (e: Exception) {
            throw ProbeException("cannot start ${cmd[0]}: ${e.message}", e)
        }
        val buf = ByteArrayOutputStream()
        val reader = Thread { runCatching { p.inputStream.copyTo(buf) } }
        try {
            p.outputStream.close()
            reader.start()
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                p.waitFor()
                throw ProbeException("${cmd[0]} timed out")
            }
            reader.join(2000)
            if (reader.isAlive) throw ProbeException("${cmd[0]} output read timed out")
            val code = p.exitValue()
            if (code !in okExit) throw ProbeException("${cmd[0]} exited $code")
            return synchronized(buf) { buf.toString(Charsets.UTF_8) }
        } finally {
            runCatching { p.inputStream.close() }
            runCatching { p.errorStream.close() }
            runCatching { p.outputStream.close() }
            if (p.isAlive) { p.destroyForcibly(); runCatching { p.waitFor() } }
        }
    }

    /**
     * The process names (first `cmdline` argument) of every process visible under [procRoot]. Fails closed with
     * [ProbeException] when /proc can't be listed, when pid 1 isn't visible (hidepid would hide other apps'
     * processes, so an empty answer couldn't be trusted), or when a still-existing process's cmdline can't be read.
     * A process that exits mid-scan is skipped.
     */
    fun processNames(procRoot: File = File("/proc")): List<String> {
        val dirs = try { procRoot.listFiles() } catch (e: SecurityException) { null }
            ?: throw ProbeException("cannot list $procRoot")
        val pids = dirs.filter { d -> d.name.isNotEmpty() && d.name.all { it in '0'..'9' } }
        if (pids.none { it.name == "1" }) throw ProbeException("pid 1 not visible in $procRoot; process list incomplete")
        val out = ArrayList<String>(pids.size)
        for (d in pids) {
            val bytes = try {
                File(d, "cmdline").readBytes()
            } catch (e: Exception) {
                if (e !is IOException && e !is SecurityException) throw e
                if (d.exists()) throw ProbeException("cannot read ${d.path}/cmdline: ${e.message}", e)
                continue   // exited during the scan
            }
            ProbeParse.processName(bytes).takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }

    /** True when a process of any of [pkgs] (or a `<pkg>:<sub>` process of one) is alive. Fails closed. */
    fun isAnyProcessAlive(pkgs: Collection<String>, procRoot: File = File("/proc")): Boolean =
        ProbeParse.anyRunning(processNames(procRoot), pkgs)

    fun installedPackages(): Set<String> = ProbeParse.parsePmList(run(setOf(0), "pm", "list", "packages"))
}
