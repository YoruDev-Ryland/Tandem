using EmuSync.Server;
using Microsoft.Extensions.FileProviders;
using Microsoft.Extensions.Options;

namespace EmuSync.Server.Web;

/// <summary>Serves the built web app (WEB_ROOT, default <c>wwwroot</c> beside the app) with a single-page-app fallback.</summary>
public static class WebHosting
{
    public const string NotBuiltHtml =
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>EmuSync</title></head>" +
        "<body><p>EmuSync server is running. The web app is not built yet.</p></body></html>";

    private static string RootOf(IServiceProvider services)
    {
        var configured = services.GetRequiredService<IOptions<ServerOptions>>().Value.WebRoot;
        return Path.GetFullPath(string.IsNullOrWhiteSpace(configured) ? Path.Combine(AppContext.BaseDirectory, "wwwroot") : configured);
    }

    /// <summary>Static files; call before routing so they bypass nothing else but API routes (which never match files).</summary>
    public static IApplicationBuilder UseWebFiles(this WebApplication app)
    {
        var root = RootOf(app.Services);
        if (Directory.Exists(root))
            app.UseStaticFiles(new StaticFileOptions { FileProvider = new PhysicalFileProvider(root), RequestPath = "" });
        return app;
    }

    /// <summary>Last-resort route: index.html for non-API GETs (so client-side routes load), or the "not built" page.</summary>
    public static IEndpointRouteBuilder MapWebFallback(this WebApplication app)
    {
        var root = RootOf(app.Services);
        var index = Path.Combine(root, "index.html");
        app.MapFallback(context =>
        {
            var path = context.Request.Path;
            var isGet = HttpMethods.IsGet(context.Request.Method) || HttpMethods.IsHead(context.Request.Method);
            if (!isGet || path.StartsWithSegments("/v1") || path.StartsWithSegments("/healthz"))
            {
                context.Response.StatusCode = StatusCodes.Status404NotFound;
                return Task.CompletedTask;
            }
            context.Response.Headers.CacheControl = "no-cache";
            if (File.Exists(index))
            {
                context.Response.ContentType = "text/html; charset=utf-8";
                return context.Response.SendFileAsync(index);
            }
            if (path == "/" || path.Value is "")
            {
                context.Response.ContentType = "text/html; charset=utf-8";
                return context.Response.WriteAsync(NotBuiltHtml);
            }
            context.Response.StatusCode = StatusCodes.Status404NotFound;
            return Task.CompletedTask;
        });
        return app;
    }
}
