using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Tandem.Protocol;
using Tandem.Server.Blobs;
using Tandem.Server.Data;
using Tandem.Server.Tests.Infrastructure;

namespace Tandem.Server.Tests.Blobs;

[Collection("Memory")]
public class FilesGateTests
{
    private static readonly TimeSpan Soon = TimeSpan.FromSeconds(10);

    private sealed class Hook : IBlobPutHook
    {
        public Func<Task>? OnCommit { get; set; }
        public Task BeforeCommitAsync(CancellationToken ct) => OnCommit?.Invoke() ?? Task.CompletedTask;
    }

    private static Task<HttpResponseMessage> Put(HttpClient device, byte[] bytes)
    {
        var content = new ByteArrayContent(bytes);
        content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        return device.PutAsync($"/v1/blobs/{Convert.ToHexStringLower(SHA256.HashData(bytes))}", content);
    }

    [Fact]
    public async Task A_write_waits_while_the_hold_is_taken_and_proceeds_after()
    {
        var gate = new FilesGate();
        var hold = await gate.HoldAsync(Soon, default);
        Assert.NotNull(hold);
        Assert.True(gate.Holding);

        var write = gate.EnterWriteAsync(default);
        await Task.Delay(100);
        Assert.False(write.IsCompleted);

        hold.Dispose();
        using var _ = await write.WaitAsync(Soon);
        Assert.False(gate.Holding);
    }

    [Fact]
    public async Task The_hold_waits_for_a_write_in_flight()
    {
        var gate = new FilesGate();
        var write = await gate.EnterWriteAsync(default);
        var hold = gate.HoldAsync(Soon, default);
        await Task.Delay(100);
        Assert.False(hold.IsCompleted);
        Assert.True(gate.Holding);

        write.Dispose();
        using var held = await hold.WaitAsync(Soon);
        Assert.NotNull(held);
    }

    [Fact]
    public async Task Hold_times_out_when_a_write_does_not_finish()
    {
        var gate = new FilesGate();
        using var write = await gate.EnterWriteAsync(default);

        Assert.Null(await gate.HoldAsync(TimeSpan.FromMilliseconds(50), default));

        Assert.False(gate.Holding);
        using var again = await gate.EnterWriteAsync(default).WaitAsync(Soon); // writers are not left waiting
    }

    [Fact]
    public async Task Blob_put_during_a_hold_is_503_server_moving_with_retry_after_15()
    {
        using var f = new ServerFactory();
        var (device, _) = await f.Register("a@example.com");
        using var hold = await f.Services.GetRequiredService<FilesGate>().HoldAsync(Soon, default);

        var r = await Put(device, "held"u8.ToArray());

        Assert.Equal(HttpStatusCode.ServiceUnavailable, r.StatusCode);
        Assert.Equal("server_moving", (await r.ReadAsync<ErrorResponse>()).Code);
        Assert.Equal("15", r.Headers.RetryAfter?.Delta?.TotalSeconds.ToString());
    }

    [Fact]
    public async Task Web_upload_during_a_hold_is_503_server_moving()
    {
        using var f = new ServerFactory();
        await f.Register("a@example.com");
        var web = await f.WebLogin("a@example.com");
        using var hold = await f.Services.GetRequiredService<FilesGate>().HoldAsync(Soon, default);
        var form = new MultipartFormDataContent
        {
            { new StringContent("eden"), "emulatorId" }, { new StringContent("Save"), "kind" },
            { new StringContent("G1"), "gameKey" }, { new StringContent(""), "slot" }, { new StringContent(""), "core" },
            { new ByteArrayContent("x"u8.ToArray()), "files", "data.dat" },
        };

        var r = await web.PostAsync("/v1/web/saves/upload", form);

        Assert.Equal(HttpStatusCode.ServiceUnavailable, r.StatusCode);
        Assert.Equal("server_moving", (await r.ReadAsync<ErrorResponse>()).Code);
    }

    [Fact]
    public async Task Manifest_upload_during_a_hold_is_accepted()
    {
        using var f = new ServerFactory();
        var (device, _) = await f.Register("a@example.com");
        var manifest = await Fixtures.Manifest(device, ("data.dat", "stored"u8.ToArray())); // blobs already stored
        using var hold = await f.Services.GetRequiredService<FilesGate>().HoldAsync(Soon, default);

        var r = await device.PostJsonAsync("/v1/saves/upload",
            new SaveUploadRequest("eden", ItemKind.Save, "g", "", "", null, manifest, null));

        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
    }

    [Fact]
    public async Task A_write_that_started_before_a_switch_lands_in_the_new_location()
    {
        var hook = new Hook();
        using var f = new ServerFactory { ConfigureExtraServices = s => { s.RemoveAll<IBlobPutHook>(); s.AddSingleton<IBlobPutHook>(hook); } };
        var (device, _) = await f.Register("a@example.com");
        var location = f.Services.GetRequiredService<FilesLocation>();
        var oldRoot = location.Root;
        var target = Directory.CreateTempSubdirectory("tandem-files-").FullName;
        try
        {
            var id = Guid.NewGuid();
            FilesLocation.WriteMarker(target, id);
            hook.OnCommit = async () => // the body has streamed to the old location; now the move switches
            {
                hook.OnCommit = null;
                using var scope = f.Services.CreateScope();
                await location.SwitchAsync(scope.ServiceProvider.GetRequiredService<TandemDbContext>(), target, id, default);
            };
            var bytes = "straddles the switch"u8.ToArray();
            var hash = Convert.ToHexStringLower(SHA256.HashData(bytes));

            Assert.Equal(HttpStatusCode.NoContent, (await Put(device, bytes)).StatusCode);

            Assert.Equal(target, location.Root);
            var inNew = Directory.GetFiles(target, hash, SearchOption.AllDirectories);
            Assert.Single(inNew);
            Assert.Empty(Directory.GetFiles(oldRoot, "*" + hash + "*", SearchOption.AllDirectories));
            Assert.Empty(Directory.GetFiles(target, "*.tmp-*", SearchOption.AllDirectories));
            Assert.Equal(bytes, await File.ReadAllBytesAsync(inNew[0]));
        }
        finally { Directory.Delete(target, true); }
    }
}
