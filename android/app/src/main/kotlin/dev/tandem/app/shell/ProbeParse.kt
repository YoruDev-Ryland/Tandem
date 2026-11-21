package dev.tandem.app.shell

/** Pure parsing of probe command output. */
object ProbeParse {
    fun parsePmList(output: String): Set<String> =
        output.lineSequence().map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }.toSet()

    /** isAnyProcessAlive's binder reply: 0 is what an empty reply (an old service without the call) reads as. */
    const val REPLY_ALIVE = 1
    const val REPLY_NOT_ALIVE = 2

    fun encodeAlive(alive: Boolean) = if (alive) REPLY_ALIVE else REPLY_NOT_ALIVE

    /** True/false for a valid reply; null (a probe failure: fail closed) for anything else, including 0. */
    fun decodeAlive(reply: Int): Boolean? = when (reply) {
        REPLY_ALIVE -> true
        REPLY_NOT_ALIVE -> false
        else -> null
    }

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
