package dev.tandem.app.modules.retroarch

/** Parsed retroarch.cfg: `key = "value"` lines. "default" and empty values read as missing. */
class RaCfg private constructor(private val map: Map<String, String>) {
    operator fun get(key: String): String? = map[key]
    fun bool(key: String): Boolean = map[key]?.equals("true", true) == true

    /** Directory value as an absolute path; `:`-prefixed values are relative to [root]. Null if default/missing. */
    fun path(key: String, root: String): String? {
        val v = map[key] ?: return null
        if (!v.startsWith(":")) return v
        val rel = v.substring(1).trimStart('/', '\\').replace('\\', '/')
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    companion object {
        val EMPTY = RaCfg(emptyMap())

        fun parse(text: String): RaCfg {
            val out = HashMap<String, String>()
            for (raw in text.lineSequence()) {
                val l = raw.trim()
                if (l.isEmpty() || l.startsWith("#")) continue
                val i = l.indexOf('=')
                if (i <= 0) continue
                val k = l.substring(0, i).trim()
                var v = l.substring(i + 1).trim()
                if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length - 1)
                if (v.isEmpty() || v == "default") out.remove(k) else out[k] = v
            }
            return RaCfg(out)
        }
    }
}
