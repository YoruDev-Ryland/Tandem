package dev.emusync.app.modules.retroarch

import dev.emusync.app.fs.LocalFs
import dev.emusync.app.model.ItemKind
import dev.emusync.app.modules.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RetroArchModuleTest {
    @get:Rule val tmp = TemporaryFolder()
    private val none = DeviceSettings(emptyList(), emptyMap())
    private val pkg = "com.retroarch.aarch64"
    private fun m() = RetroArchModule(tmp.root.path)
    private fun fs() = LocalFs().apply { packages.add(pkg) }
    private fun res(n: String) = javaClass.getResource("/fixtures/retroarch/$n")!!.readText()
    private fun ra(p: String) = "${tmp.root.path}/RetroArch/$p"
    private fun put(path: String, text: String = "x") { File(path).also { it.parentFile.mkdirs() }.writeText(text) }
    private fun cfgPath() = "${tmp.root.path}/Android/data/$pkg/files/retroarch.cfg"
    private fun withCfg(text: String = res("retroarch.cfg")) = put(cfgPath(), text)
    private fun detect() = m().detect(fs(), none)!!

    @Test fun cfg_default_dirs_resolve_to_root() {
        File(ra("saves")).mkdirs()
        withCfg("savefile_directory = \"default\"\nsavestate_directory = \"\"\nrgui_config_directory = \":/cfgs\"\n")
        put(ra("saves/a.srm")); put(ra("states/a.state")); put(ra("cfgs/x.cfg"))
        val d = detect()
        val items = m().saveItems(fs(), d)
        assertEquals(listOf(ItemKind.Save, ItemKind.State), items.map { it.kind }.sorted())
        assertEquals(ra("saves"), items.first { it.kind == ItemKind.Save }.baseDir)
        assertEquals(ra("states"), items.first { it.kind == ItemKind.State }.baseDir)
        assertEquals(ra("cfgs/x.cfg"), m().configBundle(fs(), d).files["root/config/x.cfg"])
    }

    @Test fun cfg_explicit_dirs_used() {
        withCfg()  // the real Odin cfg: explicit /storage/emulated/0/RetroArch/{saves,states,config}
        put(ra("saves/mGBA/Pokemon - Emerald Version (USA).srm"))
        val d = detect()
        assertNull(d.extraRoots["cfgMissing"])
        assertEquals(ra(""), d.extraRoots["root"]!! + "/")
        val i = m().saveItems(fs(), d).single()
        assertEquals("retroarch/Pokemon - Emerald Version (USA)", i.gameKey)
        assertEquals("srm", i.slot)
        assertEquals("mGBA", i.core)
        assertEquals(ra("saves/mGBA"), i.baseDir)
        assertEquals(listOf("Pokemon - Emerald Version (USA).srm"), i.files)
        assertTrue(m().watchDirs(fs(), d).isEmpty() || m().watchDirs(fs(), d).contains(ra("saves")))
    }

    @Test fun srm_and_rtc_are_separate_items() {
        withCfg()
        put(ra("saves/Gambatte/Pokemon - Crystal Version.srm"))
        put(ra("saves/Gambatte/Pokemon - Crystal Version.rtc"))
        put(ra("saves/Gambatte/readme.txt"))
        put(ra("saves/root game.sav"))
        val items = m().saveItems(fs(), detect())
        assertEquals(3, items.size)
        assertEquals(setOf("srm", "rtc"), items.filter { it.core == "Gambatte" }.map { it.slot }.toSet())
        assertEquals("", items.first { it.gameKey == "retroarch/root game" }.core)
    }

    @Test fun states_core_from_sorted_folder() {
        withCfg()
        put(ra("states/mGBA/Pokemon - Emerald Version.state"))
        put(ra("states/mGBA/Pokemon - Emerald Version.state3"))
        put(ra("states/mGBA/Pokemon - Emerald Version.state.auto"))
        put(ra("states/mGBA/Pokemon - Emerald Version.state.png"))
        put(ra("states/mGBA/Pokemon - Emerald Version.state3.png"))
        put(ra("states/plain.state"))
        val s = m().saveItems(fs(), detect()).filter { it.kind == ItemKind.State }
        assertEquals(setOf("", "3", "auto"), s.filter { it.core == "mGBA" }.map { it.slot }.toSet())
        assertEquals(4, s.size)
        assertEquals("", s.first { it.gameKey == "retroarch/plain" }.core)
    }

    @Test fun installed_from_playlists() {
        withCfg()
        put(ra("playlists/Nintendo - Game Boy Advance.lpl"), res("gba.lpl"))
        put(ra("playlists/builtin/content_history.lpl"),
            """{"items":[{"path":"/x/Some Game.gb","label":"Some Game","core_path":"DETECT"}]}""")
        put(ra("playlists/logs/mGBA/Some.lrtl"), """{"items":[{"path":"/x/Nope.gb"}]}""")
        val keys = m().installedGames(fs(), detect(), DeviceSettings(listOf(tmp.root.path + "/roms"), emptyMap()).also {
            put(tmp.root.path + "/roms/Zelda.gbc"); put(tmp.root.path + "/roms/notes.txt")
        }).map { it.gameKey }
        assertTrue("retroarch/Castlevania - Aria of Sorrow (USA)" in keys)
        assertTrue("retroarch/Some Game" in keys)
        assertTrue("retroarch/Zelda" in keys)
        assertFalse("retroarch/Nope" in keys)
        assertFalse("retroarch/notes" in keys)
    }

    @Test fun cores_from_playlists() {
        withCfg()
        put(ra("playlists/Nintendo - Game Boy Advance.lpl"), res("gba.lpl"))
        assertEquals(listOf("mgba"), m().cores(fs(), detect()))
    }

    @Test fun cfg_missing_flagged() {
        put("${tmp.root.path}/Android/data/$pkg/files/other", "x")
        assertEquals("1", detect().extraRoots["cfgMissing"])
        // no root either: empty everywhere
        val d = detect()
        assertTrue(m().saveItems(fs(), d).isEmpty())
        assertTrue(m().configBundle(fs(), d).files.isEmpty())
        // with a probed root and default subdirs
        put(ra("saves/mGBA/g.srm"))
        val d2 = detect()
        assertEquals("mGBA", m().saveItems(fs(), d2).single().core)
    }

    @Test fun config_bundle_includes_cfg_and_config_tree_skips_big() {
        withCfg()
        put(ra("config/mGBA/mGBA.opt")); put(ra("config/Gambatte/Gambatte.opt"))
        File(ra("config/big.bin")).writeBytes(ByteArray(1_100_000))
        val f = m().configBundle(fs(), detect()).files
        assertEquals(cfgPath(), f["data/retroarch.cfg"])
        assertEquals(ra("config/mGBA/mGBA.opt"), f["root/config/mGBA/mGBA.opt"])
        assertFalse(f.containsKey("root/config/big.bin"))
    }

    @Test fun target_dirs() {
        withCfg()
        File(ra("saves")).mkdirs()
        val mod = m(); val d = detect()
        assertEquals(ra("saves/mGBA"), mod.targetDirFor(fs(), d, ItemKind.Save, "retroarch/Game", "srm", "mGBA"))
        assertEquals(ra("saves"), mod.targetDirFor(fs(), d, ItemKind.Save, "retroarch/Game", "srm", ""))
        put(ra("saves/Gambatte/Game.srm"))
        assertEquals(ra("saves/Gambatte"), mod.targetDirFor(fs(), d, ItemKind.Save, "retroarch/Game", "srm", "mGBA"))
        assertEquals(ra("states/mGBA"), mod.targetDirFor(fs(), d, ItemKind.State, "retroarch/Game", "auto", "mGBA"))
        assertThrows(ModuleException::class.java) { mod.targetDirFor(fs(), d, ItemKind.Save, "retroarch/Game", "srm", "..") }
        assertThrows(ModuleException::class.java) { mod.targetDirFor(fs(), d, ItemKind.Save, "retroarch/a/b", "srm", "") }
    }

    @Test fun cfg_parser() {
        val c = RaCfg.parse("a = \"1\"\nb = \"default\"\nc = \"\"\n# x = \"y\"\nd = \":/saves\"\n")
        assertEquals("1", c["a"]); assertNull(c["b"]); assertNull(c["c"]); assertNull(c["x"])
        assertEquals("/r/saves", c.path("d", "/r"))
    }
}
