using System.Collections.Concurrent;
using System.Net;
using System.Threading.Channels;
using Microsoft.Extensions.Options;

namespace Tandem.Server.Art;

/// <summary>
/// Fetches covers in the background, one at a time: a de-duplicated queue that tries the sources in order, downloads at
/// most ArtMaxImageBytes, keeps only real PNG or JPEG bytes (by their magic numbers, not the claimed type), then stores
/// the image or records a miss for ArtMissDays. When a source could not be reached (network error, timeout, 5xx, 429),
/// the miss is recorded for an hour only, and marked transient. Any other failure (a full disk, say) is also a
/// transient miss for an hour. Nothing runs, and nothing is fetched, with <c>ART=false</c>.
/// </summary>
public sealed class ArtFetcher(IEnumerable<IArtSource> sources, ArtStore store, IHttpClientFactory http, IOptions<ServerOptions> options,
    TimeProvider time, ILogger<ArtFetcher> log) : BackgroundService
{
    public const string ClientName = "art";
    public static readonly TimeSpan RequestTimeout = TimeSpan.FromSeconds(20);
    public const string UserAgent = "Tandem-server";
    private const int QueueCapacity = 256;
    private static readonly TimeSpan RetryAfterFailure = TimeSpan.FromHours(1);

    /// <summary>The art client's handler: redirects are not followed, so a source can't send the server to another
    /// host (an internal address, say). The sources' URLs are fixed and answer directly.</summary>
    public static HttpMessageHandler CreatePrimaryHandler() => new SocketsHttpHandler { AllowAutoRedirect = false };

    private readonly Channel<ArtRequest> _queue = Channel.CreateBounded<ArtRequest>(new BoundedChannelOptions(QueueCapacity)
    {
        SingleReader = true, FullMode = BoundedChannelFullMode.Wait,
    });
    private readonly ConcurrentDictionary<string, byte> _pending = new(StringComparer.Ordinal);
    private readonly object _idleGate = new();
    private int _outstanding;
    private TaskCompletionSource _idle = Completed();

    private static TaskCompletionSource Completed()
    {
        var t = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        t.SetResult();
        return t;
    }

    /// <summary>Queues a fetch unless one for this game is already queued or running. False when art is off or the
    /// queue is full (the next request asks again).</summary>
    public bool Enqueue(ArtRequest request)
    {
        if (!options.Value.EnableArt) return false;
        var name = ArtStore.NameOf(request.EmulatorId, request.GameKey);
        if (!_pending.TryAdd(name, 0)) return true;
        lock (_idleGate)
        {
            if (_outstanding++ == 0) _idle = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        }
        if (_queue.Writer.TryWrite(request)) return true;
        Done(name);
        return false;
    }

    /// <summary>Completes when nothing is queued or running (a test hook).</summary>
    public Task WhenIdleAsync()
    {
        lock (_idleGate) return _idle.Task;
    }

    private void Done(string name)
    {
        _pending.TryRemove(name, out _);
        lock (_idleGate)
        {
            if (--_outstanding == 0) _idle.TrySetResult();
        }
    }

    protected override async Task ExecuteAsync(CancellationToken stop)
    {
        if (!options.Value.EnableArt) return;
        try { store.Sweep(); }
        catch (Exception e) { log.LogDebug(e, "Couldn't sweep the cover cache"); }
        await foreach (var request in _queue.Reader.ReadAllAsync(stop))
        {
            try { await FetchAsync(request, stop); }
            catch (OperationCanceledException) when (stop.IsCancellationRequested) { throw; }
            catch (Exception e)
            {
                // Not a source saying no (for example a full disk): ask again in an hour, not on every request.
                log.LogWarning(e, "Cover fetch for {Emulator} failed", request.EmulatorId);
                try { store.RecordMiss(request.EmulatorId, request.GameKey, time.GetUtcNow() + RetryAfterFailure, transient: true); }
                catch (Exception again) { log.LogDebug(again, "Couldn't record the failed cover fetch"); }
            }
            finally { Done(ArtStore.NameOf(request.EmulatorId, request.GameKey)); }
        }
    }

    private async Task FetchAsync(ArtRequest request, CancellationToken stop)
    {
        var o = options.Value;
        var unreachable = false;
        foreach (var source in sources)
        {
            IReadOnlyList<Uri> candidates;
            try { candidates = await source.CandidatesAsync(request, stop); }
            catch (ArtSourceUnavailableException e)
            {
                log.LogDebug(e, "Cover source unavailable");
                unreachable = true;
                continue;
            }
            foreach (var uri in candidates)
            {
                var (image, failed) = await DownloadAsync(uri, o.ArtMaxImageBytes, stop);
                unreachable |= failed;
                if (image is null) continue;
                store.Save(request.EmulatorId, request.GameKey, image.Value.Bytes, image.Value.Extension);
                return;
            }
        }
        var wait = unreachable ? RetryAfterFailure : TimeSpan.FromDays(Math.Max(1, o.ArtMissDays));
        store.RecordMiss(request.EmulatorId, request.GameKey, time.GetUtcNow() + wait, transient: unreachable);
    }

    /// <summary>The image at [uri], or null; Failed is true when the source could not be reached rather than said no.</summary>
    private async Task<((byte[] Bytes, string Extension)? Image, bool Failed)> DownloadAsync(Uri uri, long max, CancellationToken stop)
    {
        try
        {
            using var response = await http.CreateClient(ClientName).GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, stop);
            if (response.StatusCode == HttpStatusCode.TooManyRequests || (int)response.StatusCode >= 500) return (null, true);
            if (response.StatusCode != HttpStatusCode.OK) return (null, false);
            var bytes = await ReadCappedAsync(response.Content, max, stop);
            if (bytes is null)
            {
                log.LogDebug("Cover at {Host} is over the size cap", uri.Host);
                return (null, false);
            }
            var ext = ImageExtension(bytes);
            if (ext is null) log.LogDebug("Cover at {Host} is not a PNG or JPEG", uri.Host);
            return (ext is null ? null : (bytes, ext), false);
        }
        catch (Exception e) when (e is HttpRequestException or IOException or TaskCanceledException && !stop.IsCancellationRequested)
        {
            log.LogDebug(e, "Cover download from {Host} failed", uri.Host);
            return (null, true);
        }
    }

    /// <summary>The body, or null when it is (or claims to be) longer than [max] bytes.</summary>
    public static async Task<byte[]?> ReadCappedAsync(HttpContent content, long max, CancellationToken ct)
    {
        if (content.Headers.ContentLength is long length && length > max) return null;
        await using var stream = await content.ReadAsStreamAsync(ct);
        using var buffer = new MemoryStream();
        var chunk = new byte[81920];
        int read;
        while ((read = await stream.ReadAsync(chunk, ct)) > 0)
        {
            if (buffer.Length + read > max) return null;
            buffer.Write(chunk, 0, read);
        }
        return buffer.ToArray();
    }

    /// <summary>".png" or ".jpg" by the file's magic bytes, else null.</summary>
    public static string? ImageExtension(ReadOnlySpan<byte> bytes)
    {
        if (bytes.StartsWith((ReadOnlySpan<byte>)[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])) return ".png";
        if (bytes.StartsWith((ReadOnlySpan<byte>)[0xFF, 0xD8, 0xFF])) return ".jpg";
        return null;
    }
}
