using System.Globalization;
using Tandem.Protocol;

namespace Tandem.Server.Http;

public sealed class ProtocolVersionMiddleware(RequestDelegate next)
{
    private static readonly string Expected = Protocol.Protocol.Version.ToString(CultureInfo.InvariantCulture);

    public async Task InvokeAsync(HttpContext context)
    {
        if (RequiresHeader(context.Request) &&
            context.Request.Headers[Protocol.Protocol.Header].ToString() != Expected)
        {
            context.Response.StatusCode = StatusCodes.Status426UpgradeRequired;
            await context.Response.WriteAsJsonAsync(new ErrorResponse(
                "protocol_mismatch",
                $"This server speaks {Protocol.Protocol.Header}: {Expected}."));
            return;
        }
        await next(context);
    }

    private static bool RequiresHeader(HttpRequest request) =>
        request.Path.StartsWithSegments("/v1") &&
        !request.Path.StartsWithSegments("/v1/server-info") &&
        !IsBrowserSafeRoute(request);

    // Browsers cannot add custom headers to EventSource or plain download links, so cookie-session GETs (no Authorization
    // header) to these routes are exempt. Anything carrying a Bearer header is still checked.
    private static bool IsBrowserSafeRoute(HttpRequest request)
    {
        if (!HttpMethods.IsGet(request.Method) || request.Headers.ContainsKey("Authorization")) return false;
        var path = request.Path.Value ?? "";
        return path.Equals("/v1/events", StringComparison.Ordinal) ||
               path.Equals("/v1/export.zip", StringComparison.Ordinal) ||
               (path.StartsWith("/v1/saves/versions/", StringComparison.Ordinal) &&
                path.EndsWith("/download", StringComparison.Ordinal));
    }
}
