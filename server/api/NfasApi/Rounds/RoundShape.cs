using Dapper;
using Microsoft.Data.SqlClient;

namespace NfasApi.Rounds;

/// <summary>
/// How a round is made up, for describing it ("36 targets · 37 animals (1 predator/prey)") and for a location's usual
/// course size. API 2.0.02.
/// </summary>
public static class RoundShape
{
    /// <summary>Columns AnimalCount, PredatorPrey, Fans for round alias <c>r</c> (Woodsman rounds have no animal rows: 0).</summary>
    public const string ColumnsSql =
        "(SELECT COUNT(*) FROM dbo.AnimalScore s WHERE s.RoundId = r.RoundId) AS AnimalCount, " +
        "(SELECT COUNT(*) FROM dbo.TargetScore t WHERE t.RoundId = r.RoundId AND t.TargetType = 'PREDATOR_PREY') AS PredatorPrey, " +
        "(SELECT COUNT(*) FROM dbo.TargetScore t WHERE t.RoundId = r.RoundId AND t.TargetType = 'FAN') AS Fans";

    /// <summary>Usual course sizes: full (36 or 40) and half (18 or 20).</summary>
    public static readonly int[] CourseSizes = [18, 20, 36, 40];

    /// <summary>A target count as the nearest usual course size (ties go to the larger).</summary>
    public static int Snap(int targets) =>
        CourseSizes.OrderBy(c => Math.Abs(c - targets)).ThenByDescending(c => c).First();

    /// <summary>
    /// The course size at a location now: the number of targets on the most recent finished round there (by shoot date)
    /// that's a usual course size, or failing that the most recent round's count snapped to one. Courses change a couple
    /// of times a year, so the latest round decides. Null when nobody has finished a round there.
    /// </summary>
    public static async Task<int?> CourseTargetsAsync(SqlConnection conn, Guid locationId, CancellationToken ct)
    {
        var counts = (await conn.QueryAsync<short>(new CommandDefinition("""
            SELECT TOP (20) TargetCount FROM dbo.Round
            WHERE LocationId = @locationId AND Status = 'COMPLETED' AND IsDeleted = 0
            ORDER BY ShootDate DESC, ClientCreatedUtc DESC;
            """, new { locationId }, cancellationToken: ct))).Select(c => (int)c).ToList();
        if (counts.Count == 0) return null;
        var usual = counts.FirstOrDefault(c => CourseSizes.Contains(c));
        return usual != 0 ? usual : Snap(counts[0]);
    }
}
