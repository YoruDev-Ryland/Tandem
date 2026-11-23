package dev.tandem.app.modules.melonds

import dev.tandem.app.fs.LocalFs
import dev.tandem.app.model.ItemKind
import dev.tandem.app.modules.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MelonDsModuleTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fs = LocalFs().apply { packages.add("me.magnum.melonds") }
    private val none = DeviceSettings(emptyList(), emptyMap())
    private fun internal() = File(tmp.root, "internal").path
    private fun storage() = File(tmp.root, "storage").path
    private fun m() = MelonDsModule(internal(), storage())
    private fun put(path: String) { File(path).also { it.parentFile.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3)) }
    private fun det() = m().detect(fs, none)!!

    @Test fun sav_next_to_rom_is_item() {
        put("${internal()}/Games/nds/Final Fantasy - The 4 Heroes of Light (US).sav")
        put("${internal()}/Games/nds/Final Fantasy - The 4 Heroes of Light (US).zip")
        put("${internal()}/Games/nds/readme.txt")
        val i = m().saveItems(fs, det()).single()
        assertEquals(ItemKind.Save, i.kind)
        assertEquals("nds/Final Fantasy - The 4 Heroes of Light (US)", i.gameKey)
        assertEquals("${internal()}/Games/nds", i.baseDir)
        assertEquals(listOf("Final Fantasy - The 4 Heroes of Light (US).sav"), i.files)
    }

    @Test fun installed_games_carry_the_rom_game_code() {
        fun rom(code: String): ByteArray = ByteArray(0x200).also { b -> code.toByteArray(Charsets.ISO_8859_1).copyInto(b, 0x0C) }
        File("${internal()}/Games/nds").mkdirs()
        File("${internal()}/Games/nds/Pokemon - Platinum Version (US).nds").writeBytes(rom("CPUE"))
        File("${internal()}/Games/nds/Odd.nds").writeBytes(rom("cp\u0000E"))
        File("${internal()}/Games/nds/Tiny.nds").writeBytes(ByteArray(6))
        val ids = m().installedGames(fs, det(), none).associate { it.gameKey to it.gameId }
        assertEquals("CPUE", ids["nds/Pokemon - Platinum Version (US)"])
        assertNull(ids["nds/Odd"])
        assertTrue("nds/Tiny" in ids)
        assertNull(ids["nds/Tiny"])
        assertEquals("CPUE", MelonDsModule.gameCode(rom("CPUE").inputStream()))
    }

    @Test fun orphan_sav_is_item_but_not_installed() {
        put("${internal()}/Games/nds/Orphan.sav")
        put("${internal()}/Games/nds/Real.nds")
        val d = det()
        assertEquals(listOf("nds/Orphan"), m().saveItems(fs, d).map { it.gameKey })
        assertEquals(listOf("nds/Real"), m().installedGames(fs, d, none).map { it.gameKey })
    }

    @Test fun states_ml_slots() {
        put("${internal()}/Games/nds/Pokemon.nds")
        put("${internal()}/Games/nds/Pokemon.ml0")
        put("${internal()}/Games/nds/Pokemon.ml8")
        put("${internal()}/Games/nds/Pokemon.ml9")
        put("${internal()}/Games/nds/Pokemon.mlx")
        put("${internal()}/Android/data/me.magnum.melonds/files/savestates/Other.ml3")
        val s = m().saveItems(fs, det())
        val states = s.filter { it.kind == ItemKind.State }.associate { it.gameKey to it }
        assertEquals(setOf("nds/Pokemon", "nds/Other"), states.keys)
        assertEquals(listOf("0", "8"), s.filter { it.gameKey == "nds/Pokemon" }.map { it.slot }.sorted())
        assertEquals("3", states["nds/Other"]!!.slot)
        assertEquals("${internal()}/Android/data/me.magnum.melonds/files/savestates", states["nds/Other"]!!.baseDir)
    }

    @Test fun installed_from_rom_dirs_including_sd() {
        put("${internal()}/Games/nds/A.nds")
        put("${internal()}/Games/nds/B.dsi")
        put("${storage()}/EFF6-FC92/Games/nds/Pokemon - Platinum Version (US).zip")
        put("${storage()}/EFF6-FC92/Games/nds/Pokemon - Platinum Version (US).sav")
        put("${storage()}/EFF6-FC92/Games/nds/Pokemon - Platinum Version (US).ml0")
        put("${storage()}/emulated/ignored.nds")
        put("${internal()}/Custom/nds/C.7z")
        val settings = DeviceSettings(listOf("/sdcard/Custom/nds", "/sdcard/Other"), emptyMap())
        val d = m().detect(fs, settings)!!
        val keys = m().installedGames(fs, d, settings).map { it.gameKey }
        assertEquals(listOf("nds/A", "nds/B", "nds/C", "nds/Pokemon - Platinum Version (US)"), keys)
        assertEquals(2, m().saveItems(fs, d).size)
    }

    @Test fun export_folder_is_config_bundle() {
        put("${internal()}/Tandem/melonds-export/melonDS.ini")
        put("${internal()}/Tandem/melonds-export/sub/x.json")
        val d = det()
        assertEquals("${internal()}/Tandem/melonds-export", d.extraRoots["export"])
        val b = m().configBundle(fs, d).files
        assertEquals(setOf("export/melonDS.ini", "export/sub/x.json"), b.keys)
        assertEquals("${internal()}/Tandem/melonds-export/melonDS.ini", b["export/melonDS.ini"])
    }

    @Test fun target_is_next_to_rom_else_throws() {
        put("${storage()}/EFF6-FC92/Games/nds/Game.NDS")
        val d = det()
        val dir = "${storage()}/EFF6-FC92/Games/nds"
        assertEquals(dir, m().targetDirFor(fs, d, ItemKind.Save, "nds/game", "", ""))
        assertEquals(dir, m().targetDirFor(fs, d, ItemKind.State, "nds/Game", "4", ""))
        assertThrows(ModuleException::class.java) { m().targetDirFor(fs, d, ItemKind.Save, "nds/Missing", "", "") }
        assertThrows(ModuleException::class.java) { m().targetDirFor(fs, d, ItemKind.Save, "nds/../x", "", "") }
        assertThrows(ModuleException::class.java) { m().targetDirFor(fs, d, ItemKind.State, "nds/Game", "../1", "") }
        assertThrows(ModuleException::class.java) { m().targetDirFor(fs, d, ItemKind.State, "nds/Game", "9", "") }
    }

    @Test fun detect_needs_package() {
        assertNull(m().detect(LocalFs(), none))
    }

    @Test fun detect_survives_unlistable_storage() {
        put("${internal()}/Games/nds/A.nds")
        val boom = object : LocalFs() {
            override fun list(dir: String) = if (dir == storage()) throw java.io.IOException("denied") else super.list(dir)
        }.apply { packages.add("me.magnum.melonds") }
        val d = m().detect(boom, none)!!
        assertEquals(listOf("nds/A"), m().installedGames(boom, d, none).map { it.gameKey })
    }
}
