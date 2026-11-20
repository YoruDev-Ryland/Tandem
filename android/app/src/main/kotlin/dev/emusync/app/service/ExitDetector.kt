package dev.emusync.app.service

/** Reports alive -> dead transitions per emulator id. The first observation of an id is never a transition. */
class ExitDetector {
    private val last = HashMap<String, Boolean>()

    /** Records [alive] for [id]; true when [id] was alive at the previous observation and is dead now. */
    @Synchronized
    fun observe(id: String, alive: Boolean): Boolean = last.put(id, alive) == true && !alive

    /** Drops ids no longer detected, so a re-detected emulator starts fresh. */
    @Synchronized
    fun retain(ids: Set<String>) {
        last.keys.retainAll(ids)
    }
}
