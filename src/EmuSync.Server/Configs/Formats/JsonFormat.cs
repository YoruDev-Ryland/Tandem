using System.Text.Encodings.Web;
using System.Text.Json;

namespace EmuSync.Server.Configs.Formats;

/// <summary>
/// JSON string values only (numbers, booleans, null are ignored). Key is a JSON pointer (RFC 6901). The span is the
/// inside of the string's quotes (raw, still escaped); Value is decoded. Malformed input reads as no values.
/// </summary>
public sealed class JsonFormat : IConfigFormat
{
    private const int MaxDepth = 200;

    private static readonly JsonSerializerOptions EncodeOptions = new() { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    public IReadOnlyList<ConfigValue> Read(string text)
    {
        try
        {
            var result = new List<ConfigValue>();
            var i = 0;
            SkipWs(text, ref i);
            ParseValue(text, ref i, "", result, 0);
            SkipWs(text, ref i);
            return i == text.Length ? result : Array.Empty<ConfigValue>();
        }
        catch (Exception e) when (e is FormatException or JsonException)
        {
            return Array.Empty<ConfigValue>();
        }
    }

    private static void SkipWs(string t, ref int i)
    {
        while (i < t.Length && (char.IsWhiteSpace(t[i]) || t[i] == '﻿')) i++;
    }

    private static int StringEnd(string t, int i)
    {
        // i at opening quote; returns index of the closing quote
        for (var j = i + 1; j < t.Length; j++)
        {
            if (t[j] == '\\') j++;
            else if (t[j] == '"') return j;
        }
        throw new FormatException();
    }

    private static string Decode(string t, int open, int close) =>
        JsonSerializer.Deserialize<string>(t.AsSpan(open, close - open + 1)) ?? throw new FormatException();

    private static void ParseValue(string t, ref int i, string pointer, List<ConfigValue> result, int depth)
    {
        if (depth > MaxDepth || i >= t.Length) throw new FormatException();
        switch (t[i])
        {
            case '"':
                var close = StringEnd(t, i);
                result.Add(new ConfigValue(pointer, Decode(t, i, close), i + 1, close - i - 1));
                i = close + 1;
                break;
            case '{':
                i++;
                SkipWs(t, ref i);
                if (i < t.Length && t[i] == '}') { i++; break; }
                for (; ; )
                {
                    SkipWs(t, ref i);
                    if (i >= t.Length || t[i] != '"') throw new FormatException();
                    var kc = StringEnd(t, i);
                    var key = Decode(t, i, kc);
                    i = kc + 1;
                    SkipWs(t, ref i);
                    if (i >= t.Length || t[i] != ':') throw new FormatException();
                    i++;
                    SkipWs(t, ref i);
                    ParseValue(t, ref i, pointer + "/" + key.Replace("~", "~0").Replace("/", "~1"), result, depth + 1);
                    SkipWs(t, ref i);
                    if (i >= t.Length) throw new FormatException();
                    if (t[i] == ',') { i++; continue; }
                    if (t[i] == '}') { i++; break; }
                    throw new FormatException();
                }
                break;
            case '[':
                i++;
                SkipWs(t, ref i);
                if (i < t.Length && t[i] == ']') { i++; break; }
                for (var n = 0; ; n++)
                {
                    SkipWs(t, ref i);
                    ParseValue(t, ref i, pointer + "/" + n, result, depth + 1);
                    SkipWs(t, ref i);
                    if (i >= t.Length) throw new FormatException();
                    if (t[i] == ',') { i++; continue; }
                    if (t[i] == ']') { i++; break; }
                    throw new FormatException();
                }
                break;
            default:
                var s = i;
                while (i < t.Length && !char.IsWhiteSpace(t[i]) && t[i] is not (',' or '}' or ']')) i++;
                if (i == s) throw new FormatException();
                break;
        }
    }

    private static string Encode(ConfigValue _, string s)
    {
        var json = JsonSerializer.Serialize(s, EncodeOptions);
        return json[1..^1];
    }

    public string Replace(string text, IReadOnlyDictionary<string, string> newValuesByKey) =>
        FormatSupport.Splice(text, Read(text), newValuesByKey, Encode);
}
