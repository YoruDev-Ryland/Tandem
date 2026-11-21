namespace Tandem.Protocol;

public sealed record ErrorResponse(string Code, string Message);

public sealed record ServerInfo(string Name, int Protocol, bool RegistrationOpen);

public sealed record RegisterRequest(string Email, string Password, string DeviceName, string Platform);
public sealed record LoginRequest(string Email, string Password, string DeviceName, string Platform);
public sealed record DeviceKeyResponse(Guid UserId, Guid DeviceId, string DeviceKey);
public sealed record WebSessionRequest(string? Email, string? Password, string? PairingCode, string? DeviceName = null);
public sealed record PairingCodeResponse(string Code, string QrPayload, DateTimeOffset ExpiresUtc);
public sealed record RedeemPairingRequest(string Code, string DeviceName, string Platform);
public sealed record AccountInfo(Guid Id, string Email, bool IsAdmin);
public sealed record ChangePasswordRequest(string Current, string New);
public sealed record UserInfo(Guid Id, string Email, bool IsAdmin, DateTimeOffset CreatedUtc, int DeviceCount);
public sealed record CreateUserRequest(string Email, bool IsAdmin);
public sealed record CreatedUserResponse(string Email, string Password);
public sealed record AdminSettings(bool AllowRegistration);
/// <param name="RunningEmulators">Emulator ids running at the device's last status report; empty once the device has
/// been offline for two minutes.</param>
public sealed record DeviceSummary(Guid Id, string Name, string Platform, DateTimeOffset? LastSeenUtc, bool? ShizukuOk, bool IsCurrent,
    string[] RunningEmulators);
public sealed record MissingBlobsRequest(List<string> Hashes);
public sealed record MissingBlobsResponse(List<string> Missing);

public sealed record EmulatorStatus(string EmulatorId, string Package, string Version, IReadOnlyList<string>? Cores);
/// <param name="RunningEmulators">Emulator ids running in the foreground right now; replaces the stored list on every
/// report. Null (older clients) means none.</param>
public sealed record DeviceStatusRequest(string Platform, string AppVersion, bool ShizukuOk,
    IReadOnlyList<EmulatorStatus> Emulators, IReadOnlyDictionary<string, string> Roots, string[]? RunningEmulators = null);
public sealed record InstalledGame(string GameKey, string? DisplayName, string? Crc32);
public sealed record InstallsRequest(IReadOnlyList<InstalledGame> Games);
public sealed record InstallsResponse(int Added, int Removed);

/// <summary>
/// POST /v1/devices/me/forget: the device forgot what it knew of these emulators' saves and configs (it switched an
/// emulator's variant, whose folder holds other files, or signed in again). The server drops its own record of what
/// this device holds for them, so no upload is taken as based on a version the device no longer has, and the heads
/// are delivered again. Unknown ids are ignored; at most <see cref="MaxEmulators"/>.
/// </summary>
public sealed record ForgetRequest(IReadOnlyList<string> EmulatorIds)
{
    public const int MaxEmulators = 50;
}
public sealed record RenameDeviceRequest(string Name);
