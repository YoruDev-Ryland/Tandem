package dev.emusync.app.ui.state

import dev.emusync.app.api.Api
import dev.emusync.app.api.ApiException
import dev.emusync.app.model.ConflictRow
import dev.emusync.app.model.GameRow
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * A cached server read. [value] is the last good result and survives failures; [offline] means the server could not
 * be reached (an HTTP error sets [error] only). [updatedAt] changes only on success.
 */
data class Loaded<T>(
    val value: T? = null,
    val updatedAt: Instant? = null,
    val error: ApiException? = null,
    val offline: Boolean = false,
)

/** Marks a failure to reach the server at all (an IOException), as opposed to an HTTP error or a client bug. */
class OfflineError(message: String?) : ApiException(0, "offline", message)

/** What AppState reads from the server cache. */
interface ServerView {
    val deviceName: StateFlow<String?>
    val configGroups: StateFlow<Map<String, String>>
    val games: StateFlow<Loaded<List<GameRow>>>
}

/**
 * Caches the server's games, conflicts, this device's config groups and name. Refreshes on [start], on each
 * [deliveryNudges] change (the service's SSE signal), on each [running] change, and every [tickMillis] while
 * [setVisible] is true. Requests coalesce (one running, one pending) and stop early once the server proves unreachable.
 */
class ServerRepo(
    private val api: () -> Api?,
    private val scope: CoroutineScope,
    private val deliveryNudges: Flow<Long> = emptyFlow(),
    private val running: Flow<Set<String>> = emptyFlow(),
    private val deviceId: () -> String? = { null },
    private val clock: () -> Instant = Instant::now,
    private val tickMillis: Long = 60_000,
) : ServerView {
    private val gamesState = MutableStateFlow(Loaded<List<GameRow>>())
    private val conflictsState = MutableStateFlow(Loaded<List<ConflictRow>>())
    private val groupsState = MutableStateFlow<Map<String, String>>(emptyMap())
    private val nameState = MutableStateFlow<String?>(null)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var watch: Job? = null
    private var timer: Job? = null
    private var visible = false

    override val games: StateFlow<Loaded<List<GameRow>>> get() = gamesState
    val conflicts: StateFlow<Loaded<List<ConflictRow>>> get() = conflictsState
    /** Emulator id to the name of the config group this device is in; empty when unknown. */
    override val configGroups: StateFlow<Map<String, String>> get() = groupsState
    override val deviceName: StateFlow<String?> get() = nameState

    /** Marks a client-side failure (bad payload, bug): reported as an error, never as offline. */
    private fun clientError(e: Exception) = ApiException(-1, "client", e.message ?: e.javaClass.simpleName)

    @Synchronized fun start() {
        if (watch != null) return
        watch = scope.launch {
            launch { for (ignored in requests) refreshNow() }
            refresh()
            // drop(1): a StateFlow replays its current value on collect, which the refresh above already covered.
            merge(deliveryNudges.drop(1), running.drop(1)).collect { refresh() }
        }
        restartTimer()
    }

    @Synchronized fun stop() {
        watch?.cancel(); watch = null
        timer?.cancel(); timer = null
    }

    /** The Activity sets this in onStart/onStop; the periodic refresh runs only while true. */
    @Synchronized fun setVisible(v: Boolean) {
        visible = v
        restartTimer()
    }

    private fun restartTimer() {
        timer?.cancel(); timer = null
        if (!visible || watch == null) return
        timer = scope.launch { while (true) { delay(tickMillis); refresh() } }
    }

    /** Asks for a refresh. Requests coalesce: at most one runs and one more waits, however many arrive. */
    fun refresh() { requests.trySend(Unit) }

    private suspend fun refreshNow() {
        val a = api()
        if (a == null) {
            gamesState.value = Loaded(); conflictsState.value = Loaded()
            groupsState.value = emptyMap(); nameState.value = null
            return
        }
        if (!load(gamesState) { a.games(null) }) {
            // Unreachable: don't spend three more timeouts, just mark the rest stale.
            conflictsState.value = conflictsState.value.copy(error = gamesState.value.error, offline = true)
            return
        }
        if (!load(conflictsState) { a.conflicts() }) return
        try {
            val id = deviceId()
            val overview = a.configs()
            groupsState.value = overview.emulators.mapNotNull { e ->
                e.groups.firstOrNull { g -> g.members.any { it.id == id } }?.let { e.emulatorId to it.name }
            }.toMap()
            nameState.value = a.me().name
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* falls back to Saves(n) */ }
    }

    /** Returns false when the server could not be reached. */
    private suspend fun <T> load(state: MutableStateFlow<Loaded<T>>, call: suspend () -> T): Boolean {
        try {
            state.value = Loaded(call(), clock(), null, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            state.value = state.value.copy(error = e, offline = false)
        } catch (e: java.io.IOException) {
            state.value = state.value.copy(error = OfflineError(e.message), offline = true)
            return false
        } catch (e: Exception) {
            state.value = state.value.copy(error = clientError(e), offline = false)
        }
        return true
    }
}
