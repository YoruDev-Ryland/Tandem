package dev.emusync.app.modules

import dev.emusync.app.modules.eden.EdenModule
import dev.emusync.app.modules.dolphin.DolphinModule
import dev.emusync.app.modules.retroarch.RetroArchModule
import dev.emusync.app.modules.melonds.MelonDsModule

class ModuleRegistry(private val modules: List<EmulatorModule>) {
    fun all(): List<EmulatorModule> = modules
    fun byId(id: String): EmulatorModule? = modules.firstOrNull { it.emulatorId == id }

    companion object {
        fun default() = ModuleRegistry(listOf(EdenModule(), DolphinModule(), RetroArchModule(), MelonDsModule()))
    }
}
