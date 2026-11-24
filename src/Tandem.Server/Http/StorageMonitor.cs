using Tandem.Server.Blobs;
using Microsoft.Extensions.Options;

namespace Tandem.Server.Http;

/// <summary>Free bytes on the volume that holds a directory; replaceable in tests.</summary>
public interface IFreeSpaceProbe
{
    /// <summary>Free bytes available to this process, or null when it cannot be read.</summary>
    long? FreeBytes(string directory);
}

public sealed class DriveFreeSpaceProbe : IFreeSpaceProbe
{
    public long? FreeBytes(string directory)
    {
        try { return new DriveInfo(Path.GetFullPath(directory)).AvailableFreeSpace; }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or ArgumentException) { return null; }
    }
}

/// <summary>
/// Whether the data directory or the files location is low on space (C8): below <see cref="ServerOptions.MinFreeBytes"/>.
/// The probe is cached for <see cref="CacheFor"/>, so the hot upload path never touches the file system more than
/// twice a minute. An unreadable value is not "low" (the guard fails open: refusing every upload because statvfs
/// failed would be worse). When both are on one disk it is probed once per path and simply reported once.
/// </summary>
public sealed class StorageMonitor(IOptions<ServerOptions> options, IFreeSpaceProbe probe, TimeProvider time, FilesLocation location)
{
    public static readonly TimeSpan CacheFor = TimeSpan.FromSeconds(30);
    private readonly object _gate = new();
    private DateTimeOffset _checkedAt = DateTimeOffset.MinValue;
    private string _checkedRoot = "";
    private long? _free;
    private string? _lowDisk;

    /// <summary>Free bytes on the emptiest probed disk (the low one when <see cref="IsLow"/>).</summary>
    public long? FreeBytes => Refresh().Free;

    /// <summary>The path whose disk is low, or null when neither is.</summary>
    public string? LowDisk => Refresh().Low;

    public bool IsLow => LowDisk is not null;

    private (long? Free, string? Low) Refresh()
    {
        var now = time.GetUtcNow();
        var root = location.Root;
        lock (_gate)
        {
            if (now - _checkedAt >= CacheFor || now < _checkedAt || root != _checkedRoot)
            {
                var data = options.Value.DataDirectory;
                Directory.CreateDirectory(data);
                _free = null;
                _lowDisk = null;
                foreach (var dir in root.Length > 0 && root != data ? new[] { data, root } : [data])
                {
                    if (probe.FreeBytes(dir) is not { } free) continue;
                    if (_free is null || free < _free) _free = free;
                    if (free < options.Value.MinFreeBytes && _lowDisk is null) _lowDisk = dir;
                }
                _checkedAt = now;
                _checkedRoot = root;
            }
            return (_free, _lowDisk);
        }
    }

    /// <summary>507 storage_full while the data directory or the files location is low on space.</summary>
    public void RequireSpace()
    {
        if (IsLow)
            throw new ApiException(507, "storage_full",
                "The server is almost out of disk space; uploads are paused until space is freed.");
    }
}

public static class StorageGuard
{
    /// <summary>Refuses the request with 507 storage_full (before the handler reads the body) while storage is low.</summary>
    public static TBuilder RequireStorage<TBuilder>(this TBuilder builder) where TBuilder : IEndpointConventionBuilder =>
        builder.AddEndpointFilter(async (context, next) =>
        {
            context.HttpContext.RequestServices.GetRequiredService<StorageMonitor>().RequireSpace();
            return await next(context);
        });
}

public static class HoldGuard
{
    /// <summary>Refuses the request with 503 server_moving while the stored files are on hold for a move; devices wait and retry.</summary>
    public static TBuilder RequireNotHolding<TBuilder>(this TBuilder builder) where TBuilder : IEndpointConventionBuilder =>
        builder.AddEndpointFilter(async (context, next) =>
        {
            if (context.HttpContext.RequestServices.GetRequiredService<FilesGate>().Holding)
            {
                // A result, not an ApiException: the error middleware clears the response, and with it the header.
                context.HttpContext.Response.Headers.RetryAfter = "15";
                return Results.Json(new ApiException(503, "server_moving", "The server is moving its files. Try again shortly.").ToResponse(),
                    statusCode: StatusCodes.Status503ServiceUnavailable);
            }
            return await next(context);
        });
}
