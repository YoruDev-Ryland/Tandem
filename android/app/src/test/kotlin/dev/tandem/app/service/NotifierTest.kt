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

    @Test fun statusNotificationUsesTheTandemMark() {
        val n = Notifier(app)
        n.ensureChannels()
        assertEquals(dev.tandem.app.R.drawable.ic_stat_tandem, n.status("x").smallIcon.resId)
    }

    @Test fun alertNotificationsUseTheTandemMark() {
        val n = Notifier(app)
        n.ensureChannels()
        n.alert("Close Eden to get the newer save.")
        val nm = app.getSystemService(android.app.NotificationManager::class.java)
        val posted = org.robolectric.Shadows.shadowOf(nm).allNotifications
        assertEquals(1, posted.size)
        assertEquals(dev.tandem.app.R.drawable.ic_stat_tandem, posted.single().smallIcon.resId)
    }

    @Test fun aReadyUpdateAsksToInstallAndOpensTheAppToDoIt() {
        val n = Notifier(app)
        n.ensureChannels()
        n.updateReady("0.3.0")
        val nm = org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
        val posted = nm.getNotification(Notifier.ID_UPDATE)
        assertEquals("Tandem 0.3.0 is ready to install", posted.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals(Notifier.CHANNEL_ALERTS, posted.channelId)
        val open = org.robolectric.Shadows.shadowOf(posted.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, open.component?.className)
        assertEquals(ACTION_INSTALL_UPDATE, open.action)
        n.cancelUpdate()
        assertEquals(null, nm.getNotification(Notifier.ID_UPDATE))
    }

    @Test fun aSystemInstallPromptIsOpenedFromTheSameNotification() {
        val n = Notifier(app)
        n.ensureChannels()
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        n.updateReady("0.3.0", confirm)
        val nm = org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
        val posted = nm.getNotification(Notifier.ID_UPDATE)
        assertEquals("Tandem 0.3.0 is ready to install", posted.extras.getString(android.app.Notification.EXTRA_TITLE))
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", org.robolectric.Shadows.shadowOf(posted.contentIntent).savedIntent.action)
    }

    @Test fun configsUrlTrimsTheSlash() {
        assertEquals("https://t.example/configs", configsUrl("https://t.example/"))
    }
}
