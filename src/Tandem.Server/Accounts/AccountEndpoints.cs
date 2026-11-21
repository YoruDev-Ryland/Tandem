using Tandem.Protocol;
using Tandem.Server.Auth;
using Tandem.Server.Data;
using Tandem.Server.Http;
using Tandem.Server.Pairing;
using Microsoft.EntityFrameworkCore;

namespace Tandem.Server.Accounts;

public static class AccountEndpoints
{
    public static IEndpointRouteBuilder MapAccountEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapPost("/v1/auth/register", (RegisterRequest request, AccountService accounts, CancellationToken ct) =>
            accounts.Register(request, ct)).RequireRateLimiting(AuthSetup.AuthRateLimit);

        app.MapPost("/v1/auth/login", (LoginRequest request, HttpContext http, AccountService accounts, CancellationToken ct) =>
            accounts.Login(request, AuthSetup.PartitionKey(http.Connection.RemoteIpAddress), ct)).RequireRateLimiting(AuthSetup.AuthRateLimit);

        app.MapPost("/v1/web/session", async (WebSessionRequest request, HttpContext http, AccountService accounts, PairingService pairing, TandemDbContext db, TimeProvider time, CancellationToken ct) =>
        {
            if (!string.IsNullOrEmpty(request.PairingCode))
            {
                var pairedUserId = await pairing.RedeemForSession(request.PairingCode, ct);
                var pairedToken = await accounts.CreateWebSession(pairedUserId, null, ct);
                WebSessionCookie.Append(http, pairedToken, time);
                var info = await db.Users.AsNoTracking().Where(u => u.Id == pairedUserId)
                    .Select(u => new AccountInfo(u.Id, u.Email, u.IsAdmin)).FirstAsync(ct);
                return info;
            }
            if (request.Email is null || request.Password is null)
                throw new ApiException(400, "invalid_request", "Email and password are required.");
            var user = await accounts.Authenticate(request.Email, request.Password, AuthSetup.PartitionKey(http.Connection.RemoteIpAddress), ct);
            var token = await accounts.CreateWebSession(user.Id, null, ct);
            WebSessionCookie.Append(http, token, time);
            return new AccountInfo(user.Id, user.Email, user.IsAdmin);
        }).RequireRateLimiting(AuthSetup.AuthRateLimit);

        app.MapDelete("/v1/web/session", async (HttpContext http, TandemDbContext db, CancellationToken ct) =>
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
        account.MapGet("", async (HttpContext http, TandemDbContext db, CancellationToken ct) =>
        {
            var id = http.User.UserId();
            var user = await db.Users.AsNoTracking().Where(u => u.Id == id)
                .Select(u => new AccountInfo(u.Id, u.Email, u.IsAdmin)).FirstOrDefaultAsync(ct);
            return user is null ? throw new ApiException(401, "unauthorized", "Authentication is required.") : user;
        });
        account.MapPost("/password", async (ChangePasswordRequest request, HttpContext http, AccountService accounts, CancellationToken ct) =>
        {
            var current = http.Request.Cookies.TryGetValue(WebSessionCookie.Name, out var token) && !string.IsNullOrEmpty(token)
                ? Secrets.Hash(token) : null;
            await accounts.ChangePassword(http.User.UserId(), request, current, ct);
            return Results.NoContent();
        }).RequireAuthorization(AuthSetup.WebSessionPolicy);

        // Self-service account deletion: the password confirms it, and the only admin cannot leave other users without one.
        account.MapDelete("", async ([Microsoft.AspNetCore.Mvc.FromBody] DeleteAccountRequest? request, HttpContext http,
            UserDeletion deletion, TandemDbContext db, CancellationToken ct) =>
        {
            var userId = http.User.UserId();
            await deletion.DeleteAsync(userId, ct, async user =>
            {
                if (!AccountService.VerifyPassword(user, request?.Password))
                    throw new ApiException(403, "bad_password", "The password is incorrect.");
                if (user.IsAdmin && !await db.Users.AnyAsync(u => u.IsAdmin && u.Id != userId, ct)
                    && await db.Users.AnyAsync(u => u.Id != userId, ct))
                    throw new ApiException(409, "last_admin", "You are the only admin. Make another account an admin first.");
            });
            WebSessionCookie.Delete(http);
            return Results.NoContent();
        }).RequireAuthorization(AuthSetup.WebSessionPolicy);
        return app;
    }
}
