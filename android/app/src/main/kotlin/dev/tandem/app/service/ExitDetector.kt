package dev.tandem.app.service

/** Reports alive -> dead transitions per emulator id. The first observation of an id is never a transition. */
class ExitDetector {
    private val last = HashMap<String, Boolean>()

    /** Records [alive] for [id]; true when [id] was alive at the previous observation and is dead now. */
    @Synchronized
    fun observe(id: String, alive: Boolean): Boolean = last.put(id, alive) == true && !alive

    /**
     * Treats [ids] as alive at a previous observation, so finding one closed reports its exit. A session starts this
     * way for emulators an asked-for apply waits on, in case one closed while the service was down.
     */
    @Synchronized
    fun assumeRunning(ids: Set<String>) {
        for (id in ids) last.putIfAbsent(id, true)
    }

    /** Drops ids no longer detected, so a re-detected emulator starts fresh. */
    @Synchronized
    fun retain(ids: Set<String>) {
        last.keys.retainAll(ids)
    }
}
