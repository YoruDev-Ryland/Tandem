using Tandem.Protocol;
using Tandem.Server.Auth;

namespace Tandem.Server.Pairing;

public static class PairingEndpoints
{
    public static IEndpointRouteBuilder MapPairingEndpoints(this IEndpointRouteBuilder app)
    {
        app.MapPost("/v1/pairing/codes", (HttpContext http, PairingService pairing, CancellationToken ct) =>
            pairing.Create(http.User.UserId(), $"{http.Request.Scheme}://{http.Request.Host}", ct)).RequireAuthorization();

        app.MapPost("/v1/pairing/redeem", (RedeemPairingRequest request, PairingService pairing, CancellationToken ct) =>
            pairing.Redeem(request.Code, request.DeviceName, request.Platform, ct)).RequireRateLimiting(AuthSetup.AuthRateLimit);
        return app;
    }
}
