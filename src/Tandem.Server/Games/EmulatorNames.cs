namespace Tandem.Server.Games;

public static class EmulatorNames
{
    /// <summary>The emulator's display name (the id for an unknown one), for activity rows and notifications.</summary>
    public static string Display(string? id) => id switch
    {
        "eden" => "Eden",
        "retroarch" => "RetroArch",
        "azahar" => "Azahar",
        "ppsspp" => "PPSSPP",
        "vita3k" => "Vita3K",
        "dolphin" => "Dolphin",
        "melonds" => "melonDS",
        null => "",
        _ => id,
    };
}
