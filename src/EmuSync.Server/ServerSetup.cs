using EmuSync.Server.Accounts;
using EmuSync.Server.Auth;
using EmuSync.Server.Blobs;
using EmuSync.Server.Configs;
using EmuSync.Server.Data;
using EmuSync.Server.Deliveries;
using EmuSync.Server.Devices;
using EmuSync.Server.Events;
using EmuSync.Server.Http;
using EmuSync.Server.Pairing;
using EmuSync.Server.Saves;
using System.Text.Json.Serialization;
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
        builder.Services.AddScoped<DeliveryService>();
        builder.Services.AddHostedService<DeliveryHandlers>();
    }

    /// <summary>Builds the pipeline and maps endpoints. Each feature adds one line here.</summary>
    public static void Map(WebApplication app)
    {
        app.Services.MigrateDatabase();
        app.UseApiErrors();
        app.UseMiddleware<ProtocolVersionMiddleware>(); // before routing so unknown /v1 routes still get 426

        app.UseRouting();
        app.UseAuthentication();
        app.UseRateLimiter();
        app.UseAuthorization();

        app.MapServerInfoEndpoints();
        app.MapAccountEndpoints();
        app.MapAdminEndpoints();
        app.MapDeviceEndpoints();
        app.MapPairingEndpoints();
        app.MapBlobEndpoints();
        app.MapSaveEndpoints();
        app.MapConfigEndpoints();
        app.MapDeliveryEndpoints();
        app.MapEventEndpoints();
    }
}
