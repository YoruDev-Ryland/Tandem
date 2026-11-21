package dev.tandem.app.modules.ppsspp

import dev.tandem.app.fs.LocalFs
import dev.tandem.app.model.ItemKind
import dev.tandem.app.modules.*
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
    private var clock = 0L
    private val inst by lazy { PpssppModule(internal(), storage()) { clock } }
    private fun m() = inst
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

    @Test fun saves_states_and_installs_carry_the_param_sfo_title() {
        put("${ms()}/PSP/SYSTEM/ppsspp.ini")
        File("${ms()}/PSP/SAVEDATA/ULUS10400DATA00/PARAM.SFO").also { it.parentFile.mkdirs() }
            .writeBytes(sfoBytes(linkedMapOf("CATEGORY" to "MS", "SAVEDATA_TITLE" to "Slot 1", "TITLE" to "Crisis Core: Final Fantasy VII")))
        put("${ms()}/PSP/SAVEDATA/ULUS10400DATA00/DATA.BIN")
        put("${ms()}/PSP/SAVEDATA/ULES00151/PARAM.SFO")   // not a real PARAM.SFO: no name, not an error
        put("${ms()}/PSP/PPSSPP_STATE/ULUS10400_1.00_0.ppst")
        val d = m().detect(fs, none)!!
        val items = m().saveItems(fs, d).associate { (it.kind to it.gameKey) to it.displayName }
        assertEquals("Crisis Core: Final Fantasy VII", items[ItemKind.Save to "ULUS10400"])
        assertEquals("Crisis Core: Final Fantasy VII", items[ItemKind.State to "ULUS10400"])
        assertNull(items[ItemKind.Save to "ULES00151"])
        val games = m().installedGames(fs, d, none).associate { it.gameKey to it.displayName }
        assertEquals("Crisis Core: Final Fantasy VII", games["ULUS10400"])
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

    private fun mk(root: String, mtime: Long) {
        put("$root/PSP/SYSTEM/ppsspp.ini"); File("$root/PSP/SAVEDATA").mkdirs(); File("$root/PSP/SAVEDATA").setLastModified(mtime)
    }

    @Test fun known_location_beats_walk_decoy() {
        mk("${internal()}/Backups/psp", 9_000_000)
        mk(internal(), 1_000_000)
        assertEquals(internal(), m().detect(fs, none)!!.dataRoot)
    }

    @Test fun most_recent_savedata_wins_among_walk_matches() {
        mk("${internal()}/A/old", 1_000_000)
        mk("${internal()}/B/new", 5_000_000)
        mk("${internal()}/Android/data/other/files", 9_000_000)
        assertEquals("${internal()}/B/new", m().detect(fs, none)!!.dataRoot)
    }
}
