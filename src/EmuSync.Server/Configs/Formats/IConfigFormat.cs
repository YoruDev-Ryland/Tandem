namespace EmuSync.Server.Configs.Formats;

/// <summary>A value found in a config file. Start/Length is the span of the raw value in the original text.</summary>
public sealed record ConfigValue(string Key, string Value, int Start, int Length);

/// <summary>
/// Lists and rewrites the values of one config file format. Replace touches only the spans of the keys it is
/// given (and only where the value actually differs), so everything else stays byte-identical.
/// Malformed input reads as "no values" so the file is then treated as opaque.
/// </summary>
public interface IConfigFormat
{
    IReadOnlyList<ConfigValue> Read(string text);

    /// <summary>Replaces the value of every occurrence of each key. New values are given unescaped.</summary>
    string Replace(string text, IReadOnlyDictionary<string, string> newValuesByKey);
}
