namespace Tandem.Server;

public sealed class ServerOptions
{
    public const string Section = "Tandem";
    public const int MaxServerNameLength = 60;

    private string _serverName = "Tandem";

    public string? PublicUrl { get; set; }
    public bool AllowRegistration { get; set; }
    public string DataDirectory { get; set; } = "data";
    public string DatabaseProvider { get; set; } = "sqlite";
    public string? DatabaseConnection { get; set; }
    public bool TrustForwardedHeaders { get; set; }
    public string? WebRoot { get; set; }
    public long MaxBlobBytes { get; set; } = 8L * 1024 * 1024 * 1024;
    /// <summary>Cumulative cap on the files of one web upload request.</summary>
    public long MaxUploadBytes { get; set; } = 8L * 1024 * 1024 * 1024;
    public int AuthRequestsPerMinute { get; set; } = 10;
    public int EventHeartbeatSeconds { get; set; } = 25;
    /// <summary>Runs the retention pruner in the background (on in production; the test factory turns it off).</summary>
    public bool EnablePruning { get; set; } = true;
    public int PruneIntervalMinutes { get; set; } = 60;
    /// <summary>Runs the daily off-server backup schedule (on in production; the test factory turns it off).</summary>
    public bool EnableBackups { get; set; } = true;

    /// <summary>Runs the 5-minute Shizuku-off check in the background (on in production; the test factory turns it off).</summary>
    public bool EnableNotifyChecks { get; set; } = true;

    /// <summary>Downloads the public Switch and 3DS title lists so title-id saves get names (on in production; tests turn it off).</summary>
    public bool EnableTitleNames { get; set; } = true;
    public string? TitleNamesUrl { get; set; } = "https://raw.githubusercontent.com/blawar/titledb/master/US.en.json";
    /// <summary>Comma-separated 3dsdb region lists; for an id in several, the first list's name wins.</summary>
    public string? ThreeDsTitleNamesUrls { get; set; } =
        "https://raw.githubusercontent.com/hax0kartik/3dsdb/master/jsons/list_US.json," +
        "https://raw.githubusercontent.com/hax0kartik/3dsdb/master/jsons/list_GB.json," +
        "https://raw.githubusercontent.com/hax0kartik/3dsdb/master/jsons/list_JP.json";
    public int TitleNamesRefreshDays { get; set; } = 7;

    /// <summary>Capacity of the notification queue; when full, further notifications are dropped (and logged).</summary>
    public int NotifyQueueCapacity { get; set; } = 1000;

    public string ServerName
    {
        get => _serverName;
        set
        {
            var trimmed = (value ?? "").Trim();
            if (trimmed.Length == 0) trimmed = "Tandem";
            _serverName = trimmed.Length > MaxServerNameLength ? trimmed[..MaxServerNameLength] : trimmed;
        }
    }
}
