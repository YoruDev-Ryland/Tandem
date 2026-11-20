namespace EmuSync.Protocol;

public enum BackupTargetKind { Folder, S3 }

/// <param name="Endpoint">An S3-compatible endpoint URL (path-style addressing is used when set); null for AWS itself.</param>
/// <param name="Prefix">A folder inside the bucket ("" or null: the bucket root).</param>
/// <param name="SecretKey">Write-only: never returned (null in responses). On PUT, null keeps the stored one.</param>
/// <param name="Region">Signing region; null means us-east-1.</param>
public sealed record S3Target(string? Endpoint, string Bucket, string? Prefix, string AccessKey, string? SecretKey, string? Region)
{
    public override string ToString() =>
        $"S3Target {{ Endpoint = {Endpoint}, Bucket = {Bucket}, Prefix = {Prefix}, AccessKey = {AccessKey}, Region = {Region} }}";
}

/// <summary>Server-wide off-server backup settings (admin only), as sent on PUT.</summary>
/// <param name="Enabled">The daily schedule. A run can be started by hand either way.</param>
/// <param name="FolderPath">Absolute path of a mounted folder (NAS), for <see cref="BackupTargetKind.Folder"/>.</param>
/// <param name="HourUtc">0-23: the hour (UTC) of the daily run.</param>
/// <param name="Keep">1-1000: how many backups to keep at the target (default 7).</param>
/// <param name="Encrypt">Requires a passphrase to be set (sent now or stored earlier).</param>
/// <param name="Passphrase">Write-only. Null keeps the stored one; "" clears it. Losing it means the encrypted backups
/// cannot be restored.</param>
public sealed record BackupSettingsDto(bool Enabled, BackupTargetKind Kind, string? FolderPath, S3Target? S3, int HourUtc, int Keep,
    bool Encrypt, string? Passphrase)
{
    public override string ToString() =>
        $"BackupSettingsDto {{ Enabled = {Enabled}, Kind = {Kind}, FolderPath = {FolderPath}, S3 = {S3}, HourUtc = {HourUtc}, " +
        $"Keep = {Keep}, Encrypt = {Encrypt}, PassphraseSet = {Passphrase is not null} }}";
}

/// <summary>The settings as returned: secrets are never sent back, only whether a passphrase is set.</summary>
/// <param name="Running">A backup run is in progress right now (only set by <c>GET /v1/admin/backup</c>).</param>
public sealed record BackupSettingsResponse(bool Enabled, BackupTargetKind Kind, string? FolderPath, S3Target? S3, int HourUtc, int Keep,
    bool Encrypt, bool PassphraseSet, bool Running = false);

/// <summary>One backup run, newest first in the history.</summary>
/// <param name="Utc">When the run started (the time in the backup's name).</param>
/// <param name="Bytes">Size of the written backup file (0 when it failed).</param>
public sealed record BackupRunInfo(DateTimeOffset Utc, bool Ok, long Bytes, string? Error);
