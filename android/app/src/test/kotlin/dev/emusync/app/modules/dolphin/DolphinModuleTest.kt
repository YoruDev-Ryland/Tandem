package dev.emusync.app.modules.dolphin

import dev.emusync.app.fs.LocalFs
import dev.emusync.app.model.ItemKind
import dev.emusync.app.modules.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DolphinModuleTest {
    @get:Rule val tmp = TemporaryFolder()
    private val m = DolphinModule()
    private val none = DeviceSettings(emptyList(), emptyMap())
    private val fs = LocalFs()

    private fun res(n: String) = javaClass.getResource("/fixtures/dolphin/$n")!!.readBytes()
    private fun root() = File(tmp.root, "files").path
    private fun d(extra: Map<String, String> = mapOf("INTERNAL" to tmp.root.path)) =
        Detected("dolphin", "org.dolphinemu.dolphinemu", "", root(), extra)
    private fun put(path: String, bytes: ByteArray = byteArrayOf(1, 2, 3)) {
        File(path).also { it.parentFile.mkdirs() }.writeBytes(bytes)
    }
    private fun gci(name: String, key: String, slot: String, region: String = "USA"): String {
        val b = ByteArray(0x80)
        key.toByteArray().copyInto(b, 0); b[6] = 0xff.toByte(); b[7] = 1
        slot.toByteArray().copyInto(b, 8)
        val p = "${root()}/GC/$region/Card A/$name"; put(p, b); return p
    }

    @Test fun detect_by_package() {
        assertNull(m.detect(LocalFs(), none))
        val f = LocalFs().apply { packages.add("org.dolphinemu.dolphinemu") }
        val det = m.detect(f, none)!!
        assertEquals("/storage/emulated/0/Android/data/org.dolphinemu.dolphinemu/files", det.dataRoot)
    }

    @Test fun gci_gamekey_and_slot_from_header() {
        put("${root()}/GC/USA/Card A/01-GM8E-MetroidPrime A.gci", res("metroid_prime_header.bin") + ByteArray(64))
        val items = m.saveItems(fs, d())
        assertEquals(1, items.size)
        val i = items[0]
        assertEquals(ItemKind.Save, i.kind)
        assertEquals("GM8E01", i.gameKey)
        assertEquals("MetroidPrime A", i.slot)
        assertEquals("${root()}/GC/USA/Card A", i.baseDir)
        assertEquals(listOf("01-GM8E-MetroidPrime A.gci"), i.files)
    }

    @Test fun two_cards_same_game_and_short_files_ignored() {
        gci("01-GM8E-a.gci", "GM8E01", "SlotA")
        gci("01-GM8E-b.gci", "GM8E01", "SlotB", "EUR")
        put("${root()}/GC/USA/Card A/tiny.gci", ByteArray(0x3f))
        val items = m.saveItems(fs, d())
        assertEquals(setOf("SlotA", "SlotB"), items.map { it.slot }.toSet())
        assertEquals(2, items.size)
    }

    @Test fun unsafe_slot_skipped() {
        gci("x.gci", "GM8E01", "../evil")
        assertTrue(m.saveItems(fs, d()).isEmpty())
    }

    @Test fun wii_title_dir_item() {
        put("${root()}/Wii/title/00010000/524d4345/data/banner.bin")
        put("${root()}/Wii/title/00010000/524d4345/data/sub/save.dat")
        val i = m.saveItems(fs, d()).single()
        assertEquals("RMCE", i.gameKey)   // TODO verify "W" suffix on device
        assertEquals("${root()}/Wii/title/00010000/524d4345/data", i.baseDir)
        assertEquals(listOf("banner.bin", "sub/save.dat"), i.files)
        assertEquals("${root()}/Wii/title/00010000/524d4345/data", m.targetDirFor(fs, d(), ItemKind.Save, "RMCE", "", ""))
    }

    @Test fun state_slot_parsed() {
        put("${root()}/StateSaves/GM8E01.s03")
        put("${root()}/StateSaves/GM8E01.s00")
        put("${root()}/StateSaves/GM8E01.sav")
        val i = m.saveItems(fs, d()).single()
        assertEquals(ItemKind.State, i.kind)
        assertEquals("GM8E01", i.gameKey)
        assertEquals("03", i.slot)
        assertEquals("", i.core)
        assertEquals(listOf("GM8E01.s03"), i.files)
        assertEquals("${root()}/StateSaves", m.targetDirFor(fs, d(), ItemKind.State, "GM8E01", "03", ""))
    }

    @Test fun target_dir_gc_regions_and_safety() {
        assertEquals("${root()}/GC/USA/Card A", m.targetDirFor(fs, d(), ItemKind.Save, "GM8E01", "x", ""))
        assertEquals("${root()}/GC/EUR/Card A", m.targetDirFor(fs, d(), ItemKind.Save, "GM8P01", "x", ""))
        assertEquals("${root()}/GC/JAP/Card A", m.targetDirFor(fs, d(), ItemKind.Save, "GM8J01", "x", ""))
        assertThrows(ModuleException::class.java) { m.targetDirFor(fs, d(), ItemKind.Save, "../../x", "x", "") }
        assertThrows(ModuleException::class.java) { m.targetDirFor(fs, d(), ItemKind.State, "A/B", "01", "") }
    }

    @Test fun config_bundle_files() {
        put("${root()}/Config/Dolphin.ini"); put("${root()}/Config/GFX.ini"); put("${root()}/Config/x.txt")
        put("${root()}/GameSettings/GM8E01.ini")
        val b = m.configBundle(fs, d())
        assertEquals(listOf("data/Config/Dolphin.ini", "data/Config/GFX.ini", "data/GameSettings/GM8E01.ini"), b.files.keys.toList())
        assertEquals("${root()}/Config/GFX.ini", b.files["data/Config/GFX.ini"])
    }

    @Test fun installed_games_from_isopath_romdirs_and_gci() {
        val roms = File(tmp.root, "Games/gc").path
        File("$roms/Metroid (GM8E01).rvz").also { it.parentFile.mkdirs() }.writeBytes(ByteArray(8))
        File("$roms/x.gcm").writeBytes("G2ME01".toByteArray() + ByteArray(16))
        val other = File(tmp.root, "more").path
        File("$other/y.iso").also { it.parentFile.mkdirs() }.writeBytes("GC6E01".toByteArray() + ByteArray(16))
        File(root(), "Config").mkdirs()
        File(root(), "Config/Dolphin.ini").writeText(
            "[General]\nISOPath0 = content://com.android.externalstorage.documents/tree/primary%3AGames%2Fgc\nISOPaths = 1\n")
        gci("a.gci", "GXBE69", "s")
        val g = m.installedGames(fs, d(), DeviceSettings(listOf(other), emptyMap()))
        assertEquals(listOf("G2ME01", "GC6E01", "GM8E01", "GXBE69"), g.map { it.gameKey })
    }

    @Test fun real_ini_fixture_parses_isopath() {
        assertEquals(listOf("content://com.android.externalstorage.documents/tree/EFF6-FC92%3AGames%2Fgc"),
            m.isoPaths(res("Dolphin.ini").decodeToString()))
    }
}
