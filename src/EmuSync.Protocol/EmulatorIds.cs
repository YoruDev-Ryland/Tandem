namespace EmuSync.Protocol;

public static class EmulatorIds
{
    public static IReadOnlySet<string> All { get; } = new HashSet<string>(StringComparer.Ordinal)
    {
        "eden", "retroarch", "azahar", "ppsspp", "vita3k", "dolphin", "melonds",
    };

    public static bool IsKnown(string id) => All.Contains(id);
}
