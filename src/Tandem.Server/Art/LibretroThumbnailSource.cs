using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Options;

namespace Tandem.Server.Art;

/// <summary>
/// libretro-thumbnails box art (github.com/libretro-thumbnails, one repository per system), by No-Intro-style name.
/// What it matches:
/// <list type="bullet">
/// <item>The system comes from <see cref="ArtSystems"/>: RetroArch by key segment or core (GBA, GBC, GB, NES, SNES, N64,
/// MD, PSX, DS, …), melonDS → DS, Azahar → 3DS, PPSSPP → PSP, Vita3K → Vita, Dolphin → GameCube/Wii (after GameTDB).
/// Switch has no repository.</item>
/// <item>File names are No-Intro names with <c>&amp;*/:`&lt;&gt;?\|"</c> replaced by <c>_</c>, plus ".png", for example
/// "Pokemon - Emerald Version (USA, Europe).png". Save names rarely carry the full tag set ("Chrono Trigger (US)"),
/// and id-keyed games only have a title ("Pokémon X"), so the source first loads the repository's Named_Boxarts
/// listing (one GitHub API call per system, cached under art/index/ and refreshed every ArtMissDays) and matches in it.</item>
/// <item>Names tried: the ROM name in the key (RetroArch, melonDS), then the display name. Each is tried as an exact
/// file name first, then by its title: the text before the first " (" or " [", with accents, a leading or ", The"
/// article and all punctuation dropped, compared case-insensitively.</item>
/// <item>Several files can share a title (regions, revisions). The region named in the save's own tags wins, then
/// USA, World, Europe, Japan; demos, betas, protos and kiosk builds go last; then the shortest name.</item>
/// <item>Symlinked entries (mode 120000: "same as" pointers) and files over ArtMaxImageBytes are left out of the listing.</item>
/// </list>
/// </summary>
public sealed partial class LibretroThumbnailSource(ILibretroIndex index) : IArtSource
{
    public const string RawBase = "https://raw.githubusercontent.com/libretro-thumbnails/";

    /// <summary>The libretro-thumbnails repository per system label.</summary>
    public static readonly IReadOnlyDictionary<string, string[]> Repositories = new Dictionary<string, string[]>(StringComparer.Ordinal)
    {
        ["GBA"] = ["Nintendo_-_Game_Boy_Advance"],
        ["GBC"] = ["Nintendo_-_Game_Boy_Color", "Nintendo_-_Game_Boy"],
        ["GB"] = ["Nintendo_-_Game_Boy", "Nintendo_-_Game_Boy_Color"],
        ["NES"] = ["Nintendo_-_Nintendo_Entertainment_System"],
        ["SNES"] = ["Nintendo_-_Super_Nintendo_Entertainment_System"],
        ["N64"] = ["Nintendo_-_Nintendo_64"],
        ["DS"] = ["Nintendo_-_Nintendo_DS"],
        ["3DS"] = ["Nintendo_-_Nintendo_3DS"],
        ["GC"] = ["Nintendo_-_GameCube"],
        ["WII"] = ["Nintendo_-_Wii"],
        ["MD"] = ["Sega_-_Mega_Drive_-_Genesis"],
        ["PSX"] = ["Sony_-_PlayStation"],
        ["PSP"] = ["Sony_-_PlayStation_Portable"],
        ["VITA"] = ["Sony_-_PlayStation_Vita"],
    };

    private static readonly string[] SaveExtensions = [".srm", ".sav", ".rtc", ".mcr", ".eep", ".fla", ".dsv"];

    public async Task<IReadOnlyList<Uri>> CandidatesAsync(ArtRequest request, CancellationToken ct)
    {
        var system = ArtSystems.Label(request.EmulatorId, request.GameKey, request.Core);
        if (system is null || !Repositories.TryGetValue(system, out var repos)) return [];
        var names = Names(request);
        if (names.Count == 0) return [];
        foreach (var repo in repos)
        {
            var files = await index.FilesAsync(repo, ct);
            if (Match(files, names) is { } file) return [FileUri(repo, file)];
        }
        return [];
    }

    public static Uri FileUri(string repo, string file) =>
        new($"{RawBase}{repo}/master/Named_Boxarts/{Uri.EscapeDataString(file)}");

    /// <summary>The names to look up, best first: the ROM name a name-keyed save carries, then the display name.</summary>
    public static List<string> Names(ArtRequest request)
    {
        var names = new List<string>();
        if (request.EmulatorId is "retroarch" or "melonds")
        {
            var rom = request.GameKey.Split('/')[^1];
            foreach (var ext in SaveExtensions)
                if (rom.EndsWith(ext, StringComparison.OrdinalIgnoreCase)) rom = rom[..^ext.Length];
            if (rom.Trim().Length > 0) names.Add(rom.Trim());
        }
        if (!string.IsNullOrWhiteSpace(request.DisplayName) && !names.Contains(request.DisplayName.Trim(), StringComparer.Ordinal))
            names.Add(request.DisplayName.Trim());
        return names;
    }

    /// <summary>The listed file for the first of [names] that has one, or null.</summary>
    public static string? Match(IReadOnlyCollection<string> files, IReadOnlyList<string> names)
    {
        if (files.Count == 0) return null;
        var exact = new HashSet<string>(files, StringComparer.OrdinalIgnoreCase);
        foreach (var name in names)
        {
            var asFile = Sanitize(name) + ".png";
            if (exact.TryGetValue(asFile, out var hit)) return hit;
            var title = TitleKey(name);
            if (title.Length == 0) continue;
            var region = RegionOf(name);
            var best = files.Where(f => TitleKey(f[..^4]) == title)
                .OrderBy(f => Rank(f, region)).ThenBy(f => f.Length).ThenBy(f => f, StringComparer.Ordinal).FirstOrDefault();
            if (best is not null) return best;
        }
        return null;
    }

    /// <summary>libretro's file-name rule: these characters become '_'.</summary>
    public static string Sanitize(string name)
    {
        var sb = new StringBuilder(name.Length);
        foreach (var c in name) sb.Append("&*/:`<>?\\|\"".Contains(c) ? '_' : c);
        return sb.ToString();
    }

    /// <summary>"pokemonemeraldversion" for "Pokémon - Emerald Version (USA)"; "legendofzeldaminishcap" for both
    /// "The Legend of Zelda - Minish Cap" and "Legend of Zelda, The - Minish Cap (USA)".</summary>
    public static string TitleKey(string name)
    {
        var s = name;
        var cut = s.IndexOfAny(['(', '[']);
        if (cut > 0) s = s[..cut];
        s = s.Normalize(NormalizationForm.FormD);
        var sb = new StringBuilder(s.Length);
        foreach (var c in s)
            if (CharUnicodeInfo.GetUnicodeCategory(c) != UnicodeCategory.NonSpacingMark) sb.Append(char.ToLowerInvariant(c));
        s = sb.ToString().Replace('_', ' ').Replace("&", " ");
        s = TrailingArticle().Replace(s, " ");
        s = LeadingArticle().Replace(s.Trim(), "");
        return NonAlnum().Replace(s, "");
    }

    /// <summary>The region a name's own tags give ("USA", "Europe", "Japan"), or null.</summary>
    private static string? RegionOf(string name)
    {
        foreach (Match m in Tags().Matches(name))
        {
            foreach (var raw in m.Groups[1].Value.Split(','))
            {
                var t = raw.Trim().ToUpperInvariant();
                if (t is "US" or "USA" or "U") return "USA";
                if (t is "EU" or "EUR" or "EUROPE" or "E" or "UK") return "Europe";
                if (t is "JP" or "JPN" or "JAPAN" or "J") return "Japan";
                if (t is "WORLD" or "W") return "World";
            }
        }
        return null;
    }

    private static readonly string[] DefaultRegions = ["USA", "World", "Europe", "Japan"];

    private static int Rank(string file, string? wanted)
    {
        var tags = Tags().Matches(file).Select(m => m.Groups[1].Value).ToList();
        var regions = tags.FirstOrDefault()?.Split(',').Select(r => r.Trim()).ToList() ?? [];
        int score;
        if (wanted is not null && regions.Contains(wanted, StringComparer.OrdinalIgnoreCase)) score = 0;
        else
        {
            var i = Array.FindIndex(DefaultRegions, r => regions.Contains(r, StringComparer.OrdinalIgnoreCase));
            score = i < 0 ? 10 : 1 + i;
        }
        if (tags.Any(t => Unwanted().IsMatch(t))) score += 100;
        return score;
    }

    [GeneratedRegex(@"\(([^()]*)\)")]
    private static partial Regex Tags();

    [GeneratedRegex(@"^(beta|proto|demo|kiosk|sample|unl|pirate|aftermarket|program)\b", RegexOptions.IgnoreCase)]
    private static partial Regex Unwanted();

    [GeneratedRegex(@",\s*the\b")]
    private static partial Regex TrailingArticle();

    [GeneratedRegex(@"^the\s+")]
    private static partial Regex LeadingArticle();

    [GeneratedRegex(@"[^\p{L}\p{N}]")]
    private static partial Regex NonAlnum();
}

/// <summary>The file names in a libretro-thumbnails repository's Named_Boxarts folder.</summary>
public interface ILibretroIndex
{
    Task<IReadOnlyCollection<string>> FilesAsync(string repo, CancellationToken ct);
}

/// <summary>
/// Loads each repository's Named_Boxarts listing from the GitHub API (one request per system), keeps it in memory and in
/// <c>art/index/{repo}.txt</c>, and reloads it after ArtMissDays. A failed reload keeps the old listing.
/// </summary>
public sealed class LibretroIndex(IHttpClientFactory http, IOptions<ServerOptions> options, TimeProvider time, ILogger<LibretroIndex> log)
    : ILibretroIndex
{
    public const string ApiBase = "https://api.github.com/repos/libretro-thumbnails/";
    private const long MaxListingBytes = 32L * 1024 * 1024;
    private const string StampPrefix = "# fetched ";

    private readonly Dictionary<string, (DateTimeOffset Fetched, IReadOnlyCollection<string> Files)> _memory = new(StringComparer.Ordinal);
    private readonly SemaphoreSlim _gate = new(1, 1);

    public static Uri ListingUri(string repo) => new($"{ApiBase}{repo}/git/trees/master:Named_Boxarts");

    public async Task<IReadOnlyCollection<string>> FilesAsync(string repo, CancellationToken ct)
    {
        var o = options.Value;
        var maxAge = TimeSpan.FromDays(Math.Max(1, o.ArtMissDays));
        var now = time.GetUtcNow();
        await _gate.WaitAsync(ct);
        try
        {
            if (!_memory.TryGetValue(repo, out var known)) known = Load(repo) ?? default;
            if (known.Files is not null && now - known.Fetched < maxAge)
            {
                _memory[repo] = known;
                return known.Files;
            }
            try
            {
                var files = await DownloadAsync(repo, o.ArtMaxImageBytes, ct);
                Save(repo, now, files);
                _memory[repo] = (now, files);
                return files;
            }
            catch (Exception e) when (e is HttpRequestException or IOException or JsonException or InvalidDataException
                                          or TaskCanceledException && !ct.IsCancellationRequested)
            {
                if (known.Files is not null)
                {
                    log.LogDebug(e, "Keeping the old {Repo} cover listing", repo);
                    return known.Files;
                }
                throw new ArtSourceUnavailableException($"The {repo} cover listing could not be loaded.", e);
            }
        }
        finally { _gate.Release(); }
    }

    private async Task<IReadOnlyCollection<string>> DownloadAsync(string repo, long maxImageBytes, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, ListingUri(repo));
        request.Headers.Accept.ParseAdd("application/vnd.github+json");
        using var response = await http.CreateClient(ArtFetcher.ClientName).SendAsync(request, HttpCompletionOption.ResponseHeadersRead, ct);
        if (response.StatusCode == System.Net.HttpStatusCode.NotFound) return []; // no such repository or folder
        if (!response.IsSuccessStatusCode) throw new HttpRequestException($"listing answered {(int)response.StatusCode}");
        var body = await ArtFetcher.ReadCappedAsync(response.Content, MaxListingBytes, ct)
                   ?? throw new InvalidDataException("listing too large");
        using var doc = JsonDocument.Parse(body);
        var files = new List<string>();
        if (!doc.RootElement.TryGetProperty("tree", out var tree) || tree.ValueKind != JsonValueKind.Array) return files;
        foreach (var e in tree.EnumerateArray())
        {
            if (e.TryGetProperty("type", out var type) && type.GetString() != "blob") continue;
            if (e.TryGetProperty("mode", out var mode) && mode.GetString() != "100644") continue; // 120000 = symlink
            if (e.TryGetProperty("size", out var size) && size.TryGetInt64(out var n) && n > maxImageBytes) continue;
            if (e.TryGetProperty("path", out var path) && path.GetString() is { } p && p.EndsWith(".png", StringComparison.OrdinalIgnoreCase)
                && !p.Contains('/') && !p.Contains('\n'))
                files.Add(p);
        }
        return files;
    }

    private string CachePath(string repo) => Path.Combine(ArtStore.DirectoryOf(options.Value), "index", repo + ".txt");

    private (DateTimeOffset, IReadOnlyCollection<string>)? Load(string repo)
    {
        try
        {
            var path = CachePath(repo);
            if (!File.Exists(path)) return null;
            var lines = File.ReadAllLines(path);
            if (lines.Length == 0 || !lines[0].StartsWith(StampPrefix, StringComparison.Ordinal)
                || !DateTimeOffset.TryParse(lines[0][StampPrefix.Length..], CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal, out var fetched))
                return null;
            return (fetched, lines.Skip(1).Where(l => l.Length > 0).ToList());
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            log.LogDebug(e, "Couldn't read the {Repo} cover listing", repo);
            return null;
        }
    }

    private void Save(string repo, DateTimeOffset fetched, IReadOnlyCollection<string> files)
    {
        try
        {
            var path = CachePath(repo);
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            var tmp = path + ".tmp";
            File.WriteAllLines(tmp, files.Prepend(StampPrefix + fetched.ToString("O", CultureInfo.InvariantCulture)));
            File.Move(tmp, path, overwrite: true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            log.LogDebug(e, "Couldn't save the {Repo} cover listing", repo);
        }
    }
}
