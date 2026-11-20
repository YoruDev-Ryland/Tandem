package dev.emusync.app.service

import dev.emusync.app.sync.WaitingDelivery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only the live session may publish; a dying session's late writes are dropped. */
class SessionFlowsTest {
    private val w = WaitingDelivery("d1", "eden", "Eden", "0100A", "Title", null)

    @Test fun live_session_publishes() {
        val f = SessionFlows()
        val t = f.begin()
        f.publishWaiting(t, listOf(w))
        f.publishRunning(t, setOf("eden"))
        assertEquals(listOf(w), f.waiting.value)
        assertEquals(setOf("eden"), f.running.value)
    }

    @Test fun ending_clears_and_late_writes_of_the_ended_session_are_dropped() {
        val f = SessionFlows()
        val t = f.begin()
        f.publishWaiting(t, listOf(w)); f.publishRunning(t, setOf("eden"))
        f.end(t)
        assertTrue(f.waiting.value.isEmpty()); assertTrue(f.running.value.isEmpty())
        f.publishWaiting(t, listOf(w)); f.publishRunning(t, setOf("eden"))   // a blocked call returning late
        assertTrue(f.waiting.value.isEmpty()); assertTrue(f.running.value.isEmpty())
    }

    @Test fun ending_everything_drops_the_live_session_too() {
        val f = SessionFlows()
        val t = f.begin()
        f.publishRunning(t, setOf("eden"))
        f.end(null)   // onDestroy
        f.publishRunning(t, setOf("eden"))
        assertTrue(f.running.value.isEmpty())
    }

    @Test fun an_old_session_cannot_write_into_a_new_one() {
        val f = SessionFlows()
        val old = f.begin()
        f.end(old)
        val now = f.begin()
        f.publishRunning(now, setOf("ppsspp"))
        f.publishRunning(old, setOf("eden"))
        f.end(old)   // a duplicate end of the old session doesn't clear the new one
        assertEquals(setOf("ppsspp"), f.running.value)
    }

    @Test fun ifLive_runs_only_for_the_live_session() {
        val f = SessionFlows()
        val t = f.begin()
        var ran = 0
        f.ifLive(t) { ran++ }
        f.end(t)
        f.ifLive(t) { ran++ }
        assertEquals(1, ran)
    }
}
