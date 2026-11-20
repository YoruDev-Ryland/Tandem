using System.Security.Cryptography;
using EmuSync.Protocol;
using EmuSync.Server.Auth;
using EmuSync.Server.Data;
using EmuSync.Server.Http;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace EmuSync.Server.Accounts;

public sealed class AccountService(EmuSyncDbContext db, IOptions<ServerOptions> options, TimeProvider time)
{
    public const string AllowRegistrationKey = "allowRegistration";
    public const int MinPasswordLength = 8;
    private const string PasswordAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private const int GeneratedPasswordLength = 16;

    // Serializes registrations in this process so two first registrations cannot both become admin.
    private static readonly SemaphoreSlim RegisterLock = new(1, 1);
    private static readonly PasswordHasher<User> Hasher = new();
    private static readonly User DummyUser = new();
    private static readonly string DummyHash = Hasher.HashPassword(DummyUser, "emusync-dummy-password");

    public static string NormalizeEmail(string? email)
    {
        var e = (email ?? "").Trim().ToLowerInvariant();
        if (e.Length < 3 || e.Length > 254 || !e.Contains('@') || e.Any(char.IsWhiteSpace))
            throw new ApiException(400, "invalid_email", "A valid email address is required.");
        return e;
    }

    public static void CheckPassword(string? password)
    {
        if (password is null || password.Length < MinPasswordLength)
            throw new ApiException(400, "weak_password", $"Passwords need at least {MinPasswordLength} characters.");
    }

    public static string GeneratePassword() => RandomNumberGenerator.GetString(PasswordAlphabet, GeneratedPasswordLength);

    public async Task<bool> GetAllowRegistration(CancellationToken ct = default)
    {
        var row = await db.ServerSettings.AsNoTracking().FirstOrDefaultAsync(s => s.Key == AllowRegistrationKey, ct);
        return row is not null ? row.Value == "true" : options.Value.AllowRegistration;
    }

    public async Task SetAllowRegistration(bool value, CancellationToken ct = default)
    {
        var row = await db.ServerSettings.FirstOrDefaultAsync(s => s.Key == AllowRegistrationKey, ct);
        if (row is null) db.ServerSettings.Add(new ServerSetting { Key = AllowRegistrationKey, Value = value ? "true" : "false" });
        else row.Value = value ? "true" : "false";
        await db.SaveChangesAsync(ct);
    }

    public async Task<DeviceKeyResponse> Register(RegisterRequest request, CancellationToken ct)
    {
        var email = NormalizeEmail(request.Email);
        CheckPassword(request.Password);

        await RegisterLock.WaitAsync(ct);
        try
        {
            var first = !await db.Users.AnyAsync(ct);
            if (!first && !await GetAllowRegistration(ct))
                throw new ApiException(403, "registration_closed", "Registration is closed on this server.");
            var user = await CreateUser(email, request.Password, isAdmin: first, ct);
            return await CreateDevice(user, request.DeviceName, request.Platform, ct);
        }
        finally
        {
            RegisterLock.Release();
        }
    }

    public async Task<User> CreateUser(string email, string password, bool isAdmin, CancellationToken ct)
    {
        if (await db.Users.AnyAsync(u => u.Email == email, ct))
            throw new ApiException(409, "email_taken", "An account with this email already exists.");
        var user = new User { Id = Guid.NewGuid(), Email = email, IsAdmin = isAdmin, CreatedUtc = time.GetUtcNow() };
        user.PasswordHash = Hasher.HashPassword(user, password);
        db.Users.Add(user);
        try
        {
            await db.SaveChangesAsync(ct);
        }
        catch (DbUpdateException)
        {
            db.Entry(user).State = EntityState.Detached;
            if (await db.Users.AsNoTracking().AnyAsync(u => u.Email == email, ct))
                throw new ApiException(409, "email_taken", "An account with this email already exists.");
            throw;
        }
        return user;
    }

    public async Task<DeviceKeyResponse> Login(LoginRequest request, CancellationToken ct)
    {
        var user = await Authenticate(request.Email, request.Password, ct);
        return await CreateDevice(user, request.DeviceName, request.Platform, ct);
    }

    /// <summary>Verifies email and password; every failure is the same 401.</summary>
    public async Task<User> Authenticate(string? email, string? password, CancellationToken ct)
    {
        var normalized = (email ?? "").Trim().ToLowerInvariant();
        var user = await db.Users.FirstOrDefaultAsync(u => u.Email == normalized, ct);
        var result = Hasher.VerifyHashedPassword(user ?? DummyUser, user?.PasswordHash ?? DummyHash, password ?? "");
        if (user is null || result == PasswordVerificationResult.Failed)
            throw new ApiException(401, "invalid_credentials", "Wrong email or password.");
        if (result == PasswordVerificationResult.SuccessRehashNeeded)
        {
            user.PasswordHash = Hasher.HashPassword(user, password!);
            await db.SaveChangesAsync(ct);
        }
        return user;
    }

    private async Task<DeviceKeyResponse> CreateDevice(User user, string? name, string? platform, CancellationToken ct)
    {
        var key = Secrets.NewKey();
        var device = new Device
        {
            Id = Guid.NewGuid(),
            UserId = user.Id,
            Name = Clip(string.IsNullOrWhiteSpace(name) ? "Device" : name.Trim(), 80),
            Platform = Clip(string.IsNullOrWhiteSpace(platform) ? "unknown" : platform.Trim(), 32),
            KeyHash = Secrets.Hash(key),
            CreatedUtc = time.GetUtcNow(),
        };
        db.Devices.Add(device);
        await db.SaveChangesAsync(ct);
        return new DeviceKeyResponse(user.Id, device.Id, key);
    }

    /// <summary>Creates a session for the user and returns the cookie token.</summary>
    public async Task<string> CreateWebSession(Guid userId, Guid? deviceId, CancellationToken ct)
    {
        var token = Secrets.NewKey();
        var now = time.GetUtcNow();
        db.WebSessions.Add(new WebSession
        {
            TokenHash = Secrets.Hash(token), UserId = userId, DeviceId = deviceId, CreatedUtc = now, LastUsedUtc = now,
        });
        await db.SaveChangesAsync(ct);
        return token;
    }

    public async Task ChangePassword(Guid userId, ChangePasswordRequest request, CancellationToken ct)
    {
        CheckPassword(request.New);
        var user = await db.Users.FirstAsync(u => u.Id == userId, ct);
        if (Hasher.VerifyHashedPassword(user, user.PasswordHash, request.Current ?? "") == PasswordVerificationResult.Failed)
            throw new ApiException(400, "wrong_password", "The current password is incorrect.");
        user.PasswordHash = Hasher.HashPassword(user, request.New);
        await db.SaveChangesAsync(ct);
    }

    private static string Clip(string s, int max) => s.Length > max ? s[..max] : s;
}
