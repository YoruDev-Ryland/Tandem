package dev.tandem.app.ui.screens

import dev.tandem.app.model.DeviceGameState
import dev.tandem.app.model.DeviceGameState.Behind
import dev.tandem.app.model.DeviceGameState.Differs
import dev.tandem.app.model.DeviceGameState.HasIt
import dev.tandem.app.model.DeviceGameState.Newest
import dev.tandem.app.model.DeviceGameState.NotInstalled
import dev.tandem.app.model.DeviceGameState.Waiting
import dev.tandem.app.model.GameDeviceState
import dev.tandem.app.model.GameRow
import dev.tandem.app.sync.WaitingDelivery
import dev.tandem.app.ui.state.EmuRowState
import dev.tandem.app.ui.state.EmulatorRowUi
import dev.tandem.app.ui.state.Loaded
import dev.tandem.app.ui.state.ShelfUi
import java.time.Instant

/** The shelf's test data: the mockup's library (shelf-status.html, option 1), on the Odin 3 next to an AYN Thor. */
internal const val SHELF_ODIN = "dev-odin"
internal const val SHELF_THOR = "dev-thor"

/** Two hours after the newest saves below, so the card reads "newest · 2 h ago". */
internal val NOW: Instant = Instant.parse("2026-11-22T12:00:00Z")

internal val shelfEmulators = listOf(
    EmulatorRowUi("eden", "Eden", EmuRowState.Saves(2), 2),
    EmulatorRowUi("dolphin", "Dolphin", EmuRowState.Open(1), 9),
    EmulatorRowUi("retroarch", "RetroArch", EmuRowState.Saves(10), 10),
    EmulatorRowUi("melonds", "melonDS", EmuRowState.NotSetUp, 0),
    EmulatorRowUi("azahar", "Azahar", EmuRowState.Saves(4), 4),
    EmulatorRowUi("ppsspp", "PPSSPP", EmuRowState.NotSetUp, 0),
    EmulatorRowUi("vita3k", "Vita3K", EmuRowState.NotSetUp, 0),
)

/** A game as [devices] hold it ([states] in that order), the newest on the first device that is Newest. */
internal fun shelfGame(
    emu: String,
    key: String,
    name: String?,
    vararg states: DeviceGameState,
    devices: List<Pair<String, String>> = listOf(SHELF_ODIN to "Odin3", SHELF_THOR to "AYN Thor"),
): GameRow {
    val ds = devices.zip(states.toList()).map { (d, s) -> GameDeviceState(d.first, d.second, s) }
    val newest = ds.firstOrNull { it.state == Newest } ?: ds.firstOrNull { it.state == HasIt }
    return GameRow(
        emulatorId = emu, gameKey = key, displayName = name, installedOn = ds.filter { it.state != NotInstalled }.map { it.deviceId },
        stateCount = 1, openConflict = states.any { it == Differs }, devices = ds,
        newestOnDeviceId = newest?.deviceId, newestOnDeviceName = newest?.deviceName, newestUtc = "2026-11-22T10:00:00Z",
    )
}

internal val dolphinGames = listOf(
    shelfGame("dolphin", "GM8E01", "Metroid Prime", Newest, Behind),
    shelfGame("dolphin", "GR8E69", "Medal of Honor: Rising Sun", Behind, Newest),
    shelfGame("dolphin", "GSXE69", "SSX 3", Waiting, Newest),
    shelfGame("dolphin", "GEAE8P", "Skies of Arcadia Legends", HasIt, Newest),
    shelfGame("dolphin", "GSAE01", "Star Fox Adventures", NotInstalled, Newest),
    shelfGame("dolphin", "GZLE01", "The Legend of Zelda: The Wind Waker", Newest, HasIt),
    shelfGame("dolphin", "GPVE01", "Pikmin 2", Newest, NotInstalled),
    shelfGame("dolphin", "RMGE", "Super Mario Galaxy", HasIt, Newest),
    shelfGame("dolphin", "SOUE", "The Legend of Zelda: Skyward Sword", Behind, Newest),
)

internal val retroarchGames = listOf(
    shelfGame("retroarch", "gba/Castlevania - Aria of Sorrow", "Castlevania - Aria of Sorrow", Newest, NotInstalled),
    shelfGame("retroarch", "gba/Final Fantasy IV Advance", "Final Fantasy IV Advance", Differs, Differs),
    shelfGame("retroarch", "snes/Chrono Trigger", "Chrono Trigger", Newest, HasIt),
    shelfGame("retroarch", "gb/Tetris", "Tetris", Newest, Behind),
    shelfGame("retroarch", "gbc/Pokemon - Crystal Version", "Pokémon Crystal", Behind, Newest),
    shelfGame("retroarch", "nes/Super Mario Bros. 3", "Super Mario Bros. 3", Newest, Newest),
    shelfGame("retroarch", "n64/The Legend of Zelda - Ocarina of Time", "Ocarina of Time", HasIt, Newest),
    shelfGame("retroarch", "psx/Final Fantasy VII", "Final Fantasy VII", Newest, NotInstalled),
    shelfGame("retroarch", "md/Sonic the Hedgehog 2", "Sonic the Hedgehog 2", Newest, Newest),
    shelfGame("retroarch", "gba/Pokemon - Emerald Version", null, Newest, Behind),
)

private val four = listOf(SHELF_ODIN to "Odin3", SHELF_THOR to "AYN Thor", "dev-rp5" to "Retroid Pocket 5", "dev-deck" to "Steam Deck")

internal val azaharGames = listOf(
    shelfGame("azahar", "0004000000055D00", "Pokémon X", NotInstalled, Newest, Behind, NotInstalled, devices = four),
    shelfGame("azahar", "00040000001B5000", "Pokémon Ultra Sun", Newest, HasIt, NotInstalled, Behind, devices = four),
    shelfGame("azahar", "00040000000A0500", "Fire Emblem Awakening", Behind, Newest, Newest, NotInstalled, devices = four),
    shelfGame("azahar", "0004000000030800", "Kid Icarus: Uprising", Newest, NotInstalled, NotInstalled, Behind, devices = four),
)

internal val ssxWaiting = WaitingDelivery("d1", "dolphin", "Dolphin", "GSXE69", "SSX 3", null)

/** The whole library, as the Library tab builds it on the Odin 3. */
internal fun shelfSample(
    games: List<GameRow> = dolphinGames + retroarchGames,
    waiting: List<WaitingDelivery> = listOf(ssxWaiting),
): ShelfUi = shelfUi(shelfEmulators, Loaded(games, NOW), waiting, SHELF_ODIN, signedIn = true, thisDeviceName = "Odin3", now = NOW)
