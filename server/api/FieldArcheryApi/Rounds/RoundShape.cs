namespace FieldArcheryApi.Rounds;

/// <summary>
/// How a round is made up, for describing it ("36 targets · 37 animals (1 predator/prey)"). API 2.0.02.
/// </summary>
public static class RoundShape
{
    /// <summary>Columns AnimalCount, PredatorPrey, Fans for round alias <c>r</c> (Woodsman rounds have no animal rows: 0).</summary>
    public const string ColumnsSql =
        "(SELECT COUNT(*) FROM dbo.AnimalScore s WHERE s.RoundId = r.RoundId) AS AnimalCount, " +
        "(SELECT COUNT(*) FROM dbo.TargetScore t WHERE t.RoundId = r.RoundId AND t.TargetType = 'PREDATOR_PREY') AS PredatorPrey, " +
        "(SELECT COUNT(*) FROM dbo.TargetScore t WHERE t.RoundId = r.RoundId AND t.TargetType = 'FAN') AS Fans, " +
        "(SELECT COUNT(*) FROM dbo.TargetScore t WHERE t.RoundId = r.RoundId AND t.TargetType = 'FUN') AS FunShots, " +
        FunPointsSqlR + " AS FunPoints";

    /// <summary>
    /// Fun shots (API 3.1, schema 11): a Big Game target with unusual scoring, typed in. They count in the round
    /// total but not in any stats, so stats use the total less the fun shots' points (and the targets less the fun
    /// shots). Points of the round with alias <c>r</c>.
    /// </summary>
    private const string FunPointsSqlR = "ISNULL((SELECT SUM(t.Points) FROM dbo.TargetScore t WHERE t.RoundId = r.RoundId AND t.TargetType = 'FUN'), 0)";

    /// <summary>The fun shots' points for the round with alias [alias].</summary>
    public static string FunPointsSql(string alias) =>
        $"ISNULL((SELECT SUM(t.Points) FROM dbo.TargetScore t WHERE t.RoundId = {alias}.RoundId AND t.TargetType = 'FUN'), 0)";

    /// <summary>The round's points for stats: its total less any fun shots.</summary>
    public static string StatsPointsSql(string alias) => $"({alias}.TotalPoints - {FunPointsSql(alias)})";
}
