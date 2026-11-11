// Browser security headers (3.2.06, Pete: API security review). Added to every response, so the web app, the privacy and
// account deletion pages and the API all get them:
//  - Content-Security-Policy (web pages): scripts only from this site (no inline scripts, nothing from elsewhere), so
//    injected script can't run or send a sign-in token anywhere; map tiles from OpenStreetMap; no framing.
//  - X-Frame-Options / frame-ancestors: no other site can show these pages in a frame (e.g. to trick a click on
//    "Delete my account").
//  - X-Content-Type-Options: files are only treated as the type the server says.
//  - Referrer-Policy: other sites (map tiles) see only this site's address, never a page's path.
//  - Permissions-Policy: camera (QR codes) and location for this site only; no microphone.
//  - Strict-Transport-Security: browsers only ever use https for these addresses once they've seen it.

namespace FieldArcheryApi;

public static class SecurityHeaders
{
    public const string ContentSecurityPolicy =
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
        "img-src 'self' data: blob: https://tile.openstreetmap.org; connect-src 'self'; font-src 'self'; " +
        "worker-src 'self'; manifest-src 'self'; media-src 'self' blob:; object-src 'none'; base-uri 'none'; " +
        "form-action 'self'; frame-ancestors 'none'";

    public static void UseSecurityHeaders(this WebApplication app)
    {
        app.Use(async (ctx, next) =>
        {
            ctx.Response.OnStarting(() =>
            {
                var h = ctx.Response.Headers;
                h.XContentTypeOptions = "nosniff";
                h.XFrameOptions = "DENY";
                h["Referrer-Policy"] = "strict-origin-when-cross-origin";
                h["Permissions-Policy"] = "camera=(self), geolocation=(self), microphone=()";
                h.ContentSecurityPolicy = ContentSecurityPolicy;
                if (ctx.Request.IsHttps) h.StrictTransportSecurity = "max-age=31536000";
                return Task.CompletedTask;
            });
            await next();
        });
    }
}
