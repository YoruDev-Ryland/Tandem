package dev.tandem.app.ui.screens

import dev.tandem.app.sync.ReceivedDelivery
import dev.tandem.app.ui.state.Loaded
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/** The words the app says when something went right, and the Activity lines without their device. */
class SuccessLinesTest {
    private fun entry(running: Set<String> = emptySet()) =
        conflictsUi(Loaded(listOf(conflict("i1", "Pokémon Sword")), Instant.EPOCH), "dev-thor", running, { "Eden" }, signedIn = true, zone = ZoneOffset.UTC)
            .entries.single()

    @Test fun a_kept_save_says_whose_it_kept() {
        val e = entry()
        assertEquals("Kept this Thor's save of Pokémon Sword", resolvedLine(e, e.keepMine))
        assertEquals("Kept Odin 3's save of Pokémon Sword", resolvedLine(e, e.keepOther))
        assertEquals("Kept both saves of Pokémon Sword", resolvedLine(e, e.keepBoth))
    }

    @Test fun with_the_emulator_open_it_says_when_it_lands() {
        val e = entry(running = setOf("eden"))
        assertEquals("Kept both saves of Pokémon Sword · applied when Eden closes", resolvedLine(e, e.keepBoth))
    }

    @Test fun a_landed_delivery_says_what_is_in_place_and_from_where() {
        val row = shelfGame("eden", "0100ABF008968000", "Pokémon Sword", dev.tandem.app.model.DeviceGameState.HasIt, dev.tandem.app.model.DeviceGameState.Newest,
            devices = listOf("dev-thor" to "Thor", "dev-odin" to "Odin 3")).copy(newestOnDeviceName = "Odin 3")
        assertEquals("Pokémon Sword from Odin 3 is in place.", receivedLine(ReceivedDelivery("d", "eden", "0100ABF008968000", null), "Eden", row))
        assertEquals("Pokémon Sword is in place.", receivedLine(ReceivedDelivery("d", "eden", "x", "Pokémon Sword"), "Eden", null))
        assertEquals("Emerald is in place.", receivedLine(ReceivedDelivery("d", "retroarch", "retroarch/Emerald", null), "RetroArch", null))
        assertEquals("Eden settings are in place.", receivedLine(ReceivedDelivery("d", "eden", null, null), "Eden", null))
    }

    @Test fun activity_lines_lose_the_device_that_has_its_own_column() {
        assertEquals("Uploaded save for Pokémon Sword", withoutDevice("AYN Thor uploaded save for Pokémon Sword", "AYN Thor"))
        assertEquals("Eden config was replaced by another config", withoutDevice("Thor's Eden config was replaced by another config", "Thor"))
        assertEquals("Created eden group Handhelds from Thor", withoutDevice("Created eden group Handhelds from Thor", "Thor"))
        assertEquals("Thorough check", withoutDevice("Thorough check", "Thor"))
        assertEquals("Thor received X", withoutDevice("Thor received X", null))
    }
}
