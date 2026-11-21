package dev.tandem.app.modules

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Builds a PARAM.SFO the way Sony's tools lay it out: header, index, keys (NUL-terminated, 4-byte aligned), data
 * (each value padded to [pad] bytes). Strings are UTF-8 with a NUL (format 0x0204); Ints are int32 (0x0404).
 */
internal fun sfoBytes(values: Map<String, Any>, pad: Int = 128): ByteArray {
    val keys = ByteArrayOutputStream()
    val data = ByteArrayOutputStream()
    val index = ByteBuffer.allocate(values.size * 16).order(ByteOrder.LITTLE_ENDIAN)
    for ((k, v) in values) {
        val keyOff = keys.size()
        keys.write(k.toByteArray(Charsets.UTF_8)); keys.write(0)
        val dataOff = data.size()
        val (fmt, raw, max) = when (v) {
            is Int -> Triple(0x0404, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array(), 4)
            else -> {
                val s = v.toString().toByteArray(Charsets.UTF_8) + 0
                Triple(0x0204, s, maxOf(pad, s.size))
            }
        }
        data.write(raw); repeat(max - raw.size) { data.write(0) }
        index.putShort(keyOff.toShort()).putShort(fmt.toShort()).putInt(raw.size).putInt(max).putInt(dataOff)
    }
    while (keys.size() % 4 != 0) keys.write(0)
    val keyStart = 20 + values.size * 16
    val dataStart = keyStart + keys.size()
    val head = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        .put(byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte()))
        .putInt(0x0101).putInt(keyStart).putInt(dataStart).putInt(values.size)
    return head.array() + index.array() + keys.toByteArray() + data.toByteArray()
}

class SfoTest {
    @Test fun readsTheUtf8TitleAndTheOtherValues() {
        val bytes = sfoBytes(linkedMapOf("CATEGORY" to "MS", "PARENTAL_LEVEL" to 5, "SAVEDATA_TITLE" to "Chapter 3", "TITLE" to "Persona 3 Portable"))
        val v = Sfo.parse(bytes)!!
        assertEquals("MS", v["CATEGORY"])
        assertEquals(5, v["PARENTAL_LEVEL"])
        assertEquals("Persona 3 Portable", Sfo.title(bytes))
    }

    @Test fun titlesKeepTheirUnicodeAndLoseTheirLineBreaks() {
        assertEquals("ファイナルファンタジー 零式", Sfo.title(sfoBytes(mapOf("TITLE" to "ファイナルファンタジー\n零式"))))
        assertEquals("Pokémon", Sfo.title(sfoBytes(mapOf("TITLE" to "Pokémon"), pad = 8)))
    }

    @Test fun anythingElseIsNotAnSfo() {
        assertNull(Sfo.parse(ByteArray(10)))
        assertNull(Sfo.parse("not a param file at all, really".toByteArray()))
        val good = sfoBytes(mapOf("TITLE" to "Game"))
        assertNull("truncated in the index", Sfo.parse(good.copyOf(30)))
        assertNull("truncated in the data", Sfo.parse(sfoBytes(mapOf("TITLE" to "Game"), pad = 5).let { it.copyOf(it.size - 3) }))
        val badCount = good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 100_000) }
        assertNull(Sfo.parse(badCount))
        assertNull("no TITLE", Sfo.title(sfoBytes(mapOf("CATEGORY" to "MS"))))
        assertNull("an empty TITLE", Sfo.title(sfoBytes(mapOf("TITLE" to "  "))))
    }

    @Test fun anOversizedStreamIsNotRead() {
        assertNull(Sfo.title(ByteArray(Sfo.MAX_BYTES + 10).inputStream()))
        assertEquals("Game", Sfo.title(sfoBytes(mapOf("TITLE" to "Game")).inputStream()))
    }
}
