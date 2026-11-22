package dev.tandem.app.ui.state

import dev.tandem.app.api.ApiException
import dev.tandem.app.model.DeviceGameState
import dev.tandem.app.model.GameRow
import dev.tandem.app.ui.kit.Presence
import dev.tandem.app.ui.kit.Tone
import java.time.Duration
import java.time.Instant

/*
 * The Library tab's shelf, as data: one shelf per emulator, a spine per game coloured by its system, the pulled-out
 * card for the game under the cursor, and the status rail under the shelf with one row of presence marks per device.
 * Everything here is pure; DeviceViewModel's shelfUi() puts it together from the server's games and the service's
 * deliveries. The rail is a new layout of the existing presence states (presenceOf, presenceText), not new states.
 */

/** One device's presence mark under a game, and what it reads as. */
data class PresenceCell(val presence: Presence, val description: String)

/** At most this many devices get a rail row; the card lists the rest. */
const val MAX_RAIL = 3

/**
 * The shelf's order, the same as the web's Games page: games with an open conflict first, then by name ignoring case,
 * then the games with no name, by key.
 */
fun shelfOrder(rows: List<GameRow>): List<GameRow> = rows.sortedWith(
    compareBy<GameRow> { !it.openConflict }
        .thenBy { it.displayName == null }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName ?: "" }
        .thenBy { it.gameKey },
)

/** A rail row's label: the device name without a leading "AYN ", without spaces, in capitals, cut to 5. */
fun railLabel(deviceName: String): String {
    val n = deviceName.trim().let { if (it.startsWith("AYN ", ignoreCase = true)) it.substring(4) else it }
    return n.filterNot { it.isWhitespace() }.uppercase().take(5)
}

/** One device on the rail: its id, its short [label] in the rail's gutter, and its [name] (disambiguated). */
data class RailRow(val deviceId: String, val label: String, val name: String)

/**
 * Every device in [rows]: this device first, then the others as Presence.kt's columns have them (by name, a repeated
 * name numbered "Odin3 (2)"). This device is there even when no game lists it yet, named [thisDeviceName]. Labels
 * that would read the same get a number: "ODIN3", "ODIN·2".
 */
fun railDevices(rows: List<GameRow>, thisDeviceId: String?, thisDeviceName: String? = null): List<RailRow> {
    val others = deviceColumns(rows, thisDeviceId).map { it.id to it.name }
    val self = thisDeviceId?.let { id ->
        val name = rows.firstNotNullOfOrNull { r -> r.devices.firstOrNull { it.deviceId == id }?.deviceName } ?: thisDeviceName ?: "This device"
        id to name
    }
    val all = listOfNotNull(self) + others
    val seen = HashMap<String, Int>()
    return all.map { (id, name) ->
        val base = railLabel(name)
        val n = seen.merge(base, 1, Int::plus)!!
        RailRow(id, if (n == 1) base else base.take(4) + "·" + n, name)
    }
}

/** The rail's rows: [railDevices], at most [MAX_RAIL]. */
fun railRows(rows: List<GameRow>, thisDeviceId: String?, thisDeviceName: String? = null): List<RailRow> =
    railDevices(rows, thisDeviceId, thisDeviceName).take(MAX_RAIL)

/** One device's line in the card: its mark, its name and the state in words ("newest · 2 h ago"). */
data class CardLine(val name: String, val presence: Presence, val words: String)

/**
 * The pulled-out card: the kicker (system · emulator), the title ([unnamed]: a raw key, set in mono), one sentence
 * about this device's copy in [tone], and a line per device, every device, not only the rail's.
 */
data class ShelfCard(
    val kicker: String,
    val title: String,
    val unnamed: Boolean,
    val sentence: String,
    val tone: Tone,
    val lines: List<CardLine>,
)

/**
 * One game on the shelf. [system] is its Systems.kt label (null when it can't be told), which picks the spine's
 * colour; [marks] are its rail marks, in the rail's order; [card] is what it shows when pulled out.
 */
data class Spine(
    val emulatorId: String,
    val gameKey: String,
    val title: String,
    val unnamed: Boolean,
    val system: String?,
    val marks: List<PresenceCell>,
    val card: ShelfCard,
)

/** An emulator in the strip: its [state] follows its name when it is the current one; [dimmed] when not set up here. */
data class EmuTab(val id: String, val name: String, val state: String?, val tone: Tone, val dimmed: Boolean)

/**
 * The notice line over the hint bar: the first thing pending for this emulator, and how many more there are. A
 * waiting delivery's [text] is the amber close sentence and [rest] what follows it in ink2.
 */
data class Notice(val text: String, val tone: Tone, val more: Int, val rest: String = "")

/** One emulator's shelf. [emptyLine] is set only when it has no games at all. */
data class EmuShelf(
    val tab: EmuTab,
    val spines: List<Spine>,
    val count: Int,
    val notice: Notice?,
    val emptyLine: String?,
)

/**
 * Everything the Library tab shows: a shelf per emulator in module order, the rail's rows (shared by every shelf),
 * and [emulator], the shelf to open on. [loaded] is false until the first games list arrives; [revoked]: the server
 * removed this device.
 */
data class ShelfUi(
    val shelves: List<EmuShelf>,
    val emulator: Int,
    val rail: List<RailRow>,
    val signedIn: Boolean = true,
    val loaded: Boolean = true,
    val error: ApiException? = null,
    val revoked: Boolean = false,
) {
    val tabs: List<EmuTab> get() = shelves.map { it.tab }
}

/** This device's copy of a game in words, and its tone: the card's sentence. */
internal fun hereCopy(state: DeviceGameState?, emulator: String): Pair<String, Tone> = when (state) {
    DeviceGameState.Newest -> "this is the newest" to Tone.Ink2
    DeviceGameState.HasIt -> "up to date" to Tone.Ink2
    DeviceGameState.Waiting -> "waiting for $emulator to close" to Tone.Wait
    DeviceGameState.Pending -> "on its way" to Tone.Ink2
    DeviceGameState.Behind -> "older save" to Tone.Ink2
    DeviceGameState.Differs -> "two saves · choose in Conflicts" to Tone.Fault
    DeviceGameState.NotInstalled, null -> "not on this device" to Tone.Ink3
}

/** A system's name for the card's kicker. */
private val SYSTEM_NAMES = mapOf(
    "GC" to "GameCube", "WII" to "Wii", "GB" to "Game Boy", "GBC" to "Game Boy Color", "GBA" to "Game Boy Advance",
    "DS" to "Nintendo DS", "3DS" to "Nintendo 3DS", "SWITCH" to "Switch", "NES" to "NES", "SNES" to "SNES", "N64" to "N64",
    "PSX" to "PlayStation", "PSP" to "PSP", "VITA" to "PS Vita", "MD" to "Mega Drive",
)

private fun relative(iso: String, now: Instant): String? {
    val at = runCatching { Instant.parse(iso) }.getOrNull() ?: return null
    val age = Duration.between(at, now)
    return when {
        age.toMinutes() < 1 -> "just now"
        age.toMinutes() < 60 -> "${age.toMinutes()} min ago"
        age.toHours() < 24 -> "${age.toHours()} h ago"
        else -> "${age.toDays()} d ago"
    }
}

private fun words(p: Presence, here: Boolean, newest: Boolean, row: GameRow, emulatorName: String, now: Instant): String = when (p) {
    Presence.Newest -> if (newest) row.newestUtc?.let { relative(it, now) }?.let { "newest · $it" } ?: "newest" else "newest"
    Presence.Older -> "older copy"
    Presence.Absent -> if (here) "not on this device" else "not installed"
    Presence.Waiting -> "waiting for $emulatorName to close"
    Presence.Coming -> "on its way"
    Presence.Conflict -> "conflict"
    Presence.Empty -> "no save yet"
}

/**
 * The card for [spine] ([row] on the server). [devices] are the lines, every device in the rail's order; by default
 * the ones [row] lists.
 */
fun card(
    spine: Spine,
    row: GameRow,
    thisDeviceId: String?,
    emulatorName: String,
    devices: List<RailRow> = railDevices(listOf(row), thisDeviceId),
    now: Instant = Instant.now(),
): ShelfCard {
    val here = row.devices.firstOrNull { it.deviceId == thisDeviceId }?.state
    val (copy, tone) = hereCopy(here, emulatorName)
    val kicker = listOfNotNull(spine.system?.let { SYSTEM_NAMES[it] ?: it }, emulatorName).joinToString(" · ").uppercase()
    val lines = devices.map { d ->
        val p = presenceOf(row, d.deviceId)
        CardLine(d.name, p, words(p, d.deviceId == thisDeviceId, d.deviceId == row.newestOnDeviceId, row, emulatorName, now))
    }
    return ShelfCard(kicker, spine.title, spine.unnamed, copy.replaceFirstChar { it.uppercase() } + ".", tone, lines)
}

/** [row] as a spine with its rail marks ([rail]) and its card (a line for each of [devices]). */
fun spineOf(
    row: GameRow,
    thisDeviceId: String?,
    emulatorName: String,
    rail: List<RailRow>,
    core: String?,
    devices: List<RailRow> = rail,
    now: Instant = Instant.now(),
): Spine {
    val bare = Spine(
        emulatorId = row.emulatorId,
        gameKey = row.gameKey,
        title = row.displayName ?: bareKey(row.emulatorId, row.gameKey),
        unnamed = row.displayName == null,
        system = systemLabel(row.emulatorId, row.gameKey, core),
        marks = rail.map { d -> presenceOf(row, d.deviceId).let { PresenceCell(it, presenceText(it, d.name, emulatorName)) } },
        card = ShelfCard("", "", false, "", Tone.Ink2, emptyList()),
    )
    return bare.copy(card = card(bare, row, thisDeviceId, emulatorName, devices, now))
}

/**
 * Where the cursor is on each emulator's shelf, by game key, so switching emulators and back returns to the same
 * game. When that game is gone, the cursor goes to the nearest earlier game that is still there, or the first.
 */
class ShelfCursor {
    private class Memory(var key: String?, var keys: List<String>)

    private val memory = HashMap<String, Memory>()

    /** The cursor's index on [spines], [emulatorId]'s shelf now. */
    fun indexFor(emulatorId: String, spines: List<Spine>): Int {
        if (spines.isEmpty()) return 0
        val keys = spines.map { it.gameKey }
        val m = memory.getOrPut(emulatorId) { Memory(null, keys) }
        val key = m.key ?: return 0
        val i = keys.indexOf(key)
        if (i >= 0) {
            m.keys = keys
            return i
        }
        // The game left: the nearest one before it (in the order last seen) that is still here.
        val was = m.keys.indexOf(key)
        val present = keys.toHashSet()
        for (j in was - 1 downTo 0) {
            val k = m.keys[j]
            if (k in present) return keys.indexOf(k)
        }
        return 0
    }

    /** The cursor moved to [gameKey] on [emulatorId]'s shelf. */
    fun moveTo(emulatorId: String, gameKey: String) {
        val m = memory.getOrPut(emulatorId) { Memory(null, emptyList()) }
        m.key = gameKey
        if (gameKey !in m.keys) m.keys = m.keys + gameKey
    }
}
