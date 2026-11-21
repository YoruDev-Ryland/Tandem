package dev.tandem.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoreOfTest {
    @Test fun the_core_comes_from_this_devices_item_keys_for_retroarch_only() {
        val keys = setOf("retroarch|Save|retroarch/Pokemon Emerald||mGBA", "retroarch|State|retroarch/Other|1|", "eden|Save|0100|x|core")
        assertEquals("mGBA", coreOf(keys, "retroarch", "retroarch/Pokemon Emerald"))
        assertNull(coreOf(keys, "retroarch", "retroarch/Other"))      // an empty core says nothing
        assertNull(coreOf(keys, "eden", "0100"))
        assertNull(coreOf(keys, "retroarch", "retroarch/Missing"))
    }
}
