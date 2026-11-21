package dev.tandem.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.tandem.app.service.AppGraph
import dev.tandem.app.service.SyncService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The launcher starts the sync service whenever it comes to the front signed in; the debug screen is no longer the way in. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-land-xhdpi")
class MainActivityServiceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = AppGraph.store(app)

    @After fun signOut() { store.signOut(emptyList()) }

    @Test fun aRevokedDeviceStopsSyncNotifiesOnceAndStaysOff() {
        store.signIn("http://nas:5100", "k", "d", emptyList())
        AppGraph.revoked(app, "k")
        assertTrue(store.revoked)
        assertEquals(SyncService::class.java.name, shadowOf(app).nextStoppedService?.component?.className)
        val nm = shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
        assertEquals(1, nm.allNotifications.size)
        AppGraph.revoked(app, "k")   // every later 401 is quiet
        assertEquals(1, nm.allNotifications.size)
        Robolectric.buildActivity(MainActivity::class.java).setup().use {
            assertNull("the launcher doesn't restart a removed device", shadowOf(app).nextStartedService)
        }
        dev.tandem.app.service.BootReceiver().onReceive(app, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
        assertFalse(dev.tandem.app.service.Watchdog.check(store, running = false) { true })
    }

    @Test fun aLateRevokeForAReplacedKeyChangesNothing() {
        store.signIn("http://nas:5100", "new key", "d2", emptyList())
        AppGraph.revoked(app, "old key")
        assertFalse(store.revoked)
        assertNull(shadowOf(app).nextStoppedService)
    }

    @Test fun theWatchdogStartsSyncOnlyWhenSignedInAndStopped() {
        var starts = 0
        assertFalse(dev.tandem.app.service.Watchdog.check(store, running = false) { starts++; true })
        store.signIn("http://nas:5100", "k", "d", emptyList())
        assertFalse(dev.tandem.app.service.Watchdog.check(store, running = true) { starts++; true })
        assertTrue(dev.tandem.app.service.Watchdog.check(store, running = false) { starts++; true })
        assertEquals(1, starts)
    }

    @Test fun signedInStartsTheSyncService() {
        store.deviceKey = "test-key"
        Robolectric.buildActivity(MainActivity::class.java).setup().use {
            assertEquals(SyncService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        }
    }

    @Test fun aRefusedStartIsLoggedNotThrown() {
        // Android 12+ refuses a foreground-service start from the background (ForegroundServiceStartNotAllowedException,
        // an IllegalStateException); a choice that lands after the app left the screen must not crash it.
        assertFalse(SyncService.guardedStart { throw IllegalStateException("not allowed") })
        assertTrue(SyncService.guardedStart { })
    }

    @Test fun signedOutStartsNothing() {
        store.deviceKey = null
        Robolectric.buildActivity(MainActivity::class.java).setup().use {
            assertNull(shadowOf(app).nextStartedService)
        }
    }

    @Test fun stopStopsTheSyncService() {
        SyncService.stop(app)
        assertEquals(SyncService::class.java.name, shadowOf(app).nextStoppedService?.component?.className)
    }

    @Test fun bootStartsSyncOnlyWhenSignedIn() {
        store.deviceKey = null
        dev.tandem.app.service.BootReceiver().onReceive(app, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
        store.deviceKey = "k"
        dev.tandem.app.service.BootReceiver().onReceive(app, android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED))
        assertEquals(SyncService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
    }
}
