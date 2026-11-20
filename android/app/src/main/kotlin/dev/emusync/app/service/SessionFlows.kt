package dev.emusync.app.service

import dev.emusync.app.sync.WaitingDelivery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    val waiting: StateFlow<List<WaitingDelivery>> get() = waitingState
    val running: StateFlow<Set<String>> get() = runningState

    /** Starts a session; any previous session's token stops working. */
    fun begin(): Token = synchronized(lock) { Token().also { live = it } }

    /** Ends [token]'s session (or, when null, whichever is live) and clears both flows. */
    fun end(token: Token?) = synchronized(lock) {
        if (token != null && live !== token) return@synchronized
        live = null
        waitingState.value = emptyList()
        runningState.value = emptySet()
    }

    fun publishWaiting(token: Token, v: List<WaitingDelivery>) = synchronized(lock) {
        if (live === token) waitingState.value = v
    }

    fun publishRunning(token: Token, v: Set<String>) = synchronized(lock) {
        if (live === token) runningState.value = v
    }
}
