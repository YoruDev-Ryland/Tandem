package dev.tandem.app.update

sealed interface Decision {
    /** A debug build: signed with another key and numbered by commit count, so it never updates itself. */
    data object Off : Decision
    data object UpToDate : Decision
    data class Newer(val release: Release) : Decision
}

/** Whether [release] is an update for the build with [installedCode]: only a strictly higher versionCode is. */
fun decide(release: Release, installedCode: Int, debug: Boolean): Decision = when {
    debug -> Decision.Off
    release.versionCode > installedCode -> Decision.Newer(release)
    else -> Decision.UpToDate
}

/**
 * When to ask GitHub: at a sync session's start and then every [INTERVAL_MS]. GitHub allows 60 unauthenticated API
 * calls an hour per address, so a session start right after another check ([SESSION_GAP_MS]) does not ask again. A
 * failed check counts as a check: the next one waits its turn.
 */
object CheckSchedule {
    const val INTERVAL_MS = 6 * 3_600_000L
    const val SESSION_GAP_MS = 30 * 60_000L
    /** How often the service looks at whether a check is due. */
    const val POLL_MS = 15 * 60_000L

    fun due(now: Long, lastCheckAt: Long?, sessionStart: Boolean): Boolean {
        val last = lastCheckAt ?: return true
        if (now < last) return true   // the clock went back: the stored time says nothing
        return now - last >= if (sessionStart) SESSION_GAP_MS else INTERVAL_MS
    }
}
