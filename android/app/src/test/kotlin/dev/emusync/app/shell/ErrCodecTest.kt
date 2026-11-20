package dev.emusync.app.shell

import org.junit.Assert.*
import org.junit.Test

class ErrCodecTest {
    @Test fun roundtrip() {
        assertEquals(2 to "a:b", ErrCodec.decode(ErrCodec.encode(2, "a:b")))
        assertNull(ErrCodec.decode(UnsupportedOperationException("other")))
    }
}
