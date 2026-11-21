using System.Text.Json;

namespace EmuSync.Server.Games;

/// <summary>Switch title id → name, from the public titledb list (MIT, github.com/blawar/titledb).</summary>
public sealed class TitleNames
{
    private readonly Dictionary<string, string> _names;

    private TitleNames(Dictionary<string, string> names) => _names = names;

    public int Count => _names.Count;

    public static bool IsTitleId(string key) => key.Length == 16 && key.All(char.IsAsciiHexDigit);

    /// <summary>The name of [titleId], or of its base title when this id is another program, update or add-on slot.</summary>
    public string? Lookup(string titleId)
    {
        if (!IsTitleId(titleId)) return null;
        var id = titleId.ToUpperInvariant();
        return _names.GetValueOrDefault(id) ?? _names.GetValueOrDefault(id[..13] + "000");
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
