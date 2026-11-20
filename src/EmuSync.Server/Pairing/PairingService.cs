using System.Security.Cryptography;
using EmuSync.Protocol;
using EmuSync.Server.Accounts;
using EmuSync.Server.Auth;
using EmuSync.Server.Data;
using EmuSync.Server.Http;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace EmuSync.Server.Pairing;

public sealed class PairingService(EmuSyncDbContext db, AccountService accounts, IOptions<ServerOptions> options, TimeProvider time,
    EmuSync.Server.Events.IActivityLog activity)
{
    public const string Alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public const int CodeLength = 8;
    public static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(5);

    /// <summary>Upper-cases and strips dashes and whitespace.</summary>
    public static string Normalize(string? code) =>
        new((code ?? "").Where(c => c != '-' && !char.IsWhiteSpace(c)).Select(char.ToUpperInvariant).ToArray());

    public async Task<PairingCodeResponse> Create(Guid userId, string fallbackServerUrl, CancellationToken ct)
    {
        var code = RandomNumberGenerator.GetString(Alphabet, CodeLength);
        var now = time.GetUtcNow();
        var expires = now + Lifetime;
        db.PairingCodes.Add(new PairingCode { CodeHash = Secrets.Hash(code), UserId = userId, ExpiresUtc = expires });
        // Housekeeping: drop codes that are long dead.
        var cutoff = now - TimeSpan.FromHours(1);
        var stale = (await db.PairingCodes.AsNoTracking().ToListAsync(ct)).Where(c => c.ExpiresUtc < cutoff).Select(c => c.CodeHash).ToList();
        if (stale.Count > 0) await db.PairingCodes.Where(c => stale.Contains(c.CodeHash)).ExecuteDeleteAsync(ct);
        await db.SaveChangesAsync(ct);

        var server = string.IsNullOrWhiteSpace(options.Value.PublicUrl) ? fallbackServerUrl : options.Value.PublicUrl.TrimEnd('/');
        return new PairingCodeResponse(code, $"emusync://pair?server={server}&code={code}", expires);
    }

    /// <summary>Consumes the code atomically and creates a device for its user.</summary>
    public async Task<DeviceKeyResponse> Redeem(string? rawCode, string? deviceName, string? platform, CancellationToken ct)
    {
        var userId = await ConsumeCode(rawCode, ct);
        var created = await accounts.CreateDevice(userId, deviceName, platform, ct);
        var name = await db.Devices.AsNoTracking().Where(d => d.Id == created.DeviceId).Select(d => d.Name).FirstOrDefaultAsync(ct) ?? "Device";
        await activity.WriteAsync(userId, "device_paired", $"{name} paired", created.DeviceId);
        return created;
    }

    /// <summary>Consumes the code for a browser sign-in. No device is created; returns the code's user.</summary>
    public Task<Guid> RedeemForSession(string? rawCode, CancellationToken ct) => ConsumeCode(rawCode, ct);

    private async Task<Guid> ConsumeCode(string? rawCode, CancellationToken ct)
    {
        var hash = Secrets.Hash(Normalize(rawCode));
        var row = await db.PairingCodes.AsNoTracking().FirstOrDefaultAsync(c => c.CodeHash == hash, ct)
            ?? throw new ApiException(404, "code_not_found", "That pairing code does not exist.");
        if (row.UsedUtc is not null)
            throw new ApiException(410, "code_used", "That pairing code has already been used.");
        var now = time.GetUtcNow();
        if (row.ExpiresUtc < now)
            throw new ApiException(410, "code_expired", "That pairing code has expired.");

        var claimed = await db.PairingCodes.Where(c => c.CodeHash == hash && c.UsedUtc == null)
            .ExecuteUpdateAsync(s => s.SetProperty(c => c.UsedUtc, now), ct);
        if (claimed == 0)
            throw new ApiException(410, "code_used", "That pairing code has already been used.");
        return row.UserId;
    }
}
