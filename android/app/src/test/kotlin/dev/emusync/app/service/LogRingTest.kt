package dev.emusync.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRingTest {
    @Test fun keeps_newest_lines_up_to_capacity() {
        val r = LogRing(3) { 0L }
        (1..5).forEach { r.add("line $it") }
        assertEquals(3, r.size())
        assertEquals(listOf("line 3", "line 4", "line 5"), r.last(10).map { it.substringAfter(' ') })
    }

    @Test fun last_n_returns_newest_in_order() {
        val r = LogRing(10) { 0L }
        (1..5).forEach { r.add("l$it") }
        assertEquals(listOf("l4", "l5"), r.last(2).map { it.substringAfter(' ') })
    }

    @Test fun lines_are_timestamped() {
        val r = LogRing(2) { 0L }
        r.add("x")
        assertTrue(r.last(1)[0], r.last(1)[0].matches(Regex("""\d\d:\d\d:\d\d\.\d{3} x""")))
    }

    @Test fun lines_flow_holds_the_newest_window() {
        val r = LogRing(5, window = 3) { 0L }
        assertEquals(emptyList<String>(), r.lines.value)
        (1..4).forEach { r.add("l$it") }
        assertEquals(listOf("l2", "l3", "l4"), r.lines.value.map { it.substringAfter(' ') })
    }
}
