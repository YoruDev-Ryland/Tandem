package dev.tandem.app.service

import kotlinx.coroutines.channels.Channel

/**
 * Serializes work items through one consumer. An item already waiting is not queued twice, so a burst of ticks
 * during a long scan collapses into one. An item offered while the same item runs is queued again (the request
 * arrived after the running one started).
 */
class WorkQueue<K : Any> {
    private val ch = Channel<K>(Channel.UNLIMITED)
    private val pending = HashSet<K>()

    /** Queues [item] unless it already waits. Returns whether it was queued. */
    fun offer(item: K): Boolean {
        synchronized(pending) { if (!pending.add(item)) return false }
        ch.trySend(item)
        return true
    }

    /** The items queued and not yet started. */
    fun waiting(): Set<K> = synchronized(pending) { pending.toSet() }

    /** Runs [handle] for each item in order, one at a time, until cancelled or [handle] throws. */
    suspend fun consume(handle: suspend (K) -> Unit) {
        for (item in ch) {
            synchronized(pending) { pending.remove(item) }
            handle(item)
        }
    }

    fun close() {
        ch.close()
    }
}
