using EmuSync.Protocol;
using EmuSync.Server.Auth;
using EmuSync.Server.Data;
using EmuSync.Server.Http;
using Microsoft.EntityFrameworkCore;

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

        admin.MapDelete("/users/{id:guid}", async (Guid id, HttpContext http, EmuSyncDbContext db, CancellationToken ct) =>
        {
            if (id == http.User.UserId())
                throw new ApiException(400, "cannot_delete_self", "You cannot delete your own account.");
            var user = await db.Users.FirstOrDefaultAsync(u => u.Id == id, ct)
                ?? throw new ApiException(404, "not_found", "No such user.");
            db.Users.Remove(user);
            await db.SaveChangesAsync(ct);
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
