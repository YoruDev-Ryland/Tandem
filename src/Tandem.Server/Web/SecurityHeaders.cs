using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace Tandem.Server.Web;

/// <summary>
/// Defence-in-depth headers on every response (F-17): a CSP that allows only this origin, <c>nosniff</c>, no referrer
/// and no framing. The built index.html carries one small inline script (the theme is applied before first paint), so
/// the CSP allows exactly the inline scripts found in it by their SHA-256 hashes, read once at start. Styles allow
/// 'unsafe-inline' because Svelte's transitions and style directives write inline styles.
/// </summary>
public static partial class SecurityHeaders
{
    public static string Policy(IEnumerable<string> inlineScriptHashes)
    {
        var scripts = string.Join("", inlineScriptHashes.Select(h => $" 'sha256-{h}'"));
        return "default-src 'self'; " +
               $"script-src 'self'{scripts}; " +
               "style-src 'self' 'unsafe-inline'; " +
               "img-src 'self' data:; " +
               "font-src 'self' data:; " +
               "connect-src 'self'; " +
               "object-src 'none'; " +
               "base-uri 'self'; " +
               "form-action 'self'; " +
               "frame-ancestors 'none'";
    }

    [GeneratedRegex(@"<script(?![^>]*\bsrc\s*=)[^>]*>(.*?)</script>", RegexOptions.Singleline | RegexOptions.IgnoreCase)]
    private static partial Regex InlineScript();

    /// <summary>Base64 SHA-256 of each inline (no src) script's exact text, as CSP hashes it.</summary>
    public static List<string> InlineScriptHashes(string html) =>
        InlineScript().Matches(html).Select(m => Convert.ToBase64String(SHA256.HashData(Encoding.UTF8.GetBytes(m.Groups[1].Value))))
            .Distinct(StringComparer.Ordinal).ToList();

    public static IApplicationBuilder UseSecurityHeaders(this IApplicationBuilder app, string? indexHtmlPath)
    {
        var hashes = indexHtmlPath is not null && File.Exists(indexHtmlPath) ? InlineScriptHashes(File.ReadAllText(indexHtmlPath)) : [];
        var policy = Policy(hashes);
        return app.Use((context, next) =>
        {
            var headers = context.Response.Headers;
            headers.ContentSecurityPolicy = policy;
            headers.XContentTypeOptions = "nosniff";
            headers["Referrer-Policy"] = "no-referrer";
            headers.XFrameOptions = "DENY";
            return next(context);
        });
    }
}
