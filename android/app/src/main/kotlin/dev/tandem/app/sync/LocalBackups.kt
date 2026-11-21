package dev.tandem.app.sync

import dev.tandem.app.fs.Fs
import dev.tandem.app.modules.Paths

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
        val newest = numbered(fs, itemDir).maxOfOrNull { it.first }
        // Always sorts after every existing backup, even when the clock is behind their names.
        var t = if (newest == null) clock() else maxOf(clock(), newest + 1)
        while (fs.exists("$itemDir/$t")) t++
        val dir = "$itemDir/$t"
        for ((rel, abs) in present.toSortedMap()) fs.copy(abs, "$dir/$rel")
        prune(fs, itemDir, keep = dir)
        return dir
    }

    private fun numbered(fs: Fs, itemDir: String): List<Pair<Long, String>> =
        fs.list(itemDir).filter { it.isDir }
            .mapNotNull { e -> e.path.substringAfterLast('/').toLongOrNull()?.let { it to e.path } }

    /** Keeps [keep] plus the newest [SyncConfig.backupsPerItem] - 1 other millis-named dirs. Never touches other names. */
    private fun prune(fs: Fs, itemDir: String, keep: String) {
        val others = numbered(fs, itemDir).filter { it.second != keep }.sortedByDescending { it.first }
        for ((_, path) in others.drop(SyncConfig.backupsPerItem - 1)) fs.delete(path)
    }

    companion object {
        const val DEFAULT_ROOT = "/storage/emulated/0/Android/data/dev.tandem.app/files/backups"
    }
}
