namespace NfasApi.Rounds;

/// <summary>
/// How a round is made up, for describing it ("36 targets · 37 animals (1 predator/prey)"). API 2.0.02.
/// </summary>
public static class RoundShape
{
    /// <summary>Columns AnimalCount, PredatorPrey, Fans for round alias <c>r</c> (Woodsman rounds have no animal rows: 0).</summary>
    public const string ColumnsSql =
        "(SELECT COUNT(*) FROM dbo.AnimalScore s WHERE s.RoundId = r.RoundId) AS AnimalCount, " +
        "(SELECT COUNT(*) FROM dbo.TargetScore t WHERE t.RoundId = r.RoundId AND t.TargetType = 'PREDATOR_PREY') AS PredatorPrey, " +
        "(SELECT COUNT(*) FROM dbo.TargetScore t WHERE t.RoundId = r.RoundId AND t.TargetType = 'FAN') AS Fans";
}
