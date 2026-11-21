package dev.emusync.app.service

import dev.emusync.app.sync.ApplyReport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkPlannerTest {
    private fun automatic(p: WorkPlanner) = listOf(
        p.sessionStarted(), p.deliveriesEvent(), p.poll(), p.streamOpened(false), p.streamOpened(true),
        p.tick(), p.monitor(), p.periodicScan(), p.explicitScan(),
    ).flatten()

    private fun report(waitingOn: Set<String> = emptySet(), applied: Int = 0, reachedServer: Boolean = true) =
        ApplyReport(applied, 0, 0, waitingOn.size, waitingOn, reachedServer)

    /** Runs an exit of [id] and records what it did: "apply(requested)" or "forget". */
    private fun exit(p: WorkPlanner, id: String, result: ApplyReport = report(applied = 1)): Pair<List<String>, ApplyReport?> {
        val calls = mutableListOf<String>()
        val r = runBlocking {
            p.exited(id, apply = { requested -> calls += "apply($requested)"; result }, forget = { calls += "forget" })
        }
        return calls to r
    }

    @Test fun auto_apply_off_never_applies_except_explicitly() {
        val p = WorkPlanner(autoApply = false)
        assertFalse(automatic(p).any { it == SyncWork.ApplyAll || it == SyncWork.ApplyRequested })
        assertEquals(listOf(SyncWork.ApplyRequested), p.explicitApply())
    }

    @Test fun auto_apply_off_still_scans_on_reconnect() {
        val p = WorkPlanner(autoApply = false)
        assertEquals(listOf(SyncWork.FullScan), p.streamOpened(reconnect = true))
        assertEquals(emptyList<SyncWork>(), p.streamOpened(reconnect = false))
    }

    @Test fun auto_apply_on_applies_on_events_poll_open_and_exit() {
        val p = WorkPlanner(autoApply = true)
        assertEquals(listOf(SyncWork.ApplyAll), p.deliveriesEvent())
        assertEquals(listOf(SyncWork.ApplyAll), p.poll())
        assertEquals(listOf(SyncWork.ApplyAll), p.sessionStarted())
        assertEquals(listOf(SyncWork.FullScan, SyncWork.ApplyAll), p.streamOpened(reconnect = true))
        assertEquals(listOf("apply(false)") to report(applied = 1), exit(p, "eden"))
    }

    // ---- final review I-1: an apply the user asked for finishes when the emulator closes ----

    @Test fun explicit_apply_held_by_a_running_emulator_applies_on_its_exit_with_auto_apply_off() {
        val flags = MemoryApplyOnExitFlags()
        val p = WorkPlanner(autoApply = false, flags)
        p.applied(SyncWork.ApplyRequested, report(waitingOn = setOf("eden")))
        assertEquals(setOf("eden"), flags.flagged())

        val (calls, r) = exit(p, "eden")
        assertEquals(listOf("apply(true)"), calls)
        assertEquals(1, r?.applied)
        assertEquals(emptySet<String>(), flags.flagged())   // done: the next exit only forgets
        assertEquals(listOf("forget"), exit(p, "eden").first)
    }

    @Test fun without_an_explicit_apply_an_exit_applies_nothing_with_auto_apply_off() {
        val flags = MemoryApplyOnExitFlags()
        val p = WorkPlanner(autoApply = false, flags)
        p.applied(SyncWork.ApplyAll, report(waitingOn = setOf("eden")))   // not one the user asked for
        assertEquals(emptySet<String>(), flags.flagged())
        val (calls, r) = exit(p, "eden")
        assertEquals(listOf("forget"), calls)
        assertNull(r)
    }

    @Test fun an_exit_applies_only_the_emulator_the_explicit_apply_waited_for() {
        val p = WorkPlanner(autoApply = false)
        p.applied(SyncWork.ApplyRequested, report(waitingOn = setOf("eden")))
        assertEquals(listOf("forget"), exit(p, "azahar").first)
        assertEquals(listOf("apply(true)"), exit(p, "eden").first)
    }

    @Test fun an_exit_apply_held_again_stays_asked_for() {
        val flags = MemoryApplyOnExitFlags()
        val p = WorkPlanner(autoApply = false, flags)
        p.applied(SyncWork.ApplyRequested, report(waitingOn = setOf("eden")))
        exit(p, "eden", result = report(waitingOn = setOf("eden")))   // reopened before the write: held again
        assertEquals(setOf("eden"), flags.flagged())
    }

    @Test fun a_failed_exit_apply_keeps_the_flag() {
        val flags = MemoryApplyOnExitFlags()
        val p = WorkPlanner(autoApply = false, flags)
        p.applied(SyncWork.ApplyRequested, report(waitingOn = setOf("eden")))
        val thrown = runCatching {
            runBlocking { p.exited("eden", apply = { throw IllegalStateException("shell died") }, forget = {}) }
        }
        assertTrue(thrown.isFailure)
        assertEquals(setOf("eden"), flags.flagged())
    }

    @Test fun auto_apply_on_exit_is_unchanged_by_flags() {
        val p = WorkPlanner(autoApply = true)
        p.applied(SyncWork.ApplyAll, report(waitingOn = setOf("eden")))
        assertEquals(listOf("apply(false)"), exit(p, "eden").first)
        assertEquals(listOf("apply(false)"), exit(p, "azahar").first)
    }

    // ---- follow-up 1: the flag records exactly the latest asked-for apply ----

    @Test fun an_explicit_apply_that_reached_the_server_replaces_stale_flags() {
        val flags = MemoryApplyOnExitFlags()
        flags.flag(setOf("eden", "azahar"))   // left by an exit the service never saw
        val p = WorkPlanner(autoApply = false, flags)
        p.applied(SyncWork.ApplyRequested, report(waitingOn = setOf("azahar")))
        assertEquals(setOf("azahar"), flags.flagged())
        assertEquals(listOf("forget"), exit(p, "eden").first)   // no unprompted apply from the stale flag
    }

    @Test fun an_explicit_apply_that_never_reached_the_server_keeps_the_flags() {
        val flags = MemoryApplyOnExitFlags()
        flags.flag(setOf("eden"))
        val p = WorkPlanner(autoApply = false, flags)
        p.applied(SyncWork.ApplyRequested, report(reachedServer = false))
        assertEquals(setOf("eden"), flags.flagged())
    }

    @Test fun an_exit_apply_that_never_reached_the_server_keeps_the_request() {
        val flags = MemoryApplyOnExitFlags()
        val p = WorkPlanner(autoApply = false, flags)
        p.applied(SyncWork.ApplyRequested, report(waitingOn = setOf("eden")))
        exit(p, "eden", result = report(reachedServer = false))
        assertEquals(setOf("eden"), flags.flagged())
    }

    @Test fun flagged_emulators_are_the_exits_to_watch_for_at_session_start() {
        val flags = MemoryApplyOnExitFlags()
        flags.flag(setOf("eden"))
        assertEquals(setOf("eden"), WorkPlanner(autoApply = false, flags).awaitingExit())
    }
}
