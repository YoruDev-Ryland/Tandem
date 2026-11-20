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
}
