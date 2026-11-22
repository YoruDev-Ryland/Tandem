using Tandem.Server.Games;

namespace Tandem.Server.Art;

/// <summary>
/// GameTDB front covers (https://art.gametdb.com), by game id. What it matches:
/// <list type="bullet">
/// <item>Dolphin only. Its keys are the disc's game id: 6 characters for a GameCube save (GXBE69) and 4 for a Wii save
/// (RMCE). A 4-character id is widened to the first 6-character id in the GameTDB title list (wiitdb.txt, already
/// loaded for names); with names turned off, a Wii save gets nothing here.</item>
/// <item>The address is <c>wii/cover/{region}/{ID6}.png</c> (GameTDB keeps GameCube covers under "wii" too). The
/// region follows the id's 4th character, as Dolphin's own cover download does: E/N → US, J → JA, K/Q/T → KO,
/// W → ZH, D → DE, F → FR, S → ES, I → IT, H → NL, U → AU, R → RU, any other (P, X, Y, …) → EN. A PAL language
/// region falls back to EN.</item>
/// <item>Not used for DS, 3DS or Switch: GameTDB keys those by product code (YQUE, EKJE, AAAAA), which neither the
/// melonDS ROM-name keys nor the Azahar/Eden title ids carry. DS and 3DS come from libretro-thumbnails by name instead;
/// Switch has no source.</item>
/// </list>
/// </summary>
public sealed class GameTdbCoverSource(TitleNamesHolder titles) : IArtSource
{
    public const string BaseUrl = "https://art.gametdb.com/";

    public Task<IReadOnlyList<Uri>> CandidatesAsync(ArtRequest request, CancellationToken ct) =>
        Task.FromResult(Candidates(request, titles.Current));

    public static IReadOnlyList<Uri> Candidates(ArtRequest request, TitleNames? list)
    {
        if (request.EmulatorId != "dolphin") return [];
        var key = request.GameKey.Split('/')[^1].ToUpperInvariant();
        if (!TitleNames.IsDiscId(key)) return [];
        var id = key.Length == 6 ? key : list?.FullDiscId(key);
        if (id is null) return [];
        var region = Region(id[3]);
        var regions = region is "DE" or "FR" or "ES" or "IT" or "NL" or "AU" or "RU" ? new[] { region, "EN" } : [region];
        return regions.Select(r => new Uri($"{BaseUrl}wii/cover/{r}/{id}.png")).ToList();
    }

    private static string Region(char c) => c switch
    {
        'E' or 'N' => "US",
        'J' => "JA",
        'K' or 'Q' or 'T' => "KO",
        'W' => "ZH",
        'D' => "DE",
        'F' => "FR",
        'S' => "ES",
        'I' => "IT",
        'H' => "NL",
        'U' => "AU",
        'R' => "RU",
        _ => "EN",
    };
}
