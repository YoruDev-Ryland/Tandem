package dev.emusync.app.sync

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StateStoreFlowTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun changes_increments_on_settings_and_put() {
        val s = StateStore(File(tmp.root, "state.json"))
        assertEquals(0L, s.changes.value)
        s.settings = DeviceSettingsData(romDirs = listOf("/roms"))
        assertEquals(1L, s.changes.value)
        s.put("k", ItemState("v", "m", "u"))
        assertEquals(2L, s.changes.value)
    }

    @Test fun failed_write_does_not_increment() {
        var failing = false
        val s = StateStore(File(tmp.root, "state.json"), fsync = { if (failing) throw IOException("disk full") })
        s.deviceId = "d1"
        failing = true
        try {
            s.settings = DeviceSettingsData(autoApply = true)
            fail("expected IOException")
        } catch (_: IOException) {
        }
        assertEquals(1L, s.changes.value)
    }
}
