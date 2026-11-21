package dev.tandem.app.sync

object SyncConfig {
    const val quietMillis = 3000L
    /** The delivery poll's tick. While the event stream is healthy the poll runs only every [pollFallbackMillis]. */
    const val pollMillis = 60000L
    /** A poll even while the stream says it is up, in case a nudge was lost. */
    const val pollFallbackMillis = 5 * 60_000L
    /** While an emulator runs the status report is resent this often (the server expires the list after 2 min). */
    const val runningReportMillis = 60_000L
    /** The process monitor's period; [monitorIdleMillis] with the screen off and no emulator running. */
    const val monitorMillis = 5_000L
    const val monitorIdleMillis = 20_000L
    const val backupsPerItem = 5
    /** Server per-blob cap; a larger chunked upload is reset by the server rather than rejected. */
    const val maxBlobBytes = 8L * 1024 * 1024 * 1024
}
