using System.Text.RegularExpressions;
using Tandem.Server.Games;

namespace Tandem.Server.Art;

/// <summary>Which games can have a real spine cut from a GameTDB case scan, and the GameTDB ids devices report.</summary>
public static partial class SpineEligibility
{
    [GeneratedRegex("^[A-Z0-9]{4}$")]
    private static partial Regex FourAlnum();

    /// <summary>A reported GameTDB id (DS game code, 3DS product code) as 4 uppercase alphanumerics, or null.</summary>
    public static string? NormalizeGameId(string? id)
    {
        var s = id?.Trim().ToUpperInvariant();
        return s is not null && FourAlnum().IsMatch(s) ? s : null;
    }

    /// <summary>
    /// True when art is on and the game's scan can be looked up: a Dolphin disc id (GameCube or Wii), or a DS or 3DS
    /// game whose GameTDB id a device reported.
    /// </summary>
    public static bool Has(string emulatorId, string gameKey, string? system, string? gameId, bool artEnabled)
    {
        if (!artEnabled) return false;
        if (emulatorId == "dolphin") return TitleNames.IsDiscId(gameKey.Split('/')[^1].ToUpperInvariant());
        return system is "DS" or "3DS" && NormalizeGameId(gameId) is not null;
    }
}
