using System.Data;
using System.Security.Cryptography;
using System.Text;
using Dapper;
using Microsoft.Data.SqlClient;

namespace FieldArcheryJobs;

/// <summary>Reads and writes the handicap tables (schema version 7, server/sql/011_handicap.sql).</summary>
public sealed class Store(string connectionString)
{
    public sealed record EventRow(int EventId, string Champs, short Year, string Status, int ResultCount, byte? TargetsPerDay,
                                  string? TargetsSource, byte[]? ContentHash, DateTime CheckedUtc);

    public sealed record ModelRow(int ModelId, string Status, byte[] DataHash, double HoldoutRmse, double SwitcherBias);

    private async Task<SqlConnection> OpenAsync(CancellationToken ct)
    {
        var c = new SqlConnection(connectionString);
        await c.OpenAsync(ct);
        return c;
    }

    public async Task<int> SchemaVersionAsync(CancellationToken ct)
    {
        await using var c = await OpenAsync(ct);
        return await c.ExecuteScalarAsync<int?>(new CommandDefinition("SELECT MAX(Version) FROM dbo.SchemaVersion;", cancellationToken: ct)) ?? 0;
    }

    public async Task<List<EventRow>> EventsAsync(CancellationToken ct)
    {
        await using var c = await OpenAsync(ct);
        return (await c.QueryAsync<EventRow>(new CommandDefinition("""
            SELECT EventId, Champs, [Year], Status, ResultCount, TargetsPerDay, TargetsSource, ContentHash, CheckedUtc
            FROM dbo.ChampEvent ORDER BY Champs, [Year];
            """, cancellationToken: ct))).ToList();
    }

    /// <summary>Fingerprint of a championship's results, to tell whether anything changed.</summary>
    public static byte[] Hash(IEnumerable<(SiteResult R, byte[] Key)> rows)
    {
        var sb = new StringBuilder();
        foreach (var (r, k) in rows.OrderBy(x => x.R.Style, StringComparer.Ordinal).ThenBy(x => x.R.Class, StringComparer.Ordinal)
                     .ThenBy(x => x.R.Position).ThenBy(x => Convert.ToHexString(x.Key), StringComparer.Ordinal))
            sb.Append($"{r.Style}|{r.Class}|{r.Position}|{Convert.ToHexString(k)}|{r.Club}|{r.Day1}|{r.Day2}|{r.Total}|{r.Count24}\n");
        return SHA256.HashData(Encoding.UTF8.GetBytes(sb.ToString()));
    }

    /// <summary>
    /// Records what nfas.net shows for one championship. Results are replaced only when they've changed.
    /// Returns true if they changed (new or different).
    /// </summary>
    public async Task<bool> SaveEventAsync(string champs, int year, List<SiteResult> results, string secret, CancellationToken ct)
    {
        var keyed = results.Select(r => (R: r, Key: Names.Key(r.Name, secret))).ToList();
        var status = results.Count == 0 ? "NONE" : results.All(r => r.Total == 0) ? "PLACINGS" : "RESULTS";
        var hash = results.Count == 0 ? null : Hash(keyed);

        await using var c = await OpenAsync(ct);
        await using var tx = (SqlTransaction)await c.BeginTransactionAsync(ct);
        var current = await c.QuerySingleOrDefaultAsync<EventRow>(new CommandDefinition(
            "SELECT EventId, Champs, [Year], Status, ResultCount, TargetsPerDay, TargetsSource, ContentHash, CheckedUtc FROM dbo.ChampEvent WITH (UPDLOCK) WHERE Champs = @champs AND [Year] = @year;",
            new { champs, year }, tx, cancellationToken: ct));
        var changed = current is null ? results.Count > 0 : !SameHash(current.ContentHash, hash);
        int eventId;
        if (current is null)
        {
            eventId = await c.ExecuteScalarAsync<int>(new CommandDefinition("""
                INSERT dbo.ChampEvent (Champs, [Year], Status, ResultCount, ContentHash, CheckedUtc, ChangedUtc)
                OUTPUT inserted.EventId
                VALUES (@champs, @year, @status, @count, @hash, SYSUTCDATETIME(), CASE WHEN @changed = 1 THEN SYSUTCDATETIME() END);
                """, new { champs, year, status, count = results.Count, hash, changed }, tx, cancellationToken: ct));
        }
        else
        {
            eventId = current.EventId;
            await c.ExecuteAsync(new CommandDefinition("""
                UPDATE dbo.ChampEvent
                SET Status = @status, ResultCount = @count, ContentHash = @hash, CheckedUtc = SYSUTCDATETIME(),
                    ChangedUtc = CASE WHEN @changed = 1 THEN SYSUTCDATETIME() ELSE ChangedUtc END
                WHERE EventId = @eventId;
                """, new { eventId, status, count = results.Count, hash, changed }, tx, cancellationToken: ct));
        }
        if (changed)
        {
            await c.ExecuteAsync(new CommandDefinition("DELETE dbo.ChampResult WHERE EventId = @eventId;", new { eventId }, tx, cancellationToken: ct));
            if (results.Count > 0)
            {
                var table = new DataTable();
                foreach (var (name, type) in new (string, Type)[] {
                             ("EventId", typeof(int)), ("Style", typeof(string)), ("Class", typeof(string)), ("Position", typeof(short)),
                             ("NameKey", typeof(byte[])), ("Club", typeof(string)), ("Day1", typeof(short)), ("Day2", typeof(short)),
                             ("Total", typeof(short)), ("Count24", typeof(short)) })
                    table.Columns.Add(name, type);
                foreach (var (r, k) in keyed)
                    table.Rows.Add(eventId, r.Style, r.Class, (short)r.Position, k, r.Club.Length > 100 ? r.Club[..100] : r.Club,
                                   (short)r.Day1, (short)r.Day2, (short)r.Total, r.Count24 is { } n ? (object)(short)n : DBNull.Value);
                // CheckConstraints: checks the table's rules as it inserts, so the read/write login can do it (without the
                // hint SQL Server wants ALTER TABLE permission, which fieldarchery_api deliberately doesn't have).
                using var bulk = new SqlBulkCopy(c, SqlBulkCopyOptions.CheckConstraints, tx) { DestinationTableName = "dbo.ChampResult" };
                foreach (DataColumn col in table.Columns) bulk.ColumnMappings.Add(col.ColumnName, col.ColumnName);
                await bulk.WriteToServerAsync(table, ct);
            }
        }
        await tx.CommitAsync(ct);
        return changed;
    }

    private static bool SameHash(byte[]? a, byte[]? b) => a is null ? b is null : b is not null && a.AsSpan().SequenceEqual(b);

    /// <summary>
    /// Every stored result with scores, as the fit sees it. Person = archer key + sex; a name shared by two archers in
    /// one championship is told apart by club everywhere.
    /// </summary>
    public async Task<List<Entry>> EntriesAsync(CancellationToken ct)
    {
        await using var c = await OpenAsync(ct);
        var rows = (await c.QueryAsync<ResultRow>(
            new CommandDefinition("""
                SELECT e.Champs, e.[Year], r.Style, r.Class, r.NameKey, r.Club, r.Day1, r.Day2, r.Total, r.Count24
                FROM dbo.ChampResult r JOIN dbo.ChampEvent e ON e.EventId = r.EventId
                WHERE e.Status = 'RESULTS';
                """, cancellationToken: ct))).ToList();
        var key = rows.Select(r => Convert.ToHexString(r.NameKey)).ToArray();
        var split = rows.Select((r, i) => (r.Champs, r.Year, Key: key[i])).GroupBy(x => x).Where(g => g.Count() > 1).Select(g => g.Key.Key).ToHashSet();
        return rows.Select((r, i) =>
        {
            var (sex, age) = ClassParts(r.Class);
            var person = (split.Contains(key[i]) ? $"{key[i]}@{Names.ClubKey(r.Club)}" : key[i]) + "|" + sex;
            return new Entry(r.Champs, r.Year, r.Style, sex, age, person, r.Day1, r.Day2, r.Total, r.Count24 ?? 0);
        }).ToList();
    }

    public sealed record ResultRow(string Champs, short Year, string Style, string Class, byte[] NameKey, string Club,
                                    short Day1, short Day2, short Total, short? Count24);

    public static (string Sex, string Age) ClassParts(string cls) => cls switch
    {
        "G" => ("M", "ADULT"), "L" => ("F", "ADULT"), "JB" => ("M", "JUNIOR"), "JG" => ("F", "JUNIOR"),
        "CB" => ("M", "CUB"), "CG" => ("F", "CUB"), _ => throw new ArgumentException($"Unknown class {cls}"),
    };

    /// <summary>Saves the job's target counts; ones an Admin set by hand are left alone.</summary>
    public async Task SetTargetsAsync(Dictionary<string, Targets.Decision> decisions, CancellationToken ct)
    {
        await using var c = await OpenAsync(ct);
        foreach (var (id, d) in decisions)
        {
            var parts = id.Split(' ');
            await c.ExecuteAsync(new CommandDefinition("""
                UPDATE dbo.ChampEvent SET TargetsPerDay = @t, TargetsSource = @source, TargetsNote = @note, TargetsSetById = NULL
                WHERE Champs = @champs AND [Year] = @year AND (TargetsSource IS NULL OR TargetsSource <> 'ADMIN')
                  AND (TargetsPerDay IS NULL OR TargetsPerDay <> @t OR TargetsSource <> @source OR TargetsNote <> @note);
                """, new { t = (byte)d.TargetsPerDay, source = d.Source, note = d.Note, champs = parts[0], year = short.Parse(parts[1]) }, cancellationToken: ct));
        }
    }

    /// <summary>What a fit would be based on: every championship's results fingerprint and target count.</summary>
    public static byte[] DataHash(IEnumerable<EventRow> events)
    {
        var s = string.Join("\n", events.Where(e => e.Status == "RESULTS").OrderBy(e => e.Champs).ThenBy(e => e.Year)
            .Select(e => $"{e.Champs}|{e.Year}|{Convert.ToHexString(e.ContentHash ?? [])}|{e.TargetsPerDay}"));
        return SHA256.HashData(Encoding.UTF8.GetBytes(s));
    }

    public async Task<ModelRow?> LatestModelAsync(CancellationToken ct)
    {
        await using var c = await OpenAsync(ct);
        return await c.QuerySingleOrDefaultAsync<ModelRow>(new CommandDefinition(
            "SELECT TOP (1) ModelId, Status, DataHash, HoldoutRmse, SwitcherBias FROM dbo.HandicapModel ORDER BY ModelId DESC;", cancellationToken: ct));
    }

    public async Task<ModelRow?> ActiveModelAsync(CancellationToken ct)
    {
        await using var c = await OpenAsync(ct);
        return await c.QuerySingleOrDefaultAsync<ModelRow>(new CommandDefinition(
            "SELECT ModelId, Status, DataHash, HoldoutRmse, SwitcherBias FROM dbo.HandicapModel WHERE Status = 'ACTIVE';", cancellationToken: ct));
    }

    /// <summary>Saves a fitted model and its factors; if <paramref name="activate"/>, it replaces the active one.</summary>
    public async Task<int> SaveModelAsync(HandicapFit.Result fit, Dictionary<(string, string), (double Low, double High)> ranges,
        byte[] dataHash, int throughYear, int events, int days, int archers, double rmse, double bias, bool activate, string note, CancellationToken ct)
    {
        await using var c = await OpenAsync(ct);
        await using var tx = (SqlTransaction)await c.BeginTransactionAsync(ct);
        if (activate)
            await c.ExecuteAsync(new CommandDefinition("UPDATE dbo.HandicapModel SET Status = 'RETIRED' WHERE Status = 'ACTIVE';", transaction: tx, cancellationToken: ct));
        var id = await c.ExecuteScalarAsync<int>(new CommandDefinition("""
            INSERT dbo.HandicapModel (Status, DataHash, DataThroughYear, Events, DayScores, Archers, HoldoutRmse, SwitcherBias, Note, ActivatedUtc)
            OUTPUT inserted.ModelId
            VALUES (@status, @dataHash, @throughYear, @events, @days, @archers, @rmse, @bias, @note, CASE WHEN @activate = 1 THEN SYSUTCDATETIME() END);
            """, new { status = activate ? "ACTIVE" : "CANDIDATE", dataHash, throughYear, events, days, archers, rmse, bias, note, activate }, tx, cancellationToken: ct));
        var factors = fit.Columns.Select((col, i) => (col.Kind, col.Code, Value: fit.Beta[i]))
            .Concat(fit.Spread.Select(kv => (Kind: "SPREAD", Code: kv.Key, Value: kv.Value)))
            .Select(f => new
            {
                ModelId = id, f.Kind, f.Code, f.Value,
                Low = ranges.TryGetValue((f.Kind, f.Code), out var r) ? r.Low : (double?)null,
                High = ranges.TryGetValue((f.Kind, f.Code), out var r2) ? r2.High : (double?)null,
            });
        await c.ExecuteAsync(new CommandDefinition(
            "INSERT dbo.HandicapFactor (ModelId, Kind, Code, Value, Low, High) VALUES (@ModelId, @Kind, @Code, @Value, @Low, @High);",
            factors, tx, cancellationToken: ct));
        await tx.CommitAsync(ct);
        return id;
    }

    // ---- Job runs and "Fetch now" requests ----------------------------------------------------------------

    public sealed record Request(int RequestId, string Kind, Guid? RequestedById);

    /// <summary>Claims every waiting request (marks them started).</summary>
    public async Task<List<Request>> TakeRequestsAsync(CancellationToken ct)
    {
        await using var c = await OpenAsync(ct);
        return (await c.QueryAsync<Request>(new CommandDefinition("""
            UPDATE dbo.HandicapJobRequest SET StartedUtc = SYSUTCDATETIME()
            OUTPUT inserted.RequestId, inserted.Kind, inserted.RequestedById
            WHERE StartedUtc IS NULL;
            """, cancellationToken: ct))).ToList();
    }

    /// <summary>A run the schedule started by itself (so the Admin menu shows it).</summary>
    public async Task<int> StartScheduledAsync(string kind, CancellationToken ct)
    {
        await using var c = await OpenAsync(ct);
        return await c.ExecuteScalarAsync<int>(new CommandDefinition(
            "INSERT dbo.HandicapJobRequest (Kind, StartedUtc) OUTPUT inserted.RequestId VALUES (@kind, SYSUTCDATETIME());",
            new { kind }, cancellationToken: ct));
    }

    public async Task FinishAsync(IEnumerable<int> requestIds, string outcome, bool failed, CancellationToken ct)
    {
        await using var c = await OpenAsync(ct);
        await c.ExecuteAsync(new CommandDefinition(
            "UPDATE dbo.HandicapJobRequest SET FinishedUtc = SYSUTCDATETIME(), Outcome = @outcome, Failed = @failed WHERE RequestId IN @ids;",
            new { ids = requestIds.ToArray(), outcome = outcome.Length > 1000 ? outcome[..1000] : outcome, failed }, cancellationToken: ct));
    }

    /// <summary>When nfas.net was last read successfully (any FETCH run).</summary>
    public async Task<DateTime?> LastFetchAsync(CancellationToken ct)
    {
        await using var c = await OpenAsync(ct);
        return await c.ExecuteScalarAsync<DateTime?>(new CommandDefinition(
            "SELECT MAX(FinishedUtc) FROM dbo.HandicapJobRequest WHERE Kind = 'FETCH' AND Failed = 0 AND FinishedUtc IS NOT NULL;", cancellationToken: ct));
    }

    /// <summary>Requests started long ago that never finished (the job was stopped): mark them failed so they don't block.</summary>
    public async Task TidyStaleAsync(CancellationToken ct)
    {
        await using var c = await OpenAsync(ct);
        await c.ExecuteAsync(new CommandDefinition("""
            UPDATE dbo.HandicapJobRequest SET FinishedUtc = SYSUTCDATETIME(), Failed = 1, Outcome = N'The job stopped before it finished.'
            WHERE StartedUtc IS NOT NULL AND FinishedUtc IS NULL AND StartedUtc < DATEADD(HOUR, -3, SYSUTCDATETIME());
            """, cancellationToken: ct));
    }
}
