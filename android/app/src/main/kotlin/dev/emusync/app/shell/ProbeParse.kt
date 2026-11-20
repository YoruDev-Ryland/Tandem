package dev.emusync.app.shell

/** Pure parsing of probe command output. */
object ProbeParse {
    fun parsePidof(output: String): Boolean = output.isNotBlank()

    fun parsePmList(output: String): Set<String> =
        output.lineSequence().map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }.toSet()
}
