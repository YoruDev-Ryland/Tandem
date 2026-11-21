using System.Text.RegularExpressions;

namespace EmuSync.Server.Games;

/// <summary>
/// The name a game is shown under. A real name from the device wins, cleaned of ROM-site tags ("[v0]", "(USA)").
/// Switch saves are keyed by a 16-hex title id with no name on disk, so those fall back to the public title list.
/// </summary>
public sealed partial class GameNames(TitleNamesHolder titles)
{
    /// <summary>Emulators whose game keys are Switch title ids.</summary>
    public static readonly IReadOnlySet<string> SwitchEmulators =
        new HashSet<string>(["eden", "yuzu", "suyu", "sudachi", "citron", "torzu", "ryujinx"], StringComparer.Ordinal);

    public string? Resolve(string emulatorId, string gameKey, string? clientName)
    {
        var cleaned = Clean(clientName);
        var isId = TitleNames.IsTitleId(gameKey);
        if (cleaned is not null && !(isId && cleaned.Equals(gameKey, StringComparison.OrdinalIgnoreCase)))
            return cleaned;
        if (isId && SwitchEmulators.Contains(emulatorId))
            return titles.Current?.Lookup(gameKey);
        return cleaned;
    }

    /// <summary>Whether a stored name is missing or just the raw key, so the title list may improve it.</summary>
    public static bool NeedsName(string? displayName, string gameKey) =>
        string.IsNullOrWhiteSpace(displayName) || displayName.Equals(gameKey, StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// Drops every [bracket] tag and the (paren) tags ROM sets add for region, language, revision or version.
    /// Parentheses that are part of a title ("(Red)") stay. Null when nothing is left.
    /// </summary>
    public static string? Clean(string? name)
    {
        if (string.IsNullOrWhiteSpace(name)) return null;
        var s = Brackets().Replace(name, " ");
        s = RomParens().Replace(s, " ");
        s = Spaces().Replace(s, " ").Trim();
        return s.Length == 0 ? null : s;
    }

    [GeneratedRegex(@"\[[^\]]*\]")]
    private static partial Regex Brackets();

    private const string Tag =
        @"(?:USA|Europe|Japan|World|Asia|Korea|China|Taiwan|Australia|Brazil|Canada|France|Germany|Italy|Spain|UK|Netherlands|Sweden" +
        @"|En|Fr|De|Es|It|Ja|Nl|Pt|Sv|No|Da|Fi|Zh|Ko|Ru|Pl|Rev\s*[\w.]+|v\d[\w.]*|Beta\s*\d*|Proto\s*\d*|Demo|Sample|Unl)";

    [GeneratedRegex(@"\(\s*" + Tag + @"(?:\s*,\s*" + Tag + @")*\s*\)", RegexOptions.IgnoreCase)]
    private static partial Regex RomParens();

    [GeneratedRegex(@"\s+")]
    private static partial Regex Spaces();
}
