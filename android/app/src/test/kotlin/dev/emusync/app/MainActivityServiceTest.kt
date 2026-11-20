package dev.emusync.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.emusync.app.service.AppGraph
import dev.emusync.app.service.SyncService
import org.junit.After
import org.junit.Assert.assertEquals
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

    @After fun signOut() { store.deviceKey = null }

    @Test fun signedInStartsTheSyncService() {
        store.deviceKey = "test-key"
        Robolectric.buildActivity(MainActivity::class.java).setup().use {
            assertEquals(SyncService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
        }
    }

    @Test fun signedOutStartsNothing() {
        store.deviceKey = null
        Robolectric.buildActivity(MainActivity::class.java).setup().use {
            assertNull(shadowOf(app).nextStartedService)
        }
    }
}
