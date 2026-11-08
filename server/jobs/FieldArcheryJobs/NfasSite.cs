using System.Net;
using System.Text.RegularExpressions;

namespace FieldArcheryJobs;

/// <summary>One archer's line in a championship's results, as published.</summary>
public sealed record SiteResult(string Style, string Class, int Position, string Name, string Club, int Day1, int Day2, int Total, int? Count24);

/// <summary>
/// Reads the NFAS results pages: https://nfas.net/championships/results?y=2019&champs=Nationals&style=UL&class=
/// One page per year, championship and bow style (the all-styles page is cut short). Each page has a title row
/// "2019 - Nationals - Unlimited - Gents" before each class, then "# | Archer | Club | Day 1 | Day 2 | Total | (24s)".
/// </summary>
public sealed partial class NfasSite(HttpClient http, string resultsUrl, TimeSpan delay)
{
    public static readonly string[] Styles = ["AFB", "BB", "BH", "CL", "FS", "HT", "LB", "PV", "TB", "TD", "TXB", "UL", "XB"];

    /// <summary>The site's championship names: NATIONALS → "Nationals", 3DS → "3Ds".</summary>
    public static string SiteChamps(string champs) => champs == "3DS" ? "3Ds" : "Nationals";

    public static readonly Dictionary<string, string> ClassCodes = new()
    {
        ["Gents"] = "G", ["Ladies"] = "L", ["Junior Boys"] = "JB", ["Junior Girls"] = "JG", ["U12 Boys"] = "CB", ["U12 Girls"] = "CG",
    };

    /// <summary>Every bow style's results for one championship and year (empty when none are published).</summary>
    public async Task<List<SiteResult>> ResultsAsync(string champs, int year, CancellationToken ct)
    {
        var all = new List<SiteResult>();
        foreach (var style in Styles)
        {
            var url = $"{resultsUrl}?y={year}&champs={SiteChamps(champs)}&style={style}&class=";
            var html = await http.GetStringAsync(url, ct);
            all.AddRange(Parse(html, style));
            await Task.Delay(delay, ct);   // be polite: one page at a time
        }
        return all;
    }

    public static List<SiteResult> Parse(string html, string style)
    {
        // Only the page's main content (the footer has its own small tables).
        var start = html.IndexOf("<main", StringComparison.OrdinalIgnoreCase);
        var end = html.IndexOf("</main>", StringComparison.OrdinalIgnoreCase);
        if (start >= 0 && end > start) html = html[start..end];

        var rows = new List<SiteResult>();
        string? cls = null;
        foreach (Match tr in Row().Matches(html))
        {
            var cells = Cell().Matches(tr.Groups[1].Value).Select(c => Text(c.Groups[1].Value)).ToList();
            if (cells.Count == 0) continue;
            var title = Title().Match(string.Join(' ', cells));
            if (title.Success && cells.Count < 3)
            {
                cls = ClassCodes.GetValueOrDefault(title.Groups[4].Value.Trim());
                continue;
            }
            if (cls is null || cells[0] == "#" || cells.Count < 6) continue;
            if (!int.TryParse(cells[0], out var pos)) continue;
            int Num(int i) => i < cells.Count && int.TryParse(cells[i], out var v) ? v : 0;
            int? c24 = cells.Count > 6 && int.TryParse(cells[6].Trim('(', ')', ' '), out var n24) ? n24 : null;
            rows.Add(new SiteResult(style, cls, pos, cells[1], cells[2], Num(3), Num(4), Num(5), c24));
        }
        return rows;
    }

    private static string Text(string html) => WebUtility.HtmlDecode(Tag().Replace(html, "")).Trim();

    [GeneratedRegex(@"<tr\b[^>]*>(.*?)</tr>", RegexOptions.Singleline | RegexOptions.IgnoreCase)] private static partial Regex Row();
    [GeneratedRegex(@"<t[dh]\b[^>]*>(.*?)</t[dh]>", RegexOptions.Singleline | RegexOptions.IgnoreCase)] private static partial Regex Cell();
    [GeneratedRegex(@"<[^>]+>")] private static partial Regex Tag();
    [GeneratedRegex(@"^(\d{4}) - (.+?) - (.+?) - (.+)$")] private static partial Regex Title();
}
