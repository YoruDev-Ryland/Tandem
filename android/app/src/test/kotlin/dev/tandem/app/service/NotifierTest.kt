package dev.tandem.app.service

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.tandem.app.MainActivity
import dev.tandem.app.model.ItemKind
import dev.tandem.app.sync.SyncConflict
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotifierTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test fun aConfigConflictOpensTheWebConfigsPage() {
        val c = SyncConflict("eden", null, null, "", "", null, "Eden")
        val i = conflictTarget(app, c, "http://nas:5100/")
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("http://nas:5100/configs", i.dataString)
    }

    @Test fun aSaveConflictOrNoServerOpensTheApp() {
        val save = SyncConflict("eden", ItemKind.Save, "0100", "", "", "Game", "Eden")
        assertEquals(MainActivity::class.java.name, conflictTarget(app, save, "http://nas:5100").component?.className)
        val config = SyncConflict("eden", null, null, "", "", null, "Eden")
        assertEquals(MainActivity::class.java.name, conflictTarget(app, config, null).component?.className)
    }

    @Test fun configsUrlTrimsTheSlash() {
        assertEquals("https://t.example/configs", configsUrl("https://t.example/"))
    }
}
