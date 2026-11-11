// Field Archery Scoring API.
// Runs on Odin under IIS, behind Nginx Proxy Manager at https://fieldarchery.fnurble.co.uk (the API, used by the apps)
// and https://fieldarcheryscoring.fnurble.co.uk (the public website: web app, privacy policy, account deletion; 2.6.03).
// The connection string (with the fieldarchery_api password) lives in appsettings.Production.json
// on the server only; it is never committed to Git.

using System.Data;
using System.Net;
using System.Threading.RateLimiting;
using Dapper;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.StaticFiles;
using FieldArcheryApi.Admin;
using FieldArcheryApi.Auth;
using FieldArcheryApi.Data;
using FieldArcheryApi.Friends;
using FieldArcheryApi.Groups;
using FieldArcheryApi.Handicap;
using FieldArcheryApi.Locations;
using FieldArcheryApi.Offers;
using FieldArcheryApi.Rounds;

var builder = WebApplication.CreateBuilder(args);

// "FieldArchery" (2.0.14 on); settings files from before the rename call it "Nfas", which still works.
var connectionString = builder.Configuration.GetConnectionString("FieldArchery");
if (string.IsNullOrWhiteSpace(connectionString)) connectionString = builder.Configuration.GetConnectionString("Nfas");
if (string.IsNullOrWhiteSpace(connectionString))
{
    throw new InvalidOperationException(
        "Connection string 'FieldArchery' is missing. Create appsettings.Production.json next to FieldArcheryApi.dll " +
        "(copy appsettings.Production.json.example) and put the fieldarchery_api password in it.");
}

// Send C# dates to SQL Server as datetime2 (exact). Dapper's default is the old datetime type,
// which rounds to ~3 ms and breaks "is this copy newer?" comparisons.
SqlMapper.AddTypeMap(typeof(DateTime), DbType.DateTime2);
SqlMapper.AddTypeMap(typeof(DateTime?), DbType.DateTime2);

builder.Services.AddSingleton(new Db(connectionString));
builder.Services.AddSingleton<TokenService>();
builder.Services.AddProblemDetails();

// Behind Nginx Proxy Manager: trust its X-Forwarded-For so rate limits see each phone's IP,
// not NPM's. KnownProxies (appsettings.json) must be the address NPM connects to Odin from.
builder.Services.Configure<ForwardedHeadersOptions>(o =>
{
    o.ForwardedHeaders = ForwardedHeaders.XForwardedFor | ForwardedHeaders.XForwardedProto;
    foreach (var ip in builder.Configuration.GetSection("ForwardedHeaders:KnownProxies").Get<string[]>() ?? [])
    {
        o.KnownProxies.Add(IPAddress.Parse(ip));
    }
});

builder.Services
    .AddAuthentication(TokenAuthHandler.SchemeName)
    .AddScheme<AuthenticationSchemeOptions, TokenAuthHandler>(TokenAuthHandler.SchemeName, _ => { });
builder.Services.AddAuthorization();

// Slows down password guessing: limited sign-in attempts per IP per minute (kept in memory only, not logged).
var authPerMinute = builder.Configuration.GetValue("RateLimits:AuthPerMinute", 30);
builder.Services.AddRateLimiter(o =>
{
    o.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    o.AddPolicy(RateLimits.Auth, ctx => RateLimitPartition.GetFixedWindowLimiter(
        ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown",
        _ => new FixedWindowRateLimiterOptions
        {
            PermitLimit = authPerMinute,
            Window = TimeSpan.FromMinutes(1),
            QueueLimit = 0,
        }));
});

var app = builder.Build();

app.UseForwardedHeaders();
// The web app (2.5, built from web/ into wwwroot/app): /app/ for iPhones and any browser. The page, service worker and
// manifest are always re-checked so updates reach phones; everything else has a content hash in its name.
var types = new FileExtensionContentTypeProvider();
types.Mappings[".webmanifest"] = "application/manifest+json";
// 2.6.03 (Pete): the website's public address is Web:PublicHost; the apps keep using the API's address. Pages opened at
// the old address (Web:RedirectFrom) go to the new one: the web app's pages, /privacy and /delete-account (GET only, so
// the apps and form posts are never redirected). The web app's files aren't redirected either, so a copy installed at
// the old address can still update (and then tells you about the new address). / on the public address opens the app.
var publicHost = app.Configuration["Web:PublicHost"];
var redirectFrom = app.Configuration.GetSection("Web:RedirectFrom").Get<string[]>() ?? [];
if (!string.IsNullOrWhiteSpace(publicHost))
{
    app.Use(async (ctx, next) =>
    {
        var host = ctx.Request.Host.Host;
        var path = ctx.Request.Path.Value ?? "/";
        var isGet = HttpMethods.IsGet(ctx.Request.Method) || HttpMethods.IsHead(ctx.Request.Method);
        if (isGet && string.Equals(host, publicHost, StringComparison.OrdinalIgnoreCase) && path == "/")
        {
            ctx.Response.Redirect("/app/");
            return;
        }
        var page = path is "/privacy" or "/delete-account" or "/app" or "/app/" ||
                   (path.StartsWith("/app/", StringComparison.OrdinalIgnoreCase) && (!Path.HasExtension(path) || path.EndsWith(".html", StringComparison.OrdinalIgnoreCase)));
        if (isGet && page && redirectFrom.Any(h => string.Equals(h, host, StringComparison.OrdinalIgnoreCase)))
        {
            ctx.Response.Redirect($"https://{publicHost}{path}{ctx.Request.QueryString}");
            return;
        }
        await next();
    });
}

// 2.5.01: only the exact /app is redirected (routing ignores a trailing slash, so a mapped /app also caught /app/ and looped).
app.Use(async (ctx, next) =>
{
    if (ctx.Request.Path.Value == "/app") { ctx.Response.Redirect("/app/"); return; }
    await next();
});
app.UseDefaultFiles();
app.UseStaticFiles(new StaticFileOptions
{
    ContentTypeProvider = types,
    OnPrepareResponse = ctx =>
    {
        // 2.7.02: the web app's files keep the same names every build, so the browser must check each time (a 304 if unchanged).
        if (ctx.File.Name is "index.html" or "sw.js" or "registerSW.js" or "manifest.webmanifest" or "index.js" or "index.css")
            ctx.Context.Response.Headers.CacheControl = "no-cache";
    },
});
app.UseRouting();   // after the static files, so /app/ is served as app/index.html before any endpoint is chosen
app.UseExceptionHandler();
app.UseStatusCodePages();
app.UseRateLimiter();
app.UseAuthentication();
app.UseAuthorization();

app.MapGet("/", () => Results.Text($"Field Archery Scoring API {ApiInfo.Version}"));

// Proves the API is running and can reach SQL Server on Mimir.
// Errors are logged (Windows Event Log, Application) but not shown to callers.
app.MapGet("/health", async (Db db, ILogger<Program> log, CancellationToken ct) =>
{
    try
    {
        await using var conn = await db.OpenAsync(ct);
        var schemaVersion = await conn.ExecuteScalarAsync<int?>(
            new CommandDefinition("SELECT MAX(Version) FROM dbo.SchemaVersion;", cancellationToken: ct));

        return Results.Ok(new
        {
            status = "ok",
            database = "ok",
            schemaVersion,
            apiVersion = ApiInfo.Version,
            serverTimeUtc = DateTime.UtcNow,
        });
    }
    catch (Exception ex)
    {
        log.LogError(ex, "Health check could not reach the database");
        return Results.Json(new
        {
            status = "error",
            database = "unreachable",
            apiVersion = ApiInfo.Version,
            serverTimeUtc = DateTime.UtcNow,
        }, statusCode: StatusCodes.Status503ServiceUnavailable);
    }
});

app.MapAuthEndpoints();
app.MapRoundEndpoints();
app.MapFriendEndpoints();
app.MapAdminEndpoints();
app.MapLocationEndpoints();
app.MapLocationPageEndpoints();
app.MapGroupEndpoints();
app.MapOfferEndpoints();
app.MapHandicapEndpoints();
app.MapAccountDeletion();   // API 2.4.02: POST /api/me/delete, /delete-account and /privacy pages
app.MapFallbackToFile("/app/{*path:nonfile}", "app/index.html");   // the web app's own pages (2.5)

app.Run();

static class ApiInfo
{
    public const string Version = "3.1.06";
}
