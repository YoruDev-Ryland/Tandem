namespace EmuSync.Server;

/// <summary>Maps the documented plain env names (PUBLIC_URL, ...) onto EmuSync:* configuration keys.</summary>
public static class EnvironmentAliases
{
    private static readonly (string Env, string Key)[] Map =
    [
        ("PUBLIC_URL", "PublicUrl"),
        ("ALLOW_REGISTRATION", "AllowRegistration"),
        ("DATA_DIR", "DataDirectory"),
        ("PUID", "Puid"),
        ("PGID", "Pgid"),
        ("DATABASE_PROVIDER", "DatabaseProvider"),
        ("DATABASE_CONNECTION", "DatabaseConnection"),
        ("TRUST_FORWARDED_HEADERS", "TrustForwardedHeaders"),
        ("SERVER_NAME", "ServerName"),
        ("WEB_ROOT", "WebRoot"),
    ];

    public static void Add(ConfigurationManager configuration)
    {
        var values = new Dictionary<string, string?>();
        foreach (var (env, key) in Map)
        {
            var value = Environment.GetEnvironmentVariable(env);
            if (!string.IsNullOrEmpty(value)) values[$"{ServerOptions.Section}:{key}"] = value;
        }
        if (values.Count > 0) configuration.AddInMemoryCollection(values);
    }
}
