using System.Security.Claims;
using System.Text.RegularExpressions;
using Dapper;
using Microsoft.AspNetCore.Identity;
using Microsoft.Data.SqlClient;
using NfasApi.Data;

namespace NfasApi.Auth;

/// <summary>
/// Accounts: username + password, no email.
///   POST /api/auth/check     { username }                        → { username, exists }
///   POST /api/auth/register  { username, password, deviceName }  → 201 { archerId, username, token, expiresUtc }
///   POST /api/auth/login     { username, password, deviceName }  → 200 { archerId, username, token, expiresUtc }
///   POST /api/auth/logout    (Bearer token)                      → 204, revokes this phone's token
///   GET  /api/me             (Bearer token)                      → { archerId, username, createdUtc, role, duplicateLocations }
/// Errors are JSON: { error: "code", message: "text for the user" }.
/// </summary>
public static partial class AuthEndpoints
{
    public const int MinPasswordLength = 8;
    public const int MaxPasswordLength = 128;
    private const int MaxFailedLogins = 5;
    private static readonly TimeSpan LockoutTime = TimeSpan.FromMinutes(15);

    // Salted PBKDF2 (ASP.NET Core Identity's hasher). The user object isn't used by the hasher.
    private static readonly PasswordHasher<AccountMarker> Hasher = new();
    private static readonly AccountMarker Marker = new();

    [GeneratedRegex("^[A-Za-z0-9_.-]{3,30}$")]
    private static partial Regex UsernamePattern();

    public sealed record CheckRequest(string? Username);
    public sealed record CheckResponse(string Username, bool Exists);
    public sealed record CredentialsRequest(string? Username, string? Password, string? DeviceName);
    public sealed record AuthResponse(Guid ArcherId, string Username, string Token, DateTime ExpiresUtc);
    public sealed record MeResponse(Guid ArcherId, string Username, DateTime CreatedUtc, string Role);

    public static void MapAuthEndpoints(this WebApplication app)
    {
        var auth = app.MapGroup("/api/auth").RequireRateLimiting(RateLimits.Auth);
        auth.MapPost("/check", Check);
        auth.MapPost("/register", Register);
        auth.MapPost("/login", Login);

        app.MapPost("/api/auth/logout", Logout).RequireAuthorization();
        app.MapGet("/api/me", Me).RequireAuthorization();
    }

    private static async Task<IResult> Check(CheckRequest req, Db db, CancellationToken ct)
    {
        var username = req.Username?.Trim() ?? "";
        if (UsernameProblem(username) is { } problem) return problem;

        await using var conn = await db.OpenAsync(ct);
        var existing = await conn.QuerySingleOrDefaultAsync<string>(new CommandDefinition(
            "SELECT Username FROM dbo.Archer WHERE Username = @username;", new { username }, cancellationToken: ct));
        return Results.Ok(new CheckResponse(existing ?? username, existing is not null));
    }

    private static async Task<IResult> Register(CredentialsRequest req, Db db, TokenService tokens, CancellationToken ct)
    {
        var username = req.Username?.Trim() ?? "";
        if (UsernameProblem(username) is { } badName) return badName;
        if (PasswordProblem(req.Password) is { } badPassword) return badPassword;

        await using var conn = await db.OpenAsync(ct);
        Guid archerId;
        try
        {
            archerId = await conn.QuerySingleAsync<Guid>(new CommandDefinition("""
                INSERT dbo.Archer (Username, PasswordHash, LastLoginUtc)
                OUTPUT inserted.ArcherId
                VALUES (@username, @hash, SYSUTCDATETIME());
                """, new { username, hash = Hasher.HashPassword(Marker, req.Password!) }, cancellationToken: ct));
        }
        catch (SqlException ex) when (Db.IsDuplicateKey(ex))
        {
            return Error(StatusCodes.Status409Conflict, "username_taken", "That username already exists.");
        }

        var token = await tokens.IssueAsync(conn, archerId, req.DeviceName, ct);
        return Results.Created("/api/me", new AuthResponse(archerId, username, token.Token, token.ExpiresUtc));
    }

    private static async Task<IResult> Login(CredentialsRequest req, Db db, TokenService tokens, CancellationToken ct)
    {
        var username = req.Username?.Trim() ?? "";
        if (username.Length == 0 || string.IsNullOrEmpty(req.Password))
        {
            return Error(StatusCodes.Status400BadRequest, "missing_credentials", "Enter your username and password.");
        }

        await using var conn = await db.OpenAsync(ct);
        var archer = await conn.QuerySingleOrDefaultAsync<ArcherRow>(new CommandDefinition("""
            SELECT ArcherId, Username, PasswordHash, FailedLoginCount, LockoutUntilUtc, IsDisabled
            FROM dbo.Archer WHERE Username = @username;
            """, new { username }, cancellationToken: ct));

        if (archer is null)
        {
            return Error(StatusCodes.Status401Unauthorized, "invalid_credentials", "Wrong username or password.");
        }
        if (archer.IsDisabled)
        {
            return Error(StatusCodes.Status403Forbidden, "account_disabled", "This account has been disabled.");
        }
        if (archer.LockoutUntilUtc is { } until && until > DateTime.UtcNow)
        {
            var minutes = (int)Math.Ceiling((until - DateTime.UtcNow).TotalMinutes);
            return Error(StatusCodes.Status423Locked, "locked_out",
                $"Too many wrong passwords. Try again in {minutes} minute{(minutes == 1 ? "" : "s")}.");
        }

        var result = Hasher.VerifyHashedPassword(Marker, archer.PasswordHash, req.Password!);
        if (result == PasswordVerificationResult.Failed)
        {
            var failures = archer.FailedLoginCount + 1;
            var lockNow = failures >= MaxFailedLogins;
            await conn.ExecuteAsync(new CommandDefinition("""
                UPDATE dbo.Archer
                SET FailedLoginCount = @count, LockoutUntilUtc = @lockUntil
                WHERE ArcherId = @archerId;
                """, new
                {
                    archerId = archer.ArcherId,
                    count = lockNow ? 0 : failures,
                    lockUntil = lockNow ? DateTime.UtcNow.Add(LockoutTime) : (DateTime?)null,
                }, cancellationToken: ct));

            return lockNow
                ? Error(StatusCodes.Status423Locked, "locked_out",
                    $"Too many wrong passwords. Try again in {(int)LockoutTime.TotalMinutes} minutes.")
                : Error(StatusCodes.Status401Unauthorized, "invalid_credentials", "Wrong username or password.");
        }

        // Correct password: clear failures, record the login, upgrade the hash if the hasher asks.
        await conn.ExecuteAsync(new CommandDefinition("""
            UPDATE dbo.Archer
            SET FailedLoginCount = 0, LockoutUntilUtc = NULL, LastLoginUtc = SYSUTCDATETIME(),
                PasswordHash = COALESCE(@newHash, PasswordHash)
            WHERE ArcherId = @archerId;
            """, new
            {
                archerId = archer.ArcherId,
                newHash = result == PasswordVerificationResult.SuccessRehashNeeded
                    ? Hasher.HashPassword(Marker, req.Password!)
                    : null,
            }, cancellationToken: ct));

        var token = await tokens.IssueAsync(conn, archer.ArcherId, req.DeviceName, ct);
        return Results.Ok(new AuthResponse(archer.ArcherId, archer.Username, token.Token, token.ExpiresUtc));
    }

    private static async Task<IResult> Logout(ClaimsPrincipal user, TokenService tokens, CancellationToken ct)
    {
        await tokens.RevokeAsync(user.TokenId(), ct);
        return Results.NoContent();
    }

    private static async Task<IResult> Me(ClaimsPrincipal user, Db db, IConfiguration config, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        var me = await conn.QuerySingleAsync<MeResponse>(new CommandDefinition(
            "SELECT ArcherId, Username, CreatedUtc, Role FROM dbo.Archer WHERE ArcherId = @archerId;",
            new { archerId = user.ArcherId() }, cancellationToken: ct));
        // Admins: how many possible duplicate locations are waiting (for the badge on the Admin tile).
        var duplicateLocations = me.Role is Roles.Admin or Roles.Owner
            ? await NfasApi.Locations.LocationEndpoints.DuplicateCountAsync(conn, config, ct)
            : 0;
        return Results.Ok(new { me.ArcherId, me.Username, me.CreatedUtc, me.Role, duplicateLocations });
    }

    private static IResult? UsernameProblem(string username) =>
        UsernamePattern().IsMatch(username)
            ? null
            : Error(StatusCodes.Status400BadRequest, "invalid_username",
                "Usernames are 3 to 30 characters: letters, numbers, dot, dash or underscore.");

    private static IResult? PasswordProblem(string? password) =>
        password is { Length: >= MinPasswordLength and <= MaxPasswordLength }
            ? null
            : Error(StatusCodes.Status400BadRequest, "invalid_password",
                $"Passwords must be {MinPasswordLength} to {MaxPasswordLength} characters.");

    private static IResult Error(int status, string code, string message) =>
        Results.Json(new { error = code, message }, statusCode: status);

    private sealed record ArcherRow(
        Guid ArcherId, string Username, string PasswordHash, int FailedLoginCount, DateTime? LockoutUntilUtc, bool IsDisabled);

    /// <summary>Type parameter for PasswordHasher; carries no data.</summary>
    public sealed class AccountMarker;
}

public static class RateLimits
{
    public const string Auth = "auth";
}
