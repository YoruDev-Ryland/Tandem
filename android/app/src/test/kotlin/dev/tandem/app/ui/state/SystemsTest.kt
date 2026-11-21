package dev.tandem.app.ui.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemsTest {
    @Test fun single_system_emulators_have_fixed_marks() {
        assertEquals("SWITCH", systemLabel("eden", "0100ABF008968000"))
        assertEquals("3DS", systemLabel("azahar", "00040000001B5000"))
        assertEquals("DS", systemLabel("melonds", "Pokemon Platinum"))
        assertEquals("PSP", systemLabel("ppsspp", "ULUS10400"))
        assertEquals("VITA", systemLabel("vita3k", "PCSE00001"))
    }

    @Test fun dolphin_reads_the_system_from_the_game_id() {
        assertEquals("GC", systemLabel("dolphin", "GALE01"))     // six characters: a GameCube card save
        assertEquals("WII", systemLabel("dolphin", "RMCE"))      // four: a Wii title
        assertEquals("WII", systemLabel("dolphin", "GZ2P"))     // the length wins over the letter...
        assertEquals("GC", systemLabel("dolphin", "gc/GZ2P"))   // ...and a system segment over both
        assertEquals("GC", systemLabel("dolphin", "G8M"))       // other lengths: the first letter
        assertEquals("GC", systemLabel("dolphin", "DX"))
        assertEquals("WII", systemLabel("dolphin", "SOUE01X"))
        assertNull(systemLabel("dolphin", "HAXX0"))
    }

    @Test fun retroarch_uses_a_system_segment_then_the_core_never_its_own_name() {
        assertEquals("GBA", systemLabel("retroarch", "gba/Pokemon - Emerald Version"))
        assertEquals("GBA", systemLabel("retroarch", "retroarch/Pokemon - Emerald Version", core = "mgba_libretro"))
        assertEquals("GBC", systemLabel("retroarch", "retroarch/Pokemon Crystal", core = "Gambatte"))
        assertEquals("SNES", systemLabel("retroarch", "retroarch/Super Metroid", core = "snes9x"))
        assertEquals("NES", systemLabel("retroarch", "retroarch/Zelda", core = "mesen"))
        assertEquals("SNES", systemLabel("retroarch", "retroarch/Zelda", core = "mesen-s"))
        assertNull(systemLabel("retroarch", "retroarch/Pokemon - Emerald Version"))
        assertNull(systemLabel("retroarch", "retroarch/Pokemon - Emerald Version", core = "some_new_core"))
    }

    @Test fun unknown_emulators_only_use_a_segment() {
        assertEquals("PSX", systemLabel("other", "psx/Crash"))
        assertNull(systemLabel("other", "Crash"))
    }

    @Test fun bare_key_strips_only_the_retroarch_prefix() {
        assertEquals("Pokemon - Emerald Version", bareKey("retroarch", "retroarch/Pokemon - Emerald Version"))
        assertEquals("gba/Emerald", bareKey("retroarch", "gba/Emerald"))
        assertEquals("retroarch/x", bareKey("eden", "retroarch/x"))
    }
}
