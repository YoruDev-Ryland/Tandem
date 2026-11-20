package dev.emusync.app.shell;

import android.os.ParcelFileDescriptor;
import dev.emusync.app.shell.IWatchListener;

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
    boolean isProcessAlive(String pkg) = 8;
    String installedPackagesCsv() = 9;
    void watch(in String[] dirs, IWatchListener l) = 10;
    void unwatchAll() = 11;
}
