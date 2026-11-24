package dev.tandem.app.shell;

import android.os.ParcelFileDescriptor;
import dev.tandem.app.shell.IWatchListener;

interface IShellService {
    // Reserved id required by Shizuku for the UserService destroy hook.
    void destroy() = 16777114;

    // JSON array of FileEntry, sorted, paged by offset/limit. Empty array for a missing dir.
    String listJson(String dir, int offset, int limit) = 1;
    // Sorted recursive files only with path > afterPath ("" = start); limit <= 2000.
    String walkJson(String dir, String afterPath, int limit) = 2;
    // JSON FileEntry, or empty string if missing.
    String statJson(String path) = 3;
    ParcelFileDescriptor openRead(String path) = 4;
    void writeAtomic(String path, in ParcelFileDescriptor src) = 5;
    boolean delete(String path) = 6;
    void copy(String src, String dst) = 7;
    // 8 was isProcessAlive(String pkg): retired, do not reuse.
    String installedPackagesCsv() = 9;
    void watch(in String[] dirs, IWatchListener l) = 10;
    void unwatchAll() = 11;
    // 1 (ProbeParse.REPLY_ALIVE) when a process named exactly one of pkgs, or "<pkg>:<sub>", is alive; 2 when none
    // is. Any other value (0 is an old service's empty reply) is a probe failure. Throws a PROBE error when the process
    // list can't be trusted (fail closed).
    int isAnyProcessAlive(in String[] pkgs) = 12;
    // Runs `pm install -r -S size` with the APK streamed on stdin and returns what pm printed ("Success" or
    // "Failure [...]"). An old service without it answers null: install another way.
    String installApk(in ParcelFileDescriptor apk, long size) = 13;
}
