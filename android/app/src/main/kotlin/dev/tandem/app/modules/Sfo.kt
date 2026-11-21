package dev.tandem.app.modules

import dev.tandem.app.fs.Fs
import dev.tandem.app.fs.FsUnavailableException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A Sony PARAM.SFO (PSP, PS Vita): a 20-byte header (magic "\0PSF", version, key table start, data table start,
 * entry count), then 16-byte index entries (key offset u16, format u16, data length u32, max length u32, data offset
 * u32), the NUL-terminated keys, and the data. Formats: 0x0004 UTF-8 without terminator, 0x0204 UTF-8 with NUL,
 * 0x0404 int32. Little-endian throughout.
 */
object Sfo {
    /** PARAM.SFO files are a few KB; anything far larger isn't one. */
    const val MAX_BYTES = 64 * 1024

    private const val FMT_UTF8_S = 0x0004
    private const val FMT_UTF8 = 0x0204
    private const val FMT_INT = 0x0404

    /** The string and integer values of [bytes], or null when it isn't a well-formed PARAM.SFO. */
    fun parse(bytes: ByteArray): Map<String, Any>? {
        if (bytes.size < 20) return null
        if (bytes[0] != 0.toByte() || bytes[1] != 'P'.code.toByte() || bytes[2] != 'S'.code.toByte() || bytes[3] != 'F'.code.toByte()) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val keyStart = b.getInt(8).toLong() and 0xFFFFFFFFL
        val dataStart = b.getInt(12).toLong() and 0xFFFFFFFFL
        val count = b.getInt(16).toLong() and 0xFFFFFFFFL
        if (count > 4096 || 20 + count * 16 > bytes.size || keyStart > bytes.size || dataStart > bytes.size) return null
        val out = LinkedHashMap<String, Any>()
        for (i in 0 until count.toInt()) {
            val e = 20 + i * 16
            val keyOff = (b.getShort(e).toInt() and 0xFFFF).toLong()
            val fmt = b.getShort(e + 2).toInt() and 0xFFFF
            val len = b.getInt(e + 4).toLong() and 0xFFFFFFFFL
            val dataOff = b.getInt(e + 12).toLong() and 0xFFFFFFFFL
            val k = keyStart + keyOff
            if (k >= bytes.size) return null
            var kEnd = k.toInt()
            while (kEnd < bytes.size && bytes[kEnd] != 0.toByte()) kEnd++
            val key = String(bytes, k.toInt(), kEnd - k.toInt(), Charsets.UTF_8)
            val d = dataStart + dataOff
            if (d + len > bytes.size) return null
            when (fmt) {
                FMT_UTF8, FMT_UTF8_S -> {
                    var n = len.toInt()
                    // Strings are NUL-padded (to their max length, or with a terminator): stop at the first NUL.
                    val start = d.toInt()
                    for (j in 0 until n) if (bytes[start + j] == 0.toByte()) { n = j; break }
                    out[key] = String(bytes, start, n, Charsets.UTF_8)
                }
                FMT_INT -> if (len >= 4) out[key] = b.getInt(d.toInt())
            }
        }
        return out
    }

    /** The TITLE of [bytes], trimmed, or null when there is none (or it isn't a PARAM.SFO). */
    fun title(bytes: ByteArray): String? =
        (parse(bytes)?.get("TITLE") as? String)?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }

    /** Reads at most [MAX_BYTES] of [input] and returns its TITLE. */
    fun title(input: InputStream): String? {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) return null
        return title(bytes)
    }

    /** The TITLE of the PARAM.SFO at [path], or null when it is missing or unreadable. Shell loss still throws. */
    fun titleAt(fs: Fs, path: String): String? = try {
        val st = fs.stat(path)
        if (st == null || st.isDir || st.size > MAX_BYTES) null else fs.openRead(path).use { title(it) }
    } catch (e: FsUnavailableException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
