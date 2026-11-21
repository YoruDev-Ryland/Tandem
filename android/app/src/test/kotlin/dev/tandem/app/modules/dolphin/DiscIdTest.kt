package dev.tandem.app.modules.dolphin

import dev.tandem.app.fs.FsUnavailableException
import dev.tandem.app.fs.LocalFs
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiscIdTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fs = LocalFs()

    private fun file(name: String, offset: Int, id: String): String {
        val b = ByteArray(offset + 16); id.toByteArray().copyInto(b, offset)
        return File(tmp.root, name).also { it.writeBytes(b) }.path
    }

    @Test fun disc_id_iso_ciso_wbfs() {
        assertEquals("GM8E01", DiscId.read(fs, file("a.iso", 0, "GM8E01")))
        assertEquals("GM8E01", DiscId.read(fs, file("b.gcm", 0, "GM8E01")))
        assertEquals("G2ME01", DiscId.read(fs, file("c.ciso", 0x8000, "G2ME01")))
        assertEquals("RMCE01", DiscId.read(fs, file("d.wbfs", 0x200, "RMCE01")))
    }

    @Test fun invalid_id_is_null() {
        assertNull(DiscId.read(fs, file("e.iso", 0, "gm8e01")))
        assertNull(DiscId.read(fs, File(tmp.root, "short.iso").also { it.writeBytes(byteArrayOf(1)) }.path))
        assertNull(DiscId.read(fs, File(tmp.root, "none.iso").path))
    }

    @Test fun rvz_wia_use_filename_heuristic() {
        val p = File(tmp.root, "Metroid Prime (GM8E01).rvz").also { it.writeBytes(ByteArray(8)) }.path
        assertEquals("GM8E01", DiscId.read(fs, p))
        val q = File(tmp.root, "Metroid Prime.wia").also { it.writeBytes(ByteArray(8)) }.path
        assertNull(DiscId.read(fs, q))
    }

    @Test fun unexpected_read_error_is_null_but_shell_loss_propagates() {
        val p = file("a.iso", 0, "GM8E01")
        val odd = object : LocalFs() { override fun openRead(path: String) = throw IllegalStateException("odd") }
        assertNull(DiscId.read(odd, p))
        val dead = object : LocalFs() { override fun openRead(path: String) = throw FsUnavailableException("dead") }
        try { DiscId.read(dead, p); fail("expected FsUnavailableException") } catch (e: FsUnavailableException) {}
    }
}
