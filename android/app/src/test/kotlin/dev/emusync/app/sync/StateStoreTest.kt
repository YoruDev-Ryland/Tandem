package dev.emusync.app.sync

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StateStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun state_store_roundtrips_and_survives_reload() {
        val f = File(tmp.root, "state.json")
        val s = StateStore(f)
        s.put("k", ItemState("v1", "mh", "uh"))
        s.putConfigState("cemu", ConfigState("b1", "v2", "mh2", "uh2"))
        s.deviceKey = "dk"; s.serverUrl = "http://x"; s.deviceId = "d1"
        s.settings = DeviceSettingsData(listOf("/r"), mapOf("a" to "b"))
        val r = StateStore(f)
        assertEquals(ItemState("v1", "mh", "uh"), r.get("k"))
        assertEquals(ConfigState("b1", "v2", "mh2", "uh2"), r.configState("cemu"))
        assertEquals("dk", r.deviceKey); assertEquals("http://x", r.serverUrl); assertEquals("d1", r.deviceId)
        assertEquals(DeviceSettingsData(listOf("/r"), mapOf("a" to "b")), r.settings)
        assertNull(r.get("nope"))
        assertFalse(File(tmp.root, "state.json.tmp").exists())
    }

    @Test fun auto_apply_defaults_off_for_old_files_and_persists() {
        val f = File(tmp.root, "state.json")
        f.writeText("""{"settings":{"romDirs":["/r"],"overrides":{}}}""")
        val s = StateStore(f)
        assertFalse(s.settings.autoApply)
        s.settings = s.settings.copy(autoApply = true)
        s.signInForm = SignInForm("http://x", "a@b")
        val r = StateStore(f)
        assertTrue(r.settings.autoApply)
        assertEquals(SignInForm("http://x", "a@b"), r.signInForm)
        assertFalse(f.readText().contains("password"))
    }

    @Test fun missing_file_starts_empty() {
        val s = StateStore(File(tmp.root, "none.json"))
        assertNull(s.deviceKey); assertNull(s.get("k"))
    }

    @Test fun corrupt_file_is_renamed_and_store_starts_empty() {
        val f = File(tmp.root, "state.json")
        f.writeText("{not json")
        val s = StateStore(f)
        assertNull(s.get("k"))
        assertTrue(tmp.root.listFiles()!!.any { it.name.startsWith("state.json.corrupt-") })
        s.put("k", ItemState("a", null, null))
        assertNotNull(StateStore(f).get("k"))
    }

    @Test fun corrupt_file_is_preserved_with_contents() {
        val f = File(tmp.root, "state.json")
        f.writeText("{not json")
        StateStore(f)
        val c = tmp.root.listFiles()!!.single { it.name.startsWith("state.json.corrupt-") }
        assertEquals("{not json", c.readText())
        assertFalse(f.exists())
    }

    @Test fun failed_write_does_not_change_memory() {
        val dir = File(tmp.root, "ro").also { it.mkdirs() }
        val f = File(dir, "state.json")
        val s = StateStore(f)
        s.put("k", ItemState("a", null, null))
        // Make the temp path a non-empty directory so the write fails regardless of uid.
        val t = File(dir, "state.json.tmp")
        f.delete(); t.delete(); t.mkdirs(); File(t, "x").writeText("x")
        assertThrows(Exception::class.java) { s.put("k2", ItemState("b", null, null)) }
        assertNull(s.get("k2"))
        assertNotNull(s.get("k"))
    }

    @Test fun state_write_fsyncs_tmp_before_rename_then_syncs_dir() {
        val f = File(tmp.root, "state.json")
        val events = mutableListOf<String>()
        val s = StateStore(f,
            fsync = { out ->
                out.flush()
                val tmpFile = File(tmp.root, "state.json.tmp")
                events += "fsync tmp=${tmpFile.readText().contains("v1")} target=${f.exists()}"
            },
            dirSync = { dir -> events += "dir ${dir == tmp.root.absoluteFile} target=${f.readText().contains("v1")}" })
        s.put("k", ItemState("v1", "mh", "uh"))
        assertEquals(listOf("fsync tmp=true target=false", "dir true target=true"), events)
        assertEquals(ItemState("v1", "mh", "uh"), StateStore(f).get("k"))
    }

    @Test fun failed_fsync_leaves_previous_state_intact() {
        val f = File(tmp.root, "state.json")
        StateStore(f).put("k", ItemState("v1", "mh", "uh"))
        val s = StateStore(f, fsync = { throw java.io.IOException("eio") })
        try { s.put("k", ItemState("v2", "mh", "uh")); fail("expected IOException") } catch (e: java.io.IOException) {}
        assertEquals("v1", StateStore(f).get("k")!!.knownVersionId)
        assertEquals("v1", s.get("k")!!.knownVersionId)   // in-memory state not advanced either
    }

    @Test fun old_files_get_theme_and_variants_defaults_and_the_eden_package_override_moves_to_variants() {
        val f = File(tmp.root, "state.json")
        f.writeText("""{"settings":{"romDirs":["/r"],"overrides":{"eden.package":"dev.igawa6.edenduo","azahar.root":"/a"}}}""")
        val s = StateStore(f)
        assertEquals("system", s.settings.theme)
        assertEquals(mapOf("eden" to "dev.igawa6.edenduo"), s.settings.variants)
        assertEquals(mapOf("azahar.root" to "/a"), s.settings.overrides)
        s.deviceId = "d1"   // any write persists the migrated settings
        val r = StateStore(f)
        assertEquals(mapOf("eden" to "dev.igawa6.edenduo"), r.settings.variants)
        assertFalse(f.readText().contains("eden.package"))
    }

    @Test fun a_chosen_eden_variant_wins_over_a_leftover_eden_package_override() {
        val f = File(tmp.root, "state.json")
        f.writeText("""{"settings":{"overrides":{"eden.package":"dev.igawa6.edenduo"},"variants":{"eden":"dev.eden.eden_emulator"}}}""")
        assertEquals(mapOf("eden" to "dev.eden.eden_emulator"), StateStore(f).settings.variants)
    }

    @Test fun sign_out_clears_the_key_and_id_and_keeps_the_server() {
        val f = File(tmp.root, "state.json")
        val s = StateStore(f)
        s.serverUrl = "http://x"; s.deviceKey = "dk"; s.deviceId = "d1"
        s.put("eden|Save|G||", ItemState("v1", "m", "m"))
        s.putConfigState("eden", ConfigState("b", "c", "m", "m"))
        val before = s.changes.value
        s.signOut()
        assertEquals(before + 1, s.changes.value)
        val r = StateStore(f)
        assertNull(r.deviceKey); assertNull(r.deviceId); assertEquals("http://x", r.serverUrl)
        assertNull(r.get("eden|Save|G||")); assertNull(r.configState("eden"))
    }

    @Test fun set_variant_forgets_only_that_emulators_state_when_asked_in_one_write() {
        val f = File(tmp.root, "state.json")
        val s = StateStore(f)
        s.put("eden|Save|G||", ItemState("v1", "m", "m"))
        s.put("edenx|Save|G||", ItemState("v2", "m", "m"))   // another id sharing the prefix
        s.putConfigState("eden", ConfigState("b", "c", "m", "m"))
        s.setVariant("eden", "dev.eden.eden_emulator", forgetState = false)
        assertEquals("v1", s.get("eden|Save|G||")?.knownVersionId)
        val before = s.changes.value
        s.setVariant("eden", "dev.igawa6.edenduo", forgetState = true)
        assertEquals(before + 1, s.changes.value)
        val r = StateStore(f)
        assertEquals("dev.igawa6.edenduo", r.settings.variants["eden"])
        assertNull(r.get("eden|Save|G||")); assertNull(r.configState("eden"))
        assertEquals("v2", r.get("edenx|Save|G||")?.knownVersionId)
    }
}
