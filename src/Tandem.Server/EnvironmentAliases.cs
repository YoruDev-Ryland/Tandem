namespace Tandem.Server;

/// <summary>Maps the documented plain env names (PUBLIC_URL, ...) onto Tandem:* configuration keys.</summary>
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

    /// <summary>Adds the aliases as the LOWEST-priority source, so an explicit <c>Tandem__X</c> variable, a command-line
    /// value or a host setting always wins over the plain name. <paramref name="getEnvironment"/> is for tests.</summary>
    public static void Add(ConfigurationManager configuration, Func<string, string?>? getEnvironment = null)
    {
        getEnvironment ??= Environment.GetEnvironmentVariable;
        var values = new Dictionary<string, string?>();
        foreach (var (env, key) in Map)
        {
            var value = getEnvironment(env);
            if (!string.IsNullOrEmpty(value)) values[$"{ServerOptions.Section}:{key}"] = value;
        }
        if (values.Count > 0)
            configuration.Sources.Insert(0, new Microsoft.Extensions.Configuration.Memory.MemoryConfigurationSource { InitialData = values });
    }
}
