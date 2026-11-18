// Compared with others (3.2.17, Pete). All calls need "Authorization: Bearer <token>".
//   POST /api/stats/compare { groups: [{ roundType, bowStyle, archerClass ("G"/"L"), ageGroup, average }] }
//     → { groups: [{ roundType, bowStyle, archerClass, ageGroup, rounds, archers, betterThan }] }
//     For each group: everyone else's finished rounds of that round type, bow style, class and age group (not yours),
//     how many archers shot them, and the share of those rounds whose average per animal (Woodsman: per target; fun
//     shots left out) is below your [average]. Anonymous: betterThan is null unless there are at least 20 rounds from
//     at least 5 archers. At most 20 groups.

using System.Security.Claims;
using Dapper;
using FieldArcheryApi.Auth;
using FieldArcheryApi.Data;
using FieldArcheryApi.Rounds;

namespace FieldArcheryApi.Stats;

public static class CompareEndpoints
{
    public const int MinRounds = 20;
    public const int MinArchers = 5;

    public sealed record Group(string? RoundType, string? BowStyle, string? ArcherClass, string? AgeGroup, double? Average);
    public sealed record CompareRequest(IReadOnlyList<Group>? Groups);

    public static void MapCompareEndpoints(this WebApplication app)
    {
        app.MapPost("/api/stats/compare", Compare).RequireAuthorization();
    }

    private static async Task<IResult> Compare(CompareRequest req, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var groups = (req.Groups ?? []).Take(20).ToList();
        await using var conn = await db.OpenAsync(ct);
        var result = new List<object>();
        foreach (var g in groups)
        {
            if (g.RoundType is null || g.BowStyle is null || g.ArcherClass is null || g.AgeGroup is null || g.Average is not { } avg) continue;
            var row = await conn.QuerySingleAsync<(int Rounds, int Archers, int Below)>(new CommandDefinition($"""
                WITH d AS (
                    SELECT r.ArcherId, CAST({RoundShape.StatsPointsSql("r")} AS float) AS Pts,
                           CASE WHEN r.RoundType = 'WOODSMAN'
                                THEN (SELECT COUNT(*) FROM dbo.TargetScore t WHERE t.RoundId = r.RoundId AND t.TargetType <> 'FUN')
                                ELSE (SELECT COUNT(*) FROM dbo.AnimalScore s WHERE s.RoundId = r.RoundId) END AS Animals
                    FROM dbo.Round r
                    WHERE r.Status = 'COMPLETED' AND r.IsDeleted = 0 AND r.ArcherId <> @me
                      AND r.RoundType = @type AND r.BowStyle = @bow AND r.ArcherClass = @cls AND r.AgeGroup = @age)
                SELECT COUNT(*), COUNT(DISTINCT ArcherId), ISNULL(SUM(CASE WHEN Pts / Animals < @avg THEN 1 ELSE 0 END), 0)
                FROM d WHERE Animals > 0;
                """, new { me = user.ArcherId(), type = g.RoundType, bow = g.BowStyle, cls = g.ArcherClass, age = g.AgeGroup, avg },
                cancellationToken: ct));
            var enough = row.Rounds >= MinRounds && row.Archers >= MinArchers;
            result.Add(new
            {
                roundType = g.RoundType, bowStyle = g.BowStyle, archerClass = g.ArcherClass, ageGroup = g.AgeGroup,
                rounds = enough ? row.Rounds : 0, archers = enough ? row.Archers : 0,
                betterThan = enough ? (double?)row.Below / row.Rounds : null,
            });
        }
        return Results.Ok(new { groups = result });
    }
}
