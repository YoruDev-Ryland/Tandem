package dev.emusync.app.modules.ppsspp

import dev.emusync.app.fs.LocalFs
import dev.emusync.app.model.ItemKind
import dev.emusync.app.modules.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PpssppModuleTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fs = LocalFs().apply { packages.add("org.ppsspp.ppsspp") }
    private val none = DeviceSettings(emptyList(), emptyMap())
    private fun internal() = File(tmp.root, "internal").path
    private fun storage() = File(tmp.root, "storage").path
    private fun m() = PpssppModule(internal(), storage())
    private fun put(path: String) { File(path).also { it.parentFile.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3)) }
    private fun ms() = "${internal()}/PSP-Stick"

    @Test fun ppsspp_memstick_search_and_save_slots() {
        assertNull(m().detect(LocalFs(), none))
        put("${ms()}/PSP/SYSTEM/ppsspp.ini")
        put("${ms()}/PSP/SAVEDATA/ULUS10272DATA00/PARAM.SFO")
        put("${ms()}/PSP/SAVEDATA/ULUS10272DATA00/DATA.BIN")
        put("${ms()}/PSP/SAVEDATA/NPJH50001/PARAM.SFO")
        put("${ms()}/PSP/SAVEDATA/short/PARAM.SFO")
        File("${ms()}/PSP/SAVEDATA/ULES00000EMPTY").mkdirs()
        val d = m().detect(fs, none)!!
        assertEquals(ms(), d.dataRoot)
        assertEquals(ms(), d.extraRoots["memstick"])
        val s = m().saveItems(fs, d).filter { it.kind == ItemKind.Save }
        assertEquals(listOf("NPJH50001" to "", "ULUS10272" to "DATA00"), s.map { it.gameKey to it.slot })
        val u = s[1]
        assertEquals("${ms()}/PSP/SAVEDATA/ULUS10272DATA00", u.baseDir)
        assertEquals(listOf("DATA.BIN", "PARAM.SFO"), u.files)
        assertEquals(u.baseDir, m().targetDirFor(fs, d, ItemKind.Save, "ULUS10272", "DATA00", ""))
        assertEquals("${ms()}/PSP/SAVEDATA/ULUS99999X", m().targetDirFor(fs, d, ItemKind.Save, "ULUS99999", "X", ""))
        assertThrows(ModuleException::class.java) { m().targetDirFor(fs, d, ItemKind.Save, "ULUS10272", "../x", "") }
        assertThrows(ModuleException::class.java) { m().targetDirFor(fs, d, ItemKind.Save, "bad", "", "") }
    }

    @Test fun memstick_on_sd_fallback_and_override() {
        put("${storage()}/EFF6-FC92/PPSSPP/PSP/SYSTEM/ppsspp.ini")
        assertEquals("${storage()}/EFF6-FC92/PPSSPP", m().detect(fs, none)!!.dataRoot)
        val o = m().detect(fs, DeviceSettings(emptyList(), mapOf("ppsspp.memstick" to "/sdcard/Mine")))!!
        assertEquals("${internal()}/Mine", o.dataRoot)
        val f = File(tmp.root, "fresh"); f.mkdirs()
        val empty = PpssppModule(File(f, "int").path, File(f, "st").path).detect(fs, none)!!
        assertEquals("${File(f, "int").path}/Android/data/org.ppsspp.ppsspp/files", empty.dataRoot)
    }

    @Test fun states_and_installed() {
        put("${ms()}/PSP/SYSTEM/ppsspp.ini")
        put("${ms()}/PSP/PPSSPP_STATE/ULUS10272_1.02_3.ppst")
        put("${ms()}/PSP/PPSSPP_STATE/ULUS10272_1.02_3.jpg")
        put("${ms()}/PSP/SAVEDATA/NPJH50001/PARAM.SFO")
        put("${internal()}/Roms/psp/God of War - ULES01234.iso")
        put("${internal()}/Roms/psp/Homebrew.iso")
        val settings = DeviceSettings(listOf("/sdcard/Roms/psp"), emptyMap())
        val d = m().detect(fs, settings)!!
        val st = m().saveItems(fs, d).single { it.kind == ItemKind.State }
        assertEquals("ULUS10272", st.gameKey); assertEquals("3", st.slot); assertEquals("", st.core)
        assertEquals(listOf("ULUS10272_1.02_3.ppst"), st.files)
        assertEquals("${ms()}/PSP/PPSSPP_STATE", m().targetDirFor(fs, d, ItemKind.State, "ULUS10272", "3", ""))
        assertEquals(listOf("NPJH50001", "ULES01234"), m().installedGames(fs, d, settings).map { it.gameKey })
    }

    @Test fun config_bundle() {
        put("${ms()}/PSP/SYSTEM/ppsspp.ini")
        put("${ms()}/PSP/SYSTEM/controls.ini")
        put("${ms()}/PSP/SYSTEM/ULUS10272_ppsspp.ini")
        put("${ms()}/PSP/SYSTEM/other.txt")
        val b = m().configBundle(fs, m().detect(fs, none)!!).files
        assertEquals(setOf("memstick/PSP/SYSTEM/ppsspp.ini", "memstick/PSP/SYSTEM/controls.ini", "memstick/PSP/SYSTEM/ULUS10272_ppsspp.ini"), b.keys)
    }
}
