using System.Runtime.InteropServices;

namespace Tandem.Server.Blobs;

/// <summary>fsync of a directory, so a rename into it is on disk (F-07). .NET has no API for it; on Linux and macOS
/// this opens the directory read-only and fsyncs the descriptor. Best effort: elsewhere, or on failure, it does nothing
/// (the file's own data was already flushed to disk).</summary>
public static class Durability
{
    public static bool SyncDirectory(string directory)
    {
        if (!OperatingSystem.IsLinux() && !OperatingSystem.IsMacOS()) return false;
        try
        {
            var fd = Open(directory, 0 /* O_RDONLY */);
            if (fd < 0) return false;
            try { return Fsync(fd) == 0; }
            finally { _ = Close(fd); }
        }
        catch (Exception ex) when (ex is DllNotFoundException or EntryPointNotFoundException)
        {
            return false;
        }
    }

    // DllImport rather than LibraryImport: the source-generated form needs unsafe code, and these signatures marshal
    // trivially (a UTF-8 path and ints).
    [DllImport("libc", EntryPoint = "open", SetLastError = true)]
    private static extern int Open([MarshalAs(UnmanagedType.LPUTF8Str)] string path, int flags);

    [DllImport("libc", EntryPoint = "fsync", SetLastError = true)]
    private static extern int Fsync(int fd);

    [DllImport("libc", EntryPoint = "close", SetLastError = true)]
    private static extern int Close(int fd);
}
