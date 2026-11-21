using System.Text.Json;

namespace Tandem.Server.Games;

/// <summary>
/// Title id → name for the consoles whose saves are keyed by title id: Switch from titledb (MIT,
/// github.com/blawar/titledb), 3DS from 3dsdb (github.com/hax0kartik/3dsdb), and GameCube/Wii game ids from GameTDB
/// (gametdb.com, wiitdb.txt). The ids don't overlap (Switch ids start 01, 3DS ids 0004, both 16 hex digits; game ids
/// are 4 or 6 letters and digits). Names are stored as published; <see cref="GameNames"/> cleans them.
/// </summary>
public sealed class TitleNames
{
    private readonly Dictionary<string, string> _names;
    /// <summary>The first 6-character game id per 4-character prefix (a Wii save is keyed by the 4-character id).</summary>
    private readonly Dictionary<string, string> _byPrefix;

    private TitleNames(Dictionary<string, string> names)
    {
        _names = names;
        _byPrefix = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var id in names.Keys.Where(k => k.Length == 6 && IsDiscId(k)).Order(StringComparer.Ordinal))
            _byPrefix.TryAdd(id[..4], id);
    }

    public int Count => _names.Count;

    public static bool IsTitleId(string key) => key.Length == 16 && key.All(char.IsAsciiHexDigit);

    /// <summary>A GameCube/Wii game id as Dolphin keys saves: 6 (a GameCube memory-card save) or 4 (a Wii save)
    /// upper-case letters and digits.</summary>
    public static bool IsDiscId(string key) =>
        key.Length is 4 or 6 && key.All(c => char.IsAsciiDigit(c) || char.IsAsciiLetterUpper(c));

    /// <summary>The name of a game id: exact, else (a 4-character Wii id) the first 6-character id starting with it.</summary>
    public string? LookupDisc(string gameId)
    {
        if (!IsDiscId(gameId)) return null;
        if (_names.GetValueOrDefault(gameId) is { } name) return name;
        return gameId.Length == 4 && _byPrefix.GetValueOrDefault(gameId) is { } id ? _names.GetValueOrDefault(id) : null;
    }

    /// <summary>Reads GameTDB's wiitdb.txt: a "TITLES = …" header line, then one "ID = Title" line per game.</summary>
    public static TitleNames ParseGameTdb(Stream list)
    {
        var names = new Dictionary<string, string>(StringComparer.Ordinal);
        using var reader = new StreamReader(list, System.Text.Encoding.UTF8);
        while (reader.ReadLine() is { } line)
        {
            var eq = line.IndexOf(" = ", StringComparison.Ordinal);
            if (eq <= 0) continue;
            var id = line[..eq].Trim().ToUpperInvariant();
            var name = line[(eq + 3)..].Trim();
            if (!IsDiscId(id) || name.Length == 0 || id == "TITLES") continue; // the header line is "TITLES = <source>"
            names.TryAdd(id, name);
        }
        return new TitleNames(names);
    }

    /// <summary>The name of [titleId], or of its base title when this id is another program, update or add-on slot.</summary>
    public string? Lookup(string titleId)
    {
        if (!IsTitleId(titleId)) return null;
        var id = titleId.ToUpperInvariant();
        if (_names.GetValueOrDefault(id) is { } name) return name;
        // A Switch title's other programs, update (…800) and add-ons share its upper 52 bits.
        return id.StartsWith("01", StringComparison.Ordinal) ? _names.GetValueOrDefault(id[..13] + "000") : null;
    }

    private sealed record Entry(string? Id, string? Name);

    private static readonly JsonSerializerOptions Read = new() { PropertyNameCaseInsensitive = true };

    /// <summary>Reads titledb's region file ({ nsuId: { id, name, … } }), keeping only id and name.</summary>
    public static TitleNames Parse(Stream titledb)
    {
        var raw = JsonSerializer.Deserialize<Dictionary<string, Entry?>>(titledb, Read) ?? [];
        var names = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var e in raw.Values)
        {
            if (e?.Id is not { } id || !IsTitleId(id) || string.IsNullOrWhiteSpace(e.Name)) continue;
            names.TryAdd(id.ToUpperInvariant(), e.Name.Trim());
        }
        return new TitleNames(names);
    }

    private sealed record ThreeDsEntry(string? Name, string? TitleID);

    /// <summary>Reads a 3dsdb region file ([{ Name, TitleID, … }]).</summary>
    public static TitleNames ParseThreeDs(Stream list)
    {
        var raw = JsonSerializer.Deserialize<List<ThreeDsEntry?>>(list, Read) ?? [];
        var names = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var e in raw)
        {
            if (e?.TitleID is not { } id || !IsTitleId(id) || string.IsNullOrWhiteSpace(e.Name)) continue;
            names.TryAdd(id.ToUpperInvariant(), e.Name.Trim());
        }
        return new TitleNames(names);
    }

    /// <summary>One list from several; for an id in more than one, the earliest list's name wins.</summary>
    public static TitleNames Merge(params TitleNames[] lists)
    {
        var names = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var list in lists)
            foreach (var (id, name) in list._names) names.TryAdd(id, name);
        return new TitleNames(names);
    }

    /// <summary>Writes the compact { id: name } cache.</summary>
    public void Save(string path)
    {
        var tmp = path + ".tmp";
        using (var f = File.Create(tmp)) JsonSerializer.Serialize(f, _names);
        File.Move(tmp, path, overwrite: true);
    }

    public static TitleNames Load(string path)
    {
        using var f = File.OpenRead(path);
        var names = JsonSerializer.Deserialize<Dictionary<string, string>>(f) ?? [];
        return new TitleNames(new Dictionary<string, string>(names, StringComparer.Ordinal));
    }
}

/// <summary>The title list in use; null until one is loaded (or when the feature is off).</summary>
public sealed class TitleNamesHolder
{
    private volatile TitleNames? _current;
    public TitleNames? Current { get => _current; set => _current = value; }
}
