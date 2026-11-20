package dev.emusync.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkPlannerTest {
    private fun automatic(p: WorkPlanner) = listOf(
        p.sessionStarted(), p.deliveriesEvent(), p.poll(), p.streamOpened(false), p.streamOpened(true),
        p.tick(), p.monitor(), p.periodicScan(), p.explicitScan(),
    ).flatten()

    @Test fun auto_apply_off_never_applies_except_explicitly() {
        val p = WorkPlanner(autoApply = false)
        assertFalse(automatic(p).contains(SyncWork.ApplyAll))
        assertFalse(p.applyOnExit())
        assertEquals(listOf(SyncWork.ApplyAll), p.explicitApply())
    }

    @Test fun auto_apply_off_still_scans_on_reconnect() {
        val p = WorkPlanner(autoApply = false)
        assertEquals(listOf(SyncWork.FullScan), p.streamOpened(reconnect = true))
        assertEquals(emptyList<SyncWork>(), p.streamOpened(reconnect = false))
    }

    @Test fun auto_apply_on_applies_on_events_poll_open_and_exit() {
        val p = WorkPlanner(autoApply = true)
        assertEquals(listOf(SyncWork.ApplyAll), p.deliveriesEvent())
        assertEquals(listOf(SyncWork.ApplyAll), p.poll())
        assertEquals(listOf(SyncWork.ApplyAll), p.sessionStarted())
        assertEquals(listOf(SyncWork.FullScan, SyncWork.ApplyAll), p.streamOpened(reconnect = true))
        assertTrue(p.applyOnExit())
    }
}
