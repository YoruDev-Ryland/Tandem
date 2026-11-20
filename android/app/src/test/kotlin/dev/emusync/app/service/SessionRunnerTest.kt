package dev.emusync.app.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Collections

class SessionRunnerTest {
    @Test fun new_session_starts_only_after_previous_cleanup() = runBlocking {
        val runner = SessionRunner()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val oldStarted = CompletableDeferred<Unit>()
        runner.start(oldScope) {
            try {
                events += "old start"; oldStarted.complete(Unit); awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(200); events += "old cleanup done" }
            }
        }
        oldStarted.await()
        oldScope.cancel()   // the old service instance's onDestroy: cancel without joining
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val newJob = runner.start(newScope) { events += "new start" }
        withTimeout(5_000) { newJob.join() }
        assertEquals(listOf("old start", "old cleanup done", "new start"), events.toList())
        newScope.cancel()
    }

    @Test fun first_session_starts_immediately() = runBlocking {
        val runner = SessionRunner()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var ran = false
        withTimeout(5_000) { runner.start(scope) { ran = true }.join() }
        assertEquals(true, ran)
        scope.cancel()
    }
}
