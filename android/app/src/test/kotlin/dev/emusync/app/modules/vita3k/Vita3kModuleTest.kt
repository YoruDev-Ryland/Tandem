package dev.emusync.app.modules.vita3k

import dev.emusync.app.fs.LocalFs
import dev.emusync.app.model.ItemKind
import dev.emusync.app.modules.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Vita3kModuleTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fs = LocalFs().apply { packages.add("org.vita3k.emulator") }
    private val none = DeviceSettings(emptyList(), emptyMap())
    private fun internal() = File(tmp.root, "internal").path
    private fun m() = Vita3kModule(internal())
    private fun root() = "${internal()}/Android/data/org.vita3k.emulator/files"
    private fun put(path: String, text: String = "x") { File(path).also { it.parentFile.mkdirs() }.writeText(text) }

    @Test fun vita3k_savedata_and_apps() {
        assertNull(m().detect(LocalFs(), none))
        val v = "${root()}/vita/ux0"
        put("$v/user/00/savedata/PCSE00001/sce_sys/param.sfo")
        put("$v/user/00/savedata/PCSE00001/data.bin")
        put("$v/user/00/savedata/notatitle/x")
        put("$v/app/PCSE00001/eboot.bin")
        put("$v/app/PCSB00002/eboot.bin")
        val d = m().detect(fs, none)!!
        assertEquals(root(), d.dataRoot)
        val s = m().saveItems(fs, d).single()
        assertEquals("PCSE00001", s.gameKey)
        assertEquals("$v/user/00/savedata/PCSE00001", s.baseDir)
        assertEquals(listOf("data.bin", "sce_sys/param.sfo"), s.files)
        assertEquals(listOf("PCSB00002", "PCSE00001"), m().installedGames(fs, d, none).map { it.gameKey })
        assertEquals(s.baseDir, m().targetDirFor(fs, d, ItemKind.Save, "PCSE00001", "", ""))
        assertEquals("$v/user/00/savedata/PCSE00009", m().targetDirFor(fs, d, ItemKind.Save, "PCSE00009", "", ""))
        assertThrows(ModuleException::class.java) { m().targetDirFor(fs, d, ItemKind.Save, "../x", "", "") }
    }

    @Test fun pref_path_from_config() {
        val alt = "${internal()}/VitaFs"
        put("${root()}/config.yml", "foo: 1\npref-path: \"$alt/\"\nbar: 2\n")
        put("$alt/ux0/user/00/savedata/PCSE00003/a.bin")
        put("${root()}/vita/ux0/user/00/savedata/PCSE00004/a.bin")
        assertEquals(listOf("PCSE00003"), m().saveItems(fs, m().detect(fs, none)!!).map { it.gameKey })
    }

    @Test fun config_bundle() {
        put("${root()}/config.yml")
        put("${root()}/config/user-00.xml")
        put("${root()}/config/readme.txt")
        val b = m().configBundle(fs, m().detect(fs, none)!!).files
        assertEquals(setOf("data/config.yml", "data/config/user-00.xml"), b.keys)
        assertEquals("${root()}/config.yml", b["data/config.yml"])
    }
}
