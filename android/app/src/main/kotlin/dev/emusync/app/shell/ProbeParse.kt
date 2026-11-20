package dev.emusync.app.shell

/** Pure parsing of probe command output. */
object ProbeParse {
    fun parsePmList(output: String): Set<String> =
        output.lineSequence().map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }.toSet()

    /** The process name in a `/proc/<pid>/cmdline` (its first NUL-separated argument), or "" when empty. */
    fun processName(cmdline: ByteArray): String {
        val end = cmdline.indexOf(0).let { if (it < 0) cmdline.size else it }
        return String(cmdline, 0, end, Charsets.UTF_8).trim()
    }

    /**
     * True when any process name is exactly one of [pkgs] or a sub-process `<pkg>:<name>` of one. A prefix
     * without the colon ("com.retroarchx" for "com.retroarch") does not count.
     */
    fun anyRunning(processNames: Iterable<String>, pkgs: Collection<String>): Boolean {
        val wanted = pkgs.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return false
        return processNames.any { n -> n in wanted || (n.indexOf(':') > 0 && n.substringBefore(':') in wanted) }
    }
}
