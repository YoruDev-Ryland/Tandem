package dev.emusync.app.fs

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalFsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun roundtrip_write_read_stat_exists() {
        val fs = LocalFs()
        val p = File(tmp.root, "d/f.bin").path
        fs.writeAtomic(p, "abc".byteInputStream())
        assertTrue(fs.exists(p))
        assertEquals("abc", fs.openRead(p).use { it.readBytes().decodeToString() })
        assertEquals(3L, fs.stat(p)!!.size)
        assertNull(fs.stat(File(tmp.root, "none").path))
    }

    @Test fun fake_process_and_packages() {
        val fs = LocalFs()
        assertFalse(fs.isAnyProcessAlive(listOf("a.b")))
        fs.alive.add("a.b"); fs.packages.add("c.d")
        assertTrue(fs.isAnyProcessAlive(listOf("x.y", "a.b")))
        fs.alive.clear(); fs.alive.add("a.b:remote")
        assertTrue(fs.isAnyProcessAlive(listOf("a.b")))
        assertEquals(setOf("c.d"), fs.installedPackages())
    }
}
