package dev.tandem.app.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A downloaded update that passed every check, waiting to be installed. */
@Serializable
data class ReadyApk(val path: String, val versionName: String, val versionCode: Int, val sha256: String)

/** The updater's little state, kept apart from the sync state: nothing in it matters to sync. */
@Serializable
data class UpdateData(
    /** Wall-clock ms of the last check, failed or not. */
    val lastCheckAt: Long? = null,
    /** Why the last check, download or install failed, as shown; null after one that went through. */
    val lastError: String? = null,
    /** Settings › About › Install updates automatically. */
    val autoInstall: Boolean = true,
    val ready: ReadyApk? = null,
    /** "Install now" was pressed (or the notification tapped) for [ready]. */
    val installWanted: Boolean = false,
    /** The versionCode whose install failed: not tried again on its own, only when asked. */
    val failedInstall: Int? = null,
)

/** [UpdateData] in one small JSON file (filesDir/update.json), rewritten whole through a temporary file. */
class UpdateStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val state = MutableStateFlow(read())
    val data: StateFlow<UpdateData> get() = state

    private fun read(): UpdateData = try {
        if (file.isFile) json.decodeFromString<UpdateData>(file.readText()) else UpdateData()
    } catch (_: Exception) {
        UpdateData()
    }

    @Synchronized
    fun update(f: (UpdateData) -> UpdateData) {
        val next = f(state.value)
        if (next == state.value && file.isFile) return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(UpdateData.serializer(), next))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
        state.value = next
    }
}
