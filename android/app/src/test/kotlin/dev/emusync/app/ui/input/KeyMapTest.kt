package dev.emusync.app.ui.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyMapTest {
    @Test fun mapsTheGlobalConstraintsTable() {
        val table = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to Action.Up,
            KeyEvent.KEYCODE_DPAD_DOWN to Action.Down,
            KeyEvent.KEYCODE_DPAD_LEFT to Action.Left,
            KeyEvent.KEYCODE_DPAD_RIGHT to Action.Right,
            KeyEvent.KEYCODE_BUTTON_A to Action.A,
            KeyEvent.KEYCODE_DPAD_CENTER to Action.A,
            KeyEvent.KEYCODE_ENTER to Action.A,
            KeyEvent.KEYCODE_BUTTON_B to Action.B,
            KeyEvent.KEYCODE_BACK to Action.B,
            KeyEvent.KEYCODE_ESCAPE to Action.B,
            KeyEvent.KEYCODE_BUTTON_X to Action.X,
            KeyEvent.KEYCODE_BUTTON_Y to Action.Y,
            KeyEvent.KEYCODE_BUTTON_L1 to Action.L1,
            KeyEvent.KEYCODE_BUTTON_R1 to Action.R1,
            KeyEvent.KEYCODE_BUTTON_L2 to Action.L2,
            KeyEvent.KEYCODE_BUTTON_R2 to Action.R2,
        )
        for ((code, action) in table) assertEquals("key $code", action, keyToAction(code))
        // Every action is reachable.
        assertEquals(Action.entries.toSet(), table.values.toSet())
    }

    @Test fun otherKeysAreNotActions() {
        for (code in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_TAB)) {
            assertNull("key $code", keyToAction(code))
        }
    }

    @Test fun onlyMovementRepeats() {
        assertEquals(setOf(Action.Up, Action.Down, Action.Left, Action.Right), Action.entries.filter { it.repeats }.toSet())
    }

    private class Recorder(private val consume: (Action) -> Boolean = { true }) {
        val seen = mutableListOf<Action>()
        val router = KeyRouter { seen += it; consume(it) }
    }

    @Test fun routesOnKeyDownAndSwallowsTheMatchingUp() {
        val r = Recorder()
        assertTrue(r.router.onKey(KeyEvent.KEYCODE_BUTTON_A, down = true, repeatCount = 0, inTextField = false))
        assertTrue(r.router.onKey(KeyEvent.KEYCODE_BUTTON_A, down = false, repeatCount = 0, inTextField = false))
        assertEquals(listOf(Action.A), r.seen)
    }

    @Test fun anUnhandledKeyPassesThroughDownAndUp() {
        val r = Recorder(consume = { false })
        assertFalse(r.router.onKey(KeyEvent.KEYCODE_BACK, down = true, repeatCount = 0, inTextField = false))
        assertFalse(r.router.onKey(KeyEvent.KEYCODE_BACK, down = false, repeatCount = 0, inTextField = false))
        assertEquals(listOf(Action.B), r.seen)
    }

    @Test fun heldDpadRepeatsButHeldFaceButtonsDoNot() {
        val r = Recorder()
        r.router.onKey(KeyEvent.KEYCODE_DPAD_DOWN, true, 0, false)
        r.router.onKey(KeyEvent.KEYCODE_DPAD_DOWN, true, 1, false)
        r.router.onKey(KeyEvent.KEYCODE_DPAD_DOWN, true, 2, false)
        r.router.onKey(KeyEvent.KEYCODE_DPAD_DOWN, false, 0, false)
        assertEquals(List(3) { Action.Down }, r.seen)

        r.seen.clear()
        r.router.onKey(KeyEvent.KEYCODE_BUTTON_A, true, 0, false)
        // The repeats of a consumed A are swallowed, not routed and not passed on.
        assertTrue(r.router.onKey(KeyEvent.KEYCODE_BUTTON_A, true, 1, false))
        assertTrue(r.router.onKey(KeyEvent.KEYCODE_BUTTON_R1, true, 0, false))
        assertTrue(r.router.onKey(KeyEvent.KEYCODE_BUTTON_R1, true, 1, false))
        assertEquals(listOf(Action.A, Action.R1), r.seen)
    }

    @Test fun inATextFieldOnlyBIsRouted() {
        val r = Recorder()
        for (code in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R1)) {
            assertFalse("key $code", r.router.onKey(code, true, 0, inTextField = true))
            assertFalse(r.router.onKey(code, false, 0, inTextField = true))
        }
        assertTrue(r.router.onKey(KeyEvent.KEYCODE_BACK, true, 0, inTextField = true))
        assertTrue(r.router.onKey(KeyEvent.KEYCODE_ESCAPE, true, 0, inTextField = true))
        assertTrue(r.router.onKey(KeyEvent.KEYCODE_BUTTON_B, true, 0, inTextField = true))
        assertEquals(listOf(Action.B, Action.B, Action.B), r.seen)
    }

    @Test fun unmappedKeysAreNeverConsumed() {
        val r = Recorder()
        assertFalse(r.router.onKey(KeyEvent.KEYCODE_A, true, 0, false))
        assertTrue(r.seen.isEmpty())
    }

    @Test fun actionRouterOffersTheNewestHandlerFirst() {
        val router = ActionRouter()
        val order = mutableListOf<String>()
        router.register { order += "screen"; true }
        val removeSheet = router.register { order += "sheet"; it == Action.B }
        assertTrue(router.dispatch(Action.B))
        assertEquals(listOf("sheet"), order)

        order.clear()
        assertTrue(router.dispatch(Action.X))
        assertEquals(listOf("sheet", "screen"), order)

        order.clear()
        removeSheet()
        router.dispatch(Action.B)
        assertEquals(listOf("screen"), order)
    }

    @Test fun actionRouterReportsUnhandled() {
        val router = ActionRouter()
        router.register { false }
        assertFalse(router.dispatch(Action.Y))
    }
}
