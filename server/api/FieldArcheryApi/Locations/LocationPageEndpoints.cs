using System.Security.Claims;
using Dapper;
using FieldArcheryApi.Auth;
using FieldArcheryApi.Data;
using FieldArcheryApi.Rounds;

namespace FieldArcheryApi.Locations;

/// <summary>
/// Shoot location map and location page (API 1.7.03). All calls need "Authorization: Bearer &lt;token&gt;".
///   GET /api/locations/map         → { locations: [{ locationId, name, latitude, longitude, isPrivate, myRounds, rounds }] }
///                                    public live locations, plus your own private ones;
///                                    myRounds = your finished rounds there, rounds = everyone's (public only)
///   (2.0.05: friends and bowStyles get average36 / average40 = average per animal × 36 / 40;
///    rounds get animalCount, predatorPrey, fans.)
///   GET /api/locations/{id}/page   → { location, myRounds: [...], friends: [{ archerId, username, roundType, rounds, bestTotal,
///                                    averagePerAnimal, roundsList: [...] }], bowStyles: [{ roundType, bowStyle, archers, rounds,
///                                    bestTotal, averageTotal, averagePerAnimal }] }  (one row per round type)
///   (API 2.4: bowClasses: the same per bow style, class (G / L) and age group, for the app's class filter.)
/// Only finished, not-deleted rounds count. A private location's page is only for the archer who named it and
/// Admins, and has no friends or all-archer sections. Friends' rounds open with GET /api/friends/{id}/rounds/{roundId}.
/// </summary>
public static class LocationPageEndpoints
{
    private sealed record MapRow(Guid LocationId, string Name, decimal Latitude, decimal Longitude, bool IsPrivate, int MyRounds, int Rounds);

    private sealed record Loc(Guid LocationId, string Name, decimal Latitude, decimal Longitude, bool IsPrivate, Guid? CreatedById);

    public sealed record RoundRow(Guid RoundId, Guid ArcherId, DateTime ShootDate, short TargetCount, string ArcherClass,
        string AgeGroup, string BowStyle, int TotalPoints, string RoundType, int AnimalCount = 0, int PredatorPrey = 0, int Fans = 0);

    private sealed record FriendRow(Guid ArcherId, string Username, string RoundType, int Rounds, int? BestTotal, double? AveragePerAnimal);

    private sealed record BowClassRow(string RoundType, string BowStyle, string ArcherClass, string AgeGroup, int Archers, int Rounds,
        int? BestTotal, double? AverageTotal, double? AveragePerAnimal);

    private sealed record BowRow(string RoundType, string BowStyle, int Archers, int Rounds, int? BestTotal, double? AverageTotal, double? AveragePerAnimal);

    /// <summary>
    /// Animals in round "d" for averages: Big Game counts animals (predator/prey 2, fan n),
    /// Woodsman one per target. Both give a per-animal average (out of 24 / out of 30).
    /// </summary>
    private const string AnimalsPerRoundSql =
        "SELECT CASE WHEN d.RoundType = 'WOODSMAN' " +
        "THEN (SELECT COUNT(*) FROM dbo.TargetScore t WHERE t.RoundId = d.RoundId) " +
        "ELSE (SELECT COUNT(*) FROM dbo.AnimalScore s WHERE s.RoundId = d.RoundId) END AS Animals";

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
              AND (l.IsPrivate = 0 OR l.CreatedById = @me)
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
        if (loc.IsPrivate && !mine) return NotFound();

        var myRounds = await conn.QueryAsync<RoundRow>(new CommandDefinition($"""
            SELECT r.RoundId, r.ArcherId, CAST(r.ShootDate AS datetime2(3)) AS ShootDate, r.TargetCount, r.ArcherClass, r.AgeGroup,
                   r.BowStyle, r.TotalPoints, r.RoundType, {RoundShape.ColumnsSql}
            FROM dbo.Round r
            WHERE r.LocationId = @id AND r.ArcherId = @me AND Status = 'COMPLETED' AND IsDeleted = 0
            ORDER BY r.ShootDate DESC, r.ClientCreatedUtc DESC;
            """, new { id = loc.LocationId, me }, cancellationToken: ct));

        // Averages are also given as a whole 36- and 40-target round, from the per-animal average, so predator/prey
        // and fans don't push them up and rounds of different lengths compare fairly.
        static double? Over(double? perAnimal, int targets) => perAnimal is { } a ? Math.Round(a * targets) : null;

        object friends = Array.Empty<object>();
        object bowStyles = Array.Empty<object>();
        object bowClasses = Array.Empty<object>();
        if (!loc.IsPrivate)
        {
            // Friends' finished rounds here, with a summary each (average per animal, out of 24).
            var friendRows = (await conn.QueryAsync<FriendRow>(new CommandDefinition($"""
                WITH fr AS (SELECT FriendId FROM dbo.vFriend WHERE ArcherId = @me),
                done AS (
                    SELECT r.RoundId, r.ArcherId, r.RoundType, r.TotalPoints FROM dbo.Round r JOIN fr ON fr.FriendId = r.ArcherId
                    WHERE r.LocationId = @id AND r.Status = 'COMPLETED' AND r.IsDeleted = 0),
                an AS (
                    SELECT d.ArcherId, d.RoundType, SUM(x.Animals) AS Animals
                    FROM done d CROSS APPLY ({AnimalsPerRoundSql}) x
                    GROUP BY d.ArcherId, d.RoundType)
                SELECT a.ArcherId, a.Username, d.RoundType, COUNT(*) AS Rounds, MAX(d.TotalPoints) AS BestTotal,
                       CAST(SUM(CAST(d.TotalPoints AS bigint)) AS float) / NULLIF(MAX(an.Animals), 0) AS AveragePerAnimal
                FROM done d
                JOIN dbo.Archer a ON a.ArcherId = d.ArcherId
                LEFT JOIN an ON an.ArcherId = d.ArcherId AND an.RoundType = d.RoundType
                GROUP BY a.ArcherId, a.Username, d.RoundType
                ORDER BY d.RoundType, MAX(d.TotalPoints) DESC, a.Username;
                """, new { id = loc.LocationId, me }, cancellationToken: ct))).ToList();
            var friendRounds = (await conn.QueryAsync<RoundRow>(new CommandDefinition($"""
                SELECT r.RoundId, r.ArcherId, CAST(r.ShootDate AS datetime2(3)) AS ShootDate, r.TargetCount, r.ArcherClass,
                       r.AgeGroup, r.BowStyle, r.TotalPoints, r.RoundType, {RoundShape.ColumnsSql}
                FROM dbo.Round r JOIN dbo.vFriend f ON f.FriendId = r.ArcherId AND f.ArcherId = @me
                WHERE r.LocationId = @id AND r.Status = 'COMPLETED' AND r.IsDeleted = 0
                ORDER BY r.ShootDate DESC, r.ClientCreatedUtc DESC;
                """, new { id = loc.LocationId, me }, cancellationToken: ct))).ToLookup(r => r.ArcherId);
            friends = friendRows.Select(f => new
            {
                f.ArcherId, f.Username, f.RoundType, f.Rounds, f.BestTotal,
                averagePerAnimal = f.AveragePerAnimal is { } avg ? Math.Round(avg, 1) : (double?)null,
                average36 = Over(f.AveragePerAnimal, 36),
                average40 = Over(f.AveragePerAnimal, 40),
                roundsList = friendRounds[f.ArcherId].Where(r => r.RoundType == f.RoundType).Select(Summary),
            }).ToList();

            // Everyone's finished rounds here, per bow style (no names).
            var bowRows = await conn.QueryAsync<BowRow>(new CommandDefinition($"""
                WITH done AS (
                    SELECT RoundId, ArcherId, RoundType, BowStyle, TotalPoints FROM dbo.Round d
                    WHERE LocationId = @id AND Status = 'COMPLETED' AND IsDeleted = 0),
                an AS (
                    SELECT d.RoundType, d.BowStyle, SUM(x.Animals) AS Animals
                    FROM done d CROSS APPLY ({AnimalsPerRoundSql}) x
                    GROUP BY d.RoundType, d.BowStyle)
                SELECT d.RoundType, d.BowStyle, COUNT(DISTINCT d.ArcherId) AS Archers, COUNT(*) AS Rounds, MAX(d.TotalPoints) AS BestTotal,
                       AVG(CAST(d.TotalPoints AS float)) AS AverageTotal,
                       CAST(SUM(CAST(d.TotalPoints AS bigint)) AS float) / NULLIF(MAX(an.Animals), 0) AS AveragePerAnimal
                FROM done d LEFT JOIN an ON an.BowStyle = d.BowStyle AND an.RoundType = d.RoundType
                GROUP BY d.RoundType, d.BowStyle
                ORDER BY d.RoundType, COUNT(*) DESC, d.BowStyle;
                """, new { id = loc.LocationId }, cancellationToken: ct));
            bowStyles = bowRows.Select(b => new
            {
                b.RoundType, b.BowStyle, b.Archers, b.Rounds, b.BestTotal,
                averageTotal = b.AverageTotal is { } t ? Math.Round(t, 1) : (double?)null,
                averagePerAnimal = b.AveragePerAnimal is { } a ? Math.Round(a, 1) : (double?)null,
                average36 = Over(b.AveragePerAnimal, 36),
                average40 = Over(b.AveragePerAnimal, 40),
            }).ToList();

            // The same, per class and age group (API 2.4).
            var classRows = await conn.QueryAsync<BowClassRow>(new CommandDefinition($"""
                WITH done AS (
                    SELECT RoundId, ArcherId, RoundType, BowStyle, ArcherClass, AgeGroup, TotalPoints FROM dbo.Round d
                    WHERE LocationId = @id AND Status = 'COMPLETED' AND IsDeleted = 0),
                an AS (
                    SELECT d.RoundType, d.BowStyle, d.ArcherClass, d.AgeGroup, SUM(x.Animals) AS Animals
                    FROM done d CROSS APPLY ({AnimalsPerRoundSql}) x
                    GROUP BY d.RoundType, d.BowStyle, d.ArcherClass, d.AgeGroup)
                SELECT d.RoundType, d.BowStyle, d.ArcherClass, d.AgeGroup, COUNT(DISTINCT d.ArcherId) AS Archers, COUNT(*) AS Rounds,
                       MAX(d.TotalPoints) AS BestTotal, AVG(CAST(d.TotalPoints AS float)) AS AverageTotal,
                       CAST(SUM(CAST(d.TotalPoints AS bigint)) AS float) / NULLIF(MAX(an.Animals), 0) AS AveragePerAnimal
                FROM done d LEFT JOIN an ON an.BowStyle = d.BowStyle AND an.RoundType = d.RoundType
                    AND an.ArcherClass = d.ArcherClass AND an.AgeGroup = d.AgeGroup
                GROUP BY d.RoundType, d.BowStyle, d.ArcherClass, d.AgeGroup
                ORDER BY d.RoundType, COUNT(*) DESC, d.BowStyle;
                """, new { id = loc.LocationId }, cancellationToken: ct));
            bowClasses = classRows.Select(b => new
            {
                b.RoundType, b.BowStyle, archerClass = b.ArcherClass.Trim(), b.AgeGroup, b.Archers, b.Rounds, b.BestTotal,
                averageTotal = b.AverageTotal is { } t ? Math.Round(t, 1) : (double?)null,
                averagePerAnimal = b.AveragePerAnimal is { } a ? Math.Round(a, 1) : (double?)null,
                average36 = Over(b.AveragePerAnimal, 36),
                average40 = Over(b.AveragePerAnimal, 40),
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
            bowClasses,
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
        r.RoundType,
        r.AnimalCount,
        r.PredatorPrey,
        r.Fans,
    };

    private static IResult NotFound() =>
        Results.Json(new { error = "no_such_location", message = "That shoot location isn't available." }, statusCode: 404);
}
