namespace Tandem.Server.Configs.Formats;

/// <summary>
/// INI / Qt-ini: <c>[section]</c> and <c>key=value</c> / <c>key = value</c>. Key is <c>section/key</c> (section
/// text as written, so Qt's URL-encoded names stay as is; backslashes in keys are kept). The value is the text
/// after the first '=' trimmed of spaces and tabs, quotes included.
/// </summary>
public sealed class IniFormat : IConfigFormat
{
    public IReadOnlyList<ConfigValue> Read(string text)
    {
        var result = new List<ConfigValue>();
        var section = "";
        foreach (var (start, length) in FormatSupport.Lines(text))
        {
            var line = text.AsSpan(start, length);
            var t = line.Trim(" \t");
            if (t.IsEmpty || t[0] is ';' or '#') continue;
            if (t[0] == '[')
            {
                var close = t.IndexOf(']');
                if (close > 0) section = t[1..close].ToString();
                continue;
            }
            var eq = line.IndexOf('=');
            if (eq <= 0) continue;
            var key = line[..eq].Trim(" \t");
            if (key.IsEmpty) continue;
            var vs = eq + 1;
            while (vs < length && line[vs] is ' ' or '\t') vs++;
            var ve = length;
            while (ve > vs && line[ve - 1] is ' ' or '\t') ve--;
            result.Add(new ConfigValue(section.Length == 0 ? key.ToString() : section + "/" + key.ToString(),
                text.Substring(start + vs, ve - vs), start + vs, ve - vs));
        }
        return result;
    }

    public string Replace(string text, IReadOnlyDictionary<string, string> newValuesByKey) =>
        FormatSupport.Splice(text, Read(text), newValuesByKey, (_, s) => s);
}
