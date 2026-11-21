package dev.tandem.app.service

import dev.tandem.app.fs.ShizukuState
import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTextTest {
    @Test fun watching_counts_emulators() {
        assertEquals("Watching 6 emulators", statusText(ShizukuState.Ready, 6, 0))
        assertEquals("Watching 1 emulator", statusText(ShizukuState.Ready, 1, 0))
    }

    @Test fun shizuku_off_wins() {
        assertEquals("Shizuku is off: open Tandem", statusText(ShizukuState.NotRunning, 6, 3))
        assertEquals("Shizuku is off: open Tandem", statusText(ShizukuState.NotRunning, 0, 0, online = false, signedIn = false))
    }

    @Test fun shizuku_permission_missing() {
        assertEquals("Shizuku permission needed: open Tandem", statusText(ShizukuState.NoPermission, 0, 0))
    }

    @Test fun offline_shows_queue() {
        assertEquals("Offline: 3 changes queued", statusText(ShizukuState.Ready, 6, 3, online = false))
        assertEquals("Offline: 1 change queued", statusText(ShizukuState.Ready, 6, 1, online = false))
        assertEquals("Offline: 0 changes queued", statusText(ShizukuState.Ready, 6, 0, online = false))
    }

    @Test fun online_with_queue() {
        assertEquals("Watching 6 emulators, 2 changes queued", statusText(ShizukuState.Ready, 6, 2))
    }

    @Test fun not_signed_in() {
        assertEquals("Not signed in: open Tandem", statusText(ShizukuState.Ready, 0, 0, signedIn = false))
    }

    @Test fun no_emulators() {
        assertEquals("No emulators found", statusText(ShizukuState.Ready, 0, 0))
    }

    @Test fun removed_from_the_server_wins_over_everything() {
        assertEquals("Removed from the server: open Tandem", statusText(ShizukuState.NotRunning, 2, 1, online = false, revoked = true))
    }
}
