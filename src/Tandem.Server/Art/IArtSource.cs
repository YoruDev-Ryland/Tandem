namespace Tandem.Server.Art;

/// <summary>What the sources know about a game: its key, the name it is shown under and (RetroArch) the save's core.</summary>
/// <summary>What is fetched for a game: its front cover, or its real case spine.</summary>
public enum ArtKind { Cover, Spine }

/// <param name="System">The system a device reported for the game, used when the key and core don't tell.</param>
/// <param name="Kind">Cover (the art sources) or Spine (a GameTDB case scan, cut).</param>
/// <param name="GameId">The GameTDB id a device reported (DS, 3DS), for spines.</param>
public sealed record ArtRequest(string EmulatorId, string GameKey, string? DisplayName, string? Core, string? System = null,
    ArtKind Kind = ArtKind.Cover, string? GameId = null);

/// <summary>
/// One place cover art may come from. A source only builds addresses; the fetcher downloads them in order and keeps the
/// first real image. An empty list means the source has nothing for this game.
/// </summary>
public interface IArtSource
{
    /// <summary>Addresses to try, best first. Throws <see cref="ArtSourceUnavailableException"/> when the source could not
    /// be asked (its index could not be loaded), so the game is retried sooner than a real miss.</summary>
    Task<IReadOnlyList<Uri>> CandidatesAsync(ArtRequest request, CancellationToken ct);
}

/// <summary>A source could not be reached; not a miss.</summary>
public sealed class ArtSourceUnavailableException(string message, Exception? inner = null) : Exception(message, inner);

/// <summary>
/// The game's system, by the same rules as the app's <c>Systems.kt</c> and the web's <c>systems.ts</c>: fixed for
/// single-system emulators, from the game id for Dolphin, from a key segment or the core for RetroArch.
/// </summary>
public static class ArtSystems
{
    private static readonly Dictionary<string, string> ByEmulator = new(StringComparer.Ordinal)
    {
        ["eden"] = "SWITCH", ["azahar"] = "3DS", ["melonds"] = "DS", ["ppsspp"] = "PSP", ["vita3k"] = "VITA",
    };

    private static readonly Dictionary<string, string> BySegment = new(StringComparer.OrdinalIgnoreCase)
    {
        ["gba"] = "GBA", ["gbc"] = "GBC", ["gb"] = "GB", ["nes"] = "NES", ["snes"] = "SNES", ["nds"] = "DS", ["ds"] = "DS",
        ["n64"] = "N64", ["gc"] = "GC", ["gamecube"] = "GC", ["wii"] = "WII", ["md"] = "MD", ["genesis"] = "MD",
        ["psp"] = "PSP", ["psx"] = "PSX", ["ps1"] = "PSX",
    };

    private static readonly (System.Text.RegularExpressions.Regex Re, string System)[] ByCore =
    [
        (new(@"^(mgba|vba[-_ ]?m|vbam|gpsp|meteor|beetle[-_ ]?gba|mednafen[-_ ]?gba)"), "GBA"),
        (new(@"^(gambatte|sameboy|gearboy|tgbdual)"), "GBC"),
        (new(@"^(melonds|desmume)"), "DS"),
        (new(@"^(snes9x|bsnes|mesen[-_ ]?s|beetle[-_ ]?supafaust)"), "SNES"),
        (new(@"^(fceumm|nestopia|quicknes|mesen)"), "NES"),
        (new(@"^(mupen64|parallel[-_ ]?n64)"), "N64"),
        (new(@"^(genesis[-_ ]?plus|picodrive|blastem)"), "MD"),
        (new(@"^(pcsx|swanstation|duckstation|beetle[-_ ]?psx|mednafen[-_ ]?psx)"), "PSX"),
        (new(@"^ppsspp"), "PSP"),
        (new(@"^dolphin"), "GC"),
    ];

    /// <summary>Every label <see cref="Label"/> can give: what a device may report as a game's system.</summary>
    public static readonly IReadOnlySet<string> Known = new HashSet<string>(StringComparer.Ordinal)
    {
        "SWITCH", "3DS", "DS", "PSP", "VITA", "GBA", "GBC", "GB", "NES", "SNES", "N64", "GC", "WII", "MD", "PSX",
    };

    /// <summary>A device-reported system as a known label (case-insensitive), or null.</summary>
    public static string? Normalize(string? reported)
    {
        var s = reported?.Trim().ToUpperInvariant();
        return s is not null && Known.Contains(s) ? s : null;
    }

    /// <summary>
    /// The game's system: <see cref="Label"/>, or else the system a device reported with the install. What the key,
    /// the game id and the save's core say wins over a device's report.
    /// </summary>
    public static string? Resolve(string emulatorId, string gameKey, string? core, string? reported) =>
        Label(emulatorId, gameKey, core) ?? Normalize(reported);

    /// <summary>SWITCH, 3DS, DS, GBA, GC, WII, …; null when it cannot be told.</summary>
    public static string? Label(string emulatorId, string gameKey, string? core)
    {
        if (ByEmulator.TryGetValue(emulatorId, out var fixedSystem)) return fixedSystem;
        var parts = gameKey.Split('/');
        if (emulatorId == "dolphin")
        {
            foreach (var seg in parts[..^1])
                if (BySegment.TryGetValue(seg, out var s) && s is "GC" or "WII") return s;
            var id = parts[^1];
            if (id.Length == 6 && id.All(char.IsAsciiLetterOrDigit)) return "GC";
            if (id.Length == 4 && id.All(char.IsAsciiLetterOrDigit)) return "WII";
            return char.ToUpperInvariant(id.FirstOrDefault()) switch { 'G' or 'D' => "GC", 'R' or 'S' => "WII", _ => null };
        }
        if (emulatorId == "retroarch")
        {
            foreach (var seg in parts[..^1])
                if (BySegment.TryGetValue(seg, out var s)) return s;
            var c = System.Text.RegularExpressions.Regex.Replace((core ?? "").ToLowerInvariant(), "_libretro.*$", "").Trim();
            if (c.Length > 0)
                foreach (var (re, system) in ByCore)
                    if (re.IsMatch(c)) return system;
            return null;
        }
        return parts.Length > 1 && BySegment.TryGetValue(parts[0], out var seg0) ? seg0 : null;
    }
}
