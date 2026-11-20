package dev.emusync.app.modules.eden

import dev.emusync.app.fs.LocalFs
import dev.emusync.app.model.ItemKind
import dev.emusync.app.modules.*
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EdenModuleTest {
    @get:Rule val tmp = TemporaryFolder()
    private val m = EdenModule()
    private val none = DeviceSettings(emptyList(), emptyMap())
    private val odinProfile = "81C6300ADAB1B1F0B230A03591DF9110"
    private val thorProfile = "23E468EE7BBC25842DE9EAD88AB25814"

    private fun fixture(name: String) = File(javaClass.getResource("/fixtures/eden/$name/config.ini")!!.toURI())

    /** Temp data root holding a copy of the fixture config. */
    private fun dataRootWith(name: String): String {
        val root = tmp.newFolder("data-$name")
        fixture(name).copyTo(File(root, "config/config.ini").also { it.parentFile.mkdirs() })
        return root.path
    }

    private fun put(path: String, bytes: ByteArray = byteArrayOf(1, 2, 3), mtime: Long? = null) {
        val f = File(path); f.parentFile.mkdirs(); f.writeBytes(bytes)
        if (mtime != null) f.setLastModified(mtime)
    }

    private fun detected(saveRoot: String, nand: String = "${tmp.root}/nand", data: String = "${tmp.root}/data") =
        Detected("eden", "dev.eden.eden_emulator", "", data, mapOf("saveRoot" to saveRoot, "nand" to nand))

    @Test fun detect_null_without_package() {
        assertNull(m.detect(LocalFs(), none))
    }

    @Test fun eden_uses_save_directory_when_set() {
        val fs = LocalFs().apply { packages.add("dev.eden.eden_emulator.combo") }
        val d = m.detect(fs, none)!!
        assertEquals("dev.eden.eden_emulator.combo", d.pkg)
        assertEquals("/storage/emulated/0/Android/data/dev.eden.eden_emulator.combo/files", d.dataRoot)
        val cfg = m.readConfig(fs, dataRootWith("thor"))
        assertEquals("/storage/emulated/0/Games/switch-saves/user/save", m.saveRoot(cfg, "/x"))
    }

    @Test fun eden_falls_back_to_nand_when_save_directory_empty() {
        val cfg = m.readConfig(LocalFs(), dataRootWith("odin"))
        assertEquals("/storage/emulated/0/Android/data/dev.eden.eden_emulator/files/nand/user/save", m.saveRoot(cfg, "/x"))
    }

    @Test fun eden_package_override_and_order() {
        val fs = LocalFs().apply { packages.addAll(listOf("dev.igawa6.edenduo", "dev.eden.eden_emulator.combo")) }
        assertEquals("dev.eden.eden_emulator.combo", m.detect(fs, none)!!.pkg)
        val o = DeviceSettings(emptyList(), mapOf("eden.package" to "dev.igawa6.edenduo"))
        assertEquals("dev.igawa6.edenduo", m.detect(fs, o)!!.pkg)
    }

    @Test fun eden_items_keyed_by_titleid_only_active_profile() {
        val sr = "${tmp.root}/save"
        put("$sr/0000000000000000/$thorProfile/01002c9022770000/savedata/save000/data.dat", mtime = 5_000)
        put("$sr/0000000000000000/$thorProfile/0100AAAA00000000/a.bin", mtime = 5_000)
        put("$sr/0000000000000000/$odinProfile/0100BBBB00000000/b.bin", mtime = 9_000)
        put("$sr/0000000000000000/$odinProfile/notatitle/x", mtime = 9_000)
        put("$sr/cache/0000000000000000/0100CCCC00000000/c.bin", mtime = 99_000)
        val d = detected(sr)
        val items = m.saveItems(LocalFs(), d)
        assertEquals(listOf("0100BBBB00000000"), items.map { it.gameKey })
        assertEquals(ItemKind.Save, items[0].kind)
        assertEquals(listOf("b.bin"), items[0].files)
        assertEquals("$sr/0000000000000000/$odinProfile/0100BBBB00000000", items[0].baseDir)
        assertEquals(listOf("$sr/0000000000000000/$odinProfile", sr), m.watchDirs(LocalFs(), d))
    }

    @Test fun eden_items_files_sorted_relative_and_uppercase_key() {
        val sr = "${tmp.root}/save"
        val base = "$sr/0000000000000000/$thorProfile/01002c9022770000"
        put("$base/savedata/save000/data.dat"); put("$base/a.bin"); put("$base/savedata/b")
        val it = m.saveItems(LocalFs(), detected(sr)).single()
        assertEquals("01002C9022770000", it.gameKey)
        assertEquals(listOf("a.bin", "savedata/b", "savedata/save000/data.dat"), it.files)
    }

    @Test fun eden_target_dir_uses_this_devices_profile() {
        val sr = "${tmp.root}/save"
        put("$sr/0000000000000000/$odinProfile/0100BBBB00000000/b.bin")
        val t = m.targetDirFor(LocalFs(), detected(sr), ItemKind.Save, "01002c9022770000", "", "")
        assertEquals("$sr/0000000000000000/$odinProfile/01002C9022770000", t)
    }

    @Test fun eden_target_dir_falls_back_to_avatar_profile_then_throws() {
        val sr = "${tmp.root}/save"
        val nand = "${tmp.root}/nand"
        val d = detected(sr, nand)
        try {
            m.targetDirFor(LocalFs(), d, ItemKind.Save, "0100AAAA00000000", "", "")
            fail()
        } catch (e: ModuleException) { assertEquals("no Eden profile yet; open Eden once", e.message) }
        put("$nand/system/save/8000000000000010/su/avators/$odinProfile.jpg")
        assertEquals("$sr/0000000000000000/$odinProfile/0100AAAA00000000",
            m.targetDirFor(LocalFs(), d, ItemKind.Save, "0100AAAA00000000", "", ""))
    }

    @Test fun eden_installed_from_filenames_and_saves() {
        val roms = "${tmp.root}/roms"
        put("$roms/BRAVELY DEFAULT [010056F00C7B4000].nsp")
        put("$roms/sub/Other [0100ABCD00000800][v0].xci")
        put("$roms/readme.txt")
        val sr = "${tmp.root}/save"
        put("$sr/0000000000000000/$thorProfile/01002C9022770000/x")
        put("$sr/0000000000000000/$thorProfile/010056F00C7B4000/x")
        val g = m.installedGames(LocalFs(), detected(sr), DeviceSettings(listOf(roms), emptyMap()))
        assertEquals(listOf("0100ABCD00000000", "01002C9022770000", "010056F00C7B4000").sorted(), g.map { it.gameKey })
        assertEquals("BRAVELY DEFAULT", g.first { it.gameKey == "010056F00C7B4000" }.displayName)
        assertNull(g.first { it.gameKey == "01002C9022770000" }.displayName)
    }

    @Test fun eden_installed_reads_gamedirs_from_config() {
        val data = dataRootWith("thor")   // gamedir: primary:Games/switch
        put("${tmp.root}/Games/switch/Some Game [0100DEAD0000A000].nsp")
        val d = detected("${tmp.root}/save", data = data).let { it.copy(extraRoots = it.extraRoots + ("INTERNAL" to tmp.root.path)) }
        val g = m.installedGames(LocalFs(), d, none)
        assertEquals(listOf("0100DEAD0000A000"), g.map { it.gameKey })
        assertEquals("Some Game", g[0].displayName)
    }

    @Test fun eden_target_dir_rejects_unsafe_keys() {
        val sr = "${tmp.root}/save"
        put("$sr/0000000000000000/$odinProfile/0100BBBB00000000/b.bin")
        for (k in listOf("../x", "../../01002C9022770000", "0100BBBB0000000/", "", "01002C9022770000/..", "ZZZZ2C9022770000", "01002C90227700000")) {
            try { m.targetDirFor(LocalFs(), detected(sr), ItemKind.Save, k, "", ""); fail("accepted '$k'") }
            catch (e: ModuleException) { }
        }
    }

    @Test fun eden_target_dir_reuses_existing_dir_case_insensitively() {
        val sr = "${tmp.root}/save"
        put("$sr/0000000000000000/$odinProfile/0100bbbb00000000/b.bin")
        assertEquals("$sr/0000000000000000/$odinProfile/0100bbbb00000000",
            m.targetDirFor(LocalFs(), detected(sr), ItemKind.Save, "0100BBBB00000000", "", ""))
    }

    @Test fun eden_detect_then_saveItems_on_thor_layout() {
        val root = tmp.newFolder("dev").path
        val data = "$root/Android/data/dev.eden.eden_emulator/files"
        fixture("thor").copyTo(File(data, "config/config.ini").also { it.parentFile.mkdirs() })
        val mod = EdenModule(root)
        val fs = LocalFs().apply { packages.add("dev.eden.eden_emulator") }
        val d = mod.detect(fs, none)!!
        assertEquals(data, d.dataRoot)
        assertEquals("$root/Games/switch-saves/user/save", d.extraRoots["saveRoot"])
        put("$root/Games/switch-saves/user/save/0000000000000000/$thorProfile/01002C9022770000/savedata/save000/data.dat")
        val items = mod.saveItems(fs, d)
        assertEquals(listOf("01002C9022770000"), items.map { it.gameKey })
        assertEquals(listOf("savedata/save000/data.dat"), items[0].files)
    }

    private fun pfs0(vararg names: String): ByteArray {
        val strs = ByteArrayOutputStream()
        val offs = names.map { val o = strs.size(); strs.write(it.toByteArray()); strs.write(0); o }
        val out = ByteArrayOutputStream()
        fun u32(v: Int) { for (i in 0..3) out.write((v shr (8 * i)) and 0xFF) }
        out.write("PFS0".toByteArray()); u32(names.size); u32(strs.size()); u32(0)
        offs.forEach { u32(0); u32(0); u32(0); u32(0); u32(it); u32(0) }   // offset u64, size u64, strOff u32, pad u32
        out.write(strs.toByteArray()); out.write(ByteArray(64) { 7 })
        return out.toByteArray()
    }

    @Test fun normalizes_update_and_dlc_ids_to_base() {
        assertEquals("0100ABF008968000", m.normalizeTitleId("0100ABF008968800"))
        assertEquals("0100ABF008968000", m.normalizeTitleId("0100ABF008969001"))
        assertEquals("0100ABF008968000", m.normalizeTitleId("0100ABF008969002"))
        assertEquals("0100ABF008968000", m.normalizeTitleId("0100ABF008968000"))
        val roms = "${tmp.root}/roms"
        put("$roms/Sword [0100ABF008968800].nsp")
        put("$roms/DLC1 [0100ABF008969001].nsp")
        put("$roms/DLC2 [0100ABF008969002].nsp")
        val g = m.installedGames(LocalFs(), detected("${tmp.root}/save"), DeviceSettings(listOf(roms), emptyMap()))
        assertEquals(listOf("0100ABF008968000"), g.map { it.gameKey })
    }

    @Test fun eden_nsp_pfs0_ticket_titleid() {
        val roms = "${tmp.root}/roms"
        put("$roms/mystery.nsp", pfs0("abc.nca", "0100123456789ABC00000000000000AA.tik", "x.cnmt.xml"))
        put("$roms/notpfs.nsp", ByteArray(100) { 1 })
        put("$roms/short.nsp", ByteArray(3))
        assertEquals("0100123456789000", m.ticketTitleId(LocalFs(), "$roms/mystery.nsp"))
        assertNull(m.ticketTitleId(LocalFs(), "$roms/notpfs.nsp"))
        assertNull(m.ticketTitleId(LocalFs(), "$roms/short.nsp"))
        val g = m.installedGames(LocalFs(), detected("${tmp.root}/save"), DeviceSettings(listOf(roms), emptyMap()))
        assertEquals(listOf("0100123456789000"), g.map { it.gameKey })
        assertEquals("mystery", g[0].displayName)
    }

    @Test fun eden_config_bundle_lists_ini_files() {
        val data = "${tmp.root}/data"
        put("$data/config/config.ini"); put("$data/config/custom/0100AAAA00000000.ini")
        put("$data/config/input/player_0.ini"); put("$data/config/custom/readme.txt")
        put("$data/config/other.ini")
        val b = m.configBundle(LocalFs(), detected("${tmp.root}/save", data = data))
        assertEquals(setOf("data/config/config.ini", "data/config/custom/0100AAAA00000000.ini", "data/config/input/player_0.ini"), b.files.keys)
        assertEquals("$data/config/input/player_0.ini", b.files["data/config/input/player_0.ini"])
    }

    @Test fun registry_finds_eden() {
        val r = ModuleRegistry(listOf(m))
        assertSame(m, r.byId("eden")); assertNull(r.byId("zzz")); assertEquals(1, r.all().size)
    }
}
