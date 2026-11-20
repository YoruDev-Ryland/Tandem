using EmuSync.Protocol;
using EmuSync.Server.Auth;
using EmuSync.Server.Data;
using EmuSync.Server.Http;
using EmuSync.Server.Saves;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Options;

namespace EmuSync.Server.Accounts;

public static class AdminEndpoints
{
    public static IEndpointRouteBuilder MapAdminEndpoints(this IEndpointRouteBuilder app)
    {
        var admin = app.MapGroup("/v1/admin").RequireAuthorization(AuthSetup.AdminPolicy);

        admin.MapGet("/users", async (EmuSyncDbContext db, CancellationToken ct) =>
            await db.Users.AsNoTracking().OrderBy(u => u.CreatedUtc).ThenBy(u => u.Email)
                .Select(u => new UserInfo(u.Id, u.Email, u.IsAdmin, u.CreatedUtc,
                    db.Devices.Count(d => d.UserId == u.Id && d.RevokedUtc == null)))
                .ToArrayAsync(ct));

        admin.MapPost("/users", async (CreateUserRequest request, AccountService accounts, CancellationToken ct) =>
        {
            var email = AccountService.NormalizeEmail(request.Email);
            var password = AccountService.GeneratePassword();
            await accounts.CreateUser(email, password, request.IsAdmin, ct);
            return new CreatedUserResponse(email, password);
        });

        admin.MapDelete("/users/{id:guid}", async (Guid id, HttpContext http, EmuSyncDbContext db, MaintenanceLock maintenance,
            UserLocks locks, IOptions<ServerOptions> options, ILoggerFactory loggers, CancellationToken ct) =>
        {
            if (id == http.User.UserId())
                throw new ApiException(400, "cannot_delete_self", "You cannot delete your own account.");
            // The usual lock order: maintenance (no backup is reading the files, no prune runs) and then the user's lock
            // (no upload or other write of this user is in progress).
            using (await maintenance.AcquireAsync(ct))
            using (await locks.Acquire(id, ct))
            {
                var user = await db.Users.FirstOrDefaultAsync(u => u.Id == id, ct)
                    ?? throw new ApiException(404, "not_found", "No such user.");
                db.Users.Remove(user);
                await db.SaveChangesAsync(ct);

                // After the rows are gone, best effort: a leftover directory holds only unreachable files.
                var dir = Path.Combine(options.Value.DataDirectory, "blobs", id.ToString("N"));
                try
                {
                    if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true);
                }
                catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
                {
                    loggers.CreateLogger("EmuSync.Server.Accounts.AdminEndpoints")
                        .LogWarning(ex, "Could not delete the blob directory of deleted user {UserId}", id);
                }
            }
            return Results.NoContent();
        });

        admin.MapGet("/settings", async (AccountService accounts, CancellationToken ct) =>
            new AdminSettings(await accounts.GetAllowRegistration(ct)));

        admin.MapPut("/settings", async (AdminSettings settings, AccountService accounts, CancellationToken ct) =>
        {
            await accounts.SetAllowRegistration(settings.AllowRegistration, ct);
            return settings;
        });
        return app;
    }
}
