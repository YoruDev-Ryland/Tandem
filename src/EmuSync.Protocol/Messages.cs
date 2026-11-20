namespace EmuSync.Protocol;

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
public sealed record DeviceSummary(Guid Id, string Name, string Platform, DateTimeOffset? LastSeenUtc, bool? ShizukuOk, bool IsCurrent);
public sealed record MissingBlobsRequest(List<string> Hashes);
public sealed record MissingBlobsResponse(List<string> Missing);
