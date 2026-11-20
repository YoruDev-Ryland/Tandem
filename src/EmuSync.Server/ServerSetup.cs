using EmuSync.Server.Accounts;
using EmuSync.Server.Backup;
using EmuSync.Server.Auth;
using EmuSync.Server.Blobs;
using EmuSync.Server.Configs;
using EmuSync.Server.Data;
using EmuSync.Server.Deliveries;
using EmuSync.Server.Devices;
using EmuSync.Server.Events;
using EmuSync.Server.Vault;
using EmuSync.Server.Web;
using EmuSync.Server.Http;
using EmuSync.Server.Notify;
using EmuSync.Server.Pairing;
using EmuSync.Server.Saves;
using EmuSync.Server.Retention;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace EmuSync.Server;

public static class ServerSetup
{
    /// <summary>Registers configuration and services. Each feature adds one line here.</summary>
    public static void Configure(WebApplicationBuilder builder)
    {
        EnvironmentAliases.Add(builder.Configuration);
        builder.Services.Configure<ServerOptions>(builder.Configuration.GetSection(ServerOptions.Section));
        builder.Services.AddSingleton(TimeProvider.System);
        builder.Services.AddOpenApi();
        builder.Services.AddDatabase(builder.Configuration.GetSection(ServerOptions.Section).Get<ServerOptions>() ?? new ServerOptions());
        builder.Services.AddEmuSyncAuth(builder.Configuration.GetSection(ServerOptions.Section).Get<ServerOptions>() ?? new ServerOptions());
        builder.Services.AddScoped<AccountService>();
        builder.Services.AddScoped<PairingService>();
        builder.Services.AddSingleton<IBlobStore, BlobStore>();
        builder.Services.AddScoped<BlobService>();
        builder.Services.AddSingleton<IDomainEvents, DomainEvents>();
        builder.Services.AddScoped<DeviceService>();
        builder.Services.ConfigureHttpJsonOptions(o => o.SerializerOptions.Converters.Add(new JsonStringEnumConverter()));
        builder.Services.AddSingleton<UserLocks>();
        builder.Services.AddScoped<SaveService>();
        builder.Services.AddSingleton<IEventHub, EventHub>();
        builder.Services.AddScoped<IActivityLog, ActivityLog>();
        builder.Services.AddScoped<ConfigTransform>();
        builder.Services.AddScoped<ConfigService>();
        builder.Services.AddScoped<ConfigDiffService>();
        builder.Services.AddScoped<DeliveryService>();
        builder.Services.AddScoped<VaultService>();
        builder.Services.AddScoped<WebQueryService>();
        builder.Services.AddScoped<GameStateQueries>();
        builder.Services.AddScoped<ConflictQueries>();
        builder.Services.AddHostedService<DeliveryHandlers>();
        builder.Services.AddSingleton<MaintenanceLock>();
        builder.Services.AddSingleton<Pruner>();
        builder.Services.AddHostedService(sp => sp.GetRequiredService<Pruner>());
        builder.Services.AddSingleton<BackupService>();
        builder.Services.AddHostedService(sp => sp.GetRequiredService<BackupService>());
        builder.Services.AddHttpClient(Notifier.ClientName, c => c.Timeout = Notifier.RequestTimeout)
            .RemoveAllLoggers(); // the default handlers log every request URI, and a Discord webhook URL is a secret
        builder.Services.AddSingleton<INotifier, Notifier>();
        builder.Services.AddSingleton<NotifyTriggers>();
        builder.Services.AddHostedService(sp => sp.GetRequiredService<NotifyTriggers>());
    }

    /// <summary>Builds the pipeline and maps endpoints. Each feature adds one line here.</summary>
    public static void Map(WebApplication app)
    {
        var options = app.Services.GetRequiredService<Microsoft.Extensions.Options.IOptions<ServerOptions>>().Value;
        // First in the pipeline, so the rate limiter, cookies and URLs see the client's address, scheme and host.
        // The proxy sits on a docker network whose address is not known in advance, so every proxy is trusted:
        // the option must only be turned on when the server is reachable through the proxy alone.
        if (options.TrustForwardedHeaders)
        {
            var forwarded = new ForwardedHeadersOptions
            {
                ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto | ForwardedHeaders.XForwardedHost,
            };
            forwarded.KnownIPNetworks.Clear();
            forwarded.KnownProxies.Clear();
            app.UseForwardedHeaders(forwarded);
        }

        app.Services.MigrateDatabase();
        var dataDirectory = options.DataDirectory;
        WebEndpoints.SweepStaleUploads(dataDirectory);
        BackupService.SweepStaleTemps(dataDirectory);
        app.UseApiErrors();
        app.UseMiddleware<ProtocolVersionMiddleware>(); // before routing so unknown /v1 routes still get 426

        app.UseWebFiles();
        app.UseRouting();
        app.UseAuthentication();
        app.UseRateLimiter();
        app.UseAuthorization();

        app.MapOpenApi("/v1/openapi.json");
        app.MapServerInfoEndpoints();
        app.MapAccountEndpoints();
        app.MapAdminEndpoints();
        app.MapDeviceEndpoints();
        app.MapPairingEndpoints();
        app.MapBlobEndpoints();
        app.MapSaveEndpoints();
        app.MapConfigEndpoints();
        app.MapDeliveryEndpoints();
        app.MapVaultEndpoints();
        app.MapEventEndpoints();
        app.MapWebEndpoints();
        app.MapRetentionEndpoints();
        app.MapBackupEndpoints();
        app.MapNotifyEndpoints();
        app.MapWebFallback();
    }
}
