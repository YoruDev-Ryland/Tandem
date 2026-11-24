using System.Text.Json;
using Tandem.Server.Data;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace Tandem.Server.Blobs;

/// <summary>The stored form of <c>files.location</c>: the folder and the id its <c>.tandem-files</c> marker must hold.</summary>
public sealed record FilesLocationSetting(string Path, Guid Id);

/// <summary>
/// Where the stored files live: the one place blob paths are built. The default is <c>DATA_DIR/blobs</c>; a server
/// setting can point elsewhere. A custom folder counts as present only while its marker file holds the id stored with
/// the setting: a missing folder or marker (an unmounted NAS) is never recreated, so nothing is written to the wrong
/// disk. Until it is back <see cref="Available"/> is false and <see cref="BlobStore"/> answers 503.
/// </summary>
public sealed class FilesLocation
{
    public const string Key = "files.location";
    public const string MarkerName = ".tandem-files";

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    private readonly string _defaultRoot;
    private volatile Current _current;

    public FilesLocation(IOptions<ServerOptions> options)
    {
        _defaultRoot = Path.Combine(options.Value.DataDirectory, "blobs");
        _current = new Current(_defaultRoot, null, true);
    }

    private sealed record Current(string Root, Guid? Id, bool Available);

    public string DefaultRoot => _defaultRoot;
    public string Root => _current.Root;
    public bool IsDefault => _current.Id is null;
    public bool Available => _current.Available;

    /// <summary>Startup: reads the setting. The default location gets its marker if missing; a custom one is only checked.</summary>
    public async Task LoadAsync(TandemDbContext db, CancellationToken ct)
    {
        var row = await db.ServerSettings.AsNoTracking().FirstOrDefaultAsync(s => s.Key == Key, ct);
        var setting = row is null ? null : Parse(row.Value);
        if (row is null)
        {
            _current = new Current(_defaultRoot, null, true);
            EnsureDefaultMarker();
            return;
        }
        // An unreadable setting leaves the location unknown: unavailable, and nothing is written to the default folder.
        _current = string.IsNullOrWhiteSpace(setting?.Path) ? new Current("", Guid.Empty, false)
            : new Current(setting.Path, setting.Id, MarkerMatches(setting.Path, setting.Id));
    }

    /// <summary>Stores the setting and makes the folder the current one (the caller has written its marker).</summary>
    public async Task SwitchAsync(TandemDbContext db, string path, Guid id, CancellationToken ct)
    {
        var text = JsonSerializer.Serialize(new FilesLocationSetting(path, id), Json);
        var row = await db.ServerSettings.FirstOrDefaultAsync(s => s.Key == Key, ct);
        if (row is null) db.ServerSettings.Add(new ServerSetting { Key = Key, Value = text });
        else row.Value = text;
        await db.SaveChangesAsync(ct);
        _current = new Current(path, id, true);
    }

    /// <summary>Re-reads the marker (a NAS may have come back) and returns whether the files are available.</summary>
    public bool Recheck()
    {
        var c = _current;
        if (c.Id is { } id && !c.Available && c.Root.Length > 0)
            _current = c with { Available = MarkerMatches(c.Root, id) };
        return _current.Available;
    }

    /// <summary>Before a blob is read or written: a custom folder must still hold its marker (a vanished NAS is never
    /// rebuilt on the local disk). A missing marker marks the location unavailable until <see cref="Recheck"/> finds it.</summary>
    public bool CheckPresent()
    {
        var c = _current;
        if (!c.Available) return false;
        if (c.Id is null || File.Exists(Path.Combine(c.Root, MarkerName))) return true;
        _current = c with { Available = false };
        return false;
    }

    public static Guid? ReadMarker(string dir)
    {
        try
        {
            var path = Path.Combine(dir, MarkerName);
            if (!File.Exists(path)) return null;
            return JsonSerializer.Deserialize<Marker>(File.ReadAllText(path), Json)?.Id;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or JsonException)
        {
            return null;
        }
    }

    public static void WriteMarker(string dir, Guid id)
    {
        Directory.CreateDirectory(dir);
        File.WriteAllText(Path.Combine(dir, MarkerName), JsonSerializer.Serialize(new Marker(id), Json));
    }

    public string UserDir(Guid userId) => Path.Combine(Root, userId.ToString("N"));

    public string PathFor(Guid userId, string hash)
    {
        BlobHash.Require(hash);
        return Path.Combine(UserDir(userId), hash[..2], hash[2..4], hash);
    }

    /// <summary>Startup helper shared by the server and the admin CLI (call after the database is migrated).</summary>
    public static void LoadAtStartup(IServiceProvider services)
    {
        using var scope = services.CreateScope();
        scope.ServiceProvider.GetRequiredService<FilesLocation>()
            .LoadAsync(scope.ServiceProvider.GetRequiredService<TandemDbContext>(), CancellationToken.None).GetAwaiter().GetResult();
    }

    private void EnsureDefaultMarker()
    {
        if (ReadMarker(_defaultRoot) is not null) return;
        try { WriteMarker(_defaultRoot, Guid.NewGuid()); }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { } // the first upload reports a real failure
    }

    private static bool MarkerMatches(string dir, Guid id) => ReadMarker(dir) == id;

    private static FilesLocationSetting? Parse(string json)
    {
        try { return JsonSerializer.Deserialize<FilesLocationSetting>(json, Json); }
        catch (JsonException) { return null; }
    }

    private sealed record Marker(Guid Id);
}

/// <summary>While the files folder is missing, looks again every minute so a NAS that comes back is picked up without a restart.</summary>
public sealed class FilesLocationWatcher(FilesLocation location) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromMinutes(1));
        while (await timer.WaitForNextTickAsync(stoppingToken))
            if (!location.Available) location.Recheck();
    }
}
