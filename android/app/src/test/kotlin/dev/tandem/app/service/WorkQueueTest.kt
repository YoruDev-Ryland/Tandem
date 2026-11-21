package dev.tandem.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkQueueTest {
    @Test fun runs_items_in_order_one_at_a_time() = runBlocking {
        val q = WorkQueue<String>()
        val seen = mutableListOf<String>()
        var running = 0
        var maxRunning = 0
        listOf("scan", "tick", "apply").forEach { q.offer(it) }
        q.close()
        q.consume {
            running++; maxRunning = maxOf(maxRunning, running)
            yield()
            seen += it
            running--
        }
        assertEquals(listOf("scan", "tick", "apply"), seen)
        assertEquals(1, maxRunning)
    }

    @Test fun waiting_duplicates_collapse() {
        val q = WorkQueue<String>()
        assertTrue(q.offer("tick"))
        assertFalse(q.offer("tick"))
        assertTrue(q.offer("scan"))
    }

    @Test fun item_offered_while_running_is_queued_again() = runBlocking {
        val q = WorkQueue<String>()
        val seen = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        q.offer("apply")
        val job = launch {
            q.consume {
                seen += it
                if (seen.size == 1) { started.complete(Unit); release.await() } else q.close()
            }
        }
        started.await()
        assertTrue(q.offer("apply"))
        release.complete(Unit)
        job.join()
        assertEquals(listOf("apply", "apply"), seen)
    }

    @Test fun handler_exception_stops_consumer() = runBlocking {
        val q = WorkQueue<String>()
        q.offer("a"); q.offer("b")
        val seen = mutableListOf<String>()
        val r = runCatching { q.consume { seen += it; throw IllegalStateException("gone") } }
        assertTrue(r.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf("a"), seen)
    }
}
