using System.Text.RegularExpressions;

namespace EmuSync.Server.Configs;

public readonly record struct TokenizeResult(string Value, bool DeviceLocal);

/// <summary>
/// Turns plain paths under a device's known roots into portable tokens (<c>{SD}/ROMs/psx</c>) and back.
/// Roots map token to path, e.g. <c>{INTERNAL}</c> to <c>/storage/emulated/0</c>. One layer of matching quotes is
/// stripped before testing and re-applied to the result, so a value's quoting survives.
/// </summary>
public static partial class PathTokens
{
    [GeneratedRegex(@"^[A-Za-z]:[\\/]")]
    private static partial Regex DriveRegex();

    [GeneratedRegex(@"\{[A-Z][A-Z0-9_]*(?::[A-Za-z0-9_.\-]+)?\}")]
    private static partial Regex TokenRegex();

    /// <summary>Strips one layer of matching quotes. <paramref name="quote"/> is the quote char, or '\0' if none.</summary>
    public static string StripQuotes(string value, out char quote)
    {
        if (value.Length >= 2 && value[0] is '"' or '\'' && value[^1] == value[0])
        {
            quote = value[0];
            return value[1..^1];
        }
        quote = '\0';
        return value;
    }

    public static string Requote(string inner, char quote) => quote == '\0' ? inner : quote + inner + quote;

    public static bool IsPath(string value)
    {
        var v = StripQuotes(value, out _);
        return v.StartsWith('/') || v.StartsWith("content://", StringComparison.Ordinal)
            || v.StartsWith("~/", StringComparison.Ordinal) || DriveRegex().IsMatch(v);
    }

    public static TokenizeResult Tokenize(string value, IReadOnlyDictionary<string, string> roots)
    {
        var inner = StripQuotes(value, out var q);
        if (!IsPath(inner)) return new(value, false);
        if (inner.StartsWith("content://", StringComparison.Ordinal)) return new(value, true);

        string? bestToken = null;
        var bestLen = -1;
        foreach (var (token, path) in roots)
        {
            var root = path.TrimEnd('/');
            if (root.Length == 0 || root.Length <= bestLen) continue;
            if (!inner.StartsWith(root, StringComparison.Ordinal)) continue;
            if (inner.Length != root.Length && inner[root.Length] is not ('/' or '\\')) continue;
            (bestToken, bestLen) = (token, root.Length);
        }
        return bestToken is null
            ? new(value, true)
            : new(Requote(bestToken + inner[bestLen..], q), false);
    }

    /// <summary>Replaces every token with its root on this device; <c>null</c> if any token is unknown here.</summary>
    public static string? Expand(string value, IReadOnlyDictionary<string, string> roots)
    {
        var inner = StripQuotes(value, out var q);
        var unknown = false;
        var expanded = TokenRegex().Replace(inner, m =>
        {
            if (roots.TryGetValue(m.Value, out var path)) return path.TrimEnd('/');
            unknown = true;
            return m.Value;
        });
        return unknown ? null : Requote(expanded, q);
    }
}
