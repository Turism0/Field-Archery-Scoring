using System.Security.Claims;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using Dapper;
using Microsoft.AspNetCore.Identity;
using Microsoft.Data.SqlClient;
using FieldArcheryApi.Data;

namespace FieldArcheryApi.Auth;

/// <summary>
/// Accounts: username + password, no email.
///   POST /api/auth/check     { username }                        → { username, exists }
///   POST /api/auth/register  { username, password, deviceName }  → 201 { archerId, username, token, expiresUtc, recoveryCode }
///   POST /api/auth/login     { username, password, deviceName }  → 200 { archerId, username, token, expiresUtc }
///   POST /api/auth/reset     { username, code, newPassword, deviceName }
///                            → 200 { archerId, username, token, expiresUtc, recoveryCode } (API 2.4). [code] is the
///                            archer's recovery code or a reset code from an Owner / Admin. Every other phone is signed
///                            out, and a new recovery code is made (show it: the old one no longer works). Wrong codes
///                            count towards the same lock-out as wrong passwords.
///   POST /api/auth/recovery-code  (Bearer token) { password }    → { recoveryCode }: a new one (the old one stops working)
///   POST /api/auth/logout    (Bearer token)                      → 204, revokes this phone's token
///   GET  /api/me             (Bearer token)                      → { archerId, username, createdUtc, role, duplicateLocations, roundOffers }
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
    public sealed record AuthResponse(Guid ArcherId, string Username, string Token, DateTime ExpiresUtc, string? RecoveryCode = null);
    public sealed record ResetRequest(string? Username, string? Code, string? NewPassword, string? DeviceName);
    public sealed record PasswordOnly(string? Password);

    /// <summary>Recovery and reset codes: no 0/O, 1/I/L (easy to copy down).</summary>
    public const string CodeAlphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /// <summary>A random code of [length] characters in groups of four, e.g. 7KQ2-M9XD-4TPA-HW3C.</summary>
    public static string NewCode(int length) => string.Join("-",
        Enumerable.Range(0, length).Select(_ => CodeAlphabet[RandomNumberGenerator.GetInt32(CodeAlphabet.Length)])
            .Chunk(4).Select(c => new string(c)));

    /// <summary>A code as typed: upper case, code characters only (dashes and spaces don't matter).</summary>
    public static string CleanCode(string? code) => new((code ?? "").ToUpperInvariant().Where(CodeAlphabet.Contains).ToArray());

    public static string HashResetCode(string code) => Hasher.HashPassword(Marker, CleanCode(code));

    private static bool CodeMatches(string? hash, string code) =>
        hash is not null && code.Length > 0 && Hasher.VerifyHashedPassword(Marker, hash, code) != PasswordVerificationResult.Failed;
    public sealed record MeResponse(Guid ArcherId, string Username, DateTime CreatedUtc, string Role, bool HasRecoveryCode);

    public static void MapAuthEndpoints(this WebApplication app)
    {
        var auth = app.MapGroup("/api/auth").RequireRateLimiting(RateLimits.Auth);
        auth.MapPost("/check", Check);
        auth.MapPost("/register", Register);
        auth.MapPost("/login", Login);
        auth.MapPost("/reset", Reset);
        app.MapPost("/api/auth/recovery-code", NewRecoveryCode).RequireAuthorization().RequireRateLimiting(RateLimits.Auth);

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
        // A recovery code from the start (API 2.4): the phone shows it once, to write down.
        var recovery = NewCode(16);
        try
        {
            archerId = await conn.QuerySingleAsync<Guid>(new CommandDefinition("""
                INSERT dbo.Archer (Username, PasswordHash, LastLoginUtc, RecoveryCodeHash, RecoveryCodeSetUtc)
                OUTPUT inserted.ArcherId
                VALUES (@username, @hash, SYSUTCDATETIME(), @recoveryHash, SYSUTCDATETIME());
                """, new { username, hash = Hasher.HashPassword(Marker, req.Password!), recoveryHash = HashResetCode(recovery) },
                cancellationToken: ct));
        }
        catch (SqlException ex) when (Db.IsDuplicateKey(ex))
        {
            return Error(StatusCodes.Status409Conflict, "username_taken", "That username already exists.");
        }

        var token = await tokens.IssueAsync(conn, archerId, req.DeviceName, ct);
        return Results.Created("/api/me", new AuthResponse(archerId, username, token.Token, token.ExpiresUtc, recovery));
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

    /// <summary>Forgotten password (API 2.4): the recovery code, or a reset code from an Owner / Admin.</summary>
    private static async Task<IResult> Reset(ResetRequest req, Db db, TokenService tokens, CancellationToken ct)
    {
        var username = req.Username?.Trim() ?? "";
        var code = CleanCode(req.Code);
        if (username.Length == 0 || code.Length == 0)
            return Error(StatusCodes.Status400BadRequest, "missing_credentials", "Enter your username and your recovery or reset code.");
        if (PasswordProblem(req.NewPassword) is { } badPassword) return badPassword;

        await using var conn = await db.OpenAsync(ct);
        var archer = await conn.QuerySingleOrDefaultAsync<ResetRow>(new CommandDefinition("""
            SELECT a.ArcherId, a.Username, a.FailedLoginCount, a.LockoutUntilUtc, a.IsDisabled, a.RecoveryCodeHash,
                   CASE WHEN p.ExpiresUtc > SYSUTCDATETIME() THEN p.CodeHash END AS ResetCodeHash
            FROM dbo.Archer a LEFT JOIN dbo.PasswordResetCode p ON p.ArcherId = a.ArcherId
            WHERE a.Username = @username;
            """, new { username }, cancellationToken: ct));
        const string wrong = "Wrong username or code.";
        if (archer is null) return Error(StatusCodes.Status401Unauthorized, "invalid_code", wrong);
        if (archer.IsDisabled) return Error(StatusCodes.Status403Forbidden, "account_disabled", "This account has been disabled.");
        if (archer.LockoutUntilUtc is { } until && until > DateTime.UtcNow)
        {
            var minutes = (int)Math.Ceiling((until - DateTime.UtcNow).TotalMinutes);
            return Error(StatusCodes.Status423Locked, "locked_out", $"Too many wrong tries. Try again in {minutes} minute{(minutes == 1 ? "" : "s")}.");
        }

        if (!CodeMatches(archer.RecoveryCodeHash, code) && !CodeMatches(archer.ResetCodeHash, code))
        {
            var failures = archer.FailedLoginCount + 1;
            var lockNow = failures >= MaxFailedLogins;
            await conn.ExecuteAsync(new CommandDefinition(
                "UPDATE dbo.Archer SET FailedLoginCount = @count, LockoutUntilUtc = @lockUntil WHERE ArcherId = @archerId;",
                new { archerId = archer.ArcherId, count = lockNow ? 0 : failures, lockUntil = lockNow ? DateTime.UtcNow.Add(LockoutTime) : (DateTime?)null },
                cancellationToken: ct));
            return lockNow
                ? Error(StatusCodes.Status423Locked, "locked_out", $"Too many wrong tries. Try again in {(int)LockoutTime.TotalMinutes} minutes.")
                : Error(StatusCodes.Status401Unauthorized, "invalid_code", wrong);
        }

        // New password, a new recovery code (the old one is used up), the reset code gone, every phone signed out.
        var recovery = NewCode(16);
        await using (var tx = await conn.BeginTransactionAsync(ct))
        {
            await conn.ExecuteAsync(new CommandDefinition("""
                UPDATE dbo.Archer
                SET PasswordHash = @hash, FailedLoginCount = 0, LockoutUntilUtc = NULL, LastLoginUtc = SYSUTCDATETIME(),
                    RecoveryCodeHash = @recoveryHash, RecoveryCodeSetUtc = SYSUTCDATETIME()
                WHERE ArcherId = @archerId;
                DELETE dbo.PasswordResetCode WHERE ArcherId = @archerId;
                UPDATE dbo.AuthToken SET RevokedUtc = SYSUTCDATETIME() WHERE ArcherId = @archerId AND RevokedUtc IS NULL;
                """, new { archerId = archer.ArcherId, hash = Hasher.HashPassword(Marker, req.NewPassword!), recoveryHash = HashResetCode(recovery) },
                tx, cancellationToken: ct));
            await tx.CommitAsync(ct);
        }
        var token = await tokens.IssueAsync(conn, archer.ArcherId, req.DeviceName, ct);
        return Results.Ok(new AuthResponse(archer.ArcherId, archer.Username, token.Token, token.ExpiresUtc, recovery));
    }

    /// <summary>A new recovery code (API 2.4); the password is asked for again so a borrowed phone can't do it.</summary>
    private static async Task<IResult> NewRecoveryCode(PasswordOnly req, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        var hash = await conn.QuerySingleAsync<string>(new CommandDefinition(
            "SELECT PasswordHash FROM dbo.Archer WHERE ArcherId = @archerId;", new { archerId = user.ArcherId() }, cancellationToken: ct));
        if (string.IsNullOrEmpty(req.Password) || Hasher.VerifyHashedPassword(Marker, hash, req.Password) == PasswordVerificationResult.Failed)
            return Error(StatusCodes.Status401Unauthorized, "wrong_password", "That isn't your password.");
        var recovery = NewCode(16);
        await conn.ExecuteAsync(new CommandDefinition(
            "UPDATE dbo.Archer SET RecoveryCodeHash = @h, RecoveryCodeSetUtc = SYSUTCDATETIME() WHERE ArcherId = @archerId;",
            new { archerId = user.ArcherId(), h = HashResetCode(recovery) }, cancellationToken: ct));
        return Results.Ok(new { recoveryCode = recovery });
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
            "SELECT ArcherId, Username, CreatedUtc, Role, CAST(CASE WHEN RecoveryCodeHash IS NULL THEN 0 ELSE 1 END AS bit) AS HasRecoveryCode FROM dbo.Archer WHERE ArcherId = @archerId;",
            new { archerId = user.ArcherId() }, cancellationToken: ct));
        // Admins: how many possible duplicate locations are waiting (for the badge on the Admin tile).
        var duplicateLocations = me.Role is Roles.Admin or Roles.Owner
            ? await FieldArcheryApi.Locations.LocationEndpoints.DuplicateCountAsync(conn, config, ct)
            : 0;
        // Rounds friends have scored for you, waiting to be accepted (badge on the Friends tile).
        var roundOffers = await FieldArcheryApi.Offers.OfferEndpoints.CountForAsync(conn, me.ArcherId, ct);
        return Results.Ok(new { me.ArcherId, me.Username, me.CreatedUtc, me.Role, me.HasRecoveryCode, duplicateLocations, roundOffers });
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

    private sealed record ResetRow(Guid ArcherId, string Username, int FailedLoginCount, DateTime? LockoutUntilUtc, bool IsDisabled,
        string? RecoveryCodeHash, string? ResetCodeHash);

    private sealed record ArcherRow(
        Guid ArcherId, string Username, string PasswordHash, int FailedLoginCount, DateTime? LockoutUntilUtc, bool IsDisabled);

    /// <summary>Type parameter for PasswordHasher; carries no data.</summary>
    public sealed class AccountMarker;
}

public static class RateLimits
{
    public const string Auth = "auth";
}
