package dev.tandem.app.shell

import java.io.FileNotFoundException
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class ErrCodecTest {
    @Test fun roundtrip() {
        assertEquals(2 to "a:b", ErrCodec.decode(ErrCodec.encode(2, "a:b")))
        assertNull(ErrCodec.decode(UnsupportedOperationException("other")))
    }

    @Test fun wrap_maps_every_exception_to_a_coded_one() {
        assertEquals(ErrCodec.NOT_FOUND, ErrCodec.decode(ErrCodec.wrap(FileNotFoundException("x")))!!.first)
        assertEquals(ErrCodec.NOT_FOUND, ErrCodec.decode(ErrCodec.wrap(java.nio.file.NoSuchFileException("x")))!!.first)
        assertEquals(ErrCodec.PROBE, ErrCodec.decode(ErrCodec.wrap(ProbeException("p")))!!.first)
        assertEquals(ErrCodec.IO, ErrCodec.decode(ErrCodec.wrap(IOException("io")))!!.first)
        val sec = ErrCodec.decode(ErrCodec.wrap(SecurityException("denied")))!!
        assertEquals(ErrCodec.IO, sec.first)
        assertTrue(sec.second.contains("SecurityException"))
        assertEquals(ErrCodec.IO, ErrCodec.decode(ErrCodec.wrap(IllegalArgumentException("bad")))!!.first)
        val coded = ErrCodec.encode(ErrCodec.PROBE, "kept")
        assertSame(coded, ErrCodec.wrap(coded))
    }
}
