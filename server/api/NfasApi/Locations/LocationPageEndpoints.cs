using System.Security.Claims;
using Dapper;
using NfasApi.Auth;
using NfasApi.Data;

namespace NfasApi.Locations;

/// <summary>
/// Shoot location map and location page (API 1.7.03). All calls need "Authorization: Bearer &lt;token&gt;".
///   GET /api/locations/map         → { locations: [{ locationId, name, latitude, longitude, isPrivate, myRounds, rounds }] }
///                                    public live locations, plus your own private ones (Admins: all);
///                                    myRounds = your finished rounds there, rounds = everyone's (public only)
///   GET /api/locations/{id}/page   → { location, myRounds: [...], friends: [{ archerId, username, rounds, bestTotal,
///                                    averagePerAnimal, roundsList: [...] }], bowStyles: [{ bowStyle, archers, rounds,
///                                    bestTotal, averageTotal, averagePerAnimal }] }
/// Only finished, not-deleted rounds count. A private location's page is only for the archer who named it and
/// Admins, and has no friends or all-archer sections. Friends' rounds open with GET /api/friends/{id}/rounds/{roundId}.
/// </summary>
public static class LocationPageEndpoints
{
    private sealed record MapRow(Guid LocationId, string Name, decimal Latitude, decimal Longitude, bool IsPrivate, int MyRounds, int Rounds);

    private sealed record Loc(Guid LocationId, string Name, decimal Latitude, decimal Longitude, bool IsPrivate, Guid? CreatedById);

    public sealed record RoundRow(Guid RoundId, Guid ArcherId, DateTime ShootDate, short TargetCount, string ArcherClass,
        string AgeGroup, string BowStyle, int TotalPoints);

    private sealed record FriendRow(Guid ArcherId, string Username, int Rounds, int? BestTotal, double? AveragePerAnimal);

    private sealed record BowRow(string BowStyle, int Archers, int Rounds, int? BestTotal, double? AverageTotal, double? AveragePerAnimal);

    public static void MapLocationPageEndpoints(this WebApplication app)
    {
        var locations = app.MapGroup("/api/locations").RequireAuthorization();
        locations.MapGet("/map", Map);
        locations.MapGet("/{locationId:guid}/page", Page);
    }

    private static async Task<IResult> Map(ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        var rows = await conn.QueryAsync<MapRow>(new CommandDefinition("""
            WITH done AS (
                SELECT LocationId, ArcherId FROM dbo.Round
                WHERE LocationId IS NOT NULL AND Status = 'COMPLETED' AND IsDeleted = 0)
            SELECT l.LocationId, l.Name, l.Latitude, l.Longitude, l.IsPrivate,
                   (SELECT COUNT(*) FROM done d WHERE d.LocationId = l.LocationId AND d.ArcherId = @me) AS MyRounds,
                   (SELECT COUNT(*) FROM done d WHERE d.LocationId = l.LocationId) AS Rounds
            FROM dbo.ShootLocation l
            WHERE l.MergedIntoId IS NULL
              AND (l.IsPrivate = 0 OR l.CreatedById = @me OR @isAdmin = 1)
            ORDER BY l.Name;
            """, new { me = user.ArcherId(), isAdmin = user.IsAdmin() }, cancellationToken: ct));
        return Results.Ok(new
        {
            locations = rows.Select(r => new
            {
                r.LocationId, r.Name, latitude = (double)r.Latitude, longitude = (double)r.Longitude,
                r.IsPrivate, r.MyRounds, r.Rounds,
            }),
        });
    }

    private static async Task<IResult> Page(Guid locationId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var me = user.ArcherId();
        await using var conn = await db.OpenAsync(ct);

        // A merged id goes to the location kept.
        if (await LocationEndpoints.CurrentAsync(conn, locationId, null, ct) is not { } current) return NotFound();
        var loc = await conn.QuerySingleAsync<Loc>(new CommandDefinition(
            "SELECT LocationId, Name, Latitude, Longitude, IsPrivate, CreatedById FROM dbo.ShootLocation WHERE LocationId = @id;",
            new { id = current.LocationId }, cancellationToken: ct));
        var mine = loc.CreatedById == me;
        if (loc.IsPrivate && !mine && !user.IsAdmin()) return NotFound();

        var myRounds = await conn.QueryAsync<RoundRow>(new CommandDefinition("""
            SELECT RoundId, ArcherId, CAST(ShootDate AS datetime2(3)) AS ShootDate, TargetCount, ArcherClass, AgeGroup,
                   BowStyle, TotalPoints
            FROM dbo.Round
            WHERE LocationId = @id AND ArcherId = @me AND Status = 'COMPLETED' AND IsDeleted = 0
            ORDER BY ShootDate DESC, ClientCreatedUtc DESC;
            """, new { id = loc.LocationId, me }, cancellationToken: ct));

        object friends = Array.Empty<object>();
        object bowStyles = Array.Empty<object>();
        if (!loc.IsPrivate)
        {
            // Friends' finished rounds here, with a summary each (average per animal, out of 24).
            var friendRows = (await conn.QueryAsync<FriendRow>(new CommandDefinition("""
                WITH fr AS (SELECT FriendId FROM dbo.vFriend WHERE ArcherId = @me),
                done AS (
                    SELECT r.RoundId, r.ArcherId, r.TotalPoints FROM dbo.Round r JOIN fr ON fr.FriendId = r.ArcherId
                    WHERE r.LocationId = @id AND r.Status = 'COMPLETED' AND r.IsDeleted = 0),
                an AS (SELECT d.ArcherId, COUNT(*) AS Animals FROM dbo.AnimalScore s JOIN done d ON d.RoundId = s.RoundId GROUP BY d.ArcherId)
                SELECT a.ArcherId, a.Username, COUNT(*) AS Rounds, MAX(d.TotalPoints) AS BestTotal,
                       CAST(SUM(CAST(d.TotalPoints AS bigint)) AS float) / NULLIF(MAX(an.Animals), 0) AS AveragePerAnimal
                FROM done d
                JOIN dbo.Archer a ON a.ArcherId = d.ArcherId
                LEFT JOIN an ON an.ArcherId = d.ArcherId
                GROUP BY a.ArcherId, a.Username
                ORDER BY MAX(d.TotalPoints) DESC, a.Username;
                """, new { id = loc.LocationId, me }, cancellationToken: ct))).ToList();
            var friendRounds = (await conn.QueryAsync<RoundRow>(new CommandDefinition("""
                SELECT r.RoundId, r.ArcherId, CAST(r.ShootDate AS datetime2(3)) AS ShootDate, r.TargetCount, r.ArcherClass,
                       r.AgeGroup, r.BowStyle, r.TotalPoints
                FROM dbo.Round r JOIN dbo.vFriend f ON f.FriendId = r.ArcherId AND f.ArcherId = @me
                WHERE r.LocationId = @id AND r.Status = 'COMPLETED' AND r.IsDeleted = 0
                ORDER BY r.ShootDate DESC, r.ClientCreatedUtc DESC;
                """, new { id = loc.LocationId, me }, cancellationToken: ct))).ToLookup(r => r.ArcherId);
            friends = friendRows.Select(f => new
            {
                f.ArcherId, f.Username, f.Rounds, f.BestTotal,
                averagePerAnimal = f.AveragePerAnimal is { } avg ? Math.Round(avg, 1) : (double?)null,
                roundsList = friendRounds[f.ArcherId].Select(Summary),
            }).ToList();

            // Everyone's finished rounds here, per bow style (no names).
            var bowRows = await conn.QueryAsync<BowRow>(new CommandDefinition("""
                WITH done AS (
                    SELECT RoundId, ArcherId, BowStyle, TotalPoints FROM dbo.Round
                    WHERE LocationId = @id AND Status = 'COMPLETED' AND IsDeleted = 0),
                an AS (SELECT d.BowStyle, COUNT(*) AS Animals FROM dbo.AnimalScore s JOIN done d ON d.RoundId = s.RoundId GROUP BY d.BowStyle)
                SELECT d.BowStyle, COUNT(DISTINCT d.ArcherId) AS Archers, COUNT(*) AS Rounds, MAX(d.TotalPoints) AS BestTotal,
                       AVG(CAST(d.TotalPoints AS float)) AS AverageTotal,
                       CAST(SUM(CAST(d.TotalPoints AS bigint)) AS float) / NULLIF(MAX(an.Animals), 0) AS AveragePerAnimal
                FROM done d LEFT JOIN an ON an.BowStyle = d.BowStyle
                GROUP BY d.BowStyle
                ORDER BY COUNT(*) DESC, d.BowStyle;
                """, new { id = loc.LocationId }, cancellationToken: ct));
            bowStyles = bowRows.Select(b => new
            {
                b.BowStyle, b.Archers, b.Rounds, b.BestTotal,
                averageTotal = b.AverageTotal is { } t ? Math.Round(t, 1) : (double?)null,
                averagePerAnimal = b.AveragePerAnimal is { } a ? Math.Round(a, 1) : (double?)null,
            }).ToList();
        }

        return Results.Ok(new
        {
            location = new
            {
                loc.LocationId, loc.Name, latitude = (double)loc.Latitude, longitude = (double)loc.Longitude, loc.IsPrivate,
            },
            myRounds = myRounds.Select(Summary),
            friends,
            bowStyles,
        });
    }

    private static object Summary(RoundRow r) => new
    {
        r.RoundId,
        shootDate = DateOnly.FromDateTime(r.ShootDate),
        targetCount = (int)r.TargetCount,
        archerClass = r.ArcherClass.Trim(),
        r.AgeGroup,
        r.BowStyle,
        r.TotalPoints,
    };

    private static IResult NotFound() =>
        Results.Json(new { error = "no_such_location", message = "That shoot location isn't available." }, statusCode: 404);
}
