using System.Security.Claims;
using System.Text.Encodings.Web;
using Tandem.Protocol;
using Tandem.Server.Data;
using Microsoft.AspNetCore.Authentication;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace Tandem.Server.Auth;

/// <summary>
/// Authenticates "Authorization: Bearer &lt;device key&gt;"; the web session cookie is used only when no
/// Authorization header is present.
/// </summary>
public sealed class DeviceKeyAuthenticationHandler(
    IOptionsMonitor<AuthenticationSchemeOptions> options,
    ILoggerFactory logger,
    UrlEncoder encoder,
    TandemDbContext db,
    TimeProvider time) : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
{
    public const string SchemeName = "DeviceKey";
    private const string FailureCodeKey = "tandem:auth-failure";
    /// <summary>LastSeenUtc and a session's LastUsedUtc are written at most this often (one write per request was one
    /// SQLite write per blob GET).</summary>
    public static readonly TimeSpan TouchInterval = TimeSpan.FromMinutes(1);

    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var header = Request.Headers.Authorization.ToString();
        if (!string.IsNullOrEmpty(header))
        {
            const string prefix = "Bearer ";
            if (!header.StartsWith(prefix, StringComparison.OrdinalIgnoreCase) || header.Length == prefix.Length)
                return AuthenticateResult.Fail("Malformed Authorization header.");
            return await AuthenticateDeviceKey(header[prefix.Length..].Trim());
        }

        if (Request.Cookies.TryGetValue(WebSessionCookie.Name, out var token) && !string.IsNullOrEmpty(token))
            return await AuthenticateSession(token);

        return AuthenticateResult.NoResult();
    }

    private async Task<AuthenticateResult> AuthenticateDeviceKey(string key)
    {
        var hash = Secrets.Hash(key);
        var device = await db.Devices.AsNoTracking()
            .Where(d => d.KeyHash == hash)
            .Select(d => new { d.Id, d.UserId, d.RevokedUtc, d.LastSeenUtc })
            .FirstOrDefaultAsync();
        if (device is null) return AuthenticateResult.Fail("Unknown device key.");
        if (device.RevokedUtc is not null) return Revoked();

        var user = await db.Users.AsNoTracking().Where(u => u.Id == device.UserId)
            .Select(u => new { u.IsAdmin }).FirstOrDefaultAsync();
        if (user is null) return AuthenticateResult.Fail("Unknown user.");

        var now = time.GetUtcNow();
        if (device.LastSeenUtc is not { } seen || now - seen >= TouchInterval || seen > now)
            await db.Devices.Where(d => d.Id == device.Id).ExecuteUpdateAsync(s => s.SetProperty(d => d.LastSeenUtc, now));
        return Success(device.UserId, device.Id, user.IsAdmin, ClaimsExtensions.AuthKindDevice);
    }

    private async Task<AuthenticateResult> AuthenticateSession(string token)
    {
        var hash = Secrets.Hash(token);
        var now = time.GetUtcNow();
        var session = await db.WebSessions.AsNoTracking()
            .Where(s => s.TokenHash == hash)
            .Select(s => new { s.UserId, s.DeviceId, s.LastUsedUtc })
            .FirstOrDefaultAsync();
        if (session is null || session.LastUsedUtc + WebSessionCookie.Lifetime < now)
            return AuthenticateResult.Fail("Unknown or expired session.");

        Guid? deviceId = null;
        if (session.DeviceId is { } id)
        {
            var device = await db.Devices.AsNoTracking().Where(d => d.Id == id)
                .Select(d => new { d.Id, d.RevokedUtc }).FirstOrDefaultAsync();
            if (device is null) return AuthenticateResult.Fail("Session device was removed.");
            if (device.RevokedUtc is not null) return Revoked();
            deviceId = device.Id;
        }

        var user = await db.Users.AsNoTracking().Where(u => u.Id == session.UserId)
            .Select(u => new { u.IsAdmin }).FirstOrDefaultAsync();
        if (user is null) return AuthenticateResult.Fail("Unknown user.");

        if (now - session.LastUsedUtc >= TouchInterval || session.LastUsedUtc > now)
            await db.WebSessions.Where(s => s.TokenHash == hash).ExecuteUpdateAsync(s => s.SetProperty(x => x.LastUsedUtc, now));
        return Success(session.UserId, deviceId, user.IsAdmin, ClaimsExtensions.AuthKindWeb);
    }

    private AuthenticateResult Revoked()
    {
        Context.Items[FailureCodeKey] = "device_revoked";
        return AuthenticateResult.Fail("Device revoked.");
    }

    private AuthenticateResult Success(Guid userId, Guid? deviceId, bool isAdmin, string authKind)
    {
        var claims = new List<Claim> { new(ClaimTypes.NameIdentifier, userId.ToString()), new(ClaimsExtensions.AuthKindClaim, authKind) };
        if (deviceId is { } d) claims.Add(new Claim(ClaimsExtensions.DeviceClaim, d.ToString()));
        if (isAdmin) claims.Add(new Claim(ClaimsExtensions.AdminClaim, "true"));
        var principal = new ClaimsPrincipal(new ClaimsIdentity(claims, SchemeName));
        return AuthenticateResult.Success(new AuthenticationTicket(principal, SchemeName));
    }

    protected override async Task HandleChallengeAsync(AuthenticationProperties properties)
    {
        var code = Context.Items[FailureCodeKey] as string ?? "unauthorized";
        Response.StatusCode = StatusCodes.Status401Unauthorized;
        await Response.WriteAsJsonAsync(new ErrorResponse(code,
            code == "device_revoked" ? "This device has been revoked." : "Authentication is required."));
    }

    protected override async Task HandleForbiddenAsync(AuthenticationProperties properties)
    {
        Response.StatusCode = StatusCodes.Status403Forbidden;
        await Response.WriteAsJsonAsync(new ErrorResponse("forbidden", "You are not allowed to do this."));
    }
}

public static class AuthSetup
{
    /// <summary>The rate-limit bucket of a client address: IPv4 as is, IPv6 by its /64 (one host usually holds a whole
    /// /64, so per-address buckets were free to rotate through).</summary>
    public static string PartitionKey(System.Net.IPAddress? address)
    {
        if (address is null) return "unknown";
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        if (address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetworkV6) return address.ToString();
        var bytes = address.GetAddressBytes();
        Array.Clear(bytes, 8, 8);
        return new System.Net.IPAddress(bytes) + "/64";
    }

    /// <summary>An admin signed in through a web session (a device key never reaches admin routes).</summary>
    public const string AdminPolicy = "Admin";
    /// <summary>A web session (browser sign-in), not a device key.</summary>
    public const string WebSessionPolicy = "WebSession";
    public const string AuthRateLimit = "auth";

    public static void AddTandemAuth(this IServiceCollection services, ServerOptions options)
    {
        services.AddAuthentication(DeviceKeyAuthenticationHandler.SchemeName)
            .AddScheme<AuthenticationSchemeOptions, DeviceKeyAuthenticationHandler>(DeviceKeyAuthenticationHandler.SchemeName, null);
        services.AddAuthorization(o =>
        {
            o.AddPolicy(AdminPolicy, p => p.RequireAuthenticatedUser()
                .RequireClaim(ClaimsExtensions.AuthKindClaim, ClaimsExtensions.AuthKindWeb)
                .RequireClaim(ClaimsExtensions.AdminClaim, "true"));
            o.AddPolicy(WebSessionPolicy, p => p.RequireAuthenticatedUser()
                .RequireClaim(ClaimsExtensions.AuthKindClaim, ClaimsExtensions.AuthKindWeb));
        });

        var limit = Math.Max(1, options.AuthRequestsPerMinute);
        services.AddRateLimiter(o =>
        {
            o.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
            o.OnRejected = async (ctx, ct) =>
            {
                ctx.HttpContext.Response.StatusCode = StatusCodes.Status429TooManyRequests;
                await ctx.HttpContext.Response.WriteAsJsonAsync(
                    new ErrorResponse("rate_limited", "Too many attempts. Try again in a minute."), ct);
            };
            o.AddPolicy(AuthRateLimit, http => System.Threading.RateLimiting.RateLimitPartition.GetFixedWindowLimiter(
                PartitionKey(http.Connection.RemoteIpAddress),
                _ => new System.Threading.RateLimiting.FixedWindowRateLimiterOptions
                {
                    PermitLimit = limit,
                    Window = TimeSpan.FromMinutes(1),
                    QueueLimit = 0,
                }));
        });
    }
}
