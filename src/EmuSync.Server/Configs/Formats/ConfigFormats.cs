namespace EmuSync.Server.Configs.Formats;

public static class ConfigFormats
{
    private static readonly IniFormat Ini = new();
    private static readonly RetroArchCfgFormat RetroArch = new();
    private static readonly YamlLineFormat Yaml = new();
    private static readonly XmlFormat Xml = new();
    private static readonly JsonFormat Json = new();

    /// <summary>The adapter for a config file, chosen by extension. <c>null</c> means opaque: whole-file sync, never rewritten.</summary>
    public static IConfigFormat? ForPath(string emulatorId, string relativePath) =>
        Path.GetExtension(relativePath).ToLowerInvariant() switch
        {
            ".ini" => Ini,
            ".cfg" or ".opt" or ".rmp" => RetroArch,
            ".yml" or ".yaml" => Yaml,
            ".xml" => Xml,
            ".json" => Json,
            _ => null,
        };
}
