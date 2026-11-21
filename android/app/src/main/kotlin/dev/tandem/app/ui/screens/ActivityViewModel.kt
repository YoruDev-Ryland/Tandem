package dev.tandem.app.ui.screens

import dev.tandem.app.api.Api
import dev.tandem.app.api.ApiException
import dev.tandem.app.model.ActivityItem
import dev.tandem.app.ui.state.OfflineError
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** One line of this device's server activity, with its time already worded. [fault]: a conflict was opened. */
data class ActivityEntry(val id: Long, val time: String, val message: String, val fault: Boolean)

data class ActivityUi(
    val signedIn: Boolean = false,
    val entries: List<ActivityEntry> = emptyList(),
    /** The first request has answered, with rows or with a failure. */
    val loaded: Boolean = false,
    /** The last request failed; the screen shows it only while there are no rows to show instead. */
    val error: Throwable? = null,
    val offline: Boolean = false,
    /** "Offline · last updated 14:05" while the server cannot be reached. */
    val offlineNote: String? = null,
    /** The server has older activity than the rows loaded. */
    val hasOlder: Boolean = false,
    val loadingOlder: Boolean = false,
    /** The last "Load older" failed; the rows stay. */
    val olderError: Boolean = false,
)

private val DATED = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)
private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/** "just now", "5 min ago", "3 h ago" under a day, else "Nov 19, 22:14" in [zone]; empty if the time is unreadable. */
internal fun activityTime(iso: String, now: Instant, zone: ZoneId): String {
    val at = parseUtc(iso) ?: return ""
    val age = Duration.between(at, now)
    return when {
        age.toMinutes() < 1 -> "just now"   // includes a server clock a little ahead of ours
        age.toMinutes() < 60 -> "${age.toMinutes()} min ago"
        age.toHours() < 24 -> "${age.toHours()} h ago"
        else -> DATED.format(at.atZone(zone))
    }
}

/**
 * This device's server activity, newest first, paged by the server's `nextBefore` cursor. [nudges] (the delivery
 * signal and each server cache refresh) refresh it while [show]n; there is no activity topic for a device. A refresh
 * prepends what is newer than the top row and keeps the older pages. Every answer is checked against the state it
 * was asked in (a data epoch, plus the latest refresh), so a late one never overwrites newer state.
 */
class ActivityViewModel(
    private val api: () -> Api?,
    private val deviceId: () -> String?,
    private val scope: CoroutineScope,
    nudges: Flow<Any?>,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val limit: Int = 50,
) {
    private var items = emptyList<ActivityItem>()
    private var nextBefore: Long? = null
    private var loaded = false
    private var error: Throwable? = null
    private var offline = false
    private var updatedAt: Instant? = null
    private var loadingOlder = false
    private var olderError = false
    private var device: String? = null
    private var shown = false
    /** Bumped when the rows start over (sign-out, another device, a page too far behind); older answers are void. */
    private var epoch = 0
    private var refreshes = 0

    private val state = MutableStateFlow(ActivityUi())
    val ui: StateFlow<ActivityUi> get() = state

    init {
        scope.launch { nudges.collect { if (shown) refresh() } }
    }

    fun show() { shown = true; refresh() }
    fun hide() { shown = false }

    private fun startOver() {
        epoch++
        items = emptyList(); nextBefore = null; loaded = false; error = null; offline = false
        updatedAt = null; loadingOlder = false; olderError = false
    }

    /** Fetches the newest page and adds what is newer than the top row. */
    fun refresh() {
        val a = api()
        val dev = deviceId()
        if (a == null) { startOver(); publish(); return }
        if (dev != device) { startOver(); device = dev }
        val mine = epoch
        val token = ++refreshes
        scope.launch {
            val page = try { a.activity(dev, null, limit) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                if (mine != epoch || token != refreshes) return@launch
                loaded = true
                when (e) {
                    is IOException -> { offline = true; error = OfflineError(e.message) }
                    is ApiException -> { offline = false; error = e }
                    else -> { offline = false; error = ApiException(-1, "client", e.message ?: e.javaClass.simpleName) }
                }
                publish()
                return@launch
            }
            if (mine != epoch || token != refreshes) return@launch
            val top = items.firstOrNull()?.id ?: Long.MIN_VALUE
            val fresh = page.items.filter { it.id > top }
            when {
                items.isEmpty() -> { items = page.items; nextBefore = page.nextBefore }
                fresh.isEmpty() -> {}
                // More arrived than one page holds: the older rows would no longer join up, so start over.
                fresh.size == page.items.size && page.nextBefore != null -> {
                    epoch++; loadingOlder = false; olderError = false
                    items = page.items; nextBefore = page.nextBefore
                }
                else -> items = fresh + items
            }
            loaded = true; error = null; offline = false; updatedAt = clock()
            publish()
        }
    }

    /** Fetches the page before the oldest loaded row. One request at a time; the rows stay if it fails. */
    fun loadOlder() {
        val before = nextBefore ?: return
        if (loadingOlder) return
        val a = api() ?: return
        val mine = epoch
        val dev = device
        loadingOlder = true; olderError = false
        publish()
        scope.launch {
            try {
                val page = a.activity(dev, before, limit)
                if (mine != epoch) return@launch
                val have = items.mapTo(HashSet()) { it.id }
                items = items + page.items.filter { it.id !in have }
                nextBefore = page.nextBefore
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == epoch) olderError = true
            }
            if (mine == epoch) { loadingOlder = false; publish() }
        }
    }

    private fun publish() {
        val now = clock()
        val z = zone()
        val note = if (!offline) null else updatedAt?.let { "Offline · last updated ${HHMM.format(it.atZone(z))}" } ?: "Offline"
        state.value = ActivityUi(
            signedIn = api() != null,
            entries = items.map { ActivityEntry(it.id, activityTime(it.utc, now, z), it.message, it.kind == "conflict_opened") },
            loaded = loaded, error = error, offline = offline, offlineNote = note,
            hasOlder = nextBefore != null, loadingOlder = loadingOlder, olderError = olderError,
        )
    }
}
