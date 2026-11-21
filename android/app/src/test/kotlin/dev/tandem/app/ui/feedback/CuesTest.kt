package dev.tandem.app.ui.feedback

import android.view.HapticFeedbackConstants
import dev.tandem.app.R
import dev.tandem.app.ui.input.Action
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CuesTest {
    @Test fun a_press_nobody_takes_is_a_reject_except_b_which_leaves_through_the_system() {
        assertEquals(Cue.Reject, cueFor(Action.X, handled = false, explicit = null, repeat = false))
        assertEquals(Cue.Reject, cueFor(Action.Up, handled = false, explicit = null, repeat = false))
        assertNull(cueFor(Action.B, handled = false, explicit = null, repeat = false))
    }

    @Test fun a_taken_press_feels_like_what_it_did() {
        assertEquals(Cue.Confirm, cueFor(Action.A, true, null, false))
        assertEquals(Cue.Confirm, cueFor(Action.Y, true, null, false))
        assertEquals(Cue.Back, cueFor(Action.B, true, null, false))
        assertEquals(Cue.Move, cueFor(Action.Down, true, null, false))
        assertEquals(Cue.Move, cueFor(Action.R1, true, null, false))
    }

    @Test fun what_a_handler_says_wins_and_a_held_key_never_buzzes_against_an_end() {
        assertEquals(Cue.Reject, cueFor(Action.Down, true, Cue.Reject, repeat = false))
        assertNull(cueFor(Action.Down, true, Cue.Reject, repeat = true))
        assertEquals(Cue.Move, cueFor(Action.Down, true, Cue.Move, repeat = true))   // each held step still ticks
        assertNull(cueFor(Action.Down, false, null, repeat = true))
    }

    @Test fun haptics_tick_on_moves_confirm_on_a_and_reject_at_an_end() {
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticFor(Cue.Move))
        assertEquals(HapticFeedbackConstants.CONFIRM, hapticFor(Cue.Confirm))
        assertEquals(HapticFeedbackConstants.REJECT, hapticFor(Cue.Reject))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticFor(Cue.Back))
    }

    @Test fun sounds_are_move_open_and_back_and_a_reject_is_silent() {
        assertEquals(R.raw.ui_move, soundFor(Cue.Move))
        assertEquals(R.raw.ui_open, soundFor(Cue.Confirm))
        assertEquals(R.raw.ui_back, soundFor(Cue.Back))
        assertNull(soundFor(Cue.Reject))
    }

    @Test fun the_sink_plays_one_cue_per_press_and_a_tap_at_once() {
        val played = mutableListOf<Cue>()
        val sink = CueSink { CuePlayer { played += it } }
        assertEquals(true, sink.routed(Action.Down, repeat = false) { sink.cue(Cue.Move); true })
        sink.routed(Action.Down, repeat = false) { sink.cue(Cue.Reject); true }
        sink.routed(Action.A, repeat = false) { true }
        sink.routed(Action.X, repeat = false) { false }
        sink.cue(Cue.Move)   // a tap, outside any press
        assertEquals(listOf(Cue.Move, Cue.Reject, Cue.Confirm, Cue.Reject, Cue.Move), played)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun a_notice_lasts_five_seconds_and_a_newer_one_replaces_it() {
        val scope = TestScope(StandardTestDispatcher())
        val n = Notices(scope)
        n.post("Kept this Thor's save of Pokémon Sword")
        assertEquals("Kept this Thor's save of Pokémon Sword", n.current.value?.text)
        scope.advanceTimeBy(4_000); scope.runCurrent()
        n.post("Pokémon Sword from Odin 3 is in place.")
        scope.advanceTimeBy(4_000); scope.runCurrent()
        assertEquals("Pokémon Sword from Odin 3 is in place.", n.current.value?.text)   // the second's own five seconds
        scope.advanceTimeBy(1_500); scope.runCurrent()
        assertNull(n.current.value)
    }
}
