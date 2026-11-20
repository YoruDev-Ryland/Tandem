using System.Text;

namespace EmuSync.Server.Configs.Formats;

internal static class FormatSupport
{
    /// <summary>Line content ranges (a leading BOM is skipped). Terminators (\n or \r\n) are excluded and never touched.</summary>
    public static IEnumerable<(int Start, int Length)> Lines(string text)
    {
        var pos = text.Length > 0 && text[0] == '\uFEFF' ? 1 : 0; // spans still index the original text
        while (pos < text.Length)
        {
            var nl = text.IndexOf('\n', pos);
            var end = nl < 0 ? text.Length : nl;
            var contentEnd = end;
            if (nl >= 0 && contentEnd > pos && text[contentEnd - 1] == '\r') contentEnd--;
            yield return (pos, contentEnd - pos);
            pos = nl < 0 ? text.Length : nl + 1;
        }
    }

    /// <summary>Shared Replace: encodes changed values and splices them into the original text.</summary>
    public static string Splice(string text, IReadOnlyList<ConfigValue> values,
        IReadOnlyDictionary<string, string> newValues, Func<ConfigValue, string, string> encode)
    {
        List<(int Start, int Length, string New)>? edits = null;
        foreach (var v in values)
        {
            if (!newValues.TryGetValue(v.Key, out var nv) || nv == v.Value) continue;
            (edits ??= new()).Add((v.Start, v.Length, encode(v, nv)));
        }
        if (edits is null) return text;
        edits.Sort((a, b) => a.Start.CompareTo(b.Start));
        var sb = new StringBuilder(text.Length + 64);
        var last = 0;
        foreach (var (start, length, nv) in edits)
        {
            if (start < last) continue;
            sb.Append(text, last, start - last).Append(nv);
            last = start + length;
        }
        sb.Append(text, last, text.Length - last);
        return sb.ToString();
    }
}
