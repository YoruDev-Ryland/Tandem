using System.Security.Claims;

namespace EmuSync.Server.Auth;

public static class WebSessionCookie
{
    public const string Name = "emusync_session";
    public static readonly TimeSpan Lifetime = TimeSpan.FromDays(30);

    public static void Append(HttpContext context, string token, TimeProvider time) =>
        context.Response.Cookies.Append(Name, token, Options(context, time.GetUtcNow().Add(Lifetime)));

    public static void Delete(HttpContext context) =>
        context.Response.Cookies.Delete(Name, Options(context, null));

    private static CookieOptions Options(HttpContext context, DateTimeOffset? expires) => new()
    {
        HttpOnly = true,
        SameSite = SameSiteMode.Strict,
        Secure = context.Request.IsHttps,
        Path = "/",
        Expires = expires,
    };
}

public static class ClaimsExtensions
{
    public const string DeviceClaim = "emusync:device";
    public const string AdminClaim = "emusync:admin";
    /// <summary>How the request authenticated: <see cref="AuthKindDevice"/> (a device key) or <see cref="AuthKindWeb"/> (a session cookie).</summary>
    public const string AuthKindClaim = "emusync:auth";
    public const string AuthKindDevice = "device";
    public const string AuthKindWeb = "web";

    public static Guid UserId(this ClaimsPrincipal user) =>
        Guid.Parse(user.FindFirstValue(ClaimTypes.NameIdentifier) ?? throw new InvalidOperationException("Not authenticated."));

    public static Guid? DeviceId(this ClaimsPrincipal user) =>
        Guid.TryParse(user.FindFirstValue(DeviceClaim), out var id) ? id : null;

    public static bool IsAdmin(this ClaimsPrincipal user) => user.HasClaim(AdminClaim, "true");
}
