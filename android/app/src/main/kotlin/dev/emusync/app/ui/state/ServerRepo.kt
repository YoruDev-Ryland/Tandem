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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

/**
 * Caches the server's games, conflicts, this device's config groups and name. Refreshes on [start], on each
 * [deliveryNudges] change (the service's SSE signal), on each [running] change, and every [tickMillis] while
 * [setVisible] is true. Overlapping refreshes queue behind one lock.
 */
class ServerRepo(
    private val api: () -> Api?,
    private val scope: CoroutineScope,
    private val deliveryNudges: Flow<Long> = emptyFlow(),
    private val running: Flow<Set<String>> = emptyFlow(),
    private val deviceId: () -> String? = { null },
    private val clock: () -> Instant = Instant::now,
    private val tickMillis: Long = 60_000,
) {
    private val gamesState = MutableStateFlow(Loaded<List<GameRow>>())
    private val conflictsState = MutableStateFlow(Loaded<List<ConflictRow>>())
    private val groupsState = MutableStateFlow<Map<String, String>>(emptyMap())
    private val nameState = MutableStateFlow<String?>(null)
    private val lock = Mutex()
    private var watch: Job? = null
    private var timer: Job? = null
    private var visible = false

    val games: StateFlow<Loaded<List<GameRow>>> get() = gamesState
    val conflicts: StateFlow<Loaded<List<ConflictRow>>> get() = conflictsState
    /** Emulator id to the name of the config group this device is in; empty when unknown. */
    val configGroups: StateFlow<Map<String, String>> get() = groupsState
    val deviceName: StateFlow<String?> get() = nameState

    @Synchronized fun start() {
        if (watch != null) return
        watch = scope.launch {
            launch { refresh() }
            // drop(1): a StateFlow replays its current value on collect, which start() already covered.
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

    suspend fun refresh() = lock.withLock {
        val a = api() ?: return@withLock
        load(gamesState) { a.games(null) }
        load(conflictsState) { a.conflicts() }
        try {
            val id = deviceId()
            val overview = a.configs()
            groupsState.value = overview.emulators.mapNotNull { e ->
                e.groups.firstOrNull { g -> g.members.any { it.id == id } }?.let { e.emulatorId to it.name }
            }.toMap()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* falls back to Saves(n) */ }
        try {
            nameState.value = a.me().name
        } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    private suspend fun <T> load(state: MutableStateFlow<Loaded<T>>, call: suspend () -> T) {
        try {
            state.value = Loaded(call(), clock(), null, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            state.value = state.value.copy(error = e, offline = false)
        } catch (e: Exception) {
            state.value = state.value.copy(error = ApiException(0, "offline", e.message), offline = true)
        }
    }
}
