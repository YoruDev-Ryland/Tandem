package dev.emusync.app.shell

import java.io.ByteArrayOutputStream
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

    /** pidof exits 1 with empty output when nothing matches; anything else is a probe failure. */
    fun isProcessAlive(pkg: String): Boolean = ProbeParse.parsePidof(run(setOf(0, 1), "pidof", pkg))

    fun installedPackages(): Set<String> = ProbeParse.parsePmList(run(setOf(0), "pm", "list", "packages"))
}
