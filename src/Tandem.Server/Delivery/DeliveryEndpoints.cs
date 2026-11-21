using Tandem.Protocol;
using Tandem.Server.Auth;
using Tandem.Server.Http;

namespace Tandem.Server.Deliveries;

public static class DeliveryEndpoints
{
    public static IEndpointRouteBuilder MapDeliveryEndpoints(this IEndpointRouteBuilder app)
    {
        var deliveries = app.MapGroup("/v1/deliveries").RequireAuthorization();

        deliveries.MapGet("", (HttpContext http, DeliveryService svc, CancellationToken ct) =>
            svc.ListAsync(http.User.UserId(), RequireDevice(http), ct));

        deliveries.MapPost("/{id:guid}/ack", async (HttpContext http, Guid id, AckRequest request, DeliveryService svc, CancellationToken ct) =>
        {
            await svc.AckAsync(http.User.UserId(), RequireDevice(http), id, request, ct);
            return Results.NoContent();
        });
        return app;
    }

    private static Guid RequireDevice(HttpContext http) =>
        http.User.DeviceId() ?? throw new ApiException(400, "no_device", "This endpoint requires a device identity.");
}
