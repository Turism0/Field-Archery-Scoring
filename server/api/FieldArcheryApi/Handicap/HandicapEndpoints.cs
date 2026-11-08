using System.Security.Claims;
using Dapper;
using FieldArcheryApi.Auth;
using FieldArcheryApi.Data;

namespace FieldArcheryApi.Handicap;

/// <summary>
/// Handicap (server 2.2.01). The factors are fitted by the FieldArcheryJobs job on Odin from the NFAS Nationals and
/// 3D Championships results (see docs/HANDICAP.md); the API only reads them, plus a few Admin controls.
///
///   GET  /api/handicap                       anyone → the active model's factors, for the app to keep offline.
///        404 no_handicap until the job has fitted one.
///   GET  /api/admin/handicap                 Admins and Owner → { events, models, runs }
///   PUT  /api/admin/handicap/events/{champs}/{year}  { targetsPerDay: 36 | 40 | null }
///                                            Admins and Owner: set by hand (null = back to the job's own count).
///                                            The job refits on its next weekly run.
///   POST /api/admin/handicap/fetch           Admins and Owner: read nfas.net now (the job's next weekly run picks it up).
///   POST /api/admin/handicap/models/{id}/activate  Owner only: use a model the job left waiting.
///
/// Errors: 403 not_admin / not_owner; 400 invalid_targets; 404 no_such_event / no_such_model; 409 already_requested.
/// </summary>
public static class HandicapEndpoints
{
    public sealed record FactorRow(string Kind, string Code, double Value, double? Low, double? High);
    public sealed record ModelInfo(int ModelId, DateTime FittedUtc, string Status, short DataThroughYear, int Events, int DayScores,
                                   int Archers, double HoldoutRmse, double SwitcherBias, string? Note, DateTime? ActivatedUtc, string? ActivatedBy);
    public sealed record EventInfo(string Champs, short Year, string Status, int ResultCount, byte? TargetsPerDay, string? TargetsSource,
                                   string? TargetsNote, string? TargetsSetBy, DateTime CheckedUtc, DateTime? ChangedUtc);
    public sealed record RunInfo(int RequestId, string Kind, DateTime RequestedUtc, string? RequestedBy, DateTime? StartedUtc,
                                 DateTime? FinishedUtc, string? Outcome, bool Failed);
    public sealed record TargetsRequest(int? TargetsPerDay);

    public static void MapHandicapEndpoints(this WebApplication app)
    {
        app.MapGet("/api/handicap", Current);
        var admin = app.MapGroup("/api/admin/handicap").RequireAuthorization();
        admin.MapGet("", Overview);
        admin.MapPut("/events/{champs}/{year:int}", SetTargets);
        admin.MapPost("/fetch", RequestFetch);
        admin.MapPost("/models/{modelId:int}/activate", Activate);
    }

    private static DateTime Utc(DateTime d) => DateTime.SpecifyKind(d, DateTimeKind.Utc);
    private static DateTime? Utc(DateTime? d) => d is { } v ? Utc(v) : null;

    /// <summary>
    /// Equivalent score (what the app works out): p = score / (24 × targets), clipped to 0.01–0.99;
    /// z = logit(p) − offset(bow, class, year, 3D); equivalent = 24 × targets / (1 + e^−z), where
    /// offset = BOW[bow] + CLASS[sex-age] + (ladies ? BOWSEX[bow] : 0) + SLOPE[bow] × (year − 2014) / 10 + (3D ? BOW3D[bow] : 0),
    /// year no later than dataThroughYear. Missing factors are 0 (Unlimited Gents is the reference).
    /// </summary>
    private static async Task<IResult> Current(Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        var model = await conn.QuerySingleOrDefaultAsync<ModelInfo>(new CommandDefinition(ModelSql + " WHERE m.Status = 'ACTIVE';", cancellationToken: ct));
        if (model is null) return Error(404, "no_handicap", "There's no handicap yet: the server job hasn't fitted one.");
        var factors = await conn.QueryAsync<FactorRow>(new CommandDefinition(
            "SELECT Kind, Code, Value, Low, High FROM dbo.HandicapFactor WHERE ModelId = @id AND Kind IN ('BOW', 'SLOPE', 'BOW3D', 'CLASS', 'BOWSEX') ORDER BY Kind, Code;",
            new { id = model.ModelId }, cancellationToken: ct));
        return Results.Ok(new
        {
            modelId = model.ModelId,
            fittedUtc = Utc(model.FittedUtc),
            dataThroughYear = (int)model.DataThroughYear,
            reference = new { bowStyle = "UL", archerClass = "G", ageGroup = "ADULT" },
            scale = "logit",
            maxPerTarget = 24,
            yearZero = 2014,
            factors = factors.Select(f => new { kind = f.Kind, code = f.Code, value = f.Value, low = f.Low, high = f.High }),
        });
    }

    private const string ModelSql = """
        SELECT m.ModelId, m.FittedUtc, m.Status, m.DataThroughYear, m.Events, m.DayScores, m.Archers, m.HoldoutRmse, m.SwitcherBias,
               m.Note, m.ActivatedUtc, a.Username AS ActivatedBy
        FROM dbo.HandicapModel m LEFT JOIN dbo.Archer a ON a.ArcherId = m.ActivatedById
        """;

    private static async Task<IResult> Overview(ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        if (!user.IsAdmin()) return NotAdmin();
        await using var conn = await db.OpenAsync(ct);
        var events = await conn.QueryAsync<EventInfo>(new CommandDefinition("""
            SELECT e.Champs, e.[Year], e.Status, e.ResultCount, e.TargetsPerDay, e.TargetsSource, e.TargetsNote, a.Username AS TargetsSetBy,
                   e.CheckedUtc, e.ChangedUtc
            FROM dbo.ChampEvent e LEFT JOIN dbo.Archer a ON a.ArcherId = e.TargetsSetById
            ORDER BY e.[Year] DESC, e.Champs DESC;
            """, cancellationToken: ct));
        var models = await conn.QueryAsync<ModelInfo>(new CommandDefinition(ModelSql + " ORDER BY m.ModelId DESC OFFSET 0 ROWS FETCH NEXT 20 ROWS ONLY;", cancellationToken: ct));
        var runs = await conn.QueryAsync<RunInfo>(new CommandDefinition("""
            SELECT TOP (10) r.RequestId, r.Kind, r.RequestedUtc, a.Username AS RequestedBy, r.StartedUtc, r.FinishedUtc, r.Outcome, r.Failed
            FROM dbo.HandicapJobRequest r LEFT JOIN dbo.Archer a ON a.ArcherId = r.RequestedById
            ORDER BY r.RequestId DESC;
            """, cancellationToken: ct));
        return Results.Ok(new
        {
            events = events.Select(e => e with { CheckedUtc = Utc(e.CheckedUtc), ChangedUtc = Utc(e.ChangedUtc) }),
            models = models.Select(m => m with { FittedUtc = Utc(m.FittedUtc), ActivatedUtc = Utc(m.ActivatedUtc) }),
            runs = runs.Select(r => r with { RequestedUtc = Utc(r.RequestedUtc), StartedUtc = Utc(r.StartedUtc), FinishedUtc = Utc(r.FinishedUtc) }),
        });
    }

    private static async Task<IResult> SetTargets(string champs, int year, TargetsRequest req, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        if (!user.IsAdmin()) return NotAdmin();
        champs = champs.ToUpperInvariant();
        if (req.TargetsPerDay is { } t && t is not (36 or 40))
            return Error(400, "invalid_targets", "Targets per day must be 36 or 40 (or null to let the job work it out).");
        await using var conn = await db.OpenAsync(ct);
        // Null: hand it back to the job (it fills the count in again on its next run).
        var changed = await conn.ExecuteAsync(new CommandDefinition(req.TargetsPerDay is null
            ? """
              UPDATE dbo.ChampEvent SET TargetsSource = NULL, TargetsSetById = NULL, TargetsNote = N'Back to automatic: the job sets it on its next run.'
              WHERE Champs = @champs AND [Year] = @year AND Status = 'RESULTS';
              """
            : """
              UPDATE dbo.ChampEvent SET TargetsPerDay = @t, TargetsSource = 'ADMIN', TargetsSetById = @by, TargetsNote = N'Set by hand.'
              WHERE Champs = @champs AND [Year] = @year AND Status = 'RESULTS';
              """, new { champs, year, t = (byte?)req.TargetsPerDay, by = user.ArcherId() }, cancellationToken: ct));
        if (changed == 0) return Error(404, "no_such_event", "There are no results stored for that championship and year.");
        return Results.Ok(new { champs, year, targetsPerDay = req.TargetsPerDay, source = req.TargetsPerDay is null ? "AUTO" : "ADMIN" });
    }

    private static async Task<IResult> RequestFetch(ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        if (!user.IsAdmin()) return NotAdmin();
        await using var conn = await db.OpenAsync(ct);
        var waiting = await conn.ExecuteScalarAsync<int>(new CommandDefinition(
            "SELECT COUNT(*) FROM dbo.HandicapJobRequest WHERE Kind = 'FETCH' AND FinishedUtc IS NULL;", cancellationToken: ct));
        if (waiting > 0) return Error(409, "already_requested", "A fetch is already waiting or running.");
        var id = await conn.ExecuteScalarAsync<int>(new CommandDefinition(
            "INSERT dbo.HandicapJobRequest (Kind, RequestedById) OUTPUT inserted.RequestId VALUES ('FETCH', @by);",
            new { by = user.ArcherId() }, cancellationToken: ct));
        return Results.Accepted(value: new { requestId = id, message = "The server job reads nfas.net on its next weekly run (Monday night), or run FieldArcheryJobs.exe fetch on Odin to do it now." });
    }

    private static async Task<IResult> Activate(int modelId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        if (!user.IsOwner()) return user.IsAdmin() ? Error(403, "not_owner", "Only the owner can choose the handicap model.") : NotAdmin();
        await using var conn = await db.OpenAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(ct);
        var exists = await conn.ExecuteScalarAsync<int>(new CommandDefinition(
            "SELECT COUNT(*) FROM dbo.HandicapModel WHERE ModelId = @modelId;", new { modelId }, tx, cancellationToken: ct));
        if (exists == 0) return Error(404, "no_such_model", "There's no handicap model with that id.");
        await conn.ExecuteAsync(new CommandDefinition("""
            UPDATE dbo.HandicapModel SET Status = 'RETIRED' WHERE Status = 'ACTIVE' AND ModelId <> @modelId;
            UPDATE dbo.HandicapModel SET Status = 'ACTIVE', ActivatedUtc = SYSUTCDATETIME(), ActivatedById = @by WHERE ModelId = @modelId;
            """, new { modelId, by = user.ArcherId() }, tx, cancellationToken: ct));
        await tx.CommitAsync(ct);
        return Results.Ok(new { modelId, status = "ACTIVE" });
    }

    private static IResult NotAdmin() => Error(403, "not_admin", "Only admins can do that.");

    private static IResult Error(int status, string code, string message) =>
        Results.Json(new { error = code, message }, statusCode: status);
}
