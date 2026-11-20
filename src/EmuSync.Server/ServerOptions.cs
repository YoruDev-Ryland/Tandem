namespace EmuSync.Server;

public sealed class ServerOptions
{
    public const string Section = "EmuSync";
    public const int MaxServerNameLength = 60;

    private string _serverName = "EmuSync";

    public string? PublicUrl { get; set; }
    public bool AllowRegistration { get; set; }
    public string DataDirectory { get; set; } = "data";
    public string DatabaseProvider { get; set; } = "sqlite";
    public string? DatabaseConnection { get; set; }
    public bool TrustForwardedHeaders { get; set; }
    public string? WebRoot { get; set; }
    public long MaxBlobBytes { get; set; } = 8L * 1024 * 1024 * 1024;
    public int AuthRequestsPerMinute { get; set; } = 10;
    public int EventHeartbeatSeconds { get; set; } = 25;

    public string ServerName
    {
        get => _serverName;
        set
        {
            var trimmed = (value ?? "").Trim();
            if (trimmed.Length == 0) trimmed = "EmuSync";
            _serverName = trimmed.Length > MaxServerNameLength ? trimmed[..MaxServerNameLength] : trimmed;
        }
    }
}
