package dev.tandem.app.ui.state

import dev.tandem.app.model.DeviceGameState
import dev.tandem.app.model.GameDeviceState
import dev.tandem.app.model.GameRow
import dev.tandem.app.sync.WaitingDelivery
import dev.tandem.app.service.ReceivedAt
import dev.tandem.app.sync.ReceivedDelivery
import dev.tandem.app.ui.kit.Presence
import dev.tandem.app.ui.kit.Tone
import dev.tandem.app.ui.screens.shelfUi
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HERE = "dev-odin"

private fun dev(id: String, name: String, state: DeviceGameState) = GameDeviceState(id, name, state)

private fun row(
    key: String,
    name: String?,
    emu: String = "dolphin",
    conflict: Boolean = false,
    devices: List<GameDeviceState> = listOf(dev(HERE, "Odin3", DeviceGameState.Newest), dev("dev-thor", "AYN Thor", DeviceGameState.Behind)),
    newestOn: String? = HERE,
) = GameRow(
    emulatorId = emu, gameKey = key, displayName = name, installedOn = devices.map { it.deviceId }, stateCount = 0,
    openConflict = conflict, devices = devices, newestOnDeviceId = newestOn, newestUtc = "2026-11-22T10:00:00Z",
    newestOnDeviceName = devices.firstOrNull { it.deviceId == newestOn }?.deviceName,
)

private val emulators = listOf(
    EmulatorRowUi("eden", "Eden", EmuRowState.Saves(2), 2),
    EmulatorRowUi("dolphin", "Dolphin", EmuRowState.Open(0), 3),
    EmulatorRowUi("melonds", "melonDS", EmuRowState.NotSetUp, 0),
)

private fun five() = listOf(
    dev(HERE, "Odin3", DeviceGameState.Newest),
    dev("d-a", "Ally", DeviceGameState.Behind),
    dev("d-b", "Beta", DeviceGameState.NotInstalled),
    dev("d-c", "Ceres", DeviceGameState.Behind),
    dev("d-d", "Deck", DeviceGameState.HasIt),
)

class ShelfTest {
    @Test fun order_conflicts_then_name_then_unnamed_by_key() {
        val rows = listOf(row("kb", "b"), row("ka", "a", conflict = true), row("Z", null), row("kc", "C"), row("A", null))
        assertEquals(listOf("ka", "kb", "kc", "A", "Z"), shelfOrder(rows).map { it.gameKey })
    }

    // The same fixture as the web's shelf.test.ts ("orders names by code point ignoring case").
    @Test fun order_names_by_code_point_ignoring_case() {
        val names = listOf("Zelda", "Ōkami", "okami", "Éclair", "eve", "Apple", "_Underscore")
        val rows = names.mapIndexed { i, n -> row("k$i", n) }
        assertEquals(listOf("_Underscore", "Apple", "eve", "okami", "Zelda", "Éclair", "Ōkami"), shelfOrder(rows).map { it.displayName })
    }

    // The same table as the web's shelf.test.ts ("rail labels: the shared table").
    @Test fun rail_label_drops_ayn_and_cuts_to_five() {
        val table = listOf(
            "AYN Thor" to "THOR", "ayn thor" to "THOR", "ayn odin 3" to "ODIN3", "Odin3" to "ODIN3", "Odin 3" to "ODIN3",
            "Steam Deck" to "STEAM", "Odin3 (2)" to "ODIN3·2", "Retroid Pocket 5 (3)" to "RETRO·3",
        )
        for ((name, label) in table) assertEquals(name, label, railLabel(name))
    }

    @Test fun rail_labels_number_names_that_cut_to_the_same_label() {
        assertEquals(listOf("ODIN3", "ODIN3·2", "ODIN3·3"), railLabels(listOf("Odin 3", "Odin3 Pro", "Odin3 (2)")))
        assertEquals(listOf("THOR", "ODIN3", "ODIN3·2"), railLabels(listOf("AYN Thor", "Odin3", "Odin3 (2)")))
    }

    @Test fun rail_this_device_first_then_by_name_max_three() {
        val rows = listOf(row("k", "K", devices = five()))
        val rail = railRows(rows, HERE)
        assertEquals(3, rail.size)
        assertEquals(listOf(HERE, "d-a", "d-b"), rail.map { it.deviceId })
        assertEquals(listOf("ODIN3", "ALLY", "BETA"), rail.map { it.label })
    }

    @Test fun rail_has_this_device_even_when_no_game_lists_it() {
        val rows = listOf(row("k", "K", devices = listOf(dev("dev-thor", "AYN Thor", DeviceGameState.Newest))))
        val rail = railRows(rows, HERE, thisDeviceName = "Odin3")
        assertEquals(listOf(HERE, "dev-thor"), rail.map { it.deviceId })
        assertEquals(listOf("ODIN3", "THOR"), rail.map { it.label })
    }

    @Test fun duplicate_device_names_are_disambiguated() {
        val devices = listOf(
            dev(HERE, "AYN Thor", DeviceGameState.Newest),
            dev("o1", "Odin3", DeviceGameState.Newest),
            dev("o2", "Odin3", DeviceGameState.Behind),
        )
        val rail = railRows(listOf(row("k", "K", devices = devices)), HERE)
        // The names follow Presence.kt's columns: the second "Odin3" is "Odin3 (2)".
        assertEquals(listOf("AYN Thor", "Odin3", "Odin3 (2)"), rail.map { it.name })
        assertEquals(deviceColumns(listOf(row("k", "K", devices = devices)), HERE).map { it.name }, rail.drop(1).map { it.name })
        assertEquals(3, rail.map { it.label }.toSet().size)
        assertEquals("ODIN3", rail[1].label)
        assertEquals("ODIN3·2", rail[2].label)
    }

    @Test fun this_device_and_another_with_its_name_are_told_apart() {
        val devices = listOf(dev(HERE, "Odin3", DeviceGameState.Newest), dev("o1", "Odin3", DeviceGameState.Behind))
        val r = row("k", "K", devices = devices)
        val rail = railRows(listOf(r), HERE)
        assertEquals(listOf(HERE, "o1"), rail.map { it.deviceId })
        assertEquals(listOf("Odin3", "Odin3 (2)"), rail.map { it.name })
        assertEquals(listOf("ODIN3", "ODIN3·2"), rail.map { it.label })
        val spine = spineOf(r, HERE, "Dolphin", rail, null)
        assertEquals(listOf("Odin3", "Odin3 (2)"), spine.card.lines.map { it.name })
        assertEquals(2, spine.marks.map { it.description }.toSet().size)
    }

    @Test fun card_lists_every_device_even_beyond_rail() {
        val r = row("k", "K", devices = five())
        val ui = shelfUi(emulators, Loaded(listOf(r), Instant.EPOCH), emptyList(), HERE, signedIn = true)
        val shelf = ui.shelves.first { it.tab.id == "dolphin" }
        assertEquals(3, ui.rail.size)
        assertEquals(3, shelf.spines.single().marks.size)
        val lines = shelf.spines.single().card.lines
        assertEquals(listOf("Odin3", "Ally", "Beta", "Ceres", "Deck"), lines.map { it.name })
        assertEquals(listOf(Presence.Newest, Presence.Older, Presence.Absent, Presence.Older, Presence.Newest), lines.map { it.presence })
    }

    @Test fun card_words_reuse_the_state_copy() {
        val devices = listOf(
            dev(HERE, "Odin3", DeviceGameState.Waiting),
            dev("t", "AYN Thor", DeviceGameState.Newest),
            dev("a", "Ally", DeviceGameState.Behind),
            dev("b", "Beta", DeviceGameState.NotInstalled),
            dev("c", "Ceres", DeviceGameState.Pending),
            dev("d", "Deck", DeviceGameState.Differs),
        )
        val r = row("GSXE69", "SSX 3", devices = devices, newestOn = "t")
        val spine = spineOf(r, HERE, "Dolphin", railRows(listOf(r), HERE), core = null)
        val c = card(spine, r, HERE, "Dolphin", now = Instant.parse("2026-11-22T12:00:00Z"))
        assertEquals("GAMECUBE · DOLPHIN", c.kicker)
        assertEquals("SSX 3", c.title)
        assertEquals("Waiting for Dolphin to close.", c.sentence)
        assertEquals(Tone.Wait, c.tone)
        val words = c.lines.associate { it.name to it.words }
        assertEquals("waiting for Dolphin to close", words["Odin3"])
        assertEquals("newest · 2 h ago", words["AYN Thor"])
        assertEquals("older copy", words["Ally"])
        assertEquals("not installed", words["Beta"])
        assertEquals("on its way", words["Ceres"])
        assertEquals("conflict", words["Deck"])
        // Not on this device, for this device.
        val absent = row("k", "K", devices = listOf(dev("t", "AYN Thor", DeviceGameState.Newest)), newestOn = "t")
        val c2 = card(spineOf(absent, HERE, "Dolphin", railRows(listOf(absent), HERE, "Odin3"), null), absent, HERE, "Dolphin",
            devices = railDevices(listOf(absent), HERE, "Odin3"))
        assertEquals("not on this device", c2.lines.first().words)
        assertEquals("Not on this device.", c2.sentence)
    }

    @Test fun unknown_names_use_the_bare_key_and_say_so() {
        val r = row("retroarch/Pokemon - Emerald Version", null, emu = "retroarch")
        val spine = spineOf(r, HERE, "RetroArch", railRows(listOf(r), HERE), core = "mgba")
        assertEquals("Pokemon - Emerald Version", spine.title)
        assertTrue(spine.unnamed)
        assertEquals("GBA", spine.system)
    }

    @Test fun the_servers_system_names_a_coreless_retroarch_game() {
        val r = row("retroarch/Golden Sun", "Golden Sun", emu = "retroarch").copy(system = "GBA")
        assertEquals("GBA", spineOf(r, HERE, "RetroArch", railRows(listOf(r), HERE), core = null).system)
        val none = row("retroarch/Golden Sun", "Golden Sun", emu = "retroarch")
        assertEquals(null, spineOf(none, HERE, "RetroArch", railRows(listOf(none), HERE), core = null).system)
    }

    @Test fun cursor_remembers_per_emulator() {
        val ui = shelfUi(emulators, Loaded(listOf(row("a", "Alpha"), row("s", "SSX 3"), row("z", "Zelda"), row("p", "Pokémon", emu = "melonds")), Instant.EPOCH),
            emptyList(), HERE, signedIn = true)
        val dolphin = ui.shelves.first { it.tab.id == "dolphin" }.spines
        val melon = ui.shelves.first { it.tab.id == "melonds" }.spines
        val cursor = ShelfCursor()
        assertEquals(0, cursor.indexFor("dolphin", dolphin))
        cursor.moveTo("dolphin", "s")
        assertEquals(0, cursor.indexFor("melonds", melon))
        cursor.moveTo("melonds", "p")
        assertEquals(1, cursor.indexFor("dolphin", dolphin))
        assertEquals(0, cursor.indexFor("melonds", melon))
    }

    @Test fun cursor_clamps_when_game_disappears() {
        fun spines(vararg keys: String) = keys.map { Spine("dolphin", it, it, false, "GC", emptyList(), emptyCard) }
        val cursor = ShelfCursor()
        cursor.indexFor("dolphin", spines("a", "b", "c"))
        cursor.moveTo("dolphin", "c")
        assertEquals(2, cursor.indexFor("dolphin", spines("a", "b", "c")))
        assertEquals(1, cursor.indexFor("dolphin", spines("a", "b")))
        assertEquals(0, cursor.indexFor("dolphin", spines()))
        // A middle game goes: the nearest earlier one that is left.
        cursor.moveTo("dolphin", "b")
        assertEquals(1, cursor.indexFor("dolphin", spines("a", "b", "c")))
        assertEquals(0, cursor.indexFor("dolphin", spines("a", "c")))
    }

    @Test fun not_set_up_emulator_still_has_spines() {
        val r = row("p", "Pokémon Platinum", emu = "melonds", devices = listOf(dev("dev-thor", "AYN Thor", DeviceGameState.Newest)), newestOn = "dev-thor")
        val ui = shelfUi(emulators, Loaded(listOf(r), Instant.EPOCH), emptyList(), HERE, signedIn = true, thisDeviceName = "Odin3")
        val shelf = ui.shelves.first { it.tab.id == "melonds" }
        assertTrue(shelf.tab.dimmed)
        assertEquals("not set up", shelf.tab.state)
        assertEquals(1, shelf.spines.size)
        assertEquals(Presence.Absent, shelf.spines.single().marks.first().presence)   // this device's row
        assertNull(shelf.emptyLine)
        assertEquals(false, ui.shelves.first { it.tab.id == "dolphin" }.tab.dimmed)
    }

    @Test fun empty_emulator_line() {
        val ui = shelfUi(emulators, Loaded(emptyList(), Instant.EPOCH), emptyList(), HERE, signedIn = true)
        assertEquals("No games for Dolphin yet.", ui.shelves.first { it.tab.id == "dolphin" }.emptyLine)
        assertEquals(0, ui.shelves.first { it.tab.id == "dolphin" }.count)
    }

    @Test fun notice_latest_wait_or_received_with_more_count() {
        val ssx = row("GSXE69", "SSX 3", devices = listOf(dev(HERE, "Odin3", DeviceGameState.Waiting), dev("t", "AYN Thor", DeviceGameState.Newest)), newestOn = "t")
        val mp = row("GM8E01", "Metroid Prime", devices = listOf(dev(HERE, "Odin3", DeviceGameState.Waiting), dev("t", "AYN Thor", DeviceGameState.Newest)), newestOn = "t")
        // The service's own held delivery comes first, then what the server says waits, each game once.
        val held = WaitingDelivery("d1", "dolphin", "Dolphin", "GSXE69", "SSX 3", null)
        val ui = shelfUi(emulators, Loaded(listOf(ssx, mp), Instant.EPOCH), listOf(held), HERE, signedIn = true)
        val n = ui.shelves.first { it.tab.id == "dolphin" }.notice!!
        assertEquals("Close Dolphin to get the newer SSX 3 save from AYN Thor.", n.text)
        assertEquals("Nothing is written while Dolphin is open.", n.rest)
        assertEquals(Tone.Wait, n.tone)
        assertEquals(1, n.more)
        val tab = ui.shelves.first { it.tab.id == "dolphin" }.tab
        assertEquals("Open · 2 waiting", tab.state)
        assertEquals(Tone.Wait, tab.tone)

        // Nothing waiting: the latest received line, in ink.
        val landed = Instant.parse("2026-11-20T21:58:49Z")
        val got = listOf(
            ReceivedAt(ReceivedDelivery("d1", "dolphin", "GM8E01", "Metroid Prime"), landed),
            ReceivedAt(ReceivedDelivery("d2", "dolphin", "GSXE69", "SSX 3"), landed),
        )
        val done = listOf(ssx, mp).map { r -> r.copy(devices = r.devices.map { if (it.deviceId == HERE) it.copy(state = DeviceGameState.HasIt) else it }) }
        val ui2 = shelfUi(emulators, Loaded(done, landed), emptyList(), HERE, signedIn = true, received = got)
        val n2 = ui2.shelves.first { it.tab.id == "dolphin" }.notice!!
        assertEquals("SSX 3 from AYN Thor is in place.", n2.text)
        assertEquals(Tone.Ink, n2.tone)
        assertEquals(1, n2.more)
        // The next refresh, a cycle later, clears it; with nothing pending the line is empty.
        assertNull(shelfUi(emulators, Loaded(done, landed.plusSeconds(60)), emptyList(), HERE, true, received = got).shelves.first { it.tab.id == "dolphin" }.notice)
    }

    @Test fun keyless_waiting_notice() {
        val w = WaitingDelivery("d9", "dolphin", "Dolphin", null, null, null)
        val ui = shelfUi(emulators, Loaded(emptyList(), Instant.EPOCH), listOf(w), HERE, signedIn = true)
        val n = ui.shelves.first { it.tab.id == "dolphin" }.notice!!
        assertEquals("1 save waiting for Dolphin to close", n.text)
        assertEquals(Tone.Wait, n.tone)
        assertEquals(0, n.more)
        val two = shelfUi(emulators, Loaded(emptyList(), Instant.EPOCH), listOf(w, w.copy(deliveryId = "d10")), HERE, signedIn = true)
        assertEquals("2 saves waiting for Dolphin to close", two.shelves.first { it.tab.id == "dolphin" }.notice!!.text)
    }

    @Test fun a_local_wait_without_a_row_names_another_device() {
        val w = WaitingDelivery("d1", "dolphin", "Dolphin", "GSXE69", "SSX 3", null)
        val ui = shelfUi(emulators, Loaded(emptyList(), Instant.EPOCH), listOf(w), HERE, signedIn = true)
        assertEquals("Close Dolphin to get the newer SSX 3 save from another device.", ui.shelves.first { it.tab.id == "dolphin" }.notice!!.text)
    }

    @Test fun spines_follow_the_shelf_order_and_marks_follow_the_rail() {
        val rows = listOf(row("b", "Beta"), row("a", "Alpha", conflict = true))
        val ui = shelfUi(emulators, Loaded(rows, Instant.EPOCH), emptyList(), HERE, signedIn = true)
        val s = ui.shelves.first { it.tab.id == "dolphin" }
        assertEquals(listOf("a", "b"), s.spines.map { it.gameKey })
        assertEquals(listOf(HERE, "dev-thor"), ui.rail.map { it.deviceId })
        assertEquals(listOf(Presence.Newest, Presence.Older), s.spines[1].marks.map { it.presence })
        assertEquals("Odin3 has the newest save", s.spines[1].marks[0].description)
        assertEquals(2, s.count)
        assertNotEquals(-1, ui.emulator)
    }

    @Test fun signed_out_has_no_spines() {
        val ui = shelfUi(emulators, Loaded(listOf(row("a", "A")), Instant.EPOCH), emptyList(), HERE, signedIn = false)
        assertTrue(ui.shelves.all { it.spines.isEmpty() })
        assertEquals(false, ui.signedIn)
    }
}

private val emptyCard = ShelfCard("", "", false, "", Tone.Ink2, emptyList())
