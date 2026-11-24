package dev.tandem.app.ui.state

import dev.tandem.app.model.DeviceGameState
import dev.tandem.app.model.GameRow
import dev.tandem.app.ui.kit.Presence

/** One device column of presence marks. */
data class DeviceColumn(val id: String, val name: String)

/**
 * The other devices that appear in any of [rows], one column each, by name (repeated names get a number), without
 * [self]. The same columns as the web's matrixColumns, minus this device: its own state is the HERE column.
 */
fun deviceColumns(rows: List<GameRow>, self: String?): List<DeviceColumn> {
    val sorted = otherDevices(rows, self)
    val count = HashMap<String, Int>()
    return sorted.map { c ->
        val n = count.merge(c.name, 1, Int::plus)!!
        if (n == 1) c else c.copy(name = "${c.name} ($n)")
    }
}

/** The devices in [rows] other than [self], by name ignoring case, then id; names as they are (not numbered). */
internal fun otherDevices(rows: List<GameRow>, self: String?): List<DeviceColumn> {
    val seen = LinkedHashMap<String, String>()
    for (r in rows) for (d in r.devices) if (d.deviceId != self) seen.putIfAbsent(d.deviceId, d.deviceName)
    return seen.map { (id, name) -> DeviceColumn(id, name) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, DeviceColumn::name).thenBy { it.id })
}

/** How [deviceId] holds [g], as the web's presence() says it. */
fun presenceOf(g: GameRow, deviceId: String): Presence = when (g.devices.firstOrNull { it.deviceId == deviceId }?.state) {
    null, DeviceGameState.NotInstalled -> Presence.Absent
    DeviceGameState.Differs -> Presence.Conflict
    DeviceGameState.Newest -> Presence.Newest
    // HasIt is "has the current save"; for a game with no save yet it only means installed.
    DeviceGameState.HasIt -> if (g.newestUtc != null || g.newestOnDeviceName != null) Presence.Newest else Presence.Empty
    DeviceGameState.Behind -> Presence.Older
    DeviceGameState.Pending -> Presence.Coming
    DeviceGameState.Waiting -> Presence.Waiting
}

/** What a presence mark reads as. */
fun presenceText(p: Presence, device: String, emulator: String): String = when (p) {
    Presence.Newest -> "$device has the newest save"
    Presence.Older -> "$device has an older save"
    Presence.Coming -> "The newest save is on its way to $device"
    Presence.Waiting -> "The newest save reaches $device when $emulator closes"
    Presence.Empty -> "Installed on $device, no save yet"
    Presence.Absent -> "Not installed on $device"
    Presence.Conflict -> "$device has one of two different saves"
}
