package dev.emusync.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs at most one sync supervisor per process, across service instances. A new [start] first joins the previous
 * job (cancelled by the old instance's onDestroy), so the old session's cleanup (unwatchAll on the shared shell,
 * in-flight uploads) finishes before the new session binds and scans.
 */
/**
 * The first step of every sync session, run after the previous session has ended (the supervisor joins it): applies
 * the sync state forgets the UI recorded. Returns the forgotten emulator ids.
 */
internal fun prepareSession(store: dev.emusync.app.sync.StateStore): Set<String> = store.applyPendingForget()

class SessionRunner {
    private var current: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope, block: suspend () -> Unit): Job {
        val previous = current
        val job = scope.launch {
            previous?.join()
            block()
        }
        current = job
        return job
    }
}
