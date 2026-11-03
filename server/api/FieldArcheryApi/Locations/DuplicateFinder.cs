using System.Text;
using Dapper;
using Microsoft.Data.SqlClient;

namespace FieldArcheryApi.Locations;

/// <summary>
/// Finds pairs of live shoot locations that might be the same place: within the catchment of each
/// other, or with the same or a similar name (ignoring case, punctuation and words like "Archers",
/// "Bowmen", "Club", "Field"). Pairs an Admin has approved as different are left out.
/// </summary>
public static class DuplicateFinder
{
    public sealed record Loc(Guid LocationId, string Name, decimal Latitude, decimal Longitude, bool IsPrivate, string? CreatedBy, int Rounds);

    public sealed record Pair(Loc A, Loc B, int DistanceMetres, IReadOnlyList<string> Reasons);

    /// <summary>Words that don't tell clubs apart.</summary>
    private static readonly HashSet<string> CommonWords =
    [
        "the", "of", "and", "at", "club", "clubs", "archers", "archery", "bowmen", "bowman", "field", "fa", "fac",
        "society", "group", "ground", "grounds", "company", "co", "ltd", "nfas", "target", "targets", "range", "wood", "woods",
    ];

    public static async Task<List<Pair>> FindAsync(SqlConnection conn, int catchmentMetres, CancellationToken ct)
    {
        var locs = (await conn.QueryAsync<Loc>(new CommandDefinition("""
            SELECT l.LocationId, l.Name, l.Latitude, l.Longitude, l.IsPrivate, a.Username AS CreatedBy,
                   (SELECT COUNT(*) FROM dbo.Round r WHERE r.LocationId = l.LocationId AND r.IsDeleted = 0) AS Rounds
            FROM dbo.ShootLocation l
            LEFT JOIN dbo.Archer a ON a.ArcherId = l.CreatedById
            WHERE l.MergedIntoId IS NULL;
            """, cancellationToken: ct))).ToList();
        var approved = (await conn.QueryAsync<(Guid Low, Guid High)>(new CommandDefinition(
            "SELECT LocationLowId, LocationHighId FROM dbo.LocationPairApproval;", cancellationToken: ct)))
            .SelectMany(p => new[] { (p.Low, p.High), (p.High, p.Low) })
            .ToHashSet();

        var keys = locs.ToDictionary(l => l.LocationId, l => Key(l.Name));
        var pairs = new List<Pair>();
        for (var i = 0; i < locs.Count; i++)
        {
            for (var j = i + 1; j < locs.Count; j++)
            {
                var a = locs[i];
                var b = locs[j];
                if (approved.Contains((a.LocationId, b.LocationId))) continue;

                var metres = (int)Math.Round(LocationEndpoints.Metres(
                    (double)a.Latitude, (double)a.Longitude, (double)b.Latitude, (double)b.Longitude));
                var reasons = new List<string>();
                if (metres <= catchmentMetres) reasons.Add("nearby");
                var ka = keys[a.LocationId];
                var kb = keys[b.LocationId];
                if (ka == kb) reasons.Add("same_name");
                else if (Similar(ka, kb)) reasons.Add("similar_name");

                if (reasons.Count > 0) pairs.Add(new Pair(a, b, metres, reasons));
            }
        }
        // Most likely duplicates first: both reasons, then nearest.
        return pairs.OrderByDescending(p => p.Reasons.Count).ThenBy(p => p.DistanceMetres).ToList();
    }

    /// <summary>"The Bowmen of Foo-Bar!" → "foobar". Falls back to all the words if they're all common ones.</summary>
    public static string Key(string name)
    {
        var words = new List<string>();
        var sb = new StringBuilder();
        foreach (var c in name.ToLowerInvariant() + " ")
        {
            if (char.IsLetterOrDigit(c)) { sb.Append(c); continue; }
            if (c is '-' or '\'' or '’') continue;   // "Foo-Bar", "St. Mary's" → one word
            if (sb.Length > 0) { words.Add(sb.ToString()); sb.Clear(); }
        }
        var significant = words.Where(w => !CommonWords.Contains(w)).ToList();
        return string.Concat(significant.Count > 0 ? significant : words);
    }

    /// <summary>One contains the other (4+ letters), or at least 80% the same by edit distance.</summary>
    public static bool Similar(string a, string b)
    {
        if (a.Length == 0 || b.Length == 0) return false;
        if (Math.Min(a.Length, b.Length) >= 4 && (a.Contains(b) || b.Contains(a))) return true;
        var distance = Levenshtein(a, b);
        return 1.0 - (double)distance / Math.Max(a.Length, b.Length) >= 0.8;
    }

    private static int Levenshtein(string a, string b)
    {
        var prev = new int[b.Length + 1];
        var cur = new int[b.Length + 1];
        for (var j = 0; j <= b.Length; j++) prev[j] = j;
        for (var i = 1; i <= a.Length; i++)
        {
            cur[0] = i;
            for (var j = 1; j <= b.Length; j++)
            {
                var cost = a[i - 1] == b[j - 1] ? 0 : 1;
                cur[j] = Math.Min(Math.Min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            (prev, cur) = (cur, prev);
        }
        return prev[b.Length];
    }
}
