using Tandem.Protocol;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Time.Testing;

namespace Tandem.Server.Tests.Infrastructure;

public sealed class ServerFactory : WebApplicationFactory<Program>
{
    public string DataDirectory { get; } =
        Directory.CreateTempSubdirectory("tandem-test-").FullName;

    /// <summary>Extra configuration, applied when the host is first built (set before calling Client()).</summary>
    public Dictionary<string, string?> Settings { get; } = new()
    {
        ["Tandem:AuthRequestsPerMinute"] = "10000",
        ["Tandem:EnablePruning"] = "false",
        ["Tandem:EnableBackups"] = "false",
        ["Tandem:EnableNotifyChecks"] = "false",
        ["Tandem:EnableTitleNames"] = "false",
        ["Tandem:EnableArt"] = "false",
    };

    public FakeTimeProvider Time { get; } = new(DateTimeOffset.Parse("2026-11-19T12:00:00Z"));

    /// <summary>Extra service registrations (e.g. a capturing handler for the "notify" HttpClient); set before calling Client().</summary>
    public Action<IServiceCollection>? ConfigureExtraServices { get; set; }

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseSetting("Tandem:DataDirectory", DataDirectory);
        foreach (var (key, value) in Settings)
            builder.UseSetting(key, value);

        builder.ConfigureServices(services =>
        {
            services.RemoveAll<TimeProvider>();
            services.AddSingleton<TimeProvider>(Time);
            ConfigureExtraServices?.Invoke(services);
        });
    }

    /// <summary>A client that sends the protocol header.</summary>
    public HttpClient Client()
    {
        var client = CreateClient();
        client.DefaultRequestHeaders.Add(Protocol.Protocol.Header, Protocol.Protocol.Version.ToString());
        return client;
    }

    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);
        if (disposing)
        {
            try { Directory.Delete(DataDirectory, recursive: true); }
            catch (IOException) { }
            catch (UnauthorizedAccessException) { }
        }
    }
}
