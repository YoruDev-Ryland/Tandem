using EmuSync.Protocol;
using EmuSync.Server.Auth;
using EmuSync.Server.Data;
using EmuSync.Server.Http;
using Microsoft.EntityFrameworkCore;

namespace EmuSync.Server.Accounts;

public static class AccountEndpoints
{
    public static IEndpointRouteBuilder MapAccountEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapPost("/v1/auth/register", (RegisterRequest request, AccountService accounts, CancellationToken ct) =>
            accounts.Register(request, ct)).RequireRateLimiting(AuthSetup.AuthRateLimit);

        app.MapPost("/v1/auth/login", (LoginRequest request, AccountService accounts, CancellationToken ct) =>
            accounts.Login(request, ct)).RequireRateLimiting(AuthSetup.AuthRateLimit);

        app.MapPost("/v1/web/session", async (WebSessionRequest request, HttpContext http, AccountService accounts, TimeProvider time, CancellationToken ct) =>
        {
            if (!string.IsNullOrEmpty(request.PairingCode))
                throw new ApiException(400, "not_supported", "Signing in with a pairing code is not available yet.");
            if (request.Email is null || request.Password is null)
                throw new ApiException(400, "invalid_request", "Email and password are required.");
            var user = await accounts.Authenticate(request.Email, request.Password, ct);
            var token = await accounts.CreateWebSession(user.Id, null, ct);
            WebSessionCookie.Append(http, token, time);
            return new AccountInfo(user.Id, user.Email, user.IsAdmin);
        }).RequireRateLimiting(AuthSetup.AuthRateLimit);

        app.MapDelete("/v1/web/session", async (HttpContext http, EmuSyncDbContext db, CancellationToken ct) =>
        {
            if (http.Request.Cookies.TryGetValue(WebSessionCookie.Name, out var token) && !string.IsNullOrEmpty(token))
            {
                var hash = Secrets.Hash(token);
                await db.WebSessions.Where(s => s.TokenHash == hash).ExecuteDeleteAsync(ct);
            }
            WebSessionCookie.Delete(http);
            return Results.NoContent();
        });

        var account = app.MapGroup("/v1/account").RequireAuthorization();
        account.MapGet("", async (HttpContext http, EmuSyncDbContext db, CancellationToken ct) =>
        {
            var id = http.User.UserId();
            var user = await db.Users.AsNoTracking().Where(u => u.Id == id)
                .Select(u => new AccountInfo(u.Id, u.Email, u.IsAdmin)).FirstOrDefaultAsync(ct);
            return user is null ? throw new ApiException(401, "unauthorized", "Authentication is required.") : user;
        });
        account.MapPost("/password", async (ChangePasswordRequest request, HttpContext http, AccountService accounts, CancellationToken ct) =>
        {
            await accounts.ChangePassword(http.User.UserId(), request, ct);
            return Results.NoContent();
        });
        return app;
    }
}
