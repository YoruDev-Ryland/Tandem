using System.Globalization;
using Microsoft.Extensions.Options;
using Tandem.Protocol;
using Tandem.Server.Http;

namespace Tandem.Server.Blobs;

/// <summary>What the server runs in: whether it is a container, and which mount a path lives on.</summary>
public interface IMountTable
{
    bool InContainer { get; }

    /// <summary>The longest mount point that contains the path, or null when unknown.</summary>
    string? MountPointOf(string path);
}

/// <summary>Reads <c>/.dockerenv</c> and <c>/proc/self/mountinfo</c>; outside Linux nothing is known and nothing is a container.</summary>
public sealed class ProcMountTable : IMountTable
{
    public bool InContainer => File.Exists("/.dockerenv");

    public string? MountPointOf(string path)
    {
        string[] lines;
        try { lines = File.ReadAllLines("/proc/self/mountinfo"); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return null; }
        return MountPointOf(lines, path);
    }

    internal static string? MountPointOf(IEnumerable<string> lines, string path)
    {
        var full = Path.GetFullPath(path);
        string? best = null;
        var bestPersistent = true;
        foreach (var line in lines)
        {
            // id parent major:minor root mount-point options ...: the mount point is field 5, octal-escaped.
            var fields = line.Split(' ');
            if (fields.Length < 5) continue;
            var mount = Unescape(fields[4]);
            if (!MoveTargetCheck.IsInside(full, mount, orSame: true)) continue;
            if (best is not null && mount.Length <= best.Length) continue;
            best = mount;
            bestPersistent = !NonPersistent.Contains(FsType(fields));
        }
        // A memory-backed or container-layer filesystem is lost like the container's own root.
        return best is not null && !bestPersistent ? "/" : best;
    }

    private static readonly HashSet<string> NonPersistent =
        ["tmpfs", "proc", "sysfs", "devtmpfs", "devpts", "mqueue", "cgroup", "cgroup2", "overlay"];

    /// <summary>The filesystem type: the field after the " - " separator that ends the optional fields.</summary>
    private static string FsType(string[] fields)
    {
        var dash = Array.IndexOf(fields, "-", 6);
        return dash >= 0 && dash + 1 < fields.Length ? fields[dash + 1] : "";
    }

    private static bool IsOctal(string s, int at) => s.AsSpan(at, 3).IndexOfAnyExceptInRange('0', '7') < 0;

    private static string Unescape(string field)
    {
        if (!field.Contains('\\')) return field;
        var sb = new System.Text.StringBuilder();
        for (var i = 0; i < field.Length; i++)
        {
            if (field[i] == '\\' && i + 3 < field.Length && IsOctal(field, i + 1))
            {
                sb.Append((char)Convert.ToInt32(field.Substring(i + 1, 3), 8));
                i += 3;
            }
            else sb.Append(field[i]);
        }
        return sb.ToString();
    }
}

/// <summary>Byte counts for people: one decimal, 1024-based ("79.0 MB").</summary>
public static class HumanSize
{
    private static readonly string[] Units = ["B", "KB", "MB", "GB", "TB"];

    public static string Format(long bytes)
    {
        if (bytes < 1024) return $"{bytes} B";
        var value = (double)bytes;
        var unit = 0;
        while (value >= 1024 && unit < Units.Length - 1) { value /= 1024; unit++; }
        return string.Create(CultureInfo.InvariantCulture, $"{value:0.0} {Units[unit]}");
    }
}

/// <summary>
/// The checks before the stored files move to a new folder: a full path, not nested in the current location or the data
/// directory, writable, enough space, a real mount when running in a container, and empty. A missing folder is created
/// to test it and removed again when the check fails. A path that is not absolute or is nested in the current location
/// is answered at once (only that item), without touching the disk.
/// </summary>
public sealed class MoveTargetCheck(FilesLocation files, IFreeSpaceProbe probe, IMountTable mounts, IOptions<ServerOptions> options)
{
    public const string AbsoluteProblem = "Use a full path, starting with /.";
    public const string OutsideProblem = "The new folder can't be inside the current one, or hold it.";
    public const string WritableProblem = "Tandem can't create, rename and delete files there.";
    public const string MountProblem = "This folder is inside the container and would be lost on the next update. Mount a folder there first:";
    public const string EmptyProblem = "The folder already holds other files. Pick an empty folder.";

    public MoveCheckResult Run(string path, long bytesToCopy)
    {
        if (string.IsNullOrWhiteSpace(path) || !Path.IsPathFullyQualified(path))
            return Result(path ?? "", bytesToCopy, null, new MoveCheckItem("absolute", false, AbsoluteProblem));
        string full;
        try { full = Trim(Path.GetFullPath(path)); }
        catch (Exception ex) when (ex is ArgumentException or NotSupportedException)
        {
            return Result(path, bytesToCopy, null, new MoveCheckItem("absolute", false, AbsoluteProblem));
        }
        var absolute = new MoveCheckItem("absolute", true, null);

        var outside = Outside(full)
            ? new MoveCheckItem("outside", true, null)
            : new MoveCheckItem("outside", false, OutsideProblem);
        if (!outside.Ok) return Result(full, bytesToCopy, null, absolute, outside);

        var created = TopmostMissing(full);
        MoveCheckItem writable, space, mount, empty;
        try
        {
            empty = Empty(full);
            writable = Writable(full);
            space = Space(full, bytesToCopy);
            mount = Mount(full, out var mountLine);
            var ok = writable.Ok && space.Ok && mount.Ok && empty.Ok;
            if (!ok && created is not null) TryDelete(created);
            return Result(full, bytesToCopy, mountLine, absolute, outside, writable, space, mount, empty);
        }
        catch
        {
            if (created is not null) TryDelete(created);
            throw;
        }
    }

    private static MoveCheckResult Result(string path, long bytes, string? mountLine, params MoveCheckItem[] items) =>
        new(path, items.All(i => i.Ok), bytes, items, mountLine);

    /// <summary>Not inside the current location, not holding it, and not inside the data directory unless it is the default
    /// location. Compared by where each path really points, so a symlink into the current folder is refused too.</summary>
    private bool Outside(string full)
    {
        var real = Resolve(full);
        var current = Resolve(Trim(Path.GetFullPath(files.Root)));
        if (IsInside(real, current, orSame: true) || IsInside(current, real, orSame: false)) return false;
        var data = Resolve(Trim(Path.GetFullPath(options.Value.DataDirectory)));
        var isDefault = string.Equals(real, Resolve(Trim(Path.GetFullPath(files.DefaultRoot))), StringComparison.Ordinal);
        return isDefault || !IsInside(real, data, orSame: true);
    }

    /// <summary>True when <paramref name="path"/> lies below <paramref name="dir"/> (or is it, with <paramref name="orSame"/>); compared with a trailing separator.</summary>
    internal static bool IsInside(string path, string dir, bool orSame)
    {
        path = Trim(path);
        dir = Trim(dir);
        if (path == dir) return orSame;
        var sep = Path.DirectorySeparatorChar.ToString();
        return path.StartsWith(dir.EndsWith(sep) ? dir : dir + sep, StringComparison.Ordinal);
    }

    private static string Trim(string full)
    {
        var root = Path.GetPathRoot(full) ?? "";
        return full.Length > root.Length ? full.TrimEnd(Path.DirectorySeparatorChar) : full;
    }

    /// <summary>Empty, or holding only a marker (a previous cancelled move leaves one with no blobs).</summary>
    private static MoveCheckItem Empty(string full)
    {
        if (!Directory.Exists(full)) return new MoveCheckItem("empty", true, null);
        try
        {
            var others = Directory.EnumerateFileSystemEntries(full)
                .Any(e => Path.GetFileName(e) != FilesLocation.MarkerName);
            return others ? new MoveCheckItem("empty", false, EmptyProblem) : new MoveCheckItem("empty", true, null);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return new MoveCheckItem("empty", false, EmptyProblem);
        }
    }

    /// <summary>Creates the folder if needed, then creates, renames, lists and deletes a probe file.</summary>
    private static MoveCheckItem Writable(string full)
    {
        var bad = new MoveCheckItem("writable", false, WritableProblem);
        var probeFile = Path.Combine(full, $".tandem-probe-{Guid.NewGuid():N}");
        var renamed = probeFile + ".r";
        try
        {
            Directory.CreateDirectory(full);
            File.WriteAllText(probeFile, "probe");
            File.Move(probeFile, renamed);
            var listed = Directory.EnumerateFileSystemEntries(full).Contains(renamed);
            File.Delete(renamed);
            if (!listed) return bad;
            return new MoveCheckItem("writable", true, null);
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            foreach (var f in new[] { probeFile, renamed }) try { File.Delete(f); } catch (Exception) { /* best effort */ }
            return bad;
        }
    }

    private MoveCheckItem Space(string full, long bytesToCopy)
    {
        // An unreadable free-space value does not refuse a move (as in StorageMonitor); the copy itself reports a full disk.
        if (probe.FreeBytes(full) is not { } free) return new MoveCheckItem("space", true, null);
        var need = bytesToCopy + options.Value.MinFreeBytes;
        return free >= need
            ? new MoveCheckItem("space", true, null)
            : new MoveCheckItem("space", false, $"Not enough free space: {HumanSize.Format(need)} needed, {HumanSize.Format(free)} free.");
    }

    private MoveCheckItem Mount(string full, out string? mountLine)
    {
        mountLine = null;
        if (!mounts.InContainer) return new MoveCheckItem("mount", true, null);
        var point = mounts.MountPointOf(Resolve(full));
        if (point is not null && point != "/") return new MoveCheckItem("mount", true, null);
        mountLine = $"- /your/host/folder:{full}";
        return new MoveCheckItem("mount", false, MountProblem);
    }

    /// <summary>The first folder of the path that does not exist yet (what creating the target will add), or null when it exists.</summary>
    private static string? TopmostMissing(string full)
    {
        string? missing = null;
        for (var d = full; d is not null && !Directory.Exists(d); d = Path.GetDirectoryName(d)) missing = d;
        return missing;
    }

    /// <summary>The real path: every existing component that is a symlink is replaced by where it points.</summary>
    private static string Resolve(string full)
    {
        try
        {
            var cur = Path.GetPathRoot(full) ?? "/";
            foreach (var part in full[cur.Length..].Split(Path.DirectorySeparatorChar, StringSplitOptions.RemoveEmptyEntries))
            {
                cur = Path.Combine(cur, part);
                var info = new DirectoryInfo(cur);
                if (info.Exists && info.ResolveLinkTarget(returnFinalTarget: true) is { } target) cur = Trim(target.FullName);
            }
            return cur;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { return full; }
    }

    private static void TryDelete(string dir)
    {
        try { Directory.Delete(dir, true); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { /* best effort */ }
    }
}
