// Equipment (3.2.24, Pete): an archer's bows and arrows, synced between their phones and the web app. Private: only
// ever returned to the archer who owns them. All calls need "Authorization: Bearer <token>".
//   GET /api/equipment                → { bows: [...], arrows: [...] } every bow and arrow set, deleted ones too (flagged)
//   PUT /api/equipment/bows/{bowId}     BowDto   → 200 { bowId, result: "saved" | "unchanged", serverUpdatedUtc }
//   PUT /api/equipment/arrows/{arrowId} ArrowDto → 200 { arrowId, result, serverUpdatedUtc }
// The phone makes the ids. Sending the same thing again is safe: the copy with the later clientUpdatedUtc wins.
// Deleting is a PUT with isDeleted true (kept so other phones, and rounds shot with it, still know it).

using System.Data;
using System.Security.Claims;
using System.Text.RegularExpressions;
using Dapper;
using FieldArcheryApi.Auth;
using FieldArcheryApi.Data;
using FieldArcheryApi.Rounds;

namespace FieldArcheryApi.Equipment;

public sealed record BowDto(
    string Type,                       // LONGBOW / FLATBOW / RECURVE / COMPOUND / CROSSBOW / HORSEBOW / SELFBOW / OTHER
    string? Make,
    string? Model,
    decimal? PoundageLb,
    IReadOnlyList<string> BowStyles,   // codes (LB, HT …), at least one
    bool IsDeleted,
    DateTime ClientUpdatedUtc);

public sealed record ArrowDto(
    string? Make,
    string? Model,
    decimal? LengthIn,
    decimal? ArrowGrains,
    string? Spine,                     // 500 (carbon), 45-50 (wood) or 2213 (aluminium): the apps tidy it up
    int? FletchCount,
    string? FletchMaterial,            // FEATHER / VANE / OTHER
    decimal? FletchLengthIn,
    string? FletchColours,
    decimal? PointGrains,
    IReadOnlyList<Guid> BowIds,        // the bows they're used with
    bool IsDeleted,
    DateTime ClientUpdatedUtc);

public static class EquipmentEndpoints
{
    public const int MaxText = 60;
    public const int MaxColours = 100;
    public const int MaxBowsPerArrow = 20;
    public static readonly HashSet<string> BowTypes = ["LONGBOW", "FLATBOW", "RECURVE", "COMPOUND", "CROSSBOW", "HORSEBOW", "SELFBOW", "OTHER"];
    public static readonly HashSet<string> FletchMaterials = ["FEATHER", "VANE", "OTHER"];

    private static readonly Regex SpineFormat = new(@"^(\d{3,4}|\d{2,3}(-\d{2,3})?)$", RegexOptions.CultureInvariant);

    public static void MapEquipmentEndpoints(this WebApplication app)
    {
        var g = app.MapGroup("/api/equipment").RequireAuthorization();
        g.MapGet("/", List);
        g.MapPut("/bows/{bowId:guid}", PutBow);
        g.MapPut("/arrows/{arrowId:guid}", PutArrow);
    }

    private sealed record BowRow(Guid BowId, Guid ArcherId, string BowType, string? Make, string? Model, decimal? PoundageLb,
        string BowStyles, bool IsDeleted, DateTime ClientUpdatedUtc, DateTime ServerUpdatedUtc);

    private sealed record ArrowRow(Guid ArrowId, Guid ArcherId, string? Make, string? Model, decimal? LengthIn, decimal? ArrowGrains,
        string? Spine, byte? FletchCount, string? FletchMaterial, decimal? FletchLengthIn, string? FletchColours, decimal? PointGrains,
        string BowIds, bool IsDeleted, DateTime ClientUpdatedUtc, DateTime ServerUpdatedUtc);

    private sealed record Existing(Guid ArcherId, DateTime ClientUpdatedUtc, DateTime ServerUpdatedUtc);

    private static DateTime Utc(DateTime d) => DateTime.SpecifyKind(d, DateTimeKind.Utc);

    private static async Task<IResult> List(ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        using var multi = await conn.QueryMultipleAsync(new CommandDefinition("""
            SELECT BowId, ArcherId, BowType, Make, Model, PoundageLb, BowStyles, IsDeleted, ClientUpdatedUtc, ServerUpdatedUtc
            FROM dbo.Bow WHERE ArcherId = @me ORDER BY ClientUpdatedUtc;
            SELECT ArrowId, ArcherId, Make, Model, LengthIn, ArrowGrains, Spine, FletchCount, FletchMaterial, FletchLengthIn,
                   FletchColours, PointGrains, BowIds, IsDeleted, ClientUpdatedUtc, ServerUpdatedUtc
            FROM dbo.ArrowSet WHERE ArcherId = @me ORDER BY ClientUpdatedUtc;
            """, new { me = user.ArcherId() }, cancellationToken: ct));
        var bows = (await multi.ReadAsync<BowRow>()).Select(b => new
        {
            bowId = b.BowId, type = b.BowType, make = b.Make, model = b.Model, poundageLb = b.PoundageLb,
            bowStyles = Split(b.BowStyles), isDeleted = b.IsDeleted,
            clientUpdatedUtc = Utc(b.ClientUpdatedUtc), serverUpdatedUtc = Utc(b.ServerUpdatedUtc),
        }).ToList();
        var arrows = (await multi.ReadAsync<ArrowRow>()).Select(a => new
        {
            arrowId = a.ArrowId, make = a.Make, model = a.Model, lengthIn = a.LengthIn, arrowGrains = a.ArrowGrains, spine = a.Spine,
            fletchCount = (int?)a.FletchCount, fletchMaterial = a.FletchMaterial, fletchLengthIn = a.FletchLengthIn,
            fletchColours = a.FletchColours, pointGrains = a.PointGrains,
            bowIds = Split(a.BowIds).Select(Guid.Parse).ToList(), isDeleted = a.IsDeleted,
            clientUpdatedUtc = Utc(a.ClientUpdatedUtc), serverUpdatedUtc = Utc(a.ServerUpdatedUtc),
        }).ToList();
        return Results.Ok(new { bows, arrows });
    }

    private static List<string> Split(string s) => s.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToList();

    private static string? Clean(string? s) => string.IsNullOrWhiteSpace(s) ? null : Regex.Replace(s.Trim(), @"\s+", " ");

    private static IResult Invalid(List<string> problems) =>
        Results.Json(new { error = "invalid_equipment", message = string.Join(" ", problems), problems }, statusCode: StatusCodes.Status400BadRequest);

    private static void InRange(List<string> problems, decimal? v, decimal min, decimal max, string what)
    {
        if (v is { } x && (x < min || x > max)) problems.Add($"{what} must be between {min} and {max}.");
    }

    public static List<string> Validate(BowDto b)
    {
        var p = new List<string>();
        if (!BowTypes.Contains(b.Type ?? "")) p.Add("Unknown bow type.");
        if (Clean(b.Make) is null && Clean(b.Model) is null) p.Add("Give the bow a make or model.");
        if ((b.Make?.Trim().Length ?? 0) > MaxText || (b.Model?.Trim().Length ?? 0) > MaxText) p.Add($"Make and model can be up to {MaxText} characters.");
        InRange(p, b.PoundageLb, 5, 250, "Poundage");
        if (b.BowStyles is null || b.BowStyles.Count == 0) p.Add("Pick at least one bow style.");
        else if (b.BowStyles.Any(s => !RoundValidator.BowStyles.Contains(s))) p.Add("Unknown bow style.");
        return p;
    }

    public static List<string> Validate(ArrowDto a)
    {
        var p = new List<string>();
        if (Clean(a.Make) is null && Clean(a.Model) is null) p.Add("Give the arrows a make or model.");
        if ((a.Make?.Trim().Length ?? 0) > MaxText || (a.Model?.Trim().Length ?? 0) > MaxText) p.Add($"Make and model can be up to {MaxText} characters.");
        InRange(p, a.LengthIn, 10, 40, "Arrow length");
        InRange(p, a.ArrowGrains, 50, 2000, "Arrow weight");
        InRange(p, a.PointGrains, 10, 1000, "Point weight");
        InRange(p, a.FletchLengthIn, 0.5m, 9, "Fletch length");
        if (a.FletchCount is { } n && (n < 1 || n > 8)) p.Add("Fletches must be between 1 and 8.");
        if (a.FletchMaterial is { } m && !FletchMaterials.Contains(m)) p.Add("Unknown fletch material.");
        if ((a.FletchColours?.Trim().Length ?? 0) > MaxColours) p.Add($"Fletch colours can be up to {MaxColours} characters.");
        if (Clean(a.Spine) is { } s && !SpineFormat.IsMatch(s)) p.Add("Spine should look like 500 (carbon), 45-50 (wood) or 2213 (aluminium).");
        if (a.BowIds is null || a.BowIds.Count == 0) p.Add("Pick at least one bow.");
        else if (a.BowIds.Count > MaxBowsPerArrow) p.Add($"Up to {MaxBowsPerArrow} bows.");
        return p;
    }

    private static async Task<IResult> PutBow(Guid bowId, BowDto bow, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        // A deleted one only needs to say so; what it was is kept.
        List<string> problems = bow.IsDeleted ? new List<string>() : Validate(bow);
        if (problems.Count > 0) return Invalid(problems);
        var me = user.ArcherId();
        await using var conn = await db.OpenAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(IsolationLevel.ReadCommitted, ct);
        var existing = await conn.QuerySingleOrDefaultAsync<Existing>(new CommandDefinition(
            "SELECT ArcherId, ClientUpdatedUtc, ServerUpdatedUtc FROM dbo.Bow WITH (UPDLOCK, HOLDLOCK) WHERE BowId = @bowId;",
            new { bowId }, tx, cancellationToken: ct));
        if (existing is not null && existing.ArcherId != me) return NotFound();
        var updated = RoundEndpoints.TrimUtc(bow.ClientUpdatedUtc);
        if (existing is not null && existing.ClientUpdatedUtc >= updated)
        {
            await tx.CommitAsync(ct);
            return Results.Ok(new { bowId, result = "unchanged", serverUpdatedUtc = Utc(existing.ServerUpdatedUtc) });
        }
        if (existing is null && bow.IsDeleted && !BowTypes.Contains(bow.Type ?? ""))
        {
            await tx.CommitAsync(ct);   // deleted before it was ever sent: nothing to keep
            return Results.Ok(new { bowId, result = "unchanged", serverUpdatedUtc = DateTime.UtcNow });
        }
        var p = new
        {
            bowId, me, type = bow.Type, make = Clean(bow.Make), model = Clean(bow.Model), bow.PoundageLb,
            styles = string.Join(",", (bow.BowStyles ?? []).Distinct()), deleted = bow.IsDeleted, updated,
        };
        var sql = existing is null
            ? """
              INSERT dbo.Bow (BowId, ArcherId, BowType, Make, Model, PoundageLb, BowStyles, IsDeleted, ClientUpdatedUtc)
              VALUES (@bowId, @me, @type, @make, @model, @PoundageLb, @styles, @deleted, @updated);
              """
            : bow.IsDeleted
                ? "UPDATE dbo.Bow SET IsDeleted = 1, ClientUpdatedUtc = @updated, ServerUpdatedUtc = SYSUTCDATETIME() WHERE BowId = @bowId;"
                : """
                  UPDATE dbo.Bow SET BowType = @type, Make = @make, Model = @model, PoundageLb = @PoundageLb, BowStyles = @styles,
                                     IsDeleted = 0, ClientUpdatedUtc = @updated, ServerUpdatedUtc = SYSUTCDATETIME()
                  WHERE BowId = @bowId;
                  """;
        await conn.ExecuteAsync(new CommandDefinition(sql, p, tx, cancellationToken: ct));
        var server = await conn.ExecuteScalarAsync<DateTime>(new CommandDefinition(
            "SELECT ServerUpdatedUtc FROM dbo.Bow WHERE BowId = @bowId;", new { bowId }, tx, cancellationToken: ct));
        await tx.CommitAsync(ct);
        return Results.Ok(new { bowId, result = "saved", serverUpdatedUtc = Utc(server) });
    }

    private static async Task<IResult> PutArrow(Guid arrowId, ArrowDto arrow, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        List<string> problems = arrow.IsDeleted ? new List<string>() : Validate(arrow);
        if (problems.Count > 0) return Invalid(problems);
        var me = user.ArcherId();
        await using var conn = await db.OpenAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(IsolationLevel.ReadCommitted, ct);
        var existing = await conn.QuerySingleOrDefaultAsync<Existing>(new CommandDefinition(
            "SELECT ArcherId, ClientUpdatedUtc, ServerUpdatedUtc FROM dbo.ArrowSet WITH (UPDLOCK, HOLDLOCK) WHERE ArrowId = @arrowId;",
            new { arrowId }, tx, cancellationToken: ct));
        if (existing is not null && existing.ArcherId != me) return NotFound();
        var updated = RoundEndpoints.TrimUtc(arrow.ClientUpdatedUtc);
        if (existing is not null && existing.ClientUpdatedUtc >= updated)
        {
            await tx.CommitAsync(ct);
            return Results.Ok(new { arrowId, result = "unchanged", serverUpdatedUtc = Utc(existing.ServerUpdatedUtc) });
        }
        var p = new
        {
            arrowId, me, make = Clean(arrow.Make), model = Clean(arrow.Model), arrow.LengthIn, arrow.ArrowGrains, spine = Clean(arrow.Spine),
            fletchCount = arrow.FletchCount is { } n ? (byte?)n : null, arrow.FletchMaterial, arrow.FletchLengthIn,
            colours = Clean(arrow.FletchColours), arrow.PointGrains,
            bowIds = string.Join(",", (arrow.BowIds ?? []).Distinct()), deleted = arrow.IsDeleted, updated,
        };
        var sql = existing is null
            ? """
              INSERT dbo.ArrowSet (ArrowId, ArcherId, Make, Model, LengthIn, ArrowGrains, Spine, FletchCount, FletchMaterial,
                                   FletchLengthIn, FletchColours, PointGrains, BowIds, IsDeleted, ClientUpdatedUtc)
              VALUES (@arrowId, @me, @make, @model, @LengthIn, @ArrowGrains, @spine, @fletchCount, @FletchMaterial,
                      @FletchLengthIn, @colours, @PointGrains, @bowIds, @deleted, @updated);
              """
            : arrow.IsDeleted
                ? "UPDATE dbo.ArrowSet SET IsDeleted = 1, ClientUpdatedUtc = @updated, ServerUpdatedUtc = SYSUTCDATETIME() WHERE ArrowId = @arrowId;"
                : """
                  UPDATE dbo.ArrowSet SET Make = @make, Model = @model, LengthIn = @LengthIn, ArrowGrains = @ArrowGrains, Spine = @spine,
                                          FletchCount = @fletchCount, FletchMaterial = @FletchMaterial, FletchLengthIn = @FletchLengthIn,
                                          FletchColours = @colours, PointGrains = @PointGrains, BowIds = @bowIds, IsDeleted = 0,
                                          ClientUpdatedUtc = @updated, ServerUpdatedUtc = SYSUTCDATETIME()
                  WHERE ArrowId = @arrowId;
                  """;
        if (existing is null && arrow.IsDeleted && (arrow.BowIds is null || arrow.BowIds.Count == 0))
        {
            await tx.CommitAsync(ct);   // deleted before it was ever sent: nothing to keep
            return Results.Ok(new { arrowId, result = "unchanged", serverUpdatedUtc = DateTime.UtcNow });
        }
        await conn.ExecuteAsync(new CommandDefinition(sql, p, tx, cancellationToken: ct));
        var server = await conn.ExecuteScalarAsync<DateTime>(new CommandDefinition(
            "SELECT ServerUpdatedUtc FROM dbo.ArrowSet WHERE ArrowId = @arrowId;", new { arrowId }, tx, cancellationToken: ct));
        await tx.CommitAsync(ct);
        return Results.Ok(new { arrowId, result = "saved", serverUpdatedUtc = Utc(server) });
    }

    private static IResult NotFound() =>
        Results.Json(new { error = "not_found", message = "Not found." }, statusCode: StatusCodes.Status404NotFound);
}
