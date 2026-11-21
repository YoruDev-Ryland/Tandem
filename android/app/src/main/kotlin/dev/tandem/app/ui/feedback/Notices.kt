package dev.tandem.app.ui.feedback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A success line for the hint bar; [id] tells two equal lines apart, so the second one is shown afresh. */
data class Notice(val id: Long, val text: String)

/** How long a success line stays in the hint bar. */
const val NOTICE_MILLIS = 5_000L

/**
 * What just went right, said once in the hint bar's status place for [NOTICE_MILLIS] ("Kept this Thor's save of
 * Pokémon Sword", "Pokémon Sword from Odin 3 is in place."). A newer line replaces the one showing.
 */
class Notices(private val scope: CoroutineScope, private val millis: Long = NOTICE_MILLIS) {
    private val state = MutableStateFlow<Notice?>(null)
    val current: StateFlow<Notice?> get() = state
    private var next = 0L
    private var clear: Job? = null

    fun post(text: String) {
        val n = Notice(++next, text)
        state.value = n
        clear?.cancel()
        clear = scope.launch {
            delay(millis)
            if (state.value == n) state.value = null
        }
    }
}
