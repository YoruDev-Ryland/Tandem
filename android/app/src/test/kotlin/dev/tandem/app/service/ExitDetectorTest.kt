package dev.tandem.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExitDetectorTest {
    @Test fun alive_then_dead_is_a_transition_once() {
        val d = ExitDetector()
        assertFalse(d.observe("dolphin", true))
        assertFalse(d.observe("dolphin", true))
        assertTrue(d.observe("dolphin", false))
        assertFalse(d.observe("dolphin", false))
    }

    @Test fun first_observation_dead_is_not_a_transition() {
        val d = ExitDetector()
        assertFalse(d.observe("eden", false))
        assertFalse(d.observe("eden", true))
        assertTrue(d.observe("eden", false))
    }

    @Test fun ids_are_independent_and_retain_forgets() {
        val d = ExitDetector()
        d.observe("a", true); d.observe("b", true)
        assertTrue(d.observe("a", false))
        d.retain(setOf("a"))
        assertFalse(d.observe("b", false))
    }

    // follow-up 2: an asked-for apply whose emulator closed while the service was down finishes at session start
    @Test fun an_id_assumed_running_reports_an_exit_on_its_first_dead_observation() {
        val d = ExitDetector()
        d.assumeRunning(setOf("eden"))
        assertTrue(d.observe("eden", alive = false))
        assertFalse(d.observe("azahar", alive = false))
    }

    @Test fun an_id_assumed_running_that_is_alive_exits_later_as_usual() {
        val d = ExitDetector()
        d.assumeRunning(setOf("eden"))
        assertFalse(d.observe("eden", alive = true))
        assertTrue(d.observe("eden", alive = false))
    }
}
