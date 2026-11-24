package dev.tandem.app.shell

import java.io.InputStream
import java.util.concurrent.TimeUnit

/** `pm install` run by the shell service (uid 2000), the APK streamed to its stdin. */
object ShellInstall {
    private const val TIMEOUT_MINUTES = 5L

    /** Replaces the installed app with the [size]-byte APK on stdin. */
    fun command(size: Long): List<String> = listOf("pm", "install", "-r", "-S", size.toString())

    /** Runs [cmd] with [apk] on its stdin; returns what it printed (stdout and stderr), trimmed. */
    fun run(apk: InputStream, cmd: List<String>): String {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        // Read while writing, so a command that talks before stdin ends can't block on a full pipe.
        val reader = Thread { runCatching { p.inputStream.bufferedReader().use { out.append(it.readText()) } } }.apply { start() }
        try {
            p.outputStream.use { apk.copyTo(it) }
        } catch (e: java.io.IOException) {
            // The command stopped reading (it failed early): its output says why.
        }
        if (!p.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            p.destroyForcibly()
            return "Failure [pm install took longer than $TIMEOUT_MINUTES minutes]"
        }
        reader.join(10_000)
        return out.toString().trim()
    }
}

/** Null when `pm install` printed Success; else its reason ("INSTALL_FAILED_…: …"). */
fun pmFailure(output: String?): String? {
    val text = output?.trim().orEmpty()
    if (text.lines().any { it.trim() == "Success" }) return null
    if (text.isEmpty()) return "pm printed nothing"
    val failure = Regex("""Failure \[(.*)]""", RegexOption.DOT_MATCHES_ALL).find(text)
    return failure?.groupValues?.get(1)?.trim() ?: text.lines().first().trim()
}
