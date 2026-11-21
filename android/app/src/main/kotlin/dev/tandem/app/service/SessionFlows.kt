package dev.tandem.app.service

import dev.tandem.app.sync.ReceivedDelivery
import dev.tandem.app.sync.WaitingDelivery
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A held-back delivery that was applied at [at]. */
data class ReceivedAt(val delivery: ReceivedDelivery, val at: Instant)

/**
 * What the live sync session publishes for the UI: the deliveries waiting for a running emulator and the running
 * emulators. Each session writes with the [Token] from [begin]; once [end] ran for it (or for everything), its writes
 * are dropped. A dying session's blocking calls (network, shell binder) can't be cancelled and may return after the
 * session ended: without the token they'd republish stale state that nothing overwrites. Check and write share one lock.
 */
class SessionFlows {
    class Token internal constructor()

    private val lock = Any()
    private var live: Token? = null
    private val waitingState = MutableStateFlow<List<WaitingDelivery>>(emptyList())
    private val runningState = MutableStateFlow<Set<String>>(emptySet())

    private val nudgeState = MutableStateFlow(0L)
    private val receivedState = MutableStateFlow<List<ReceivedAt>>(emptyList())

    /** Counts the server's `deliveries` events (monotonic, never reset); the UI refetches on every change. */
    val deliveryNudges: StateFlow<Long> get() = nudgeState

    val waiting: StateFlow<List<WaitingDelivery>> get() = waitingState
    val running: StateFlow<Set<String>> get() = runningState
    /** The held-back deliveries applied lately, oldest first (at most [MAX_RECEIVED]); the UI decides how long to show them. */
    val received: StateFlow<List<ReceivedAt>> get() = receivedState

    /** Starts a session; any previous session's token stops working. */
    fun begin(): Token = synchronized(lock) { Token().also { live = it } }

    /** Ends [token]'s session (or, when null, whichever is live) and clears both flows. */
    fun end(token: Token?) = synchronized(lock) {
        if (token != null && live !== token) return@synchronized
        live = null
        waitingState.value = emptyList()
        runningState.value = emptySet()
        receivedState.value = emptyList()
    }

    /** Runs [block] under the lock only while [token]'s session is live, so it can't interleave with [end]. */
    fun ifLive(token: Token, block: () -> Unit) = synchronized(lock) {
        if (live === token) block()
    }

    fun publishWaiting(token: Token, v: List<WaitingDelivery>) = synchronized(lock) {
        if (live === token) waitingState.value = v
    }

    fun publishRunning(token: Token, v: Set<String>) = synchronized(lock) {
        if (live === token) runningState.value = v
    }

    fun publishReceived(token: Token, v: List<ReceivedDelivery>, at: Instant) = synchronized(lock) {
        if (live === token) receivedState.value = (receivedState.value + v.map { ReceivedAt(it, at) }).takeLast(MAX_RECEIVED)
    }

    fun nudgeDeliveries(token: Token) = synchronized(lock) {
        if (live === token) nudgeState.value += 1
    }

    private companion object {
        const val MAX_RECEIVED = 20
    }
}
