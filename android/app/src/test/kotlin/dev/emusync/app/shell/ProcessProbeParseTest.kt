package dev.emusync.app.shell

import org.junit.Assert.*
import org.junit.Test

class ProcessProbeParseTest {
    @Test fun pidof_nonempty_is_alive() {
        assertTrue(ProbeParse.parsePidof("1234 5678\n"))
        assertFalse(ProbeParse.parsePidof(""))
        assertFalse(ProbeParse.parsePidof("\n"))
    }

    @Test fun pm_list_parses_packages() {
        val out = "package:com.a\r\npackage:dev.b.c\nWarning: junk\n\npackage:\n"
        assertEquals(setOf("com.a", "dev.b.c"), ProbeParse.parsePmList(out))
        assertTrue(ProbeParse.parsePmList("").isEmpty())
    }
}
