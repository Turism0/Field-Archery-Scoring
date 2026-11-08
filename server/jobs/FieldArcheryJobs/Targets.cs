namespace FieldArcheryJobs;

/// <summary>A stored result, as the fit and the target rule see it (person = archer key + sex, split by club if needed).</summary>
public sealed record Entry(string Champs, int Year, string Bow, string Sex, string Age, string Person, int Day1, int Day2, int Total, int Count24)
{
    public string EventId => $"{Champs} {Year}";
}

/// <summary>
/// Targets per day for a championship (the results don't say). Agreed rules:
///   1. Before 2020 (Covid) every championship was 40 targets a day (Pete).
///   2. A day score above 36 × 24 = 864 is impossible on 36 targets → 40.
///   3. Otherwise, from the 24s: on the targets that weren't 24s an archer averages a fairly fixed amount for how
///      many 24s they shot (a curve from championships certain to be 40). Whichever of 36 / 40 fits that curve better
///      wins; it's flagged DOUBTFUL unless it's clearly better (0.75 points per target) or 36 would be impossible
///      (averaging over 20 on the other targets).
///   4. No 24s recorded → 40, DOUBTFUL.
/// An Admin can set any event by hand (ADMIN), which always wins.
/// </summary>
public static class Targets
{
    public sealed record Decision(int TargetsPerDay, string Source, string Note);

    public static Dictionary<string, Decision> Decide(IReadOnlyList<Entry> entries)
    {
        var events = entries.Where(e => e.Total > 0).GroupBy(e => (e.Champs, e.Year)).ToList();
        var full = entries.Where(e => e.Day1 > 0 && e.Day2 > 0 && e.Count24 > 0).ToList();
        var certain = events.Where(g => g.Max(e => Math.Max(e.Day1, e.Day2)) > 24 * 36).Select(g => g.Key).ToHashSet();
        var result = new Dictionary<string, Decision>();
        foreach (var g in events)
        {
            var (champs, year) = g.Key;
            var id = $"{champs} {year}";
            var maxDay = g.Max(e => Math.Max(e.Day1, e.Day2));
            if (year < 2020) { result[id] = new(40, "RULE", "Before Covid every championship was 40 targets a day."); continue; }
            if (maxDay > 24 * 36) { result[id] = new(40, "AUTO", $"A day of {maxDay} is more than 36 targets allow."); continue; }
            var here = full.Where(e => e.Champs == champs && e.Year == year).ToList();
            if (here.Count < 20) { result[id] = new(40, "DOUBTFUL", "Not enough 24s recorded to tell."); continue; }

            var reference = full.Where(e => certain.Contains((e.Champs, e.Year)) && !(e.Champs == champs && e.Year == year)).ToList();
            var curve = Quadratic(reference.Select(e => e.Count24 / 80.0).ToArray(),
                                  reference.Select(e => (e.Total - 24.0 * e.Count24) / (80 - e.Count24)).ToArray());
            double Gap(int t)
            {
                var n = 2 * t;
                var non24 = here.Select(e => (e.Total - 24.0 * e.Count24) / (n - e.Count24)).ToArray();
                if (non24.Any(v => v > 20)) return double.PositiveInfinity;
                return Math.Abs(here.Select((e, i) => non24[i] - Eval(curve, e.Count24 / (double)n)).Average());
            }
            var g36 = Gap(36); var g40 = Gap(40);
            var best = g36 <= g40 ? 36 : 40;
            var clear = Math.Abs(g36 - g40) >= 0.75;
            result[id] = new(best, clear ? "AUTO" : "DOUBTFUL",
                $"Points on the targets that weren't 24s fit {best} targets better (off by {Math.Min(g36, g40):0.00} against {(double.IsInfinity(Math.Max(g36, g40)) ? "impossible" : Math.Max(g36, g40).ToString("0.00"))}).");
        }
        return result;
    }

    /// <summary>Least-squares y = c0 + c1 x + c2 x² (the same curve as numpy.polyfit(x, y, 2)).</summary>
    public static double[] Quadratic(double[] x, double[] y)
    {
        var a = new double[3, 3]; var b = new double[3];
        for (var i = 0; i < x.Length; i++)
        {
            double[] p = [1, x[i], x[i] * x[i]];
            for (var r = 0; r < 3; r++) { b[r] += p[r] * y[i]; for (var c = 0; c < 3; c++) a[r, c] += p[r] * p[c]; }
        }
        return Linear.Solve(a, b);
    }

    public static double Eval(double[] c, double x) => c[0] + c[1] * x + c[2] * x * x;
}
