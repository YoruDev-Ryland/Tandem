package dev.emusync.app.service

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
}
