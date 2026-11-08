namespace FieldArcheryJobs;

/// <summary>
/// The handicap model (worked out and tested in Nov 2026, see docs/HANDICAP.md). Each day's score as a share of the
/// maximum (24 a target), on a logit scale, is explained by
///   championship difficulty (each Nationals / 3Ds)
/// + bow style (with a straight-line change over the years, per 10 years from 2014)
/// + bow style at 3Ds (small, partly pooled)
/// + class (sex × age group) + bow × ladies (small, partly pooled)
/// + the archer's own ability (archers followed across years; shrunk towards average, λ = 1).
/// Noise is allowed to differ by bow style. Unlimited Gents (adult) is the reference (all its factors are 0).
/// Fitted by alternating between the fixed part (about 100 unknowns, solved exactly) and the archers (closed form),
/// three rounds of re-weighting by bow. Must match the Python reference (Verify\handicap-reference.json).
/// </summary>
public sealed class HandicapFit
{
    public const string RefBow = "UL";
    public const string RefClass = "M-ADULT";
    public const double YearZero = 2014;
    public const double Lambda = 1.0;

    public sealed record Day(string EventId, string Champs, int Year, string Bow, string Class, string Person, string Pid, string Entry,
                             double Score, int Targets, double Y);

    public sealed record Column(string Kind, string Code, double Penalty);

    public sealed class Result
    {
        public required List<Column> Columns { get; init; }
        public required double[] Beta { get; init; }
        public required Dictionary<string, double> Spread { get; init; }
        public required Dictionary<string, double> Ability { get; init; }

        public double Get(string kind, string code)
        {
            var i = Columns.FindIndex(c => c.Kind == kind && c.Code == code);
            return i < 0 ? 0 : Beta[i];
        }

        public double? Event(string id)
        {
            var i = Columns.FindIndex(c => c.Kind == "EVENT" && c.Code == id);
            return i < 0 ? null : Beta[i];
        }

        /// <summary>Logit-scale advantage of a bow and class over Unlimited Gents in a year (what the app uses).</summary>
        public double Offset(string bow, string cls, int year, bool is3d) =>
            Get("BOW", bow) + Get("CLASS", cls) + (cls.StartsWith("F-") ? Get("BOWSEX", bow) : 0)
            + Get("SLOPE", bow) * (year - YearZero) / 10 + (is3d ? Get("BOW3D", bow) : 0);
    }

    // ---- From stored results to day scores ------------------------------------------------------------------

    public static List<Day> Days(IEnumerable<Entry> entries, IReadOnlyDictionary<string, int> targets)
    {
        var days = new List<Day>();
        foreach (var e in entries)
        {
            if (!targets.TryGetValue(e.EventId, out var t)) continue;
            var cls = $"{e.Sex}-{e.Age}";
            foreach (var score in new[] { e.Day1, e.Day2 })
            {
                if (score <= 0) continue;
                var p = Math.Clamp(score / (24.0 * t), 0.01, 0.99);
                days.Add(new Day(e.EventId, e.Champs, e.Year, e.Bow, cls, e.Person, $"{e.Person}|{e.Age}", $"{e.EventId}|{e.Person}",
                                 score, t, Math.Log(p / (1 - p))));
            }
        }
        return days;
    }

    // ---- The fit -----------------------------------------------------------------------------------------------

    public static List<Column> Design(IReadOnlyList<Day> days)
    {
        var cols = new List<Column>();
        foreach (var e in days.Select(d => d.EventId).Distinct().OrderBy(x => x, StringComparer.Ordinal)) cols.Add(new("EVENT", e, 1e-8));
        var bows = days.Select(d => d.Bow).Distinct().OrderBy(x => x, StringComparer.Ordinal).Where(b => b != RefBow).ToList();
        foreach (var b in bows) { cols.Add(new("BOW", b, 1e-8)); cols.Add(new("SLOPE", b, 1e-8)); cols.Add(new("BOW3D", b, 5.0)); }
        foreach (var c in days.Select(d => d.Class).Distinct().OrderBy(x => x, StringComparer.Ordinal).Where(c => c != RefClass)) cols.Add(new("CLASS", c, 1e-8));
        foreach (var b in bows) cols.Add(new("BOWSEX", b, 5.0));
        // Only columns some day uses (e.g. a bow never shot at a 3Ds has no 3D term).
        var used = new HashSet<int>();
        var index = cols.Select((c, i) => (c, i)).ToDictionary(x => (x.c.Kind, x.c.Code), x => x.i);
        foreach (var d in days) foreach (var (i, v) in Features(d, index)) if (v != 0) used.Add(i);
        return cols.Where((c, i) => used.Contains(i)).ToList();
    }

    private static IEnumerable<(int, double)> Features(Day d, Dictionary<(string, string), int> index)
    {
        if (index.TryGetValue(("EVENT", d.EventId), out var e)) yield return (e, 1);
        if (d.Bow != RefBow)
        {
            if (index.TryGetValue(("BOW", d.Bow), out var b)) yield return (b, 1);
            if (index.TryGetValue(("SLOPE", d.Bow), out var s)) yield return (s, (d.Year - YearZero) / 10);
            if (d.Champs == "3DS" && index.TryGetValue(("BOW3D", d.Bow), out var t)) yield return (t, 1);
            if (d.Class.StartsWith("F-") && index.TryGetValue(("BOWSEX", d.Bow), out var x)) yield return (x, 1);
        }
        if (d.Class != RefClass && index.TryGetValue(("CLASS", d.Class), out var c)) yield return (c, 1);
    }

    public static Result Fit(IReadOnlyList<Day> days, int reweights = 3, int maxInner = 2000, double tol = 1e-8)
    {
        var cols = Design(days);
        var index = cols.Select((c, i) => (c, i)).ToDictionary(x => (x.c.Kind, x.c.Code), x => x.i);
        var n = days.Count; var p = cols.Count;
        var feat = days.Select(d => Features(d, index).ToArray()).ToArray();
        var pidIndex = new Dictionary<string, int>();
        var pid = days.Select(d => pidIndex.TryGetValue(d.Pid, out var k) ? k : pidIndex[d.Pid] = pidIndex.Count).ToArray();
        var np = pidIndex.Count;
        var y = days.Select(d => d.Y).ToArray();
        var w = Enumerable.Repeat(1.0, n).ToArray();
        var a = new double[np]; var beta = new double[p];
        var spread = new Dictionary<string, double>();

        for (var round = 0; round < reweights; round++)
        {
            var A = new double[p, p];
            for (var j = 0; j < p; j++) A[j, j] = cols[j].Penalty;
            for (var i = 0; i < n; i++)
                foreach (var (j1, v1) in feat[i])
                    foreach (var (j2, v2) in feat[i]) A[j1, j2] += w[i] * v1 * v2;
            var L = Linear.Cholesky(A);
            for (var it = 0; it < maxInner; it++)
            {
                var rhs = new double[p];
                for (var i = 0; i < n; i++) { var r = w[i] * (y[i] - a[pid[i]]); foreach (var (j, v) in feat[i]) rhs[j] += v * r; }
                var bn = Linear.CholeskySolve(L, rhs);
                var num = new double[np]; var den = new double[np];
                for (var i = 0; i < n; i++)
                {
                    var fit = 0.0; foreach (var (j, v) in feat[i]) fit += v * bn[j];
                    num[pid[i]] += w[i] * (y[i] - fit); den[pid[i]] += w[i];
                }
                var change = 0.0;
                for (var j = 0; j < p; j++) change = Math.Max(change, Math.Abs(bn[j] - beta[j]));
                for (var k = 0; k < np; k++) { var an = num[k] / (den[k] + Lambda); change = Math.Max(change, Math.Abs(an - a[k])); a[k] = an; }
                beta = bn;
                if (change < tol) break;
            }
            // Noise by bow style; weight each day by 1 / its bow's variance.
            var sum = new Dictionary<string, (double s, int c)>();
            for (var i = 0; i < n; i++)
            {
                var fit = a[pid[i]]; foreach (var (j, v) in feat[i]) fit += v * beta[j];
                var r = y[i] - fit;
                var cur = sum.GetValueOrDefault(days[i].Bow);
                sum[days[i].Bow] = (cur.s + r * r, cur.c + 1);
            }
            var variance = sum.ToDictionary(kv => kv.Key, kv => kv.Value.s / kv.Value.c);
            for (var i = 0; i < n; i++) w[i] = 1 / variance[days[i].Bow];
            spread = variance.ToDictionary(kv => kv.Key, kv => Math.Sqrt(kv.Value));
        }
        return new Result
        {
            Columns = cols, Beta = beta, Spread = spread,
            Ability = pidIndex.ToDictionary(kv => kv.Key, kv => a[kv.Value]),
        };
    }

    // ---- Tests (the same as the Python reference) -----------------------------------------------------------

    /// <summary>Hide 15% of entries (chosen by hash), fit the rest, predict the hidden days of archers seen elsewhere.</summary>
    public static double HoldoutRmse(IReadOnlyList<Day> days)
    {
        var hide = days.Select(d => Names.Fnv(d.Entry) % 100 < 15).ToArray();
        var train = days.Where((d, i) => !hide[i]).ToList();
        var test = days.Where((d, i) => hide[i]).ToList();
        var f = Fit(train);
        double sq = 0; var count = 0;
        foreach (var d in test)
        {
            if (!f.Ability.TryGetValue(d.Pid, out var ability) || f.Event(d.EventId) is not { } ev) continue;
            var z = ev + f.Offset(d.Bow, d.Class, d.Year, d.Champs == "3DS") + ability;
            var pred = 24.0 * d.Targets / (1 + Math.Exp(-z));
            sq += (pred - d.Score) * (pred - d.Score); count++;
        }
        return Math.Sqrt(sq / count);
    }

    /// <summary>
    /// Archers who shot 2+ bow styles, every 5th left out of the fit: their ability measured on each bow should agree.
    /// Returns the systematic bias per bow (in SDs of ability), as an rms weighted by archers (bows with 10+).
    /// </summary>
    public static double SwitcherBias(IReadOnlyList<Day> days)
    {
        var switchers = days.GroupBy(d => d.Person).Where(g => g.Select(d => d.Bow).Distinct().Count() > 1).Select(g => g.Key);
        var hold = switchers.Where(p => Names.Fnv(p) % 5 == 0).ToHashSet();
        var f = Fit(days.Where(d => !hold.Contains(d.Person)).ToList());
        var ability = days.Where(d => hold.Contains(d.Person) && f.Event(d.EventId) is not null)
            .Select(d => (d.Person, d.Bow, A: d.Y - f.Event(d.EventId)!.Value - f.Offset(d.Bow, d.Class, d.Year, d.Champs == "3DS")))
            .GroupBy(x => (x.Person, x.Bow)).Select(g => (g.Key.Person, g.Key.Bow, A: g.Average(x => x.A))).ToList();
        var mean = ability.GroupBy(x => x.Person).ToDictionary(g => g.Key, g => g.Average(x => x.A));
        var all = ability.Select(x => x.A).ToArray();
        var avg = all.Average();
        var sd = Math.Sqrt(all.Sum(v => (v - avg) * (v - avg)) / (all.Length - 1));
        var perBow = ability.GroupBy(x => x.Bow).Select(g => (Mean: g.Average(x => x.A - mean[x.Person]), N: g.Count())).Where(b => b.N >= 10).ToList();
        return Math.Sqrt(perBow.Sum(b => Math.Pow(b.Mean / sd, 2) * b.N) / perBow.Sum(b => b.N));
    }

    /// <summary>90% ranges for every factor: refit on archers drawn at random with replacement.</summary>
    public static Dictionary<(string, string), (double Low, double High)> Ranges(IReadOnlyList<Day> days, int resamples, int seed = 3)
    {
        var persons = days.Select(d => d.Person).Distinct().ToArray();
        var byPerson = days.GroupBy(d => d.Person).ToDictionary(g => g.Key, g => g.ToList());
        var rng = new Random(seed);
        var draws = new List<Result>();
        for (var r = 0; r < resamples; r++)
        {
            var sample = new List<Day>();
            var copies = new Dictionary<string, int>();
            for (var i = 0; i < persons.Length; i++)
            {
                var p = persons[rng.Next(persons.Length)];
                var k = copies[p] = copies.GetValueOrDefault(p) + 1;
                foreach (var d in byPerson[p]) sample.Add(d with { Person = $"{p}#{k}", Pid = $"{d.Pid}#{k}", Entry = $"{d.Entry}#{k}" });
            }
            draws.Add(Fit(sample));
        }
        var keys = draws.SelectMany(f => f.Columns.Where(c => c.Kind != "EVENT").Select(c => (c.Kind, c.Code))).Distinct();
        return keys.ToDictionary(k => k, k =>
        {
            var v = draws.Select(f => f.Get(k.Kind, k.Code)).ToArray();
            return (Linear.Percentile(v, 5), Linear.Percentile(v, 95));
        });
    }
}
