package dev.tandem.app.update

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun nothingStoredMeansNeverCheckedWithAutoInstallOn() {
        assertEquals(UpdateData(), UpdateStore(File(tmp.root, "update.json")).data.value)
        assertEquals(true, UpdateData().autoInstall)
    }

    @Test fun whatIsWrittenIsReadBack() {
        val f = File(tmp.root, "update.json")
        val d = UpdateData(lastCheckAt = 42, lastError = "x", autoInstall = false,
            ready = ReadyApk("/c/tandem-3000.apk", "0.3.0", 3_000, "ab"), failedInstall = 3_000)
        UpdateStore(f).update { d }
        assertEquals(d, UpdateStore(f).data.value)
    }

    @Test fun anUnreadableFileIsAFreshStart() {
        val f = File(tmp.root, "update.json").apply { writeText("{not json") }
        assertEquals(UpdateData(), UpdateStore(f).data.value)
    }
}
