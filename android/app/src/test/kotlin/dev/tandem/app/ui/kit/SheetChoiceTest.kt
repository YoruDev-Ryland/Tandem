package dev.tandem.app.ui.kit

import dev.tandem.app.ui.input.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetChoiceTest {
    private var clock = 1_000L
    private var confirms = 0

    @Test fun aButtonPressConfirmsOnlyAfterTheSheetSettled() {
        val c = SheetChoice { clock }
        c.move(Action.Left)
        c.press({ confirms++ }, {})
        assertEquals("a quick second press never confirms", 0, confirms)
        clock += SHEET_SETTLE_MS
        c.press({ confirms++ }, {})
        assertEquals(1, confirms)
    }

    @Test fun aTapConfirmsSoonerAndAnEarlyTapIsNotSwallowed() {
        val c = SheetChoice { clock }
        assertFalse(c.onConfirm)
        clock += 100
        c.tap { confirms++ }
        assertEquals(0, confirms)
        assertTrue("the early tap moved the cursor onto confirm", c.onConfirm)
        clock += TAP_SETTLE_MS   // well before the button press settle
        c.tap { confirms++ }
        assertEquals(1, confirms)
    }
}
