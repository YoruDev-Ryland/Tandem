package dev.tandem.app.ui.state

import dev.tandem.app.model.DeviceGameState
import dev.tandem.app.model.GameDeviceState
import dev.tandem.app.model.GameRow
import dev.tandem.app.ui.kit.Presence
import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceTest {
    private fun row(vararg d: Pair<String, DeviceGameState>, newest: String? = "2026-11-20T10:00:00Z") = GameRow(
        emulatorId = "eden", gameKey = "k", installedOn = d.map { it.first }, stateCount = 0, openConflict = false,
        newestUtc = newest, devices = d.map { (id, s) -> GameDeviceState(id, id.replaceFirstChar { it.uppercase() }, s) },
    )

    @Test fun every_state_has_its_mark() {
        val r = row(
            "a" to DeviceGameState.Newest, "b" to DeviceGameState.HasIt, "c" to DeviceGameState.Behind, "d" to DeviceGameState.Pending,
            "e" to DeviceGameState.Waiting, "f" to DeviceGameState.Differs, "g" to DeviceGameState.NotInstalled,
        )
        assertEquals(
            listOf(Presence.Newest, Presence.Newest, Presence.Older, Presence.Coming, Presence.Waiting, Presence.Conflict, Presence.Absent, Presence.Absent),
            listOf("a", "b", "c", "d", "e", "f", "g", "missing").map { presenceOf(r, it) },
        )
        // With no save anywhere yet, "has it" only means installed.
        assertEquals(Presence.Empty, presenceOf(row("a" to DeviceGameState.HasIt, newest = null), "a"))
    }

    @Test fun columns_are_the_other_devices_by_name_with_repeats_numbered() {
        val rows = listOf(row("thor" to DeviceGameState.Newest, "odin" to DeviceGameState.Behind), row("ayn" to DeviceGameState.Newest))
            .plus(row("x2" to DeviceGameState.Newest).copy(devices = listOf(GameDeviceState("x2", "Odin", DeviceGameState.Newest))))
        assertEquals(
            listOf(DeviceColumn("ayn", "Ayn"), DeviceColumn("odin", "Odin"), DeviceColumn("x2", "Odin (2)")),
            deviceColumns(rows, self = "thor"),
        )
    }

    @Test fun marks_read_as_sentences() {
        assertEquals("Odin 3 has the newest save", presenceText(Presence.Newest, "Odin 3", "Eden"))
        assertEquals("The newest save reaches Odin 3 when Eden closes", presenceText(Presence.Waiting, "Odin 3", "Eden"))
        assertEquals("Not installed on Odin 3", presenceText(Presence.Absent, "Odin 3", "Eden"))
        assertEquals("Odin 3 has one of two different saves", presenceText(Presence.Conflict, "Odin 3", "Eden"))
    }
}
