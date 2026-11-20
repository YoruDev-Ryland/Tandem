package dev.emusync.app.shell

/**
 * ServiceSpecificException is not in the public SDK, and Parcel.writeException only carries a fixed set of
 * runtime exceptions across binder. UnsupportedOperationException is one of them, so the error code rides in
 * the message as "emusync:<code>:<msg>".
 */
object ErrCodec {
    const val IO = 1
    const val NOT_FOUND = 2
    const val PROBE = 3
    private const val PREFIX = "emusync:"

    fun encode(code: Int, msg: String?) = UnsupportedOperationException("$PREFIX$code:${msg ?: ""}")

    /** Returns (code, message) or null if [e] is not one of ours. */
    fun decode(e: Throwable): Pair<Int, String>? {
        val m = e.message ?: return null
        if (!m.startsWith(PREFIX)) return null
        val rest = m.removePrefix(PREFIX)
        val i = rest.indexOf(':')
        val code = rest.substring(0, if (i < 0) rest.length else i).toIntOrNull() ?: return null
        return code to (if (i < 0) "" else rest.substring(i + 1))
    }
}
