package dev.emusync.app.modules.azahar

import dev.emusync.app.fs.LocalFs
import dev.emusync.app.model.ItemKind
import dev.emusync.app.modules.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AzaharModuleTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fs = LocalFs().apply { packages.add("org.azahar_emu.azahar") }
    private val none = DeviceSettings(emptyList(), emptyMap())
    private fun internal() = File(tmp.root, "internal").path
    private fun m() = AzaharModule(internal())
    private fun user() = "${internal()}/Games/3ds/user"
    private val pair = "sdmc/Nintendo 3DS/00000000000000000000000000000000/00000000000000000000000000000000"
    private fun put(path: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) { File(path).also { it.parentFile.mkdirs() }.writeBytes(bytes) }
    private fun fixtureConfig() {
        val b = javaClass.getResource("/fixtures/azahar/config.ini")!!.readBytes()
        put("${user()}/config/config.ini", b)
    }
    private fun layout() { fixtureConfig(); File("${user()}/sdmc").mkdirs() }
    private fun det() = m().detect(fs, none)!!

    @Test fun azahar_root_detected_by_markers() {
        assertNull(m().detect(LocalFs(), none))
        assertNull(m().detect(fs, none))   // no root yet
        fixtureConfig()
        assertNull(m().detect(fs, none))   // sdmc missing
        File("${user()}/sdmc").mkdirs()
        put("${internal()}/Decoy/config/config.ini")
        val d = det()
        assertEquals(user(), d.dataRoot)
        assertEquals(user(), d.extraRoots["root"])
        // override wins
        put("${internal()}/Other/config/config.ini"); File("${internal()}/Other/sdmc").mkdirs()
        val o = m().detect(fs, DeviceSettings(emptyList(), mapOf("azahar.root" to "/sdcard/Other")))!!
        assertEquals("${internal()}/Other", o.dataRoot)
    }

    @Test fun azahar_save_titleid() {
        layout()
        put("${user()}/$pair/title/00040000/0017e100/data/00000001.sav")
        put("${user()}/$pair/title/00040000/0017e100/data/sub/x.bin")
        put("${user()}/$pair/title/00040000/0017e100/content/0000.app")
        put("${user()}/$pair/title/00040000/0017e200/content/0000.app")
        val items = m().saveItems(fs, det())
        val s = items.single()
        assertEquals(ItemKind.Save, s.kind)
        assertEquals("000400000017E100", s.gameKey)
        assertEquals("${user()}/$pair/title/00040000/0017e100/data", s.baseDir)
        assertEquals(listOf("00000001.sav", "sub/x.bin"), s.files)
        assertEquals(listOf("000400000017E100", "000400000017E200"), m().installedGames(fs, det(), none).map { it.gameKey })
        assertEquals("${user()}/$pair/title/00040000/0017e100/data", m().targetDirFor(fs, det(), ItemKind.Save, "000400000017E100", "", ""))
        assertEquals("${user()}/$pair/title/00040000/0017E300/data", m().targetDirFor(fs, det(), ItemKind.Save, "000400000017E300", "", ""))
        assertThrows(ModuleException::class.java) { m().targetDirFor(fs, det(), ItemKind.Save, "../../x", "", "") }
        assertThrows(ModuleException::class.java) { m().targetDirFor(fs, det(), ItemKind.Save, "0004000000", "", "") }
    }

    @Test fun most_recent_id_pair_used() {
        layout()
        val old = "sdmc/Nintendo 3DS/${"a".repeat(32)}/${"b".repeat(32)}"
        put("${user()}/$old/title/00040000/00000001/data/a.sav")
        File("${user()}/$old").setLastModified(1_000_000)
        put("${user()}/$pair/title/00040000/00000002/data/b.sav")
        assertEquals(listOf("0004000000000002"), m().saveItems(fs, det()).map { it.gameKey })
    }

    @Test fun states_slots() {
        layout()
        put("${user()}/states/000400000017E100.01.cst")
        put("${user()}/states/000400000017E100.movie0000000000000001.02.cst")
        put("${user()}/states/readme.txt")
        val s = m().saveItems(fs, det()).single()
        assertEquals(ItemKind.State, s.kind)
        assertEquals("000400000017E100", s.gameKey)
        assertEquals("01", s.slot)
        assertEquals("${user()}/states", m().targetDirFor(fs, det(), ItemKind.State, "000400000017E100", "01", ""))
        assertThrows(ModuleException::class.java) { m().targetDirFor(fs, det(), ItemKind.State, "000400000017E100", "../1", "") }
    }

    private fun ncch(pid: Long): ByteArray {
        val b = ByteBuffer.allocate(0x200).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0x100, 'N'.code.toByte()); b.put(0x101, 'C'.code.toByte()); b.put(0x102, 'C'.code.toByte()); b.put(0x103, 'H'.code.toByte())
        b.putLong(0x118, pid)
        return b.array()
    }

    @Test fun ncsd_program_id() {
        layout()
        val pid = 0x000400000017E100L
        // NCSD: header, first partition at 0x20 media units (0x4000)
        val ncsd = ByteBuffer.allocate(0x4200).order(ByteOrder.LITTLE_ENDIAN)
        "NCSD".toByteArray().forEachIndexed { i, c -> ncsd.put(0x100 + i, c) }
        ncsd.putInt(0x120, 0x20); ncsd.putInt(0x124, 0x100)
        ncch(pid).copyInto(ncsd.array(), 0x4000)
        put("${internal()}/Games/3ds/Game.3ds", ncsd.array())
        put("${internal()}/Games/3ds/Raw.cxi", ncch(0x0004000000000042L))
        put("${internal()}/Games/3ds/Update.cxi", ncch(0x0004000E00000042L))   // not an application
        put("${internal()}/Games/3ds/Junk.cci", ByteArray(100))
        put("${internal()}/Games/3ds/Some.cia")
        val keys = m().installedGames(fs, det(), none).map { it.gameKey }
        assertEquals(listOf("0004000000000042", "000400000017E100"), keys)
        assertEquals(0x000400000017E100L, AzaharModule.programId(ncsd.array().inputStream()))
        assertNull(AzaharModule.programId(ByteArray(10).inputStream()))
    }

    @Test fun config_bundle() {
        layout()
        put("${user()}/config/custom/000400000017E100.ini")
        put("${user()}/config/custom/notes.txt")
        put("${user()}/sysdata/mcu.dat")
        val b = m().configBundle(fs, det()).files
        assertEquals(setOf("root/config/config.ini", "root/config/custom/000400000017E100.ini"), b.keys)
        assertEquals("${user()}/config/config.ini", b["root/config/config.ini"])
    }
}
