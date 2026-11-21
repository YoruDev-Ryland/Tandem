package dev.tandem.app.ui.kit

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadoutTest {
    @Test fun the_clock_follows_the_devices_12_or_24_hour_setting() {
        val at = Instant.parse("2026-11-21T14:05:40Z")
        assertEquals("14:05", readoutTime(at, ZoneOffset.UTC, is24Hour = true))
        assertEquals("2:05", readoutTime(at, ZoneOffset.UTC, is24Hour = false))
    }

    @Test fun it_reads_as_time_and_battery() {
        assertEquals("14:05, battery 82 percent", readoutDescription(Readout("14:05", 82)))
        assertEquals("14:05, battery 100 percent, charging", readoutDescription(Readout("14:05", 100, charging = true)))
        assertEquals("14:05", readoutDescription(Readout("14:05", null)))
    }
}
