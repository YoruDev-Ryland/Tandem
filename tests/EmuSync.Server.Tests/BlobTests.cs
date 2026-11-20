using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Security.Cryptography;
using EmuSync.Protocol;
using Microsoft.Extensions.DependencyInjection;
using EmuSync.Server.Tests.Infrastructure;

namespace EmuSync.Server.Tests;

[Collection("Memory")]
public class BlobTests
{
    private static string Sha(byte[] data) => Convert.ToHexStringLower(SHA256.HashData(data));

    private static HttpContent Raw(Stream s)
    {
        var c = new StreamContent(s, 81920);
        c.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
        return c;
    }

    /// <summary>A plain HttpClient over a real socket (no in-memory handler that would buffer the body).</summary>
    private static HttpClient Socket(ServerFactory f, HttpClient authed)
    {
        var client = new HttpClient(new SocketsHttpHandler()) { BaseAddress = f.ClientOptions.BaseAddress, Timeout = TimeSpan.FromMinutes(10) };
        client.DefaultRequestHeaders.Add(Protocol.Protocol.Header, Protocol.Protocol.Version.ToString());
        client.DefaultRequestHeaders.Authorization = authed.DefaultRequestHeaders.Authorization;
        return client;
    }

    private static string[] Files(ServerFactory f)
    {
        var dir = Path.Combine(f.DataDirectory, "blobs");
        return Directory.Exists(dir) ? Directory.GetFiles(dir, "*", SearchOption.AllDirectories) : [];
    }

    /// <summary>Deterministic pseudo-random stream of a fixed length that hashes as it is read.</summary>
    private sealed class GeneratedStream(long length) : Stream
    {
        private readonly IncrementalHash _hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
        private long _pos;
        private ulong _state = 0x9E3779B97F4A7C15;
        public string? Hash { get; private set; }
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => length;
        public override long Position { get => _pos; set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override long Seek(long o, SeekOrigin s) => throw new NotSupportedException();
        public override void SetLength(long v) => throw new NotSupportedException();
        public override void Write(byte[] b, int o, int c) => throw new NotSupportedException();
        public override int Read(byte[] buffer, int offset, int count)
        {
            var n = (int)Math.Min(count, length - _pos);
            if (n == 0) return 0;
            for (var i = 0; i < n; i++)
            {
                _state ^= _state << 13; _state ^= _state >> 7; _state ^= _state << 17;
                buffer[offset + i] = (byte)_state;
            }
            _hash.AppendData(buffer, offset, n);
            _pos += n;
            if (_pos == length) Hash = Convert.ToHexStringLower(_hash.GetHashAndReset());
            return n;
        }
    }

    private sealed class ThrowingStream(int after) : Stream
    {
        private int _sent;
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override long Seek(long o, SeekOrigin s) => throw new NotSupportedException();
        public override void SetLength(long v) => throw new NotSupportedException();
        public override void Write(byte[] b, int o, int c) => throw new NotSupportedException();
        public override int Read(byte[] buffer, int offset, int count)
        {
            if (_sent >= after) throw new IOException("client aborted");
            var n = Math.Min(count, after - _sent);
            Array.Fill(buffer, (byte)7, offset, n);
            _sent += n;
            return n;
        }
    }

    [Fact]
    public async Task Put_then_get_roundtrips()
    {
        using var factory = new ServerFactory();
        var (device, key) = await factory.Register("a@example.com");
        var data = new byte[5000]; new Random(1).NextBytes(data);
        var hash = Sha(data);

        var put = await device.PutAsync($"/v1/blobs/{hash}", Raw(new MemoryStream(data)));
        Assert.Equal(HttpStatusCode.NoContent, put.StatusCode);
        var expected = Path.Combine(factory.DataDirectory, "blobs", key.UserId.ToString("N"), hash[..2], hash[2..4], hash);
        Assert.True(File.Exists(expected));

        var again = await device.PutAsync($"/v1/blobs/{hash}", Raw(new MemoryStream(data)));
        Assert.Equal(HttpStatusCode.NoContent, again.StatusCode);
        Assert.Single(Files(factory));

        var get = await device.GetAsync($"/v1/blobs/{hash}");
        Assert.Equal(HttpStatusCode.OK, get.StatusCode);
        Assert.Equal(data, await get.Content.ReadAsByteArrayAsync());
    }

    [Fact]
    public async Task Missing_reports_only_absent_hashes()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var data = "hello"u8.ToArray();
        var have = Sha(data);
        var absent = Sha("other"u8.ToArray());
        (await device.PutAsync($"/v1/blobs/{have}", Raw(new MemoryStream(data)))).EnsureSuccessStatusCode();

        var r = await device.PostAsJsonAsync("/v1/blobs/missing", new MissingBlobsRequest([have, absent]));
        Assert.Equal(HttpStatusCode.OK, r.StatusCode);
        Assert.Equal([absent], (await r.ReadAsync<MissingBlobsResponse>()).Missing);

        var bad = await device.PostAsJsonAsync("/v1/blobs/missing", new MissingBlobsRequest(["nope"]));
        Assert.Equal(HttpStatusCode.BadRequest, bad.StatusCode);
        Assert.Equal("bad_hash", (await bad.ReadAsync<ErrorResponse>()).Code);

        var many = await device.PostAsJsonAsync("/v1/blobs/missing",
            new MissingBlobsRequest(Enumerable.Repeat(absent, 1001).ToList()));
        Assert.Equal(HttpStatusCode.BadRequest, many.StatusCode);
        Assert.Equal("too_many", (await many.ReadAsync<ErrorResponse>()).Code);
    }

    [Fact]
    public async Task Wrong_hash_is_400_and_leaves_no_file()
    {
        using var factory = new ServerFactory();
        var (device, _) = await factory.Register("a@example.com");
        var wrong = Sha("something else"u8.ToArray());

        var r = await device.PutAsync($"/v1/blobs/{wrong}", Raw(new MemoryStream("payload"u8.ToArray())));
        Assert.Equal(HttpStatusCode.BadRequest, r.StatusCode);
        Assert.Equal("hash_mismatch", (await r.ReadAsync<ErrorResponse>()).Code);
        Assert.Empty(Files(factory));

        var badName = await device.PutAsync("/v1/blobs/ABC", Raw(new MemoryStream([1])));
        Assert.Equal("bad_hash", (await badName.ReadAsync<ErrorResponse>()).Code);
    }

    [Fact]
    public async Task Aborted_upload_leaves_no_partial_blob()
    {
        using var factory = new ServerFactory();
        factory.UseKestrel(0); // TestServer buffers request bodies, so use a real socket
        factory.StartServer();
        var (inMemory, _) = await factory.Register("a@example.com");
        var device = Socket(factory, inMemory);
        var hash = Sha("x"u8.ToArray());

        try { await device.PutAsync($"/v1/blobs/{hash}", Raw(new ThrowingStream(1024 * 1024))); }
        catch (HttpRequestException) { }
        await Task.Delay(300);
        Assert.Empty(Files(factory));
    }

    [Fact]
    public async Task Too_large_is_413_and_leaves_no_file()
    {
        using var factory = new ServerFactory();
        factory.Settings["EmuSync:MaxBlobBytes"] = "1000";
        var (device, _) = await factory.Register("a@example.com");
        var data = new byte[5000];
        var r = await device.PutAsync($"/v1/blobs/{Sha(data)}", Raw(new MemoryStream(data)));
        Assert.Equal(HttpStatusCode.RequestEntityTooLarge, r.StatusCode);
        Assert.Equal("too_large", (await r.ReadAsync<ErrorResponse>()).Code);
        Assert.Empty(Files(factory));
    }

    [Fact]
    public async Task Too_large_chunked_upload_on_kestrel_is_413_and_leaves_no_file()
    {
        using var factory = new ServerFactory();
        factory.Settings["EmuSync:MaxBlobBytes"] = (1024 * 1024).ToString();
        factory.UseKestrel(0);
        factory.StartServer();
        var (inMemory, key) = await factory.Register("a@example.com");
        var device = Socket(factory, inMemory);
        const long size = 5L * 1024 * 1024;
        var probe = new GeneratedStream(size);
        var buf = new byte[1 << 20];
        while (probe.Read(buf, 0, buf.Length) > 0) { }

        var content = Raw(new GeneratedStream(size)); // non-seekable: no Content-Length, chunked
        Assert.Null(content.Headers.ContentLength);
        var outcome = "reset";
        try
        {
            var r = await device.PutAsync($"/v1/blobs/{probe.Hash}", content);
            Assert.Equal(HttpStatusCode.RequestEntityTooLarge, r.StatusCode);
            Assert.Equal("too_large", (await r.ReadAsync<ErrorResponse>()).Code);
            outcome = "413";
        }
        catch (HttpRequestException) { }
        Console.WriteLine($"chunked over-limit outcome: {outcome}");

        await Task.Delay(300);
        Assert.Empty(Files(factory));
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<EmuSync.Server.Data.EmuSyncDbContext>();
        Assert.False(db.UserBlobs.Any(b => b.UserId == key.UserId));
    }

    [Fact]
    public async Task Large_upload_is_not_buffered()
    {
        using var factory = new ServerFactory();
        factory.UseKestrel(0); // TestServer buffers request bodies, so use a real socket
        factory.StartServer();
        var (inMemory, _) = await factory.Register("a@example.com");
        var device = Socket(factory, inMemory);
        const long size = 300L * 1024 * 1024;

        // First pass computes the hash; the second is the real upload.
        var probe = new GeneratedStream(size);
        var buf = new byte[1 << 20];
        while (probe.Read(buf, 0, buf.Length) > 0) { }
        var hash = probe.Hash!;

        var before = GC.GetTotalMemory(true);
        var peak = before;
        using var cts = new CancellationTokenSource();
        var sampler = Task.Run(async () =>
        {
            while (!cts.IsCancellationRequested)
            {
                peak = Math.Max(peak, GC.GetTotalMemory(false));
                await Task.Delay(50);
            }
        });
        var r = await device.PutAsync($"/v1/blobs/{hash}", Raw(new GeneratedStream(size)));
        cts.Cancel();
        await sampler;
        Assert.Equal(HttpStatusCode.NoContent, r.StatusCode);
        var after = GC.GetTotalMemory(true);

        Assert.True(after - before < 100L * 1024 * 1024, $"retained growth {after - before}");
        Assert.True(peak - before < 100L * 1024 * 1024, $"peak growth {peak - before}");
    }

    [Fact]
    public async Task User_cannot_read_another_users_blob()
    {
        using var factory = new ServerFactory();
        factory.Settings["EmuSync:AllowRegistration"] = "true";
        var (a, _) = await factory.Register("a@example.com");
        var (b, _) = await factory.Register("b@example.com");
        var data = "secret"u8.ToArray();
        var hash = Sha(data);
        (await a.PutAsync($"/v1/blobs/{hash}", Raw(new MemoryStream(data)))).EnsureSuccessStatusCode();

        var r = await b.GetAsync($"/v1/blobs/{hash}");
        Assert.Equal(HttpStatusCode.NotFound, r.StatusCode);
        Assert.Equal("blob_not_found", (await r.ReadAsync<ErrorResponse>()).Code);
        var missing = await b.PostAsJsonAsync("/v1/blobs/missing", new MissingBlobsRequest([hash]));
        Assert.Equal([hash], (await missing.ReadAsync<MissingBlobsResponse>()).Missing);
    }
}
