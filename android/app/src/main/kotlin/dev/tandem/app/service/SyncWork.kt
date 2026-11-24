package dev.tandem.app.service

import dev.tandem.app.sync.ApplyReport
import dev.tandem.app.sync.StateStore
import dev.tandem.app.sync.SyncConfig

/**
 * How long the process monitor waits before its next probe: [SyncConfig.monitorIdleMillis] while the screen is off
 * and no emulator runs (nobody is playing, so a start is caught a little later), else [SyncConfig.monitorMillis].
 */
fun monitorDelay(screenOn: Boolean, anyRunning: Boolean): Long =
    if (!screenOn && !anyRunning) SyncConfig.monitorIdleMillis else SyncConfig.monitorMillis

/** A unit of engine or applier work, run one at a time by the session's [WorkQueue]. */
sealed interface SyncWork {
    data object FullScan : SyncWork
    data object Tick : SyncWork
    /** An apply the session decided on (auto-apply on). */
    data object ApplyAll : SyncWork
    /** An apply the user asked for: what it holds back for a running emulator is applied when that emulator closes. */
    data object ApplyRequested : SyncWork
    data object Monitor : SyncWork
    /**
     * Installs a downloaded app update if sync is idle ([installBlocker]). It runs in the queue's one consumer, so no
     * scan, upload or apply runs while it installs; an update that is held back is offered again later.
     */
    data object InstallUpdate : SyncWork
}

/** What a sync session has in hand, for the app-update gate. */
data class SyncActivity(
    /** The work items queued and not started (the running one is the caller itself). */
    val waitingWork: Set<SyncWork>,
    /** Uploads the engine holds, debounced or retrying. */
    val queuedUploads: Int,
    /** Emulators whose exit finishes an apply the user asked for. */
    val awaitingExit: Set<String>,
    /** Deliveries held back for a running emulator, applied when it closes. */
    val heldDeliveries: Int,
)

/**
 * Why an app update must not install now, or null when sync is idle: no scan or apply is queued, no upload waits,
 * and nothing waits for an emulator to close to be applied. Replacing the app kills its process, which would cut any
 * of those short. Ticks and process probes come every few seconds whatever happens, so they don't count.
 */
fun installBlocker(a: SyncActivity): String? {
    val work = a.waitingWork - setOf(SyncWork.Tick, SyncWork.Monitor, SyncWork.InstallUpdate)
    return when {
        SyncWork.FullScan in work -> "a scan is queued"
        work.isNotEmpty() -> "an apply is queued"
        a.queuedUploads == 1 -> "1 upload is waiting"
        a.queuedUploads > 1 -> "${a.queuedUploads} uploads are waiting"
        a.awaitingExit.isNotEmpty() -> "an apply waits for ${a.awaitingExit.sorted().joinToString(", ")} to close"
        a.heldDeliveries == 1 -> "1 delivery waits for an emulator to close"
        a.heldDeliveries > 1 -> "${a.heldDeliveries} deliveries wait for an emulator to close"
        else -> null
    }
}

/** The emulators whose next exit finishes an apply the user asked for. */
interface ApplyOnExitFlags {
    fun flagged(): Set<String>
    fun flag(ids: Set<String>)
    fun clear(id: String)
    /** Makes the flagged set exactly [ids]. */
    fun replace(ids: Set<String>)
}

class MemoryApplyOnExitFlags : ApplyOnExitFlags {
    private val ids = LinkedHashSet<String>()
    @Synchronized override fun flagged(): Set<String> = ids.toSet()
    @Synchronized override fun flag(ids: Set<String>) { this.ids += ids }
    @Synchronized override fun clear(id: String) { ids -= id }
    @Synchronized override fun replace(ids: Set<String>) { this.ids.clear(); this.ids += ids }
}

/** The flags persisted in [StateStore], so a session or process restart keeps them. */
class StoredApplyOnExitFlags(private val store: StateStore) : ApplyOnExitFlags {
    override fun flagged(): Set<String> = store.applyOnExit
    override fun flag(ids: Set<String>) { if (!store.applyOnExit.containsAll(ids)) store.flagApplyOnExit(ids) }
    override fun clear(id: String) { if (id in store.applyOnExit) store.clearApplyOnExit(id) }
    override fun replace(ids: Set<String>) { if (store.applyOnExit != ids) store.setApplyOnExit(ids) }
}

/**
 * Decides which work each trigger queues. With [autoApply] false, nothing but [explicitApply] ever yields an apply,
 * and an emulator exit applies only what an explicit apply left waiting for it ([flags]).
 */
class WorkPlanner(private val autoApply: Boolean, private val flags: ApplyOnExitFlags = MemoryApplyOnExitFlags()) {
    /** Right after the session's first full scan. */
    fun sessionStarted(): List<SyncWork> = applyIfAuto()

    /** The server's `deliveries` event. */
    fun deliveriesEvent(): List<SyncWork> = applyIfAuto()

    /** The server's `storage` event: a hold on uploads started or ended. Scans, which sends what was held. */
    fun storageEvent(): List<SyncWork> = listOf(SyncWork.FullScan)

    /**
     * How long to wait before rescanning after a full scan, or null for no rescan. A scan the server's hold turned
     * away ([moving]) runs again after the server's `Retry-After` (a fixed [MOVING_RETRY_MS]).
     */
    fun afterScan(moving: Boolean): Long? = if (moving) MOVING_RETRY_MS else null

    /** The periodic delivery poll. */
    fun poll(): List<SyncWork> = applyIfAuto()

    private var lastPollAt: Long? = null

    /**
     * The delivery poll's tick at [now] (ms, monotonic). While the event stream is [streamHealthy] its nudges bring
     * deliveries, so the poll runs only every [fallbackMillis]; otherwise on every tick.
     */
    fun pollTick(streamHealthy: Boolean, now: Long, fallbackMillis: Long = SyncConfig.pollFallbackMillis): List<SyncWork> {
        val last = lastPollAt
        if (streamHealthy && last != null && now - last in 0 until fallbackMillis) return emptyList()
        lastPollAt = now
        return poll()
    }

    /** The event stream opened; [reconnect] is false for the session's first open. */
    fun streamOpened(reconnect: Boolean): List<SyncWork> =
        (if (reconnect) listOf(SyncWork.FullScan) else emptyList()) + applyIfAuto()

    /** "Sync now", a resolve, a setup choice, "Apply deliveries now". Always applies (marker recovery runs at the start of every apply). */
    fun explicitApply(): List<SyncWork> = listOf(SyncWork.ApplyRequested)

    /**
     * After an apply pass ran [work]: the emulators an asked-for apply waited on finish it when they close. A pass that
     * reached the server saw every delivery, so its waiting set replaces the flags (dropping any left by an exit the
     * service missed); one that didn't keeps them and only adds what it saw held.
     */
    fun applied(work: SyncWork, report: ApplyReport) {
        if (work != SyncWork.ApplyRequested) return
        if (report.reachedServer) flags.replace(report.waitingOn)
        else if (report.waitingOn.isNotEmpty()) flags.flag(report.waitingOn)
    }

    /** The emulators an asked-for apply still waits on; the session treats them as running until it sees them closed. */
    fun awaitingExit(): Set<String> = flags.flagged()

    /**
     * Emulator [id] exited. Applies through [apply] (the normal applier pass, which re-checks the process and backs
     * up before writing) when auto-apply is on or an asked-for apply waited for it; `requested` tells the applier to
     * run even if this session held nothing. Otherwise only [forget]s the waiting entries. The flag is cleared once
     * the pass returns, and set again when the emulator held the deliveries back once more (reopened at once); it stays when the pass
     * never reached the server.
     * Returns the pass's report, or null when nothing was applied.
     */
    suspend fun exited(id: String, apply: suspend (requested: Boolean) -> ApplyReport, forget: suspend () -> Unit): ApplyReport? {
        val requested = id in flags.flagged()
        if (!requested && !autoApply) {
            forget()
            return null
        }
        val r = apply(requested)
        // A pass that never reached the server finished nothing: the request stands until one does.
        if (requested && r.reachedServer) {
            flags.clear(id)
            if (id in r.waitingOn) flags.flag(setOf(id))
        }
        return r
    }

    fun tick(): List<SyncWork> = listOf(SyncWork.Tick)
    fun monitor(): List<SyncWork> = listOf(SyncWork.Monitor)
    fun periodicScan(): List<SyncWork> = listOf(SyncWork.FullScan)
    fun explicitScan(): List<SyncWork> = listOf(SyncWork.FullScan)

    companion object { const val MOVING_RETRY_MS = 15_000L }

    private fun applyIfAuto(): List<SyncWork> = if (autoApply) listOf(SyncWork.ApplyAll) else emptyList()
}
