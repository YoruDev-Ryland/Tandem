package dev.tandem.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun release(code: Int) = Release(
    "v$code", "$code", code,
    ReleaseAsset(APK_ASSET, "https://x/tandem.apk", 10), ReleaseAsset(SHA_ASSET, "https://x/tandem.apk.sha256", 77),
)

class UpdateDecisionTest {
    @Test fun aHigherCodeIsNewer() {
        val r = release(3_000)
        assertEquals(Decision.Newer(r), decide(r, installedCode = 2_005, debug = false))
    }

    @Test fun theSameOrALowerCodeIsUpToDate() {
        assertEquals(Decision.UpToDate, decide(release(3_000), installedCode = 3_000, debug = false))
        assertEquals(Decision.UpToDate, decide(release(3_000), installedCode = 1_000_000, debug = false))
    }

    @Test fun aDebugBuildNeverUpdates() {
        // A debug build counts commits (a low code) and is signed with the debug key: every release looks newer.
        assertEquals(Decision.Off, decide(release(3_000), installedCode = 412, debug = true))
    }

    @Test fun theFirstCheckIsAlwaysDue() {
        assertTrue(CheckSchedule.due(now = 1_000, lastCheckAt = null, sessionStart = false))
        assertTrue(CheckSchedule.due(now = 1_000, lastCheckAt = null, sessionStart = true))
    }

    @Test fun aPeriodicCheckIsDueEverySixHours() {
        val last = 100_000_000L
        assertFalse(CheckSchedule.due(last + CheckSchedule.INTERVAL_MS - 1, last, sessionStart = false))
        assertTrue(CheckSchedule.due(last + CheckSchedule.INTERVAL_MS, last, sessionStart = false))
    }

    @Test fun aSessionStartChecksUnlessOneRanLately() {
        // A session restarts whenever Shizuku flaps: those restarts must not eat GitHub's 60 an hour.
        val last = 100_000_000L
        assertFalse(CheckSchedule.due(last + CheckSchedule.SESSION_GAP_MS - 1, last, sessionStart = true))
        assertTrue(CheckSchedule.due(last + CheckSchedule.SESSION_GAP_MS, last, sessionStart = true))
        assertTrue(CheckSchedule.SESSION_GAP_MS * 60 >= 3_600_000L * 1)   // at most a few an hour however it flaps
    }

    @Test fun aClockThatWentBackMakesACheckDue() {
        assertTrue(CheckSchedule.due(now = 5_000, lastCheckAt = 9_000_000, sessionStart = false))
    }
}
