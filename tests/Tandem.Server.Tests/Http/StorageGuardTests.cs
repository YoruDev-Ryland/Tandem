using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using Tandem.Protocol;
using Tandem.Server.Http;
using Tandem.Server.Notify;
using Tandem.Server.Tests.Infrastructure;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace Tandem.Server.Tests.Http;

/// <summary>C8 (storage-low guard), C5 (Attention.DiskLow / BackupNotConfigured) and C4 (server version).</summary>
public class StorageGuardTests
{
    private sealed class FakeProbe : IFreeSpaceProbe
    {
        public long? Free { get; set; } = 100L << 30;
        public int Calls { get; private set; }
        public long? FreeBytes(string directory) { Calls++; return Free; }
    }

    private static (ServerFactory, FakeProbe) NewFactory()
    {
        var probe = new FakeProbe();
        var f = new ServerFactory();
        f.Settings["Tandem:AllowRegistration"] = "true";
        f.ConfigureExtraServices = s => { s.RemoveAll<IFreeSpaceProbe>(); s.AddSingleton<IFreeSpaceProbe>(probe); };
        return (f, probe);
    }

    private static async Task<HttpResponseMessage> PutBlob(HttpClient device, byte[] bytes)
    {
        var content = new ByteArrayContent(bytes);
        content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        return await device.PutAsync($"/v1/blobs/{Fixtures.Sha256(bytes)}", content);
    }

    [Fact]
    public async Task Low_storage_refuses_uploads_with_507_degrades_healthz_and_shows_on_home()
    {
        var (f, probe) = NewFactory();
        using var _ = f;
        var (device, _) = await f.Register("a@example.com");
        var web = await f.WebLogin("a@example.com");
        Assert.Equal(HttpStatusCode.NoContent, (await PutBlob(device, "one"u8.ToArray())).StatusCode);
        Assert.False((await web.GetOk<HomeResponse>("/v1/web/home")).Attention.DiskLow);

        probe.Free = 10L << 20;
        f.Time.Advance(StorageMonitor.CacheFor); // the cached value expires
        var refused = await PutBlob(device, "two"u8.ToArray());
        Assert.Equal((HttpStatusCode)507, refused.StatusCode);
        Assert.Equal("storage_full", (await refused.ReadAsync<ErrorResponse>()).Code);
        var save = await device.PostJsonAsync("/v1/saves/upload",
            new SaveUploadRequest("eden", ItemKind.Save, "g", "", "", null, [], null));
        Assert.Equal((HttpStatusCode)507, save.StatusCode);
        var config = await device.PostJsonAsync("/v1/configs/upload", new ConfigUploadRequest("eden", null, []));
        Assert.Equal((HttpStatusCode)507, config.StatusCode);

        var health = await f.CreateClient().GetAsync("/healthz");
        Assert.Equal(HttpStatusCode.ServiceUnavailable, health.StatusCode);
        Assert.Equal("degraded: storage low", await health.Content.ReadAsStringAsync());
        Assert.True((await web.GetOk<HomeResponse>("/v1/web/home")).Attention.DiskLow);

        // Reads still work.
        Assert.Equal(HttpStatusCode.OK, (await device.GetAsync("/v1/deliveries")).StatusCode);

        probe.Free = 5L << 30;
        f.Time.Advance(StorageMonitor.CacheFor);
        Assert.Equal(HttpStatusCode.NoContent, (await PutBlob(device, "two"u8.ToArray())).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await f.CreateClient().GetAsync("/healthz")).StatusCode);
    }

    [Fact]
    public async Task The_free_space_probe_is_cached_for_30_seconds()
    {
        var (f, probe) = NewFactory();
        using var _ = f;
        var (device, _) = await f.Register("a@example.com");
        var before = probe.Calls;
        for (var i = 0; i < 5; i++) await PutBlob(device, [(byte)i]);
        Assert.True(probe.Calls - before <= 1);
        f.Time.Advance(TimeSpan.FromSeconds(31));
        await PutBlob(device, [9]);
        Assert.True(probe.Calls - before <= 2);
    }

    [Fact]
    public async Task Storage_low_notifies_admins_once_per_episode()
    {
        var (f, probe) = NewFactory();
        using var _ = f;
        await f.Register("a@example.com");
        var triggers = f.Services.GetRequiredService<NotifyTriggers>();
        Assert.False(await triggers.CheckStorageAsync(default));
        probe.Free = 1;
        f.Time.Advance(StorageMonitor.CacheFor);
        Assert.True(await triggers.CheckStorageAsync(default));
        Assert.False(await triggers.CheckStorageAsync(default));
        probe.Free = 100L << 30;
        f.Time.Advance(StorageMonitor.CacheFor);
        Assert.False(await triggers.CheckStorageAsync(default));
        probe.Free = 1;
        f.Time.Advance(StorageMonitor.CacheFor);
        Assert.True(await triggers.CheckStorageAsync(default)); // a new episode
    }

    [Fact]
    public async Task Backup_not_configured_is_shown_to_admins_only()
    {
        var (f, _) = NewFactory();
        using var _f = f;
        await f.Register("admin@example.com");
        await f.Register("user@example.com");
        var admin = await f.WebLogin("admin@example.com");
        var user = await f.WebLogin("user@example.com");
        Assert.True((await admin.GetOk<HomeResponse>("/v1/web/home")).Attention.BackupNotConfigured);
        Assert.False((await user.GetOk<HomeResponse>("/v1/web/home")).Attention.BackupNotConfigured);

        var folder = Directory.CreateTempSubdirectory("tandem-backup-target-").FullName;
        try
        {
            var put = await admin.PutAsJsonAsync("/v1/admin/backup",
                new BackupSettingsDto(true, BackupTargetKind.Folder, folder, null, 3, 7, false, null), TestApi.Json);
            Assert.True(put.IsSuccessStatusCode, await put.Content.ReadAsStringAsync());
            Assert.False((await admin.GetOk<HomeResponse>("/v1/web/home")).Attention.BackupNotConfigured);
        }
        finally { Directory.Delete(folder, true); }
    }

    [Fact]
    public async Task Server_info_reports_the_version_without_the_commit_suffix()
    {
        using var f = new ServerFactory();
        var info = await (await f.CreateClient().GetAsync("/v1/server-info")).ReadAsync<ServerInfo>();
        Assert.False(string.IsNullOrWhiteSpace(info.Version));
        Assert.DoesNotContain("+", info.Version);
        Assert.Equal("1.2.3", ServerInfoEndpoints.StripBuild("1.2.3+abcdef"));
        Assert.Equal("unknown", ServerInfoEndpoints.StripBuild(null));
    }
}
