using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text.Json;
using Microsoft.Extensions.DependencyInjection;
using Tandem.Protocol;
using Tandem.Server.Backup;
using Tandem.Server.Blobs;
using Tandem.Server.Data;
using Tandem.Server.Tests.Infrastructure;

namespace Tandem.Server.Tests.Blobs;

[Collection("Memory")]
public class FilesLocationTests
{
    private static readonly byte[] Bytes = "hello files"u8.ToArray();
    private static string Hash => Convert.ToHexStringLower(SHA256.HashData(Bytes));

    private static Task<HttpResponseMessage> Put(HttpClient device)
    {
        var content = new ByteArrayContent(Bytes);
        content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        return device.PutAsync($"/v1/blobs/{Hash}", content);
    }

    /// <summary>Stores the setting and re-runs the startup load, as a restart would.</summary>
    private static async Task Configure(ServerFactory factory, string path, Guid id)
    {
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<TandemDbContext>();
        db.ServerSettings.Add(new ServerSetting
        {
            Key = FilesLocation.Key,
            Value = JsonSerializer.Serialize(new FilesLocationSetting(path, id), new JsonSerializerOptions(JsonSerializerDefaults.Web)),
        });
        await db.SaveChangesAsync();
        await scope.ServiceProvider.GetRequiredService<FilesLocation>().LoadAsync(db, CancellationToken.None);
    }

    private static string TempDir() => Directory.CreateTempSubdirectory("tandem-files-").FullName;

    [Fact]
    public void Default_location_is_blobs_under_the_data_directory_and_gets_a_marker()
    {
        using var factory = new ServerFactory();
        factory.Client();
        var location = factory.Services.GetRequiredService<FilesLocation>();

        Assert.Equal(Path.Combine(factory.DataDirectory, "blobs"), location.Root);
        Assert.True(location.IsDefault);
        Assert.True(location.Available);
        Assert.True(File.Exists(Path.Combine(location.Root, FilesLocation.MarkerName)));
    }

    [Fact]
    public async Task A_custom_location_from_settings_is_used()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var dir = TempDir();
        try
        {
            var id = Guid.NewGuid();
            FilesLocation.WriteMarker(dir, id);
            await Configure(factory, dir, id);

            Assert.Equal(HttpStatusCode.NoContent, (await Put(device)).StatusCode);

            Assert.Single(Directory.GetFiles(dir, Hash, SearchOption.AllDirectories));
            Assert.Empty(Directory.GetFiles(Path.Combine(factory.DataDirectory, "blobs"), Hash, SearchOption.AllDirectories));
        }
        finally { Directory.Delete(dir, recursive: true); }
    }

    [Fact]
    public async Task A_missing_custom_location_is_not_recreated_and_refuses_blob_writes()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var dir = Path.Combine(Path.GetTempPath(), "tandem-gone-" + Guid.NewGuid().ToString("N"));
        await Configure(factory, dir, Guid.NewGuid());

        var r = await Put(device);

        Assert.Equal(HttpStatusCode.ServiceUnavailable, r.StatusCode);
        Assert.Equal("files_unavailable", (await r.ReadAsync<ErrorResponse>()).Code);
        Assert.False(Directory.Exists(dir));
    }

    [Fact]
    public async Task A_marker_with_another_id_counts_as_missing()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var dir = TempDir();
        try
        {
            FilesLocation.WriteMarker(dir, Guid.NewGuid());
            await Configure(factory, dir, Guid.NewGuid());

            var r = await Put(device);

            Assert.Equal(HttpStatusCode.ServiceUnavailable, r.StatusCode);
            Assert.Equal("files_unavailable", (await r.ReadAsync<ErrorResponse>()).Code);
        }
        finally { Directory.Delete(dir, recursive: true); }
    }

    [Fact]
    public async Task A_location_that_comes_back_is_picked_up_by_recheck()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var dir = Path.Combine(Path.GetTempPath(), "tandem-back-" + Guid.NewGuid().ToString("N"));
        var id = Guid.NewGuid();
        await Configure(factory, dir, id);
        var location = factory.Services.GetRequiredService<FilesLocation>();
        Assert.False(location.Available);
        try
        {
            FilesLocation.WriteMarker(dir, id);

            Assert.True(location.Recheck());
            Assert.Equal(HttpStatusCode.NoContent, (await Put(device)).StatusCode);
        }
        finally { Directory.Delete(dir, recursive: true); }
    }

    [Fact]
    public void Exported_settings_drop_the_files_location()
    {
        Assert.Null(BackupSettings.RedactForExport(FilesLocation.Key, "{}"));
        Assert.Equal("v", BackupSettings.RedactForExport("other.key", "v"));
    }
}
