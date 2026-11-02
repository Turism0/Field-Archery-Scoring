using System.Data;
using System.Security.Claims;
using System.Text.Json;
using Dapper;
using NfasApi.Auth;
using NfasApi.Data;
using NfasApi.Locations;
using NfasApi.Rounds;

namespace NfasApi.Offers;

/// <summary>
/// Rounds scored for a friend (API 1.9.06): they arrive as a request and only become the friend's round when accepted.
/// All calls need "Authorization: Bearer &lt;token&gt;".
///   PUT    /api/offers/{roundId}  { forArcherId, groupId?, round }  scorer: send (or replace) a finished round for a friend
///   GET    /api/offers                     → { received: [...], sent: [...] }  (summaries, newest first)
///   GET    /api/offers/{roundId}           → { offer summary, round }  for the friend or the scorer
///   POST   /api/offers/{roundId}/accept    friend: → 200 { roundId, result: "accepted", serverUpdatedUtc } – now their round
///   POST   /api/offers/{roundId}/decline   friend: → 204, deleted
///   DELETE /api/offers/{roundId}           scorer: → 204, withdrawn
/// Requests are in nobody's history, stats or friends' views until accepted. /api/me has the count waiting (roundOffers).
/// </summary>
public static class OfferEndpoints
{
    public sealed record OfferIn(Guid ForArcherId, Guid? GroupId, RoundDto? Round);

    private sealed record OfferRow(Guid RoundId, Guid ForArcherId, Guid FromArcherId, Guid? GroupId, string RoundJson);

    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    public static void MapOfferEndpoints(this WebApplication app)
    {
        var offers = app.MapGroup("/api/offers").RequireAuthorization();
        offers.MapGet("/", List);
        offers.MapPut("/{roundId:guid}", Put);
        offers.MapGet("/{roundId:guid}", Get);
        offers.MapPost("/{roundId:guid}/accept", Accept);
        offers.MapPost("/{roundId:guid}/decline", Decline);
        offers.MapDelete("/{roundId:guid}", Withdraw);
    }

    public static Task<int> CountForAsync(Microsoft.Data.SqlClient.SqlConnection conn, Guid archerId, CancellationToken ct) =>
        conn.ExecuteScalarAsync<int>(new CommandDefinition(
            "SELECT COUNT(*) FROM dbo.RoundOffer WHERE ForArcherId = @archerId;", new { archerId }, cancellationToken: ct));

    private static async Task<IResult> Put(Guid roundId, OfferIn o, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var me = user.ArcherId();
        if (o.Round is not { } round) return Error(400, "invalid_round", "Send the round.");
        var problems = RoundValidator.Validate(round);
        if (round.Status != "COMPLETED") problems.Add("Only finished rounds can be sent to a friend.");
        if (problems.Count > 0)
            return Results.Json(new { error = "invalid_round", message = string.Join(" ", problems), problems }, statusCode: 400);
        if (o.ForArcherId == me) return Error(400, "not_yourself", "This is for rounds scored for someone else.");

        await using var conn = await db.OpenAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(IsolationLevel.Serializable, ct);
        var friends = await conn.ExecuteScalarAsync<int>(new CommandDefinition(
            "SELECT COUNT(*) FROM dbo.vFriend WHERE ArcherId = @me AND FriendId = @forId;", new { me, forId = o.ForArcherId }, tx, cancellationToken: ct));
        if (friends == 0) return Error(404, "not_a_friend", "You can only send rounds to friends.");

        var taken = await conn.ExecuteScalarAsync<int>(new CommandDefinition(
            "SELECT COUNT(*) FROM dbo.Round WHERE RoundId = @roundId;", new { roundId }, tx, cancellationToken: ct));
        if (taken > 0) return Error(409, "round_exists", "That round has already been accepted.");
        var existing = await conn.QuerySingleOrDefaultAsync<OfferRow>(new CommandDefinition(
            "SELECT RoundId, ForArcherId, FromArcherId, GroupId, RoundJson FROM dbo.RoundOffer WITH (UPDLOCK) WHERE RoundId = @roundId;",
            new { roundId }, tx, cancellationToken: ct));
        if (existing is not null && existing.FromArcherId != me) return Error(404, "not_found", "Round not found.");

        Guid? locationId = round.LocationId is { } lid && await LocationEndpoints.CurrentAsync(conn, lid, tx, ct) is { } loc ? loc.LocationId : null;
        var p = new
        {
            roundId, forId = o.ForArcherId, me, o.GroupId, json = JsonSerializer.Serialize(round with { LocationId = locationId }, Json),
            roundType = RoundValidator.TypeOf(round), shootDate = round.ShootDate.ToDateTime(TimeOnly.MinValue),
            targetCount = (short)round.TargetCount, round.ArcherClass, round.AgeGroup, round.BowStyle, round.TotalPoints, locationId,
        };
        await conn.ExecuteAsync(new CommandDefinition(existing is null
            ? """
              INSERT dbo.RoundOffer (RoundId, ForArcherId, FromArcherId, GroupId, RoundJson, RoundType, ShootDate, TargetCount,
                                     ArcherClass, AgeGroup, BowStyle, TotalPoints, LocationId)
              VALUES (@roundId, @forId, @me, @GroupId, @json, @roundType, @shootDate, @targetCount,
                      @ArcherClass, @AgeGroup, @BowStyle, @TotalPoints, @locationId);
              """
            : """
              UPDATE dbo.RoundOffer
              SET ForArcherId = @forId, GroupId = @GroupId, RoundJson = @json, RoundType = @roundType, ShootDate = @shootDate,
                  TargetCount = @targetCount, ArcherClass = @ArcherClass, AgeGroup = @AgeGroup, BowStyle = @BowStyle,
                  TotalPoints = @TotalPoints, LocationId = @locationId, UpdatedUtc = SYSUTCDATETIME()
              WHERE RoundId = @roundId;
              """, p, tx, cancellationToken: ct));
        await tx.CommitAsync(ct);
        return Results.Ok(new { roundId, result = "offered" });
    }

    private const string SummarySql = """
        SELECT o.RoundId, o.ForArcherId, f.Username AS ForUsername, o.FromArcherId, s.Username AS FromUsername, o.GroupId,
               o.RoundType, CAST(o.ShootDate AS datetime2(3)) AS ShootDate, o.TargetCount, o.ArcherClass, o.AgeGroup, o.BowStyle,
               o.TotalPoints, o.LocationId, {0} AS LocationName, o.CreatedUtc, o.UpdatedUtc
        FROM dbo.RoundOffer o
        JOIN dbo.Archer f ON f.ArcherId = o.ForArcherId
        JOIN dbo.Archer s ON s.ArcherId = o.FromArcherId
        LEFT JOIN dbo.ShootLocation l ON l.LocationId = o.LocationId
        """;

    private sealed record SummaryRow(Guid RoundId, Guid ForArcherId, string ForUsername, Guid FromArcherId, string FromUsername,
        Guid? GroupId, string RoundType, DateTime ShootDate, short TargetCount, string ArcherClass, string AgeGroup, string BowStyle,
        int TotalPoints, Guid? LocationId, string? LocationName, DateTime CreatedUtc, DateTime UpdatedUtc);

    private static object Summary(SummaryRow r) => new
    {
        r.RoundId, r.ForArcherId, r.ForUsername, r.FromArcherId, r.FromUsername, r.GroupId, r.RoundType,
        shootDate = DateOnly.FromDateTime(r.ShootDate), targetCount = (int)r.TargetCount, archerClass = r.ArcherClass.Trim(),
        r.AgeGroup, r.BowStyle, r.TotalPoints, r.LocationId, r.LocationName,
        createdUtc = DateTime.SpecifyKind(r.CreatedUtc, DateTimeKind.Utc), updatedUtc = DateTime.SpecifyKind(r.UpdatedUtc, DateTimeKind.Utc),
    };

    private static async Task<IResult> List(ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var viewer = user.ArcherId();
        await using var conn = await db.OpenAsync(ct);
        var rows = (await conn.QueryAsync<SummaryRow>(new CommandDefinition(
            string.Format(SummarySql, LocationEndpoints.VisibleNameSql) +
            " WHERE o.ForArcherId = @viewer OR o.FromArcherId = @viewer ORDER BY o.CreatedUtc DESC;",
            new { viewer, viewerIsAdmin = user.IsAdmin() }, cancellationToken: ct))).ToList();
        return Results.Ok(new
        {
            received = rows.Where(r => r.ForArcherId == viewer).Select(Summary),
            sent = rows.Where(r => r.FromArcherId == viewer).Select(Summary),
        });
    }

    private static async Task<IResult> Get(Guid roundId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var viewer = user.ArcherId();
        await using var conn = await db.OpenAsync(ct);
        var row = await conn.QuerySingleOrDefaultAsync<SummaryRow>(new CommandDefinition(
            string.Format(SummarySql, LocationEndpoints.VisibleNameSql) + " WHERE o.RoundId = @roundId AND (o.ForArcherId = @viewer OR o.FromArcherId = @viewer);",
            new { roundId, viewer, viewerIsAdmin = user.IsAdmin() }, cancellationToken: ct));
        if (row is null) return Error(404, "not_found", "Round not found.");
        var json = await conn.ExecuteScalarAsync<string>(new CommandDefinition(
            "SELECT RoundJson FROM dbo.RoundOffer WHERE RoundId = @roundId;", new { roundId }, cancellationToken: ct));
        return Results.Ok(new { offer = Summary(row), round = JsonSerializer.Deserialize<RoundDto>(json!, Json) });
    }

    private static async Task<IResult> Accept(Guid roundId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var me = user.ArcherId();
        await using var conn = await db.OpenAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(IsolationLevel.Serializable, ct);
        var offer = await conn.QuerySingleOrDefaultAsync<OfferRow>(new CommandDefinition(
            "SELECT RoundId, ForArcherId, FromArcherId, GroupId, RoundJson FROM dbo.RoundOffer WITH (UPDLOCK) WHERE RoundId = @roundId AND ForArcherId = @me;",
            new { roundId, me }, tx, cancellationToken: ct));
        if (offer is null) return Error(404, "not_found", "Round not found.");
        var round = JsonSerializer.Deserialize<RoundDto>(offer.RoundJson, Json)!;
        if (RoundValidator.Validate(round) is { Count: > 0 } problems)
            return Results.Json(new { error = "invalid_round", message = string.Join(" ", problems), problems }, statusCode: 400);
        var taken = await conn.ExecuteScalarAsync<int>(new CommandDefinition(
            "SELECT COUNT(*) FROM dbo.Round WITH (UPDLOCK, HOLDLOCK) WHERE RoundId = @roundId;", new { roundId }, tx, cancellationToken: ct));
        if (taken > 0) return Error(409, "round_exists", "That round has already been accepted.");

        var serverUpdated = await RoundWriter.SaveAsync(conn, tx, roundId, round, me, exists: false, offer.GroupId, offer.FromArcherId, ct);
        await conn.ExecuteAsync(new CommandDefinition("DELETE dbo.RoundOffer WHERE RoundId = @roundId;", new { roundId }, tx, cancellationToken: ct));
        await tx.CommitAsync(ct);
        return Results.Ok(new { roundId, result = "accepted", serverUpdatedUtc = serverUpdated });
    }

    private static async Task<IResult> Decline(Guid roundId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        var n = await conn.ExecuteAsync(new CommandDefinition(
            "DELETE dbo.RoundOffer WHERE RoundId = @roundId AND ForArcherId = @me;", new { roundId, me = user.ArcherId() }, cancellationToken: ct));
        return n == 0 ? Error(404, "not_found", "Round not found.") : Results.NoContent();
    }

    private static async Task<IResult> Withdraw(Guid roundId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        var n = await conn.ExecuteAsync(new CommandDefinition(
            "DELETE dbo.RoundOffer WHERE RoundId = @roundId AND FromArcherId = @me;", new { roundId, me = user.ArcherId() }, cancellationToken: ct));
        return n == 0 ? Error(404, "not_found", "Round not found.") : Results.NoContent();
    }

    private static IResult Error(int status, string code, string message) => Results.Json(new { error = code, message }, statusCode: status);
}
