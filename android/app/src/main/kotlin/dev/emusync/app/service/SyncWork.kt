package dev.emusync.app.service

/** A unit of engine or applier work, run one at a time by the session's [WorkQueue]. */
sealed interface SyncWork {
    data object FullScan : SyncWork
    data object Tick : SyncWork
    data object ApplyAll : SyncWork
    data object Monitor : SyncWork
}

/**
 * Decides which work each trigger queues. With [autoApply] false, nothing but [explicitApply] ever yields
 * [SyncWork.ApplyAll], and an emulator exit doesn't apply what waited for it.
 */
class WorkPlanner(private val autoApply: Boolean) {
    /** Right after the session's first full scan. */
    fun sessionStarted(): List<SyncWork> = applyIfAuto()

    /** The server's `deliveries` event. */
    fun deliveriesEvent(): List<SyncWork> = applyIfAuto()

    /** The periodic delivery poll. */
    fun poll(): List<SyncWork> = applyIfAuto()

    /** The event stream opened; [reconnect] is false for the session's first open. */
    fun streamOpened(reconnect: Boolean): List<SyncWork> =
        (if (reconnect) listOf(SyncWork.FullScan) else emptyList()) + applyIfAuto()

    /** Whether an emulator exit also retries the deliveries that waited for it. */
    fun applyOnExit(): Boolean = autoApply

    /** "Apply deliveries now". Always applies (marker recovery runs at the start of every apply). */
    fun explicitApply(): List<SyncWork> = listOf(SyncWork.ApplyAll)

    fun tick(): List<SyncWork> = listOf(SyncWork.Tick)
    fun monitor(): List<SyncWork> = listOf(SyncWork.Monitor)
    fun periodicScan(): List<SyncWork> = listOf(SyncWork.FullScan)
    fun explicitScan(): List<SyncWork> = listOf(SyncWork.FullScan)

    private fun applyIfAuto(): List<SyncWork> = if (autoApply) listOf(SyncWork.ApplyAll) else emptyList()
}
