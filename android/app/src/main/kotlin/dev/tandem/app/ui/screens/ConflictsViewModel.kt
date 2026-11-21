package dev.tandem.app.ui.screens

import dev.tandem.app.api.Api
import dev.tandem.app.model.BulkFirstSyncRequest
import dev.tandem.app.model.ConflictReason
import dev.tandem.app.model.ConflictRow
import dev.tandem.app.model.ConflictSide
import dev.tandem.app.model.FirstSyncChoice
import dev.tandem.app.model.ItemKind
import dev.tandem.app.model.ResolveChoice
import dev.tandem.app.model.ResolveConflictRequest
import dev.tandem.app.ui.state.Loaded
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One side's column: "This Thor" or "Odin 3", when the server received it, and its size with file count. */
data class SideColumn(val title: String, val received: String, val size: String)

/**
 * A bulk first-sync choice: its button [label], what its confirm sheet asks ([question], [detail], [confirm]) and the
 * [calls] it makes, worked out when the list was read so the sheet sends exactly what it showed.
 */
data class BulkChoice(val label: String, val question: String, val detail: String, val confirm: String, val calls: List<BulkFirstSyncRequest>)

/** One conflict this device is part of, ready to show and to decide with A (mine), X (the other's) or Y (both). */
data class ConflictEntry(
    val itemId: String,
    val emulatorId: String,
    val emulator: String,
    val game: String,
    /** The emulator, and the slot for a save state. */
    val meta: String,
    val reason: ConflictReason,
    /** The emulator runs here, so whatever is chosen is applied when it closes. */
    val running: Boolean,
    /** "This Thor" */
    val me: String,
    /** The first other side's device, "Odin 3"; "the earlier save" when every side is this device's. */
    val other: String,
    /** The other device; null when the conflict is with this device's own earlier save. */
    val otherId: String?,
    /** The X hint: "Keep Odin 3's", or "Keep the earlier save". */
    val keepOtherLabel: String,
    val subtitle: String,
    val columns: List<SideColumn>,
    val keepMine: ResolveConflictRequest,
    val keepOther: ResolveConflictRequest,
    val keepBoth: ResolveConflictRequest,
)

data class ConflictsUi(
    val signedIn: Boolean,
    val entries: List<ConflictEntry>,
    val loaded: Boolean,
    val error: Throwable?,
    /** "Use this device's for all", offered with two or more first-sync conflicts. */
    val bulkMine: BulkChoice? = null,
    /** "Use {Other}'s for all", by the other device's id; offered alongside [bulkMine]. */
    val bulkOther: Map<String, BulkChoice> = emptyMap(),
    /** A choice is on its way to the server; another is ignored until it lands. */
    val busy: Boolean = false,
    /** The last choice failed. */
    val failure: Throwable? = null,
)

private val WHEN = DateTimeFormatter.ofPattern("EEE, MMM d, HH:mm", Locale.US)

/** "Thu, Nov 19, 21:48", like the web's Facts rows; empty if the server sent something unreadable. */
internal fun formatWhen(iso: String, zone: ZoneId): String = parseUtc(iso)?.let { WHEN.format(it.atZone(zone)) }.orEmpty()

/**
 * A server timestamp. The server writes DateTimeOffset ("…+00:00", up to seven fraction digits), which older
 * java.time (Instant.parse on Android before API 34) rejects; OffsetDateTime reads it everywhere.
 */
internal fun parseUtc(iso: String): Instant? = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()

/**
 * One side per device: the server lists every open conflict version, and a device that kept uploading while the
 * conflict was open has several. Each device is represented by its newest (by received time; the server lists them
 * oldest first, so the last wins a tie). Sides with no device stand alone. In first-appearance order.
 */
internal fun deviceSides(sides: List<ConflictSide>): List<ConflictSide> {
    val newest = linkedMapOf<String, ConflictSide>()
    for (s in sides) {
        val k = s.deviceId ?: "version:${s.versionId}"
        val prev = newest[k]
        if (prev == null || !(parseUtc(s.receivedUtc) ?: Instant.MIN).isBefore(parseUtc(prev.receivedUtc) ?: Instant.MIN)) newest[k] = s
    }
    return newest.values.toList()
}

/** "512 KB", "1.0 MB": binary units, one decimal from MB up (the web's formatSize). */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${Math.round(bytes / 1024.0)} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
}

private fun files(n: Int) = if (n == 1) "1 file" else "$n files"
private fun games(n: Int) = if (n == 1) "1 game" else "$n games"

/** "a", "a and b", "a, b and c". */
private fun join(names: List<String>): String =
    if (names.size < 2) names.firstOrNull().orEmpty() else names.dropLast(1).joinToString(", ") + " and " + names.last()

private fun name(s: ConflictSide) = s.deviceName ?: "Unknown device"

/**
 * The Conflicts tab's content: the open conflicts that involve this device ([deviceId] has a side), with the copy
 * for each and, when two or more are from the first sync, the bulk choices.
 */
fun conflictsUi(
    rows: Loaded<List<ConflictRow>>,
    deviceId: String?,
    running: Set<String>,
    emulatorName: (String) -> String,
    signedIn: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): ConflictsUi {
    val mine = if (deviceId == null) emptyList() else rows.value.orEmpty().filter { r ->
        // Also a conflict whose sides are all this device's: its own older upload is the head (it uploaded again on an
        // old base after a resolve). Hidden, it could never be settled.
        r.sides.size >= 2 && r.sides.any { it.deviceId == deviceId }
    }
    val entries = mine.map { r ->
        val devices = deviceSides(r.sides)
        val me = devices.first { it.deviceId == deviceId }
        val others = devices.filter { it !== me }
        val meName = "This ${me.deviceName ?: "device"}"
        val column = { title: String, s: ConflictSide ->
            SideColumn(title, formatWhen(s.receivedUtc, zone), "${formatSize(s.totalSize)} · ${files(s.fileCount)}")
        }
        val emulator = emulatorName(r.emulatorId)
        if (others.isEmpty()) return@map selfOnly(r, me, meName, emulator, column, running)
        val other = others.first()
        ConflictEntry(
            itemId = r.itemId,
            emulatorId = r.emulatorId,
            emulator = emulator,
            game = r.displayName ?: r.gameKey,
            meta = meta(r, emulator),
            reason = r.reason,
            running = r.emulatorId in running,
            me = meName,
            other = name(other),
            otherId = other.deviceId,
            keepOtherLabel = "Keep ${name(other)}'s",
            subtitle = when (r.reason) {
                ConflictReason.FirstSync ->
                    "${join(listOf(meName) + others.map { "the ${name(it)}" })} each had a save before they first synced. " +
                        (if (others.size == 1) "Both" else "All") + " are safe on the server."
                ConflictReason.Concurrent -> "Both devices changed this save before syncing."
            },
            columns = listOf(column(meName, me)) + others.map { column(name(it), it) },
            keepMine = ResolveConflictRequest(ResolveChoice.KeepVersion, me.versionId, null),
            keepOther = ResolveConflictRequest(ResolveChoice.KeepVersion, other.versionId, null),
            // With three or more saves the server names each kept-aside copy "From {device}"; one name would stick to all.
            keepBoth = ResolveConflictRequest(ResolveChoice.KeepBoth, me.versionId, if (others.size == 1) "From ${name(other)}" else null),
        )
    }

    val firstSync = mine.filter { it.reason == ConflictReason.FirstSync }
    var bulkMine: BulkChoice? = null
    val bulkOther = linkedMapOf<String, BulkChoice>()
    if (deviceId != null && firstSync.size >= 2) {
        val myName = "this ${firstSync.first().sides.first { it.deviceId == deviceId }.deviceName ?: "device"}"
        bulkMine = bulk("Use this device's for all", myName, firstSync.size, bulkPlan(firstSync, deviceId))
        for (r in firstSync) for (s in deviceSides(r.sides)) {
            val id = s.deviceId ?: continue
            if (id == deviceId || id in bulkOther) continue
            val theirs = firstSync.filter { row -> row.sides.any { it.deviceId == id } }
            bulkOther[id] = bulk("Use ${name(s)}'s for all", name(s), theirs.size, bulkPlan(theirs, id))
        }
    }

    val v = rows.value
    return ConflictsUi(signedIn, entries, loaded = v != null, error = rows.error, bulkMine = bulkMine, bulkOther = bulkOther)
}

private fun meta(r: ConflictRow, emulator: String) =
    if (r.kind == ItemKind.State) "$emulator · ${"Slot ${r.slot.orEmpty()}".trim()}" else emulator

/**
 * A conflict between this device's newest upload ([me]) and its own earlier save: the head if that is one of its
 * uploads, else its latest other open version. X keeps the earlier one; Y keeps both and lets the server name the copy.
 */
private fun selfOnly(
    r: ConflictRow,
    me: ConflictSide,
    meName: String,
    emulator: String,
    column: (String, ConflictSide) -> SideColumn,
    running: Set<String>,
): ConflictEntry {
    val earlier = r.sides.firstOrNull { it.isHead && it !== me } ?: r.sides.last { it !== me }
    return ConflictEntry(
        itemId = r.itemId,
        emulatorId = r.emulatorId,
        emulator = emulator,
        game = r.displayName ?: r.gameKey,
        meta = meta(r, emulator),
        reason = r.reason,
        running = r.emulatorId in running,
        me = meName,
        other = "the earlier save",
        otherId = null,
        keepOtherLabel = "Keep the earlier save",
        subtitle = "$meName has two different saves for this game. Both are safe on the server.",
        columns = listOf(column(meName, me), column("Earlier save", earlier)),
        keepMine = ResolveConflictRequest(ResolveChoice.KeepVersion, me.versionId, null),
        keepOther = ResolveConflictRequest(ResolveChoice.KeepVersion, earlier.versionId, null),
        keepBoth = ResolveConflictRequest(ResolveChoice.KeepBoth, me.versionId, null),
    )
}

private fun bulk(label: String, who: String, n: Int, calls: List<BulkFirstSyncRequest>) = BulkChoice(
    label = label,
    question = "Use $who's saves for ${games(n)}?",
    detail = "Every device gets $who's save for ${if (n == 1) "this game" else "these games"}. The other copies stay in history.",
    confirm = "Use $who's",
    calls = calls,
)

/**
 * The first-sync calls that keep device [deviceId]'s save for each of [rows] it has a side in (the web's plan()).
 * Where its save is not the server's current one, its own upload replaces it (UseDevice); every other device's upload
 * that is not current is set aside (UseServer), so the row ends with no open conflict. All UseDevice calls go first:
 * UseServer keeps whatever is current by then, which must be the chosen device's save.
 */
fun bulkPlan(rows: List<ConflictRow>, deviceId: String): List<BulkFirstSyncRequest> {
    val calls = linkedMapOf<Pair<String, FirstSyncChoice>, MutableList<String>>()
    fun add(device: String, choice: FirstSyncChoice, item: String) {
        val items = calls.getOrPut(device to choice) { mutableListOf() }
        if (item !in items) items += item
    }
    for (r in rows) {
        if (r.reason != ConflictReason.FirstSync) continue
        // The device's newest version is the one kept; a device with any upload that is not current is set aside.
        val mine = deviceSides(r.sides).firstOrNull { it.deviceId == deviceId } ?: continue
        if (!mine.isHead) add(deviceId, FirstSyncChoice.UseDevice, r.itemId)
        // Its newest is already current, but an older upload of its own is still open: set that aside too.
        else if (r.sides.any { it.deviceId == deviceId && !it.isHead }) add(deviceId, FirstSyncChoice.UseServer, r.itemId)
        for (o in r.sides) {
            val id = o.deviceId ?: continue
            if (id != deviceId && !o.isHead) add(id, FirstSyncChoice.UseServer, r.itemId)
        }
    }
    return calls.map { (k, items) -> BulkFirstSyncRequest(k.first, k.second, items.toList()) }
        .sortedBy { if (it.choice == FirstSyncChoice.UseDevice) 0 else 1 }
}

/** A conflict as it stood when it was decided: the same item with the same saves. */
private fun shape(r: ConflictRow) = r.itemId + "|" + r.sides.map { it.versionId }.sorted().joinToString(",")

/**
 * The Conflicts tab's state over the server cache, and the choices. One choice runs at a time ([ConflictsUi.busy]);
 * after each, [afterResolve] applies and refreshes. A decided conflict stays hidden until the server's list stops
 * showing it, so a refresh that left before the choice landed cannot bring it back to be decided twice.
 */
class ConflictsViewModel(
    private val conflicts: StateFlow<Loaded<List<ConflictRow>>>,
    running: StateFlow<Set<String>>,
    signedIn: StateFlow<Boolean>,
    deviceId: () -> String?,
    private val api: () -> Api?,
    private val scope: CoroutineScope,
    private val afterResolve: () -> Unit,
    emulatorName: (String) -> String,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    private val busy = MutableStateFlow(false)
    private val failure = MutableStateFlow<Throwable?>(null)
    private val hidden = MutableStateFlow<Set<String>>(emptySet())

    val ui: StateFlow<ConflictsUi> = combine(conflicts, running, signedIn, busy, combine(failure, hidden, ::Pair)) { c, run, s, b, (f, h) ->
        val shown = if (h.isEmpty()) c else c.copy(value = c.value?.filterNot { shape(it) in h })
        conflictsUi(shown, deviceId(), run, emulatorName, s, zone).copy(busy = b, failure = f)
    }.stateIn(scope, SharingStarted.Eagerly, ConflictsUi(false, emptyList(), false, null))

    init {
        // Forget a hidden conflict once the server no longer lists it.
        scope.launch {
            conflicts.collect { l -> l.value?.let { v -> val present = v.map(::shape).toSet(); hidden.update { it intersect present } } }
        }
    }

    fun resolve(itemId: String, req: ResolveConflictRequest) = send(listOf(itemId)) { it.resolveConflict(itemId, req) }

    /** Runs [calls] in order (the UseDevice ones come first in a [bulkPlan]). */
    fun bulk(calls: List<BulkFirstSyncRequest>) = send(calls.flatMap { it.itemIds.orEmpty() }.distinct()) { a -> for (c in calls) a.firstSync(c) }

    private fun send(items: List<String>, block: suspend (Api) -> Unit) {
        val a = api() ?: return
        if (!busy.compareAndSet(expect = false, update = true)) return
        failure.value = null
        val decided = conflicts.value.value.orEmpty().filter { it.itemId in items }.map(::shape)
        scope.launch {
            try {
                block(a)
                hidden.update { it + decided }
            } catch (e: CancellationException) {
                busy.value = false
                throw e
            } catch (e: Exception) {
                failure.value = e
            }
            busy.value = false
            // Also after a failure: part of a bulk choice may have landed, or the conflict was decided elsewhere.
            afterResolve()
        }
    }
}
