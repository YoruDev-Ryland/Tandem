using Tandem.Protocol;

namespace Tandem.Server.Http;

public sealed class ApiException(int status, string code, string message) : Exception(message)
{
    public int Status { get; } = status;
    public string Code { get; } = code;

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
                await context.Response.WriteAsJsonAsync(ex.ToResponse());
            }
        });
}
