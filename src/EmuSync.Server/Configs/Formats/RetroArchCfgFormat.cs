namespace EmuSync.Server.Configs.Formats;

/// <summary>RetroArch <c>key = "value"</c> (also core options .opt and remaps .rmp). The value is the inside of the quotes.</summary>
public sealed class RetroArchCfgFormat : IConfigFormat
{
    public IReadOnlyList<ConfigValue> Read(string text)
    {
        var result = new List<ConfigValue>();
        foreach (var (start, length) in FormatSupport.Lines(text))
        {
            var line = text.AsSpan(start, length);
            var t = line.Trim(" \t");
            if (t.IsEmpty || t[0] == '#') continue;
            var eq = line.IndexOf('=');
            if (eq <= 0) continue;
            var key = line[..eq].Trim(" \t");
            if (key.IsEmpty) continue;
            var vs = eq + 1;
            while (vs < length && line[vs] is ' ' or '\t') vs++;
            var ve = length;
            while (ve > vs && line[ve - 1] is ' ' or '\t') ve--;
            if (ve - vs >= 2 && line[vs] == '"' && line[ve - 1] == '"') { vs++; ve--; }
            result.Add(new ConfigValue(key.ToString(), text.Substring(start + vs, ve - vs), start + vs, ve - vs));
        }
        return result;
    }

    public string Replace(string text, IReadOnlyDictionary<string, string> newValuesByKey) =>
        FormatSupport.Splice(text, Read(text), newValuesByKey, (_, s) => s);
}
