using EmuSync.Protocol;
using EmuSync.Server.Data;
using EmuSync.Server.Http;
using Microsoft.EntityFrameworkCore;

namespace EmuSync.Server.Configs;

/// <summary>
/// Line diff (Myers O(ND)) with 3 lines of context. Lines are split on '\n' and compared without their terminator
/// (a trailing '\r' is dropped), so a CRLF/LF-only change yields no hunks even though the bytes differ, and a
/// missing final newline is not reported. The text of a line never includes its terminator.
/// </summary>
public static class ConfigDiff
{
    public const int Context = 3;

    /// <summary>Beyond this many edits the exact script is not worth its memory: report the whole file as replaced.</summary>
    private const int MaxEdits = 3000;

    public static List<DiffHunk> Hunks(string oldText, string newText)
    {
        var a = Lines(oldText);
        var b = Lines(newText);
        return Group(Script(a, b));
    }

    public static string[] Lines(string text)
    {
        if (text.Length == 0) return [];
        var lines = text.Split('\n');
        if (lines[^1].Length == 0) lines = lines[..^1];
        for (var i = 0; i < lines.Length; i++)
            if (lines[i].EndsWith('\r')) lines[i] = lines[i][..^1];
        return lines;
    }

    private readonly record struct Edit(char Op, string Text);

    /// <summary>The full edit script: every line of both files, as ' ', '-' or '+'.</summary>
    private static List<Edit> Script(string[] a, string[] b)
    {
        var start = 0;
        while (start < a.Length && start < b.Length && a[start] == b[start]) start++;
        var endA = a.Length;
        var endB = b.Length;
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB--; }

        var script = new List<Edit>(a.Length + b.Length);
        for (var i = 0; i < start; i++) script.Add(new Edit(' ', a[i]));
        script.AddRange(Myers(a[start..endA], b[start..endB]));
        for (var i = endA; i < a.Length; i++) script.Add(new Edit(' ', a[i]));
        return script;
    }

    private static List<Edit> Myers(string[] a, string[] b)
    {
        int n = a.Length, m = b.Length;
        if (n == 0) return b.Select(l => new Edit('+', l)).ToList();
        if (m == 0) return a.Select(l => new Edit('-', l)).ToList();

        var max = n + m;
        var offset = max + 1;
        var v = new int[2 * max + 3];
        var trace = new List<int[]>();
        var found = false;
        for (var d = 0; d <= max && !found; d++)
        {
            if (d > MaxEdits) return a.Select(l => new Edit('-', l)).Concat(b.Select(l => new Edit('+', l))).ToList();
            trace.Add((int[])v.Clone());
            for (var k = -d; k <= d; k += 2)
            {
                int x = k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1]) ? v[offset + k + 1] : v[offset + k - 1] + 1;
                var y = x - k;
                while (x < n && y < m && a[x] == b[y]) { x++; y++; }
                v[offset + k] = x;
                if (x >= n && y >= m) { found = true; break; }
            }
        }

        // Walk back through the saved frontiers.
        var edits = new List<Edit>();
        int px = n, py = m;
        for (var d = trace.Count - 1; d >= 0; d--)
        {
            var frontier = trace[d];
            var k = px - py;
            int prevK = k == -d || (k != d && frontier[offset + k - 1] < frontier[offset + k + 1]) ? k + 1 : k - 1;
            var prevX = frontier[offset + prevK];
            var prevY = prevX - prevK;
            while (px > prevX && py > prevY) { edits.Add(new Edit(' ', a[px - 1])); px--; py--; }
            if (d > 0)
            {
                if (px == prevX) edits.Add(new Edit('+', b[py - 1]));
                else edits.Add(new Edit('-', a[px - 1]));
            }
            px = prevX;
            py = prevY;
        }
        edits.Reverse();
        return edits;
    }

    private static List<DiffHunk> Group(List<Edit> script)
    {
        var hunks = new List<DiffHunk>();
        // Old/new line number (1-based) of each script position.
        var oldNo = new int[script.Count + 1];
        var newNo = new int[script.Count + 1];
        int o = 1, nn = 1;
        for (var i = 0; i < script.Count; i++)
        {
            oldNo[i] = o;
            newNo[i] = nn;
            if (script[i].Op != '+') o++;
            if (script[i].Op != '-') nn++;
        }

        var idx = 0;
        while (idx < script.Count)
        {
            while (idx < script.Count && script[idx].Op == ' ') idx++;
            if (idx >= script.Count) break;
            var first = Math.Max(0, idx - Context);
            var last = idx; // last changed position so far
            var scan = idx;
            while (scan < script.Count)
            {
                if (script[scan].Op != ' ') { last = scan; scan++; continue; }
                // Look ahead: another change within 2*Context unchanged lines keeps the hunk going.
                var gap = 0;
                while (scan + gap < script.Count && script[scan + gap].Op == ' ') gap++;
                if (scan + gap < script.Count && gap <= 2 * Context) { scan += gap; continue; }
                break;
            }
            var end = Math.Min(script.Count, last + 1 + Context);
            hunks.Add(new DiffHunk(oldNo[first], newNo[first],
                script.Skip(first).Take(end - first).Select(e => new DiffLine(e.Op, e.Text)).ToList()));
            idx = end;
        }
        return hunks;
    }
}

/// <summary>Reads config version files and diffs versions, canonical or expanded for one device.</summary>
public sealed class ConfigDiffService(EmuSyncDbContext db, ConfigTransform transform)
{
    private sealed record Loaded(List<ManifestEntry> Manifest, string EmulatorId);

    public async Task<string> FileTextAsync(Guid userId, Guid versionId, string path, Guid? deviceId, CancellationToken ct)
    {
        var loaded = await LoadAsync(userId, versionId, deviceId, ct);
        var entry = loaded.Manifest.FirstOrDefault(e => e.Path == path)
            ?? throw new ApiException(404, "file_not_found", "That file is not in this version.");
        return await transform.ReadTextAsync(userId, entry, ct)
            ?? throw new ApiException(415, "not_text", "That file is binary or too large to show as text.");
    }

    public async Task<ConfigDiffResponse> DiffAsync(Guid userId, Guid a, Guid b, Guid? deviceId, CancellationToken ct)
    {
        var from = await LoadAsync(userId, a, deviceId, ct);
        var to = await LoadAsync(userId, b, deviceId, ct);
        if (from.EmulatorId != to.EmulatorId)
            throw new ApiException(400, "wrong_emulator", "The versions are for different emulators.");
        var oldFiles = from.Manifest.ToDictionary(e => e.Path, StringComparer.Ordinal);
        var newFiles = to.Manifest.ToDictionary(e => e.Path, StringComparer.Ordinal);

        var files = new List<FileDiff>();
        foreach (var path in oldFiles.Keys.Union(newFiles.Keys).Order(StringComparer.Ordinal))
        {
            oldFiles.TryGetValue(path, out var o);
            newFiles.TryGetValue(path, out var n);
            if (o is not null && n is not null && o.Hash == n.Hash)
            {
                files.Add(new FileDiff(path, FileDiffStatus.Same, []));
                continue;
            }
            var status = o is null ? FileDiffStatus.Added : n is null ? FileDiffStatus.Removed : FileDiffStatus.Changed;
            var oldText = o is null ? "" : await transform.ReadTextAsync(userId, o, ct);
            var newText = n is null ? "" : await transform.ReadTextAsync(userId, n, ct);
            // A binary or oversized side gives no hunks, only the status.
            files.Add(new FileDiff(path, status, oldText is null || newText is null ? [] : ConfigDiff.Hunks(oldText, newText)));
        }
        return new ConfigDiffResponse(files);
    }

    private async Task<Loaded> LoadAsync(Guid userId, Guid versionId, Guid? deviceId, CancellationToken ct)
    {
        if (deviceId is Guid device && !await db.Devices.AnyAsync(d => d.Id == device && d.UserId == userId, ct))
            throw new ApiException(404, "device_not_found", "Device not found.");
        var row = await db.ConfigVersions.AsNoTracking().Where(v => v.Id == versionId)
                .Join(db.ConfigBundles.AsNoTracking().Where(b => b.UserId == userId), v => v.BundleId, b => b.Id,
                    (v, b) => new { v.ManifestJson, b.EmulatorId })
                .FirstOrDefaultAsync(ct)
            ?? throw new ApiException(404, "version_not_found", "Version not found.");
        var canonical = Saves.Manifests.FromJson(row.ManifestJson);
        if (deviceId is not Guid target) return new Loaded(canonical, row.EmulatorId);
        var (expanded, _) = await transform.ExpandAsync(userId, target, row.EmulatorId, canonical, ct);
        return new Loaded(expanded, row.EmulatorId);
    }

}
