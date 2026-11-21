using Tandem.Protocol;
using Tandem.Server.Auth;
using Tandem.Server.Data;
using Tandem.Server.Http;
using Microsoft.EntityFrameworkCore;

namespace Tandem.Server.Accounts;

public static class AdminEndpoints
{
    public static IEndpointRouteBuilder MapAdminEndpoints(this IEndpointRouteBuilder app)
    {
        var admin = app.MapGroup("/v1/admin").RequireAuthorization(AuthSetup.AdminPolicy);

        admin.MapGet("/users", async (TandemDbContext db, CancellationToken ct) =>
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

        admin.MapDelete("/users/{id:guid}", async (Guid id, HttpContext http, UserDeletion deletion, CancellationToken ct) =>
        {
            if (id == http.User.UserId())
                throw new ApiException(400, "cannot_delete_self", "You cannot delete your own account.");
            await deletion.DeleteAsync(id, ct);
            return Results.NoContent();
        });

        admin.MapPost("/users/{id:guid}/reset-password", async (Guid id, AccountService accounts, CancellationToken ct) =>
        {
            var password = AccountService.GeneratePassword();
            await accounts.ResetPassword(id, password, ct);
            return new ResetPasswordResponse(password);
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
