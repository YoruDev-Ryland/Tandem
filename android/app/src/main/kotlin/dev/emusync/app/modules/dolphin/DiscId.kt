package dev.emusync.app.modules.dolphin

import dev.emusync.app.fs.Fs
import java.io.IOException
import java.io.InputStream

object DiscId {
    private val idRe = Regex("^[A-Z0-9]{6}$")
    private val nameRe = Regex("\\(([A-Z0-9]{6})\\)")

    /**
     * Reads the 6-char game ID from a disc image header, or null.
     * TODO: .rvz/.wia carry the disc header inside the WIA header; offset not verified against Dolphin
     * source (WIABlob.h unavailable), so those use the "(ID6)" filename heuristic only.
     */
    fun read(fs: Fs, path: String): String? {
        val name = path.substringAfterLast('/')
        val offset = when (name.substringAfterLast('.', "").lowercase()) {
            "iso", "gcm" -> 0L
            "ciso" -> 0x8000L
            "wbfs" -> 0x200L
            "rvz", "wia" -> return nameRe.find(name)?.groupValues?.get(1)
            else -> return null
        }
        return try {
            fs.openRead(path).use { ins ->
                if (!skipFully(ins, offset)) return null
                val b = ins.readNBytes(8)
                if (b.size < 6) return null
                String(b, 0, 6, Charsets.US_ASCII).takeIf { idRe.matches(it) }
            }
        } catch (e: IOException) { null }
    }

    private fun skipFully(ins: InputStream, n: Long): Boolean {
        var left = n
        while (left > 0) {
            val s = ins.skip(left)
            if (s <= 0) {
                if (ins.read() < 0) return false
                left--
            } else left -= s
        }
        return true
    }
}
