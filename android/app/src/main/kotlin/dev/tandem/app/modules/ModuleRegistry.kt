package dev.tandem.app.modules

import dev.tandem.app.modules.eden.EdenModule
import dev.tandem.app.modules.dolphin.DolphinModule
import dev.tandem.app.modules.retroarch.RetroArchModule
import dev.tandem.app.modules.melonds.MelonDsModule
import dev.tandem.app.modules.azahar.AzaharModule
import dev.tandem.app.modules.ppsspp.PpssppModule
import dev.tandem.app.modules.vita3k.Vita3kModule

/**
 * The emulator modules, one per emulator id. Each module's detection takes its active package (the chosen variant
 * when installed, else the first installed of [EmulatorModule.packages]); the running check covers all of them.
 */
class ModuleRegistry(private val modules: List<EmulatorModule>) {
    fun all(): List<EmulatorModule> = modules
    fun byId(id: String): EmulatorModule? = modules.firstOrNull { it.emulatorId == id }

    companion object {
        fun default() = ModuleRegistry(listOf(EdenModule(), DolphinModule(), RetroArchModule(), MelonDsModule(), AzaharModule(), PpssppModule(), Vita3kModule()))
    }
}
