using Tandem.Server.Accounts;
using Tandem.Server.Art;
using Tandem.Server.Backup;
using Tandem.Server.Auth;
using Tandem.Server.Blobs;
using Tandem.Server.Configs;
using Tandem.Server.Data;
using Tandem.Server.Deliveries;
using Tandem.Server.Devices;
using Tandem.Server.Events;
using Tandem.Server.Vault;
using Tandem.Server.Web;
using Tandem.Server.Http;
using Tandem.Server.Notify;
using Tandem.Server.Pairing;
using Tandem.Server.Saves;
using Tandem.Server.Retention;
using System.Text.Json.Serialization;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace Tandem.Server;

public static class ServerSetup
{
    /// <summary>Registers configuration and services. Each feature adds one line here.</summary>
    public static void Configure(WebApplicationBuilder builder)
    {
        EnvironmentAliases.Add(builder.Configuration);
        builder.Services.Configure<ServerOptions>(builder.Configuration.GetSection(ServerOptions.Section));
        builder.Services.AddSingleton(TimeProvider.System);
        builder.Services.AddSingleton<IFreeSpaceProbe, DriveFreeSpaceProbe>();
        builder.Services.AddSingleton<StorageMonitor>();
        builder.Services.AddOpenApi();
        builder.Services.AddDatabase(builder.Configuration.GetSection(ServerOptions.Section).Get<ServerOptions>() ?? new ServerOptions());
        builder.Services.AddTandemAuth(builder.Configuration.GetSection(ServerOptions.Section).Get<ServerOptions>() ?? new ServerOptions());
        builder.Services.AddSingleton<LoginThrottle>();
        builder.Services.AddScoped<AccountService>();
        builder.Services.AddScoped<UserDeletion>();
        builder.Services.AddScoped<PairingService>();
        builder.Services.AddSingleton<FilesLocation>();
        builder.Services.AddSingleton<FilesGate>();
        builder.Services.AddSingleton<IMountTable, ProcMountTable>();
        builder.Services.AddSingleton<MoveTargetCheck>();
        builder.Services.AddSingleton<IBlobPutHook, NoBlobPutHook>();
        builder.Services.AddHostedService<FilesLocationWatcher>();
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
        builder.Services.AddSingleton<Games.TitleNamesHolder>();
        builder.Services.AddSingleton<Games.GameNames>();
        builder.Services.AddSingleton<Games.TitleNamesBackfill>();
        builder.Services.AddHttpClient(Games.TitleNamesRefresher.ClientName, c => c.Timeout = TimeSpan.FromMinutes(10))
            .RemoveAllLoggers(); // one Information line per list download is noise (F-28)
        builder.Services.AddHostedService<Games.TitleNamesRefresher>();
        builder.Services.AddSingleton<Art.ArtStore>();
        builder.Services.AddSingleton<Art.ILibretroIndex, Art.LibretroIndex>();
        builder.Services.AddSingleton<Art.IArtSource, Art.GameTdbCoverSource>();     // sources are tried in this order
        builder.Services.AddSingleton<Art.IArtSource, Art.LibretroThumbnailSource>();
        builder.Services.AddSingleton<Art.GameTdbSpineSource>();
        builder.Services.AddSingleton<Art.ArtFetcher>();
        builder.Services.AddHostedService(sp => sp.GetRequiredService<Art.ArtFetcher>());
        builder.Services.AddHttpClient(Art.ArtFetcher.ClientName, c =>
            {
                c.Timeout = Art.ArtFetcher.RequestTimeout;
                c.DefaultRequestHeaders.UserAgent.ParseAdd(Art.ArtFetcher.UserAgent);
            })
            .ConfigurePrimaryHttpMessageHandler(Art.ArtFetcher.CreatePrimaryHandler)
            .RemoveAllLoggers(); // one Information line per cover is noise
        builder.Services.AddSingleton<NotifyTriggers>();
        builder.Services.AddHostedService(sp => sp.GetRequiredService<NotifyTriggers>());
    }

    /// <summary>Builds the pipeline and maps endpoints. Each feature adds one line here.</summary>
    public static void Map(WebApplication app)
    {
        var options = app.Services.GetRequiredService<Microsoft.Extensions.Options.IOptions<ServerOptions>>().Value;
        // First in the pipeline, so the rate limiter, cookies and URLs see the client's address, scheme and host.
        // With KnownProxies (KNOWN_PROXIES: IPs and CIDRs) only those may send X-Forwarded-*. Without it, the proxy sits
        // on a docker network whose address is not known in advance, so every proxy is trusted: TrustForwardedHeaders
        // must then only be turned on when the server is reachable through the proxy alone.
        var known = KnownProxies.Parse(string.Join(',', options.KnownProxies, options.KnownNetworks));
        if (options.TrustForwardedHeaders || known.Count > 0)
        {
            var forwarded = new ForwardedHeadersOptions
            {
                ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto | ForwardedHeaders.XForwardedHost,
                // With known proxies, walk back through every hop that is one (Cloudflare in front of Traefik gives
                // "client, edge"), stopping at the first address that isn't: a client can't get past its own hop. Trusting
                // every sender (TrustForwardedHeaders alone) keeps the default single hop, or anyone could pick an address.
                ForwardLimit = known.Count > 0 ? null : 1,
            };
            forwarded.KnownIPNetworks.Clear();
            forwarded.KnownProxies.Clear();
            foreach (var network in known) forwarded.KnownIPNetworks.Add(network);
            app.UseForwardedHeaders(forwarded);
        }
        app.UseSecurityHeaders(WebHosting.IndexOf(app.Services));

        app.Services.MigrateDatabase();
        FilesLocation.LoadAtStartup(app.Services);
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
        app.MapArtEndpoints();
        app.MapRetentionEndpoints();
        app.MapBackupEndpoints();
        app.MapNotifyEndpoints();
        app.MapWebFallback();
    }
}
