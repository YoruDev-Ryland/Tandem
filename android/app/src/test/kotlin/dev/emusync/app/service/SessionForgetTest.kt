package dev.emusync.app.service

import dev.emusync.app.sync.ItemState
import dev.emusync.app.sync.StateStore
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A variant change records a forget; the service applies it when the next session starts. SyncService's supervisor
 * restarts sessions with collectLatest, which joins the old session first, so a write the old session makes while
 * it winds down lands before the forget and cannot survive it.
 */
class SessionForgetTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun the_forget_runs_after_the_old_sessions_late_write() = runBlocking {
        val store = StateStore(File(tmp.root, "state.json"))
        store.put("eden|Save|G||", ItemState("v1", "m", "m"))
        val reloads = MutableStateFlow(0)
        val started = CompletableDeferred<Unit>()
        val forgotten = mutableListOf<Set<String>>()
        val supervisor = launch {
            reloads.collectLatest { n ->
                forgotten += prepareSession(store)   // what runSession does first
                if (n == 0) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally {
                        // The old session's upload answer arriving while it is cancelled.
                        withContext(NonCancellable) { yield(); store.put("eden|Save|G||", ItemState("v2", "m", "m")) }
                    }
                }
            }
        }
        started.await()
        store.setVariant("eden", "dev.igawa6.edenduo", forgetState = true)   // the UI
        reloads.value = 1                                                      // ACTION_RELOAD
        while (forgotten.size < 2) yield()
        supervisor.cancel()
        assertEquals(listOf(emptySet(), setOf("eden")), forgotten)
        assertNull(store.get("eden|Save|G||"))
        assertTrue(store.isFresh("eden"))
    }
}
