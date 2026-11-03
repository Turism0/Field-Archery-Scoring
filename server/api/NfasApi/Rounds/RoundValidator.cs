namespace NfasApi.Rounds;

/// <summary>
/// Checks an uploaded round against the rules for its type (Big Game or Woodsman), the same rules as
/// the app (app/src/main/java/.../domain/Scoring.kt). Returns a list of problems; empty means valid.
/// </summary>
public static class RoundValidator
{
    public const int MaxTargets = 100;
    public const int MinFan = 2;
    public const int MaxFan = 6;
    public const int MaxOverride = MaxFan * 24;

    private static readonly HashSet<string> Classes = ["L", "G"];
    private static readonly HashSet<string> AgeGroups = ["ADULT", "JUNIOR", "CUB"];
    private static readonly HashSet<string> BowStyles = ["AFB", "BB", "BH", "CL", "XB", "TXB", "FS", "HT", "LB", "PV", "TD", "TB", "UL"];
    private static readonly HashSet<string> Statuses = ["IN_PROGRESS", "COMPLETED"];
    public const string BigGame = "BIG_GAME";
    public const string Woodsman = "WOODSMAN";
    public const int WoodsmanMax = 30;

    /// <summary>The round's type, BIG_GAME when not sent (older apps).</summary>
    public static string TypeOf(RoundDto r) => string.IsNullOrWhiteSpace(r.RoundType) ? BigGame : r.RoundType.Trim().ToUpperInvariant();

    /// <summary>
    /// Points for a Woodsman target's arrows (W wound 5 each, K kill 30/20/10 on arrow 1/2/3, M miss 0),
    /// or null if the arrows aren't possible: only W/K/M, at most 3, nothing after a kill.
    /// </summary>
    public static int? WoodsmanPoints(string? arrows)
    {
        var a = arrows ?? "";
        if (a.Length > 3 || a.Any(c => c is not ('W' or 'K' or 'M'))) return null;
        var kill = a.IndexOf('K');
        if (kill >= 0 && kill != a.Length - 1) return null;   // shooting stops at the kill
        return a.Count(c => c == 'W') * 5 + (kill < 0 ? 0 : WoodsmanMax - kill * 10);
    }

    /// <summary>Points for a scoring arrow, or null if that hit isn't allowed on that arrow.</summary>
    public static int? PointsFor(int arrowNo, string hit) => (arrowNo, hit) switch
    {
        (1, "INNER_KILL") => 24,
        (1, "KILL") => 20,
        (1, "WOUND") => 16,
        (2, "KILL") => 14,
        (2, "WOUND") => 10,
        (3, "KILL") => 8,
        (3, "WOUND") => 4,
        _ => null,
    };

    public static List<string> Validate(RoundDto r)
    {
        var problems = new List<string>();

        if (r.TargetCount is < 1 or > MaxTargets) problems.Add($"Target count must be 1 to {MaxTargets}.");
        if (!Classes.Contains(r.ArcherClass)) problems.Add("Class must be L or G.");
        if (!AgeGroups.Contains(r.AgeGroup)) problems.Add("Unknown age group.");
        if (!BowStyles.Contains(r.BowStyle)) problems.Add("Unknown bow style.");
        if (!Statuses.Contains(r.Status)) problems.Add("Unknown status.");
        if (r.ShootDate < new DateOnly(2000, 1, 1) || r.ShootDate > DateOnly.FromDateTime(DateTime.UtcNow.AddDays(1)))
            problems.Add("Shoot date is out of range.");
        if (r.ClientUpdatedUtc < r.ClientCreatedUtc) problems.Add("Updated time is before created time.");

        var targets = r.Targets ?? [];
        var numbers = targets.Select(t => t.TargetNo).OrderBy(n => n).ToList();
        if (!numbers.SequenceEqual(Enumerable.Range(1, Math.Max(0, r.TargetCount))))
            problems.Add($"Targets must be numbered 1 to {r.TargetCount}, once each.");

        var type = TypeOf(r);
        if (type == Woodsman) foreach (var t in targets) ValidateWoodsmanTarget(t, problems);
        else if (type == BigGame) foreach (var t in targets) ValidateTarget(t, problems);
        else problems.Add("Round type must be BIG_GAME or WOODSMAN.");

        if (problems.Count == 0 && targets.Sum(t => t.Points) != r.TotalPoints)
            problems.Add($"Total points {r.TotalPoints} don't match the targets ({targets.Sum(t => t.Points)}).");

        return problems;
    }

    /// <summary>Checks one target on its own (a group score entry) against the rules for the round type.</summary>
    public static List<string> ValidateEntry(string roundType, TargetDto t)
    {
        var problems = new List<string>();
        if (roundType == Woodsman) ValidateWoodsmanTarget(t, problems);
        else if (t.Type == Woodsman) problems.Add($"Target {t.TargetNo}: a Big Game round can't use a Woodsman target.");
        else ValidateTarget(t, problems);
        return problems;
    }

    private static void ValidateWoodsmanTarget(TargetDto t, List<string> problems)
    {
        var where = $"Target {t.TargetNo}";
        if (t.Type != Woodsman) { problems.Add($"{where}: a Woodsman round's targets must be WOODSMAN."); return; }
        if ((t.Animals ?? []).Count != 0) { problems.Add($"{where}: Woodsman targets record arrows, not animals."); return; }
        var points = WoodsmanPoints(t.Arrows);
        if (points is null)
        {
            problems.Add($"{where}: arrows '{t.Arrows}' aren't possible (up to 3 of W, K, M; nothing after a kill).");
            return;
        }
        if (t.OverridePoints is { } ov)
        {
            if (ov is < 0 or > WoodsmanMax) problems.Add($"{where}: override must be 0 to {WoodsmanMax}.");
            else if (t.Points != ov) problems.Add($"{where}: points must equal the override ({ov}).");
        }
        else if (t.Points != points)
        {
            problems.Add($"{where}: points {t.Points} don't match the arrows '{t.Arrows}' ({points}).");
        }
    }

    private static void ValidateTarget(TargetDto t, List<string> problems)
    {
        var where = $"Target {t.TargetNo}";
        var animals = (t.Animals ?? []).OrderBy(a => a.Index).ToList();
        if (!string.IsNullOrEmpty(t.Arrows)) { problems.Add($"{where}: Big Game targets don't record arrows."); return; }

        var countOk = t.Type switch
        {
            "STANDARD" => animals.Count == 1,
            "PREDATOR_PREY" => animals.Count == 2,
            "FAN" => animals.Count is >= MinFan and <= MaxFan,
            _ => false,
        };
        if (!countOk)
        {
            problems.Add($"{where}: wrong number of animals for type {t.Type}.");
            return;
        }
        if (!animals.Select(a => a.Index).SequenceEqual(Enumerable.Range(0, animals.Count)))
        {
            problems.Add($"{where}: animals must be numbered from 0.");
            return;
        }

        for (var i = 0; i < animals.Count; i++)
        {
            var a = animals[i];
            var label = t.Type == "PREDATOR_PREY" ? (i == 0 ? "predator" : "prey") : $"animal {i + 1}";
            switch (a.State)
            {
                case "SCORED":
                    var expected = a.ArrowNo is { } arrow && a.Hit is { } hit ? PointsFor(arrow, hit) : null;
                    if (expected is null) problems.Add($"{where} {label}: {a.Hit} isn't allowed on arrow {a.ArrowNo}.");
                    else if (a.Points != expected) problems.Add($"{where} {label}: points should be {expected}, not {a.Points}.");
                    break;
                case "MISSED":
                case "PENDING":
                    if (a.ArrowNo is not null || a.Hit is not null || a.Points != 0)
                        problems.Add($"{where} {label}: a {a.State.ToLowerInvariant()} animal has no arrow, hit or points.");
                    break;
                default:
                    problems.Add($"{where} {label}: unknown state {a.State}.");
                    break;
            }
        }

        // Predator/prey: 3 arrows shared; the prey only gets the arrows the predator didn't use.
        if (t.Type == "PREDATOR_PREY" && animals[1].State == "SCORED")
        {
            var predator = animals[0];
            var arrowsLeft = predator.State == "SCORED" && predator.ArrowNo is { } p ? 3 - p : 0;
            if (animals[1].ArrowNo > arrowsLeft)
                problems.Add($"{where}: the prey can't score on arrow {animals[1].ArrowNo}; only {arrowsLeft} arrow(s) were left.");
        }

        if (t.Type == "PREDATOR_PREY" && animals[1].State == "MISSED" && animals[0].State == "PENDING")
            problems.Add($"{where}: the prey can't be marked missed before the predator is scored.");

        if (t.OverridePoints is { } ov)
        {
            if (ov is < 0 or > MaxOverride) problems.Add($"{where}: override must be 0 to {MaxOverride}.");
            else if (t.Points != ov) problems.Add($"{where}: points must equal the override ({ov}).");
        }
        else if (t.Points != animals.Sum(a => a.Points))
        {
            problems.Add($"{where}: points {t.Points} don't match the animals ({animals.Sum(a => a.Points)}).");
        }
    }
}
