using System.Text.Json;
using System.Text.Json.Serialization;
using Tandem.Protocol;
using Tandem.Server.Auth;
using Tandem.Server.Blobs;
using Tandem.Server.Data;
using Tandem.Server.Http;
using Microsoft.EntityFrameworkCore;

namespace Tandem.Server.Backup;

/// <summary>
/// The server-wide backup settings, stored as one JSON <see cref="ServerSetting"/> row. The passphrase and the S3
/// secret key are kept as given (scheduled runs need them) but never returned, never logged, and stripped from the
/// copy of this row that goes into a backup (which also arrives disabled, see <see cref="RedactForExport"/>).
/// </summary>
public static class BackupSettings
{
    public const string Key = "backup.settings";
    public const int MinKeep = 1;
    public const int MaxKeep = 1000;

    public static readonly BackupSettingsDto Defaults = new(false, BackupTargetKind.Folder, null, null, 3, 7, false, null);

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { Converters = { new JsonStringEnumConverter() } };

    public static async Task<BackupSettingsDto> LoadAsync(TandemDbContext db, CancellationToken ct)
    {
        var row = await db.ServerSettings.AsNoTracking().FirstOrDefaultAsync(s => s.Key == Key, ct);
        return row is null ? Defaults : Parse(row.Value);
    }

    private static BackupSettingsDto Parse(string json)
    {
        try { return JsonSerializer.Deserialize<BackupSettingsDto>(json, Json) ?? Defaults; }
        catch (JsonException) { return Defaults; }
    }

    public static BackupSettingsResponse ToResponse(BackupSettingsDto s) =>
        new(s.Enabled, s.Kind, s.FolderPath, s.S3 is null ? null : s.S3 with { SecretKey = null }, s.HourUtc, s.Keep, s.Encrypt,
            !string.IsNullOrEmpty(s.Passphrase));

    /// <summary>Validates (400 bad_value), merges the write-only secrets with the stored ones, and stores.</summary>
    public static async Task<BackupSettingsDto> SaveAsync(TandemDbContext db, BackupSettingsDto? value, CancellationToken ct)
    {
        if (value is null) throw new ApiException(400, "bad_request", "A request body is required.");
        var current = await LoadAsync(db, ct);

        // Null keeps the stored secret; "" clears the passphrase.
        var passphrase = value.Passphrase is null ? current.Passphrase : value.Passphrase.Length == 0 ? null : value.Passphrase;
        var s3 = value.S3 is null ? null : value.S3 with
        {
            SecretKey = value.S3.SecretKey ?? current.S3?.SecretKey,
            Endpoint = string.IsNullOrWhiteSpace(value.S3.Endpoint) ? null : value.S3.Endpoint.Trim(),
        };
        var folder = string.IsNullOrWhiteSpace(value.FolderPath) ? null : value.FolderPath.Trim();
        var merged = value with { Passphrase = passphrase, S3 = s3, FolderPath = folder };

        if (!Enum.IsDefined(merged.Kind)) throw Bad("kind must be Folder or S3.");
        if (merged.HourUtc is < 0 or > 23) throw Bad("hourUtc must be 0-23.");
        if (merged.Keep is < MinKeep or > MaxKeep) throw Bad($"keep must be {MinKeep}-{MaxKeep}.");
        if (merged.Encrypt && string.IsNullOrEmpty(merged.Passphrase))
            throw Bad("Set a passphrase to encrypt backups. Losing it means the backups cannot be restored.");
        if (merged.FolderPath is not null && !Path.IsPathFullyQualified(merged.FolderPath))
            throw Bad("folderPath must be an absolute path.");
        if (merged.S3?.Endpoint is { } endpoint
            && (!Uri.TryCreate(endpoint, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https")))
            throw Bad("s3.endpoint must be an http(s) URL.");
        if (merged.Enabled)
        {
            if (merged.Kind == BackupTargetKind.Folder && merged.FolderPath is null)
                throw Bad("folderPath is required for a folder target.");
            if (merged.Kind == BackupTargetKind.S3 && (merged.S3 is null || string.IsNullOrWhiteSpace(merged.S3.Bucket)
                                                       || string.IsNullOrWhiteSpace(merged.S3.AccessKey)
                                                       || string.IsNullOrEmpty(merged.S3.SecretKey)))
                throw Bad("s3 with bucket, accessKey and secretKey is required for an S3 target.");
        }

        var text = JsonSerializer.Serialize(merged, Json);
        var row = await db.ServerSettings.FirstOrDefaultAsync(s => s.Key == Key, ct);
        if (row is null) db.ServerSettings.Add(new ServerSetting { Key = Key, Value = text });
        else row.Value = text;
        await db.SaveChangesAsync(ct);
        return merged;
    }

    /// <summary>The value of a server setting as it goes into a backup: the backup settings lose their secrets and are
    /// disabled (a restored server must not start writing to, and pruning, the target it was restored from). The files
    /// location and the move status are dropped (null): the folders belong to the server they were set on.</summary>
    public static string? RedactForExport(string key, string value)
    {
        if (key == FilesLocation.Key) return null; // a restored server keeps its files in its own default folder
        if (key == FilesMover.Key) return null; // and has no move (or old copy in a folder of the old server) to finish
        if (key != Key) return value;
        var s = Parse(value);
        return JsonSerializer.Serialize(s with { Enabled = false, Passphrase = null, Encrypt = false, S3 = s.S3 is null ? null : s.S3 with { SecretKey = null } }, Json);
    }

    private static ApiException Bad(string message) => new(400, "bad_value", message);
}

public static class BackupEndpoints
{
    public static IEndpointRouteBuilder MapBackupEndpoints(this IEndpointRouteBuilder app)
    {
        var backup = app.MapGroup("/v1/admin/backup").RequireAuthorization(AuthSetup.AdminPolicy);

        backup.MapGet("", async (TandemDbContext db, BackupService backups, CancellationToken ct) =>
            BackupSettings.ToResponse(await BackupSettings.LoadAsync(db, ct)) with { Running = backups.IsRunning });

        backup.MapPut("", async (TandemDbContext db, BackupSettingsDto? body, CancellationToken ct) =>
            BackupSettings.ToResponse(await BackupSettings.SaveAsync(db, body, ct)));

        backup.MapPost("/run", (BackupService backups, Blobs.FilesGate gate) =>
        {
            if (gate.Moving) throw ApiException.ServerMoving("The stored files are being moved. Run the backup when the move is done.");
            if (!backups.TryStartInBackground())
                throw new ApiException(409, "backup_running", "A backup is already running.");
            return Results.Accepted();
        });

        backup.MapGet("/history", async (TandemDbContext db, CancellationToken ct) =>
            await db.BackupRuns.AsNoTracking().OrderByDescending(r => r.Id).Take(BackupService.HistoryShown)
                .Select(r => new BackupRunInfo(r.Utc, r.Ok, r.Bytes, r.Error, r.Partial)).ToListAsync(ct));
        return app;
    }
}
