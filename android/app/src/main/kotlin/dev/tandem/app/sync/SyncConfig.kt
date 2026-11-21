package dev.tandem.app.sync

object SyncConfig {
    const val quietMillis = 3000L
    const val pollMillis = 60000L
    /** While an emulator runs the status report is resent this often (the server expires the list after 2 min). */
    const val runningReportMillis = 90_000L
    const val backupsPerItem = 5
    /** Server per-blob cap; a larger chunked upload is reset by the server rather than rejected. */
    const val maxBlobBytes = 8L * 1024 * 1024 * 1024
}
