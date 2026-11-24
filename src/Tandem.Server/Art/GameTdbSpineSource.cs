using Tandem.Server.Games;

namespace Tandem.Server.Art;

/// <summary>
/// GameTDB full case scans (back, spine and front), to cut real spines from:
/// <list type="bullet">
/// <item>Dolphin: <c>wii/coverfullHQ/{region}/{ID6}.png</c> (GameCube scans live under "wii" too). A 4-character Wii
/// id is widened to its 6-character id as for covers.</item>
/// <item>DS: <c>ds/coverfullHQ/{region}/{ID4}.jpg</c>, and 3DS: <c>3ds/coverfullHQ/{region}/{ID4}.jpg</c>, by the
/// GameTDB id the device reported.</item>
/// </list>
/// Regions follow the id's 4th character (<see cref="GameTdb.Regions"/>).
/// </summary>
public sealed class GameTdbSpineSource(TitleNamesHolder titles)
{
    public IReadOnlyList<Uri> Candidates(ArtRequest request) => Candidates(request, titles.Current);

    public static IReadOnlyList<Uri> Candidates(ArtRequest request, TitleNames? list)
    {
        var b = GameTdbCoverSource.BaseUrl;
        if (request.EmulatorId == "dolphin")
        {
            var key = request.GameKey.Split('/')[^1].ToUpperInvariant();
            if (!TitleNames.IsDiscId(key)) return [];
            var id = key.Length == 6 ? key : list?.FullDiscId(key);
            return id is null ? [] : GameTdb.Regions(id).Select(r => new Uri($"{b}wii/coverfullHQ/{r}/{id}.png")).ToList();
        }
        var system = ArtSystems.Resolve(request.EmulatorId, request.GameKey, request.Core, request.System);
        var folder = system switch { "DS" => "ds", "3DS" => "3ds", _ => null };
        var gameId = SpineEligibility.NormalizeGameId(request.GameId);
        if (folder is null || gameId is null) return [];
        return GameTdb.Regions(gameId).Select(r => new Uri($"{b}{folder}/coverfullHQ/{r}/{gameId}.jpg")).ToList();
    }
}
