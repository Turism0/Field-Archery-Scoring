using System.Security.Claims;
using Dapper;
using FieldArcheryApi.Auth;
using FieldArcheryApi.Data;

namespace FieldArcheryApi.Admin;

/// <summary>
/// Admin menu (roadmap step 1). All calls need "Authorization: Bearer &lt;token&gt;" and are checked here
/// against the role stored in the database, so a changed app can't get round them.
///   GET /api/admin/archers                        Admins and Owner → { archers: [{ archerId, username, role, createdUtc, lastLoginUtc }] }
///   PUT /api/admin/archers/{archerId}/role  { role: "ADMIN" | "USER" }
///                                                 Owner only → 200 { archerId, username, role }
///   POST /api/admin/archers/{archerId}/reset-code  Admins (for users) and Owner (for anyone else) → { username, code, expiresUtc }
///                                                 (API 2.4): a one-off code for an archer who has lost their password and
///                                                 recovery code; check who they are in person first. Works once, for 24 hours.
/// Errors: 403 not_admin / not_owner; 400 invalid_role / not_yourself; 404 no_such_archer; 409 cannot_change_owner.
/// </summary>
public static class AdminEndpoints
{
    public sealed record ArcherEntry(Guid ArcherId, string Username, string Role, DateTime CreatedUtc, DateTime? LastLoginUtc);
    public sealed record RoleRequest(string? Role);
    public sealed record RoleResponse(Guid ArcherId, string Username, string Role);

    public static void MapAdminEndpoints(this WebApplication app)
    {
        var admin = app.MapGroup("/api/admin").RequireAuthorization();
        admin.MapGet("/archers", ListArchers);
        admin.MapPut("/archers/{archerId:guid}/role", SetRole);
        admin.MapPost("/archers/{archerId:guid}/reset-code", ResetCode);
    }

    private static async Task<IResult> ListArchers(ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        if (!user.IsAdmin()) return NotAdmin();

        await using var conn = await db.OpenAsync(ct);
        var archers = await conn.QueryAsync<ArcherEntry>(new CommandDefinition("""
            SELECT ArcherId, Username, Role, CreatedUtc, LastLoginUtc
            FROM dbo.Archer
            ORDER BY CASE Role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, Username;
            """, cancellationToken: ct));
        return Results.Ok(new
        {
            archers = archers.Select(a => a with
            {
                CreatedUtc = DateTime.SpecifyKind(a.CreatedUtc, DateTimeKind.Utc),
                LastLoginUtc = a.LastLoginUtc is { } l ? DateTime.SpecifyKind(l, DateTimeKind.Utc) : null,
            }),
        });
    }

    private static async Task<IResult> SetRole(Guid archerId, RoleRequest req, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        if (!user.IsOwner())
        {
            return user.IsAdmin()
                ? Error(403, "not_owner", "Only the owner can make or remove admins.")
                : NotAdmin();
        }

        var role = req.Role?.Trim().ToUpperInvariant();
        if (role is not (Roles.Admin or Roles.User))
        {
            return Error(400, "invalid_role", "Role must be ADMIN or USER.");
        }

        await using var conn = await db.OpenAsync(ct);
        var target = await conn.QuerySingleOrDefaultAsync<RoleResponse>(new CommandDefinition(
            "SELECT ArcherId, Username, Role FROM dbo.Archer WHERE ArcherId = @archerId;",
            new { archerId }, cancellationToken: ct));
        if (target is null) return Error(404, "no_such_archer", "There's no account with that id.");
        if (target.Role == Roles.Owner) return Error(409, "cannot_change_owner", "The owner's role can't be changed.");

        if (target.Role != role)
        {
            // "AND Role <> 'OWNER'" again in case it changed in between; the unique index stops a second Owner anyway.
            await conn.ExecuteAsync(new CommandDefinition("""
                UPDATE dbo.Archer
                SET Role = @role, RoleChangedUtc = SYSUTCDATETIME(), RoleChangedById = @by
                WHERE ArcherId = @archerId AND Role <> 'OWNER';
                """, new { archerId, role, by = user.ArcherId() }, cancellationToken: ct));
        }
        return Results.Ok(target with { Role = role });
    }

    private static async Task<IResult> ResetCode(Guid archerId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        if (!user.IsAdmin()) return NotAdmin();
        if (archerId == user.ArcherId()) return Error(400, "not_yourself", "Use your own recovery code (or ask another admin).");

        await using var conn = await db.OpenAsync(ct);
        var target = await conn.QuerySingleOrDefaultAsync<RoleResponse>(new CommandDefinition(
            "SELECT ArcherId, Username, Role FROM dbo.Archer WHERE ArcherId = @archerId;", new { archerId }, cancellationToken: ct));
        if (target is null) return Error(404, "no_such_archer", "There's no account with that id.");
        // Admins reset users; only the Owner resets admins; nobody resets the Owner's this way.
        if (target.Role == Roles.Owner) return Error(403, "not_owner", "The owner's password can only be reset with their recovery code.");
        if (target.Role == Roles.Admin && !user.IsOwner()) return Error(403, "not_owner", "Only the owner can reset an admin's password.");

        var code = AuthEndpoints.NewCode(8);
        var expires = DateTime.UtcNow.AddHours(24);
        await conn.ExecuteAsync(new CommandDefinition("""
            MERGE dbo.PasswordResetCode AS t USING (SELECT @archerId AS ArcherId) AS s ON t.ArcherId = s.ArcherId
            WHEN MATCHED THEN UPDATE SET CodeHash = @hash, ExpiresUtc = @expires, CreatedUtc = SYSUTCDATETIME(), CreatedById = @by
            WHEN NOT MATCHED THEN INSERT (ArcherId, CodeHash, ExpiresUtc, CreatedById) VALUES (@archerId, @hash, @expires, @by);
            """, new { archerId, hash = AuthEndpoints.HashResetCode(code), expires, by = user.ArcherId() }, cancellationToken: ct));
        return Results.Ok(new { target.Username, code, expiresUtc = expires });
    }

    private static IResult NotAdmin() => Error(403, "not_admin", "This needs an admin account.");

    private static IResult Error(int status, string code, string message) =>
        Results.Json(new { error = code, message }, statusCode: status);
}
