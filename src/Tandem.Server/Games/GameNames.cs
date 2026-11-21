using System.Text.RegularExpressions;

namespace Tandem.Server.Games;

/// <summary>
/// The name a game is shown under. A real name from the device wins, cleaned of ROM-site tags ("[v0]", "(USA)") and of
/// an emulator-path prefix ("retroarch/"). Switch and 3DS saves are keyed by a 16-hex title id and Dolphin saves by a
/// GameCube/Wii game id (6 or 4 characters), with no name on disk, so those fall back to the public title lists.
/// RetroArch keys are "retroarch/&lt;ROM name&gt;", so without a client name the ROM name itself is used.
/// </summary>
public sealed partial class GameNames(TitleNamesHolder titles)
{
    /// <summary>Emulators whose game keys are console title ids (Switch, 3DS).</summary>
    public static readonly IReadOnlySet<string> TitleIdEmulators = new HashSet<string>(
        ["eden", "yuzu", "suyu", "sudachi", "citron", "torzu", "ryujinx", "azahar", "citra", "lime3ds"], StringComparer.Ordinal);

    public string? Resolve(string emulatorId, string gameKey, string? clientName)
    {
        var cleaned = Clean(clientName);
        var isId = TitleNames.IsTitleId(gameKey);
        var isDisc = emulatorId == "dolphin" && TitleNames.IsDiscId(gameKey);
        if (cleaned is not null && !((isId || isDisc) && cleaned.Equals(gameKey, StringComparison.OrdinalIgnoreCase)))
            return cleaned;
        if (isId && TitleIdEmulators.Contains(emulatorId))
            return Clean(titles.Current?.Lookup(gameKey));
        if (isDisc)
            return Clean(titles.Current?.LookupDisc(gameKey));
        if (gameKey.StartsWith(RetroArchPrefix, StringComparison.Ordinal))
            return Clean(gameKey[RetroArchPrefix.Length..]);
        return cleaned;
    }

    private const string RetroArchPrefix = "retroarch/";

    /// <summary>What activity rows and notifications call a game: its stored name, else a cleaned key, else the key.</summary>
    public static string Label(string gameKey, string? displayName) =>
        !string.IsNullOrWhiteSpace(displayName) ? displayName : Clean(gameKey) ?? gameKey;

    /// <summary>Whether a stored name is missing or just the raw key, so the title list may improve it.</summary>
    public static bool NeedsName(string? displayName, string gameKey) =>
        string.IsNullOrWhiteSpace(displayName) || displayName.Equals(gameKey, StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// Drops ™ ® ©, every [bracket] tag and the (paren) tags ROM sets add for region, language, revision or version.
    /// Parentheses that are part of a title ("(Red)") stay. Null when nothing is left.
    /// </summary>
    public static string? Clean(string? name)
    {
        if (string.IsNullOrWhiteSpace(name)) return null;
        var s = EmulatorPrefix().Replace(name.Trim(), "");
        s = Brackets().Replace(s, " ");
        s = s.Replace("™", "").Replace("®", "").Replace("©", "");
        s = RomParens().Replace(s, " ");
        s = Spaces().Replace(s, " ").Trim();
        return s.Length == 0 ? null : s;
    }

    [GeneratedRegex(@"\[[^\]]*\]")]
    private static partial Regex Brackets();

    /// <summary>A game key used as a name: "retroarch/Pokemon - Emerald Version" shows as "Pokemon - Emerald Version".</summary>
    [GeneratedRegex(@"\A(?:eden|retroarch|azahar|ppsspp|vita3k|dolphin|melonds)/", RegexOptions.IgnoreCase)]
    private static partial Regex EmulatorPrefix();

    private const string Tag =
        @"(?:USA|Europe|Japan|World|Asia|Korea|China|Taiwan|Australia|Brazil|Canada|France|Germany|Italy|Spain|UK|Netherlands|Sweden" +
        @"|En|Fr|De|Es|It|Ja|Nl|Pt|Sv|No|Da|Fi|Zh|Ko|Ru|Pl|English|Japanese|French|German|Spanish|Italian|Chinese|Korean|Rev\s*[\w.]+|v\d[\w.]*|Beta\s*\d*|Proto\s*\d*|Demo|Sample|Unl)";

    [GeneratedRegex(@"\(\s*" + Tag + @"(?:\s*,\s*" + Tag + @")*\s*\)", RegexOptions.IgnoreCase)]
    private static partial Regex RomParens();

    [GeneratedRegex(@"\s+")]
    private static partial Regex Spaces();
}
