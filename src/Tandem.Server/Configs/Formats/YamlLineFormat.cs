namespace Tandem.Server.Configs.Formats;

/// <summary>
/// Line-based YAML: <c>key: value</c> scalars, nested maps by indentation (key = dotted path). Lists, flow
/// collections and block scalars (| >) are left untouched and unreported. Quoted scalars report the inside of
/// the quotes; trailing <c> # comments</c> on plain scalars are excluded from the span.
/// </summary>
public sealed class YamlLineFormat : IConfigFormat
{
    public IReadOnlyList<ConfigValue> Read(string text)
    {
        var result = new List<ConfigValue>();
        var stack = new List<(int Indent, string Key)>();
        var skipAbove = -1; // lines indented deeper than this belong to a skipped list item / block scalar
        foreach (var (start, length) in FormatSupport.Lines(text))
        {
            var line = text.AsSpan(start, length);
            var indent = 0;
            while (indent < length && line[indent] == ' ') indent++;
            var body = line[indent..].TrimEnd(" \t");
            if (body.IsEmpty || body[0] == '#') continue;
            if (body is "---" or "...") { stack.Clear(); skipAbove = -1; continue; }
            if (skipAbove >= 0)
            {
                if (indent > skipAbove) continue;
                skipAbove = -1;
            }
            if (body[0] == '-' && (body.Length == 1 || body[1] is ' ' or '\t'))
            {
                skipAbove = indent;
                continue;
            }

            var colon = FindKeyColon(body);
            if (colon <= 0) continue;
            var key = Unquote(body[..colon].Trim(" \t"));
            while (stack.Count > 0 && stack[^1].Indent >= indent) stack.RemoveAt(stack.Count - 1);

            var vs = colon + 1;
            while (vs < body.Length && body[vs] is ' ' or '\t') vs++;
            if (vs >= body.Length || body[vs] == '#')
            {
                stack.Add((indent, key));
                continue;
            }
            var first = body[vs];
            if (first is '|' or '>') { skipAbove = indent; continue; }
            if (first is '[' or '{' or '&' or '*' or '!') continue;

            int ve;
            if (first is '"' or '\'')
            {
                var close = body[(vs + 1)..].IndexOf(first);
                if (close < 0) continue;
                (vs, ve) = (vs + 1, vs + 1 + close);
            }
            else
            {
                ve = body.Length;
                for (var i = vs + 1; i < body.Length; i++)
                    if (body[i] == '#' && body[i - 1] is ' ' or '\t') { ve = i; break; }
                while (ve > vs && body[ve - 1] is ' ' or '\t') ve--;
            }
            var path = string.Join('.', stack.Select(s => s.Key).Append(key));
            result.Add(new ConfigValue(path, body.Slice(vs, ve - vs).ToString(), start + indent + vs, ve - vs));
        }
        return result;
    }

    private static int FindKeyColon(ReadOnlySpan<char> body)
    {
        char quote = '\0';
        for (var i = 0; i < body.Length; i++)
        {
            var c = body[i];
            if (quote != '\0') { if (c == quote) quote = '\0'; continue; }
            if (c is '"' or '\'' && i == 0) { quote = c; continue; }
            if (c == ':' && (i == body.Length - 1 || body[i + 1] is ' ' or '\t')) return i;
        }
        return -1;
    }

    private static string Unquote(ReadOnlySpan<char> k) =>
        k.Length >= 2 && k[0] is '"' or '\'' && k[^1] == k[0] ? k[1..^1].ToString() : k.ToString();

    public string Replace(string text, IReadOnlyDictionary<string, string> newValuesByKey) =>
        FormatSupport.Splice(text, Read(text), newValuesByKey, (_, s) => s);
}
