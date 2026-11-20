package dev.emusync.app.modules

import dev.emusync.app.modules.eden.EdenModule

class ModuleRegistry(private val modules: List<EmulatorModule>) {
    fun all(): List<EmulatorModule> = modules
    fun byId(id: String): EmulatorModule? = modules.firstOrNull { it.emulatorId == id }

    companion object {
        fun default() = ModuleRegistry(listOf(EdenModule()))
    }
}
