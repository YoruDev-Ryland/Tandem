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
/// Whether the data directory is low on space (C8): below <see cref="ServerOptions.MinFreeBytes"/>. The probe is cached
/// for <see cref="CacheFor"/>, so the hot upload path never touches the file system more than twice a minute. An
/// unreadable value is not "low" (the guard fails open: refusing every upload because statvfs failed would be worse).
/// </summary>
public sealed class StorageMonitor(IOptions<ServerOptions> options, IFreeSpaceProbe probe, TimeProvider time)
{
    public static readonly TimeSpan CacheFor = TimeSpan.FromSeconds(30);
    private readonly object _gate = new();
    private DateTimeOffset _checkedAt = DateTimeOffset.MinValue;
    private long? _free;

    public long? FreeBytes
    {
        get
        {
            var now = time.GetUtcNow();
            lock (_gate)
            {
                if (now - _checkedAt >= CacheFor || now < _checkedAt)
                {
                    Directory.CreateDirectory(options.Value.DataDirectory);
                    _free = probe.FreeBytes(options.Value.DataDirectory);
                    _checkedAt = now;
                }
                return _free;
            }
        }
    }

    public bool IsLow => FreeBytes is { } free && free < options.Value.MinFreeBytes;

    /// <summary>507 storage_full while the data directory is low on space.</summary>
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
