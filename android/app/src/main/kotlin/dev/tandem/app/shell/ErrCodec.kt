package dev.tandem.app.shell

/**
 * ServiceSpecificException is not in the public SDK, and Parcel.writeException only carries a fixed set of
 * runtime exceptions across binder. UnsupportedOperationException is one of them, so the error code rides in
 * the message as "tandem:<code>:<msg>".
 */
object ErrCodec {
    const val IO = 1
    const val NOT_FOUND = 2
    const val PROBE = 3
    private const val PREFIX = "tandem:"

    fun encode(code: Int, msg: String?) = UnsupportedOperationException("$PREFIX$code:${msg ?: ""}")

    /**
     * Maps anything the shell service throws to a coded exception, so only codes cross binder: missing files are
     * NOT_FOUND, probe failures PROBE, and every other exception (IO, SecurityException, any other runtime
     * exception) IO, named in the message. An already coded exception is returned as is.
     */
    fun wrap(e: Exception): UnsupportedOperationException = when {
        e is UnsupportedOperationException && decode(e) != null -> e
        e is java.io.FileNotFoundException || e is java.nio.file.NoSuchFileException -> encode(NOT_FOUND, e.message)
        e is ProbeException -> encode(PROBE, e.message)
        e is java.io.IOException -> encode(IO, e.message)
        else -> encode(IO, "${e.javaClass.simpleName}: ${e.message}")
    }

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
