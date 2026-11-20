using System.Globalization;
using EmuSync.Protocol;

namespace EmuSync.Server.Http;

public sealed class ProtocolVersionMiddleware(RequestDelegate next)
{
    private static readonly string Expected = Protocol.Protocol.Version.ToString(CultureInfo.InvariantCulture);

    public async Task InvokeAsync(HttpContext context)
    {
        if (RequiresHeader(context.Request.Path) &&
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

    private static bool RequiresHeader(PathString path) =>
        path.StartsWithSegments("/v1") && !path.StartsWithSegments("/v1/server-info");
}
