package dev.emusync.app.sync

import dev.emusync.app.fs.Fs
import dev.emusync.app.modules.Paths

/**
 * Copies files about to be overwritten to `<root>/<emulatorId>/<safeKey>/<millis>/<relpath>` and keeps the newest
 * [SyncConfig.backupsPerItem] backup dirs per item. All I/O goes through [Fs]; exceptions propagate (a failed
 * backup must stop the overwrite).
 */
class LocalBackups(
    private val root: String = DEFAULT_ROOT,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Backs up `baseDir/<file>` for each of [files]. Returns the backup dir, or null when no file exists. */
    fun backup(fs: Fs, emulatorId: String, safeKey: String, baseDir: String, files: List<String>): String? =
        backupFiles(fs, emulatorId, safeKey, files.associateWith { "$baseDir/$it" })

    /** Backs up each existing absolute path under its relative name (rel -> abs). Null when none exists. */
    fun backupFiles(fs: Fs, emulatorId: String, safeKey: String, files: Map<String, String>): String? {
        Paths.requireSafeSegment(emulatorId)
        Paths.requireSafeSegment(safeKey)
        files.keys.forEach { Paths.requireSafeRelative(it) }
        val present = files.filterValues { fs.exists(it) }
        if (present.isEmpty()) return null
        val itemDir = "$root/$emulatorId/$safeKey"
        var t = clock()
        while (fs.exists("$itemDir/$t")) t++   // two backups in the same millisecond
        val dir = "$itemDir/$t"
        for ((rel, abs) in present.toSortedMap()) fs.copy(abs, "$dir/$rel")
        prune(fs, itemDir)
        return dir
    }

    /** Deletes all but the newest [SyncConfig.backupsPerItem] millis-named dirs. Never touches other names. */
    private fun prune(fs: Fs, itemDir: String) {
        val dirs = fs.list(itemDir).filter { it.isDir }
            .mapNotNull { e -> e.path.substringAfterLast('/').toLongOrNull()?.let { it to e.path } }
            .sortedByDescending { it.first }
        for ((_, path) in dirs.drop(SyncConfig.backupsPerItem)) fs.delete(path)
    }

    companion object {
        const val DEFAULT_ROOT = "/storage/emulated/0/Android/data/dev.emusync.app/files/backups"
    }
}
