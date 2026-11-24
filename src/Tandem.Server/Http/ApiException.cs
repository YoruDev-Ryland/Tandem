using Tandem.Protocol;

namespace Tandem.Server.Http;

public sealed class ApiException(int status, string code, string message) : Exception(message)
{
    public int Status { get; } = status;
    public string Code { get; } = code;

    /// <summary>Sent as the Retry-After header (seconds) when set.</summary>
    public int? RetryAfterSeconds { get; init; }

    /// <summary>503 server_moving with Retry-After 15: the stored files are being moved; the client waits and retries.</summary>
    public static ApiException ServerMoving(string message = "The server is moving its files. Try again shortly.") =>
        new(503, "server_moving", message) { RetryAfterSeconds = 15 };

    public ErrorResponse ToResponse() => new(Code, Message);
}

public static class ApiErrors
{
    /// <summary>Converts ApiException into a JSON ErrorResponse; other exceptions stay unhandled.</summary>
    public static IApplicationBuilder UseApiErrors(this IApplicationBuilder app) =>
        app.Use(async (context, next) =>
        {
            try
            {
                await next();
            }
            catch (ApiException ex) when (!context.Response.HasStarted)
            {
                context.Response.Clear();
                context.Response.StatusCode = ex.Status;
                if (ex.RetryAfterSeconds is { } retry) context.Response.Headers.RetryAfter = retry.ToString(System.Globalization.CultureInfo.InvariantCulture);
                await context.Response.WriteAsJsonAsync(ex.ToResponse());
            }
        });
}
