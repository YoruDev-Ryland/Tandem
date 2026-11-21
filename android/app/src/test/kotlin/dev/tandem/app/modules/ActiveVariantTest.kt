package dev.tandem.app.modules

import dev.tandem.app.fs.LocalFs
import dev.tandem.app.modules.eden.EdenModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveVariantTest {
    private fun fs(vararg pkgs: String) = LocalFs().apply { packages.addAll(pkgs) }
    private fun chosen(emulatorId: String, pkg: String) = DeviceSettings(emptyList(), emptyMap(), mapOf(emulatorId to pkg))

    @Test fun choosing_edenduo_makes_eden_detect_return_it_when_installed() {
        val fs = fs("dev.eden.eden_emulator", "dev.igawa6.edenduo")
        assertEquals("dev.eden.eden_emulator", EdenModule().detect(fs, DeviceSettings(emptyList(), emptyMap()))!!.pkg)
        assertEquals("dev.igawa6.edenduo", EdenModule().detect(fs, chosen("eden", "dev.igawa6.edenduo"))!!.pkg)
    }

    @Test fun a_stored_variant_that_is_not_installed_is_ignored() {
        val fs = fs("dev.eden.eden_emulator.combo", "dev.igawa6.edenduo")
        // Preference order, not install order: combo comes before Duo in EdenModule.packages.
        assertEquals("dev.eden.eden_emulator.combo", EdenModule().detect(fs, chosen("eden", "dev.eden.eden_emulator"))!!.pkg)
    }

    @Test fun another_emulators_variant_does_not_apply() {
        val fs = fs("dev.eden.eden_emulator", "dev.igawa6.edenduo")
        assertEquals("dev.eden.eden_emulator", EdenModule().detect(fs, chosen("ppsspp", "dev.igawa6.edenduo"))!!.pkg)
    }

    @Test fun every_module_with_variants_detects_the_chosen_one() {
        val multi = ModuleRegistry.default().all().filter { it.packages.size > 1 }
        assertEquals(setOf("eden", "retroarch", "azahar", "ppsspp"), multi.map { it.emulatorId }.toSet())
        // Azahar finds its folder by searching; the override names one so detection reaches the package choice.
        val folders = mapOf("azahar.root" to "/sdcard/Azahar")
        for (m in multi) {
            val fs = fs(*m.packages.toTypedArray())
            assertEquals(m.emulatorId, m.packages.first(), m.detect(fs, DeviceSettings(emptyList(), folders))?.pkg)
            val pick = DeviceSettings(emptyList(), folders, mapOf(m.emulatorId to m.packages.last()))
            assertEquals(m.emulatorId, m.packages.last(), m.detect(fs, pick)?.pkg)
        }
    }

    @Test fun active_package_rule() {
        val m = EdenModule()
        val s = chosen("eden", "dev.igawa6.edenduo")
        assertEquals("dev.igawa6.edenduo", m.activePackage(setOf("dev.eden.eden_emulator", "dev.igawa6.edenduo"), s))
        assertEquals("dev.eden.eden_emulator", m.activePackage(setOf("dev.eden.eden_emulator"), s))
        assertNull(m.activePackage(emptySet(), s))
    }
}
