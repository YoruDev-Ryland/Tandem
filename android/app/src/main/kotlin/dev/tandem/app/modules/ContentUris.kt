package dev.tandem.app.modules

import java.net.URLDecoder

object ContentUris {
    private const val PREFIX = "content://com.android.externalstorage.documents/tree/"

    /** SAF external-storage tree URI to a filesystem path, or null if not recognised. [roots] may override "INTERNAL". */
    fun toPath(uri: String, roots: Map<String, String> = emptyMap()): String? {
        val u = uri.trim().trim('"')
        if (!u.startsWith(PREFIX)) return null
        val v = try { URLDecoder.decode(u.removePrefix(PREFIX), "UTF-8") } catch (e: Exception) { return null }
        val colon = v.indexOf(':')
        if (colon <= 0) return null
        val vol = v.substring(0, colon)
        val rel = v.substring(colon + 1).trim('/')
        val base = when {
            vol == "primary" -> roots["INTERNAL"] ?: "/storage/emulated/0"
            Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}").matches(vol) -> "/storage/$vol"
            else -> return null
        }
        return if (rel.isEmpty()) base else "$base/$rel"
    }
}
