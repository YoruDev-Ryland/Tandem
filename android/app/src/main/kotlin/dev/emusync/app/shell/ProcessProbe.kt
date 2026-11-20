package dev.emusync.app.shell

import java.util.concurrent.TimeUnit

object ProcessProbe {
    private fun run(vararg cmd: String): String? {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
        reader.start()
        if (!p.waitFor(5, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return null
        }
        reader.join(1000)
        return out.toString()
    }

    fun isProcessAlive(pkg: String): Boolean =
        try { !run("pidof", pkg).isNullOrBlank() } catch (e: Exception) { false }

    fun installedPackages(): Set<String> =
        try {
            run("pm", "list", "packages")?.lineSequence()
                ?.map { it.trim() }?.filter { it.startsWith("package:") }
                ?.map { it.removePrefix("package:") }?.toSet() ?: emptySet()
        } catch (e: Exception) { emptySet() }
}
