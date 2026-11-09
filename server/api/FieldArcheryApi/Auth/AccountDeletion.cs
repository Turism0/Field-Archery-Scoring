using System.Data;
using System.Net;
using System.Security.Claims;
using Dapper;
using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Data.SqlClient;
using FieldArcheryApi.Data;

namespace FieldArcheryApi.Auth;

/// <summary>
/// Deleting an account (API 2.4.02, needed for Google Play) and the privacy policy page.
///   POST /api/me/delete   (Bearer token) { password }  → 204: the account and everything about it is deleted.
///   GET  /delete-account                         → a web page to delete an account with username and password
///   POST /delete-account  (form: username, password)
///   GET  /privacy                                → the privacy policy
/// Deleted: the account, its sign-ins, recovery and reset codes, rounds and scores, friendships, round requests to or
/// from it, groups it hosted, its place in other people's groups (with its entries) and private locations it named.
/// Kept: public shoot locations it named (club details, no longer linked to it). The Owner account can't be deleted.
/// </summary>
public static class AccountDeletion
{
    public const string ContactEmail = "fieldarcheryscoring@gmail.com";

    private static readonly PasswordHasher<AuthEndpoints.AccountMarker> Hasher = new();
    private static readonly AuthEndpoints.AccountMarker Marker = new();

    public sealed record DeleteRequest(string? Password);

    private sealed record Row(Guid ArcherId, string Username, string PasswordHash, string Role, int FailedLoginCount, DateTime? LockoutUntilUtc);

    public static void MapAccountDeletion(this WebApplication app)
    {
        app.MapPost("/api/me/delete", DeleteMe).RequireAuthorization().RequireRateLimiting(RateLimits.Auth);
        app.MapGet("/delete-account", () => Page(Form(null)));
        app.MapPost("/delete-account", DeleteFromWeb).DisableAntiforgery().RequireRateLimiting(RateLimits.Auth);
        app.MapGet("/privacy", () => Page(PrivacyHtml));
    }

    private static async Task<IResult> DeleteMe([FromBody] DeleteRequest req, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        var row = await conn.QuerySingleAsync<Row>(new CommandDefinition(Select + " WHERE ArcherId = @id;", new { id = user.ArcherId() }, cancellationToken: ct));
        var problem = await CheckAsync(conn, row, req.Password, ct);
        if (problem is not null) return Results.Json(new { error = problem.Value.Code, message = problem.Value.Message }, statusCode: problem.Value.Status);
        await DeleteAsync(conn, row.ArcherId, ct);
        return Results.NoContent();
    }

    private static async Task<IResult> DeleteFromWeb([FromForm] string? username, [FromForm] string? password, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        var row = await conn.QuerySingleOrDefaultAsync<Row>(new CommandDefinition(Select + " WHERE Username = @u;", new { u = username?.Trim() ?? "" }, cancellationToken: ct));
        if (row is null) return Page(Form("Wrong username or password."));
        var problem = await CheckAsync(conn, row, password, ct);
        if (problem is not null) return Page(Form(problem.Value.Message));
        await DeleteAsync(conn, row.ArcherId, ct);
        return Page($"<h1>Account deleted</h1><p>The account <b>{Enc(row.Username)}</b> and everything stored about it on the server " +
                    "have been deleted. Rounds kept on your phone stay there until you delete the app.</p>");
    }

    private const string Select = "SELECT ArcherId, Username, PasswordHash, Role, FailedLoginCount, LockoutUntilUtc FROM dbo.Archer";

    /// <summary>The password, with the same lock-out as signing in; the Owner can't delete their account.</summary>
    private static async Task<(int Status, string Code, string Message)?> CheckAsync(SqlConnection conn, Row row, string? password, CancellationToken ct)
    {
        if (row.LockoutUntilUtc is { } until && until > DateTime.UtcNow)
            return (423, "locked_out", "Too many wrong passwords. Try again in 15 minutes.");
        if (string.IsNullOrEmpty(password) || Hasher.VerifyHashedPassword(Marker, row.PasswordHash, password) == PasswordVerificationResult.Failed)
        {
            var failures = row.FailedLoginCount + 1;
            var lockNow = failures >= 5;
            await conn.ExecuteAsync(new CommandDefinition(
                "UPDATE dbo.Archer SET FailedLoginCount = @c, LockoutUntilUtc = @l WHERE ArcherId = @id;",
                new { id = row.ArcherId, c = lockNow ? 0 : failures, l = lockNow ? DateTime.UtcNow.AddMinutes(15) : (DateTime?)null }, cancellationToken: ct));
            return (401, "wrong_password", "Wrong username or password.");
        }
        if (row.Role == Roles.Owner) return (409, "is_owner", "The owner's account can't be deleted.");
        return null;
    }

    /// <summary>Deletes the account and everything about it, in one transaction.</summary>
    public static async Task DeleteAsync(SqlConnection conn, Guid archerId, CancellationToken ct)
    {
        await using var tx = await conn.BeginTransactionAsync(IsolationLevel.Serializable, ct);
        await conn.ExecuteAsync(new CommandDefinition("""
            -- Friendships and round requests (no cascade on these).
            DELETE dbo.Friendship WHERE ArcherLowId = @id OR ArcherHighId = @id;
            DELETE dbo.RoundOffer WHERE ForArcherId = @id OR FromArcherId = @id;

            -- Their place in other people's groups, with their entries and scoring.
            DECLARE @members TABLE (GroupId UNIQUEIDENTIFIER, MemberId UNIQUEIDENTIFIER);
            INSERT @members SELECT GroupId, MemberId FROM dbo.ScoreGroupMember WHERE ArcherId = @id;
            DELETE e FROM dbo.ScoreEntry e JOIN @members m ON m.GroupId = e.GroupId AND (e.CardMemberId = m.MemberId OR e.ScorerMemberId = m.MemberId);
            DELETE s FROM dbo.ScoreGroupScorer s JOIN @members m ON m.GroupId = s.GroupId AND (s.CardMemberId = m.MemberId OR s.ScorerMemberId = m.MemberId);
            DELETE g FROM dbo.ScoreGroupMember g JOIN @members m ON m.GroupId = g.GroupId AND m.MemberId = g.MemberId;

            -- Private locations they named go; public ones stay (club details) but are no longer linked to them.
            DECLARE @loc TABLE (LocationId UNIQUEIDENTIFIER PRIMARY KEY);
            INSERT @loc SELECT LocationId FROM dbo.ShootLocation WHERE CreatedById = @id AND IsPrivate = 1;
            UPDATE dbo.Round SET LocationId = NULL WHERE LocationId IN (SELECT LocationId FROM @loc);
            UPDATE dbo.ScoreGroup SET LocationId = NULL WHERE LocationId IN (SELECT LocationId FROM @loc);
            DELETE dbo.LocationPairApproval WHERE LocationLowId IN (SELECT LocationId FROM @loc) OR LocationHighId IN (SELECT LocationId FROM @loc);
            UPDATE dbo.ShootLocation SET MergedIntoId = NULL WHERE MergedIntoId IN (SELECT LocationId FROM @loc);
            DELETE dbo.ShootLocation WHERE LocationId IN (SELECT LocationId FROM @loc);
            UPDATE dbo.ShootLocation SET CreatedById = NULL WHERE CreatedById = @id;
            UPDATE dbo.ShootLocation SET RenamedById = NULL WHERE RenamedById = @id;
            UPDATE dbo.ShootLocation SET MergedById = NULL WHERE MergedById = @id;

            -- The account: sign-ins, rounds and scores, groups they hosted, reset codes go with it (cascade).
            DELETE dbo.Archer WHERE ArcherId = @id;
            """, new { id = archerId }, tx, commandTimeout: 120, cancellationToken: ct));
        await tx.CommitAsync(ct);
    }

    private static string Enc(string s) => WebUtility.HtmlEncode(s);

    private static string Form(string? problem) => $"""
        <h1>Delete your account</h1>
        <p>This deletes your Field Archery Scoring account and everything the server keeps about it: your rounds and scores,
        friends, round requests, your place in live groups and private shoot locations you named. It can't be undone.
        You can also do this in the app: Profile → Delete account.</p>
        {(problem is null ? "" : $"<p class=\"err\">{Enc(problem)}</p>")}
        <form method="post" action="/delete-account">
          <label>Username<br><input name="username" autocomplete="username" required></label><br>
          <label>Password<br><input name="password" type="password" autocomplete="current-password" required></label><br>
          <button type="submit">Delete my account</button>
        </form>
        <p>Forgotten your password? Reset it in the app first (Sign in → Forgot your password?), or email
        <a href="mailto:{ContactEmail}">{ContactEmail}</a> with your username and we'll help.</p>
        """;

    private static IResult Page(string body) => Results.Content($$"""
        <!doctype html><html lang="en"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Field Archery Scoring</title>
        <style>body{font-family:system-ui,sans-serif;max-width:46rem;margin:2rem auto;padding:0 1rem;line-height:1.5}
        input{font-size:1rem;padding:.4rem;margin:.2rem 0 .8rem;width:100%;max-width:20rem}
        button{font-size:1rem;padding:.5rem 1rem} .err{color:#b00020} h2{margin-top:1.6rem}</style>
        </head><body>{{body}}</body></html>
        """, "text/html; charset=utf-8");

    private const string PrivacyHtml = $"""
        <h1>Field Archery Scoring – privacy policy</h1>
        <p>Last updated 9 November 2026. Field Archery Scoring is an app for scoring field archery rounds. It is run by an
        individual, not a company, and is not connected with the National Field Archery Society (NFAS).
        Contact: <a href="mailto:{ContactEmail}">{ContactEmail}</a>.</p>

        <h2>Without an account</h2>
        <p>The app works fully on your phone without an account. Your rounds, and anything you enter in Profile → About you
        (your name and date of birth, used only to work out your age group), stay on your phone and are never sent anywhere.</p>

        <h2>With an account</h2>
        <p>If you create an account, the server keeps:</p>
        <ul>
          <li>your username and a one-way hash of your password and recovery code (never the password or code itself);
              no email address, phone number or real name;</li>
          <li>your rounds: date, class (Gents / Ladies), age group, bow style, scores and which shoot location they were at;</li>
          <li>your friends and friend requests, and rounds friends have scored for you;</li>
          <li>live groups you're in (for 30 days): members' usernames, names of guests the host added, and the scores entered;</li>
          <li>for each phone you sign in on: its make and model (e.g. "Google Pixel 8") and when it was last used;</li>
          <li>shoot locations you name: the name and the club's position.</li>
        </ul>
        <p>Friends you accept can see your finished rounds. Everyone's finished rounds at a public shoot location count towards
        that location's stats, which show bow styles and classes but no names.</p>

        <h2>Location</h2>
        <p>When you start a round the app takes one position from your phone to find the shoot location. The position is only
        used to look up nearby locations; the server stores which location the round was at, not where your phone was.</p>

        <h2>Nearby devices (Bluetooth and Wi-Fi)</h2>
        <p>In a live group with no signal, phones in the group share scores directly over Bluetooth and Wi-Fi (Google Nearby
        Connections). Only the group's details and scores are shared, and only with the phones in that group.</p>

        <h2>Handicap</h2>
        <p>The handicap is worked out from the published results of the NFAS National and 3D Championships. Archers' names are
        not stored: each is replaced by a one-way code used only to recognise the same archer in different years.</p>

        <h2>What isn't collected</h2>
        <p>No adverts, no analytics or tracking, no data sold or shared with anyone else. Data is sent over encrypted
        connections (HTTPS) and kept on a server in the UK.</p>

        <h2>Deleting your data</h2>
        <p>Delete your account in the app (Profile → Delete account) or at
        <a href="/delete-account">fieldarchery.fnurble.co.uk/delete-account</a>. Everything the server keeps about you is
        deleted straight away, except public shoot locations you named, which stay as club details without your name.
        Rounds on your phone stay there until you delete them or the app.</p>

        <h2>Children</h2>
        <p>The app is for archers aged 13 and over. Younger archers' rounds can be scored by a parent or as a guest in a group.</p>

        <h2>Your rights</h2>
        <p>Under UK data protection law you can ask for a copy of your data, or for it to be corrected or deleted, by emailing
        {ContactEmail}. You can also complain to the Information Commissioner's Office (ico.org.uk).</p>
        """;
}
