// Field Archery Scoring – server jobs (2.2.01). Run by Windows Task Scheduler on Odin every 15 minutes:
//
//   FieldArcheryJobs.exe            (run)  does work only when it's due: a "Fetch now" from the Admin menu, the weekly
//                                          check of nfas.net, or a target count an Admin changed. Otherwise exits at once.
//   FieldArcheryJobs.exe fetch            read nfas.net now (then refit if anything changed)
//   FieldArcheryJobs.exe fit              refit the handicap now
//   FieldArcheryJobs.exe verify           check the fit against the Python reference (Verify\*), no database needed
//
// Settings: appsettings.json, and appsettings.Production.json next to the exe (connection string, NameKeySecret).
// Exit code 0 = fine, 1 = a problem (shown in red, and recorded in the Admin menu for scheduled runs).

using System.Diagnostics;
using System.IO.Compression;
using System.Text.Json;
using FieldArcheryJobs;
using Microsoft.Extensions.Configuration;

var config = new ConfigurationBuilder()
    .SetBasePath(AppContext.BaseDirectory)
    .AddJsonFile("appsettings.json", optional: false)
    .AddJsonFile("appsettings.Production.json", optional: true)
    .Build();
var mode = args.FirstOrDefault()?.ToLowerInvariant() ?? "run";
using var cts = new CancellationTokenSource();
Console.CancelKeyPress += (_, e) => { e.Cancel = true; cts.Cancel(); };
var ct = cts.Token;

void Ok(string text) { Console.ForegroundColor = ConsoleColor.Green; Console.WriteLine($"OK: {text}"); Console.ResetColor(); }
void Problem(string text) { Console.ForegroundColor = ConsoleColor.Red; Console.WriteLine($"PROBLEM: {text}"); Console.ResetColor(); }
void Info(string text) => Console.WriteLine($"{DateTime.Now:HH:mm:ss} {text}");

if (mode == "verify") return Verify.Run(Path.Combine(AppContext.BaseDirectory, "Verify"), Ok, Problem, Info);

var connection = config.GetConnectionString("FieldArchery");
var secret = config["Handicap:NameKeySecret"];
if (string.IsNullOrWhiteSpace(connection) || connection.Contains("PUT_THE_")) { Problem("The connection string is missing: copy appsettings.Production.json.example to appsettings.Production.json and fill it in."); return 1; }
if (string.IsNullOrWhiteSpace(secret) || secret.StartsWith("PUT_A_")) { Problem("Handicap:NameKeySecret is missing from appsettings.Production.json (a long random string, never changed afterwards)."); return 1; }

var store = new Store(connection);
if (await store.SchemaVersionAsync(ct) < 7) { Problem("The database isn't at schema version 7 yet: run server/sql/011_handicap.sql first."); return 1; }
await store.TidyStaleAsync(ct);

var requests = await store.TakeRequestsAsync(ct);
var fetchDays = config.GetValue("Handicap:FetchEveryDays", 7);
var lastFetch = await store.LastFetchAsync(ct);
var doFetch = mode == "fetch" || requests.Any(r => r.Kind == "FETCH") || (mode == "run" && (lastFetch is null || lastFetch < DateTime.UtcNow.AddDays(-fetchDays)));
var doFit = mode == "fit" || requests.Any(r => r.Kind == "FIT");
var ids = requests.Select(r => r.RequestId).ToList();
// A target count changed by an Admin (or a fit that never happened) shows as data the latest model wasn't fitted on.
if (!doFetch && !doFit)
{
    var latest = await store.LatestModelAsync(ct);
    var events = await store.EventsAsync(ct);
    if (events.Count == 0 || latest is not null && latest.DataHash.AsSpan().SequenceEqual(Store.DataHash(events))) return 0;   // nothing to do
    doFit = true;
}
if (ids.Count == 0) ids.Add(await store.StartScheduledAsync(doFetch ? "FETCH" : "FIT", ct));

var outcome = new List<string>();
var sw = Stopwatch.StartNew();
try
{
    if (doFetch)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(60) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd(config["Nfas:UserAgent"]);
        var site = new NfasSite(http, config["Nfas:ResultsUrl"]!, TimeSpan.FromMilliseconds(config.GetValue("Nfas:DelayMilliseconds", 500)));
        var known = (await store.EventsAsync(ct)).ToDictionary(e => (e.Champs, (int)e.Year));
        var thisYear = DateTime.UtcNow.Year;
        int read = 0, changed = 0;
        foreach (var champs in new[] { "NATIONALS", "3DS" })
            for (var year = config.GetValue("Nfas:FirstYear", 2002); year <= thisYear; year++)
            {
                // New years and the last two every time; years with nothing monthly; everything else once a year.
                var due = !known.TryGetValue((champs, year), out var ev) || year >= thisYear - 1
                          || (ev.Status == "NONE" && ev.CheckedUtc < DateTime.UtcNow.AddDays(-30))
                          || ev.CheckedUtc < DateTime.UtcNow.AddDays(-365);
                if (!due) continue;
                var results = await site.ResultsAsync(champs, year, ct);
                read++;
                if (await store.SaveEventAsync(champs, year, results, secret, ct))
                {
                    changed++;
                    Info($"{champs} {year}: {results.Count} results (changed)");
                }
            }
        outcome.Add($"Read {read} championship(s) from nfas.net, {changed} new or changed.");
        if (changed > 0) doFit = true;
    }

    // Target counts (recomputed every run: the rule compares with other years).
    var entries = await store.EntriesAsync(ct);
    var decisions = Targets.Decide(entries);
    await store.SetTargetsAsync(decisions, ct);
    var eventsNow = await store.EventsAsync(ct);
    var dataHash = Store.DataHash(eventsNow);
    var latestModel = await store.LatestModelAsync(ct);
    if (latestModel is null || !latestModel.DataHash.AsSpan().SequenceEqual(dataHash)) doFit = true;
    var doubtful = eventsNow.Where(e => e.TargetsSource == "DOUBTFUL").Select(e => $"{e.Champs} {e.Year}").ToList();
    if (doubtful.Count > 0) outcome.Add($"Target count doubtful (check in Admin → Handicap): {string.Join(", ", doubtful)}.");

    if (doFit)
    {
        var targets = eventsNow.Where(e => e.Status == "RESULTS" && e.TargetsPerDay is not null)
            .ToDictionary(e => $"{e.Champs} {e.Year}", e => (int)e.TargetsPerDay!.Value);
        var days = HandicapFit.Days(entries, targets);
        if (days.Count == 0) throw new InvalidOperationException("No results with target counts to fit on yet.");
        Info($"Fitting on {days.Count} day scores…");
        var fit = HandicapFit.Fit(days);
        var rmse = HandicapFit.HoldoutRmse(days);
        var bias = HandicapFit.SwitcherBias(days);
        Info($"Tests: hidden results {rmse:0.0} points a day, bow bias {bias:0.000}. Working out ranges…");
        var ranges = HandicapFit.Ranges(days, config.GetValue("Handicap:Bootstraps", 20));
        var active = await store.ActiveModelAsync(ct);
        var sane = fit.Beta.All(double.IsFinite) && fit.Columns.Where(c => c.Kind != "EVENT").Select(c => fit.Get(c.Kind, c.Code)).All(v => Math.Abs(v) < 4);
        var passes = sane && (active is null || (rmse <= active.HoldoutRmse * 1.03 && bias <= Math.Max(active.SwitcherBias * 1.5, 0.05)));
        var note = active is null ? "First handicap model." :
            passes ? $"Tests no worse than model {active.ModelId} ({active.HoldoutRmse:0.0} pts, bias {active.SwitcherBias:0.000})." :
            !sane ? "Some factors look wrong (not finite or very large): left waiting." :
            $"Tests worse than model {active.ModelId} ({active.HoldoutRmse:0.0} pts, bias {active.SwitcherBias:0.000}): left waiting for an Owner to activate.";
        var id = await store.SaveModelAsync(fit, ranges, dataHash, entries.Max(e => e.Year), targets.Count, days.Count,
            days.Select(d => d.Person).Distinct().Count(), rmse, bias, passes, note, ct);
        outcome.Add($"Model {id}: {(passes ? "ACTIVE" : "waiting")} – hidden results {rmse:0.0} points a day, bow bias {bias:0.000}. {note}");
    }
    else outcome.Add("Nothing changed, so no refit.");

    var text = string.Join(" ", outcome) + $" ({sw.Elapsed.TotalMinutes:0.0} min)";
    await store.FinishAsync(ids, text, failed: false, ct);
    Ok(text);
    return 0;
}
catch (Exception ex)
{
    var text = $"{string.Join(" ", outcome)} Failed: {ex.Message}".Trim();
    try { await store.FinishAsync(ids, text, failed: true, CancellationToken.None); } catch { /* the database may be what failed */ }
    Problem(text);
    Console.WriteLine(ex);
    return 1;
}

/// <summary>Checks the C# fit against the Python reference results (Verify\handicap-reference.json).</summary>
static class Verify
{
    public static int Run(string folder, Action<string> ok, Action<string> problem, Action<string> info)
    {
        var problems = 0;
        void Check(string what, bool good, string detail = "") { if (good) ok(what); else { problem($"{what} {detail}"); problems++; } }

        // Name tidying (archers are matched on this).
        Check("names tidied", Names.Normalise("Pete Mc Dermott") == "pete mcdermott" && Names.Normalise("Anne-Marie  O'Neill") == "anne marie oneill",
              $"('{Names.Normalise("Pete Mc Dermott")}', '{Names.Normalise("Anne-Marie  O'Neill")}')");
        Check("FNV hash", Names.Fnv("a") == 0xE40C292C && Names.Fnv("NATIONALS 2019|abc|M") == 0x27579FA3, $"({Names.Fnv("a"):X8})");

        var entries = new List<Entry>();
        using (var gz = new GZipStream(File.OpenRead(Path.Combine(folder, "handicap-verify.tsv.gz")), CompressionMode.Decompress))
        using (var reader = new StreamReader(gz))
        {
            var header = reader.ReadLine()!.Split('\t');
            int Col(string n) => Array.IndexOf(header, n);
            string? line;
            while ((line = reader.ReadLine()) is not null)
            {
                var f = line.Split('\t');
                entries.Add(new Entry(f[Col("champs")], int.Parse(f[Col("year")]), f[Col("bow")], f[Col("sex")], f[Col("age")],
                    $"{f[Col("pk")]}|{f[Col("sex")]}", int.Parse(f[Col("d1")]), int.Parse(f[Col("d2")]), int.Parse(f[Col("total")]), int.Parse(f[Col("s24")])));
            }
        }
        info($"{entries.Count} results read");
        using var refDoc = JsonDocument.Parse(File.ReadAllText(Path.Combine(folder, "handicap-reference.json")));
        var root = refDoc.RootElement;

        var decisions = Targets.Decide(entries);
        var wrong = root.GetProperty("targets").EnumerateObject()
            .Where(p => !decisions.TryGetValue(p.Name, out var d) || d.TargetsPerDay != p.Value.GetProperty("targets").GetInt32() || d.Source != p.Value.GetProperty("source").GetString())
            .Select(p => p.Name).ToList();
        Check($"target counts for {decisions.Count} championships match", wrong.Count == 0 && decisions.Count == root.GetProperty("targets").EnumerateObject().Count(), string.Join(", ", wrong));

        var targets = decisions.ToDictionary(kv => kv.Key, kv => kv.Value.TargetsPerDay);
        var days = HandicapFit.Days(entries, targets);
        Check("day scores", days.Count == root.GetProperty("days").GetInt32(), $"({days.Count} against {root.GetProperty("days").GetInt32()})");
        var sw = Stopwatch.StartNew();
        var fit = HandicapFit.Fit(days);
        info($"fit took {sw.Elapsed.TotalSeconds:0.0} s");
        double worst = 0; var worstName = "";
        foreach (var p in root.GetProperty("factors").EnumerateObject())
        {
            var kind = p.Name[..p.Name.IndexOf(':')]; var code = p.Name[(p.Name.IndexOf(':') + 1)..].Replace("|F", "");
            var d = Math.Abs(fit.Get(kind, code) - p.Value.GetDouble());
            if (d > worst) { worst = d; worstName = p.Name; }
        }
        Check("factors match the reference (within 0.002)", worst < 0.002, $"(worst {worstName}: off by {worst:0.0000})");
        double worstEvent = root.GetProperty("events").EnumerateObject().Max(p => Math.Abs((fit.Event(p.Name) ?? double.NaN) - p.Value.GetDouble()));
        Check("championship levels match (within 0.002)", worstEvent < 0.002, $"(off by {worstEvent:0.0000})");
        double worstSpread = root.GetProperty("spread").EnumerateObject().Max(p => Math.Abs(fit.Spread[p.Name] - p.Value.GetDouble()));
        Check("noise by bow matches (within 0.002)", worstSpread < 0.002, $"(off by {worstSpread:0.0000})");
        var rmse = HandicapFit.HoldoutRmse(days);
        Check($"hidden-results test {rmse:0.0} points a day", Math.Abs(rmse - root.GetProperty("holdoutRmse").GetDouble()) < 0.5, $"(reference {root.GetProperty("holdoutRmse").GetDouble():0.0})");
        var bias = HandicapFit.SwitcherBias(days);
        Check($"bow switcher test {bias:0.000}", Math.Abs(bias - root.GetProperty("switcherBias").GetDouble()) < 0.01, $"(reference {root.GetProperty("switcherBias").GetDouble():0.000})");

        Console.WriteLine();
        if (problems == 0) ok("The C# handicap fit matches the Python reference.");
        else problem($"{problems} problem(s).");
        return problems == 0 ? 0 : 1;
    }

}
