using EmuSync.Protocol;
using EmuSync.Server.Auth;
using EmuSync.Server.Http;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.Extensions.Options;

namespace EmuSync.Server.Blobs;

public static class BlobEndpoints
{
    public const int MaxMissingHashes = 1000;

    public static IEndpointRouteBuilder MapBlobEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapPost("/v1/blobs/missing", async (HttpContext http, MissingBlobsRequest request, BlobService blobs, CancellationToken ct) =>
        {
            var hashes = request.Hashes ?? [];
            if (hashes.Count > MaxMissingHashes)
                throw new ApiException(400, "too_many", $"At most {MaxMissingHashes} hashes per request.");
            foreach (var h in hashes) BlobHash.Require(h);
            return new MissingBlobsResponse(await blobs.MissingAsync(http.User.UserId(), hashes, ct));
        }).RequireAuthorization();

        app.MapPut("/v1/blobs/{hash}", async (string hash, HttpContext http, IBlobStore store, BlobService blobs,
            IOptions<ServerOptions> options, CancellationToken ct) =>
        {
            BlobHash.Require(hash);
            var max = options.Value.MaxBlobBytes;

            // Kestrel enforces this limit while streaming; the stream wrapper below enforces it everywhere else.
            var limit = http.Features.Get<IHttpMaxRequestBodySizeFeature>();
            if (limit is { IsReadOnly: false }) limit.MaxRequestBodySize = max;
            if (http.Request.ContentLength > max) throw TooLarge();

            long size;
            try
            {
                size = await store.PutAsync(http.User.UserId(), hash, new LimitedStream(http.Request.Body, max), ct);
            }
            catch (BadHttpRequestException ex) when (ex.StatusCode == StatusCodes.Status413PayloadTooLarge)
            {
                throw TooLarge();
            }
            await blobs.RecordAsync(http.User.UserId(), hash, size, ct);
            return Results.NoContent();
        }).RequireAuthorization().DisableAntiforgery();

        app.MapGet("/v1/blobs/{hash}", async (string hash, HttpContext http, IBlobStore store, BlobService blobs, CancellationToken ct) =>
        {
            BlobHash.Require(hash);
            var userId = http.User.UserId();
            var stream = await blobs.HasAsync(userId, hash, ct) ? await store.OpenAsync(userId, hash, ct) : null;
            if (stream is null) throw new ApiException(404, "blob_not_found", "Blob not found.");
            return Results.File(stream, "application/octet-stream", enableRangeProcessing: true);
        }).RequireAuthorization();
        return app;
    }

    private static ApiException TooLarge() => new(413, "too_large", "Blob exceeds the maximum allowed size.");

    private sealed class LimitedStream(Stream inner, long max) : Stream
    {
        private long _read;
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();

        public override int Read(byte[] buffer, int offset, int count) => Count(inner.Read(buffer, offset, count));

        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default) =>
            Count(await inner.ReadAsync(buffer, ct));

        private int Count(int n)
        {
            _read += n;
            if (_read > max) throw TooLarge();
            return n;
        }
    }
}
