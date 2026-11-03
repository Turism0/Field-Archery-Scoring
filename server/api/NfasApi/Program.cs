// Field Archery Scoring API.
// Runs on Odin under IIS, behind Nginx Proxy Manager at https://fieldarchery.fnurble.co.uk.
// The connection string (with the nfas_api password) lives in appsettings.Production.json
// on the server only; it is never committed to Git.

using System.Data;
using System.Net;
using System.Threading.RateLimiting;
using Dapper;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.HttpOverrides;
using NfasApi.Admin;
using NfasApi.Auth;
using NfasApi.Data;
using NfasApi.Friends;
using NfasApi.Groups;
using NfasApi.Locations;
using NfasApi.Offers;
using NfasApi.Rounds;

var builder = WebApplication.CreateBuilder(args);

var connectionString = builder.Configuration.GetConnectionString("Nfas");
if (string.IsNullOrWhiteSpace(connectionString))
{
    throw new InvalidOperationException(
        "Connection string 'Nfas' is missing. Create appsettings.Production.json next to NfasApi.dll " +
        "(copy appsettings.Production.json.example) and put the nfas_api password in it.");
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

app.Run();

static class ApiInfo
{
    public const string Version = "2.0.05";
}
