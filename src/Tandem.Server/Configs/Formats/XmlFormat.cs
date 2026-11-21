using System.Globalization;
using System.Text;

namespace Tandem.Server.Configs.Formats;

/// <summary>
/// XML attribute values and element text. Key is <c>/a/b/@attr</c> or <c>/a/b</c>; a repeated sibling name gets
/// <c>[n]</c> from the second occurrence on. Comments, CDATA, processing instructions and whitespace-only text are
/// ignored. Spans are the raw (entity-encoded) text; Value is decoded. Malformed input reads as no values.
/// </summary>
public sealed class XmlFormat : IConfigFormat
{
    public IReadOnlyList<ConfigValue> Read(string text)
    {
        try { return Scan(text); }
        catch (FormatException) { return Array.Empty<ConfigValue>(); }
    }

    private sealed class Frame(string path)
    {
        public readonly string Path = path;
        public readonly Dictionary<string, int> Seen = new();
    }

    private static List<ConfigValue> Scan(string t)
    {
        var result = new List<ConfigValue>();
        var stack = new List<(Frame Frame, string Name)>();
        var root = new Frame("");
        var pos = t.Length > 0 && t[0] == '\uFEFF' ? 1 : 0;
        var rootSeen = false;
        while (pos < t.Length)
        {
            var lt = t.IndexOf('<', pos);
            var textEnd = lt < 0 ? t.Length : lt;
            if (textEnd > pos)
            {
                var (s, e) = Trim(t, pos, textEnd);
                if (e > s)
                {
                    if (stack.Count == 0) throw new FormatException();
                    result.Add(new ConfigValue(stack[^1].Frame.Path, Decode(t.AsSpan(s, e - s)), s, e - s));
                }
            }
            if (lt < 0) break;
            pos = lt;
            if (Starts(t, pos, "<!--")) pos = After(t, pos + 4, "-->");
            else if (Starts(t, pos, "<![CDATA[")) pos = After(t, pos + 9, "]]>");
            else if (Starts(t, pos, "<?")) pos = After(t, pos + 2, "?>");
            else if (Starts(t, pos, "<!"))
            {
                var depth = 0;
                var i = pos + 2;
                for (; i < t.Length; i++)
                {
                    if (t[i] == '[') depth++;
                    else if (t[i] == ']') depth--;
                    else if (t[i] == '>' && depth <= 0) break;
                }
                if (i >= t.Length) throw new FormatException();
                pos = i + 1;
            }
            else if (Starts(t, pos, "</"))
            {
                var gt = t.IndexOf('>', pos);
                if (gt < 0 || stack.Count == 0) throw new FormatException();
                if (t.AsSpan(pos + 2, gt - pos - 2).Trim().ToString() != stack[^1].Name) throw new FormatException();
                stack.RemoveAt(stack.Count - 1);
                pos = gt + 1;
            }
            else
            {
                var i = pos + 1;
                var nameStart = i;
                while (i < t.Length && !char.IsWhiteSpace(t[i]) && t[i] is not ('/' or '>')) i++;
                if (i == nameStart || i >= t.Length) throw new FormatException();
                var name = t[nameStart..i];
                var parent = stack.Count == 0 ? root : stack[^1].Frame;
                if (stack.Count == 0) { if (rootSeen) throw new FormatException(); rootSeen = true; }
                parent.Seen[name] = parent.Seen.GetValueOrDefault(name) + 1;
                var n = parent.Seen[name];
                var frame = new Frame(parent.Path + "/" + name + (n > 1 ? "[" + n + "]" : ""));
                for (; ; )
                {
                    while (i < t.Length && char.IsWhiteSpace(t[i])) i++;
                    if (i >= t.Length) throw new FormatException();
                    if (t[i] is '/' or '>') break;
                    var an = i;
                    while (i < t.Length && !char.IsWhiteSpace(t[i]) && t[i] != '=') i++;
                    var attr = t[an..i];
                    while (i < t.Length && char.IsWhiteSpace(t[i])) i++;
                    if (attr.Length == 0 || i >= t.Length || t[i] != '=') throw new FormatException();
                    i++;
                    while (i < t.Length && char.IsWhiteSpace(t[i])) i++;
                    if (i >= t.Length || t[i] is not ('"' or '\'')) throw new FormatException();
                    var close = t.IndexOf(t[i], i + 1);
                    if (close < 0) throw new FormatException();
                    result.Add(new ConfigValue(frame.Path + "/@" + attr, Decode(t.AsSpan(i + 1, close - i - 1)), i + 1, close - i - 1));
                    i = close + 1;
                }
                if (t[i] == '/')
                {
                    if (i + 1 >= t.Length || t[i + 1] != '>') throw new FormatException();
                    pos = i + 2;
                }
                else
                {
                    stack.Add((frame, name));
                    pos = i + 1;
                }
            }
        }
        if (stack.Count != 0 || !rootSeen) throw new FormatException();
        return result;
    }

    private static bool Starts(string t, int pos, string s) => string.CompareOrdinal(t, pos, s, 0, s.Length) == 0;

    private static int After(string t, int from, string terminator)
    {
        var i = t.IndexOf(terminator, from, StringComparison.Ordinal);
        if (i < 0) throw new FormatException();
        return i + terminator.Length;
    }

    private static (int Start, int End) Trim(string t, int s, int e)
    {
        while (s < e && char.IsWhiteSpace(t[s])) s++;
        while (e > s && char.IsWhiteSpace(t[e - 1])) e--;
        return (s, e);
    }

    private static string Decode(ReadOnlySpan<char> raw)
    {
        if (raw.IndexOf('&') < 0) return raw.ToString();
        var sb = new StringBuilder(raw.Length);
        for (var i = 0; i < raw.Length; i++)
        {
            if (raw[i] != '&') { sb.Append(raw[i]); continue; }
            var semi = raw[i..].IndexOf(';');
            if (semi < 0) throw new FormatException();
            var ent = raw.Slice(i + 1, semi - 1);
            switch (ent)
            {
                case "amp": sb.Append('&'); break;
                case "lt": sb.Append('<'); break;
                case "gt": sb.Append('>'); break;
                case "quot": sb.Append('"'); break;
                case "apos": sb.Append('\''); break;
                default:
                    var cp = -1;
                    var ok = ent.Length > 1 && ent[0] == '#' &&
                        (ent[1] is 'x' or 'X'
                            ? int.TryParse(ent[2..], NumberStyles.AllowHexSpecifier, CultureInfo.InvariantCulture, out cp)
                            : int.TryParse(ent[1..], NumberStyles.None, CultureInfo.InvariantCulture, out cp));
                    if (!ok || cp is < 0 or > 0x10FFFF || cp is >= 0xD800 and <= 0xDFFF) throw new FormatException();
                    sb.Append(char.ConvertFromUtf32(cp));
                    break;
            }
            i += semi;
        }
        return sb.ToString();
    }

    private static string Encode(ConfigValue v, string s)
    {
        var attr = v.Key.AsSpan(v.Key.LastIndexOf('/') + 1).StartsWith("@");
        var sb = new StringBuilder(s.Length + 8);
        foreach (var c in s)
            sb.Append(c switch
            {
                '&' => "&amp;",
                '<' => "&lt;",
                '>' => "&gt;",
                '"' when attr => "&quot;",
                '\'' when attr => "&apos;",
                _ => c.ToString(),
            });
        return sb.ToString();
    }

    public string Replace(string text, IReadOnlyDictionary<string, string> newValuesByKey) =>
        FormatSupport.Splice(text, Read(text), newValuesByKey, Encode);
}
