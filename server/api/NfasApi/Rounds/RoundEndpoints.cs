using System.Data;
using System.Security.Claims;
using Dapper;
using NfasApi.Auth;
using NfasApi.Data;

namespace NfasApi.Rounds;

/// <summary>
/// Round upload (step 5a). All calls need "Authorization: Bearer &lt;token&gt;" and only touch the signer's own rounds.
///   PUT    /api/rounds/{roundId}   full round JSON → 200 { roundId, result: "saved" | "unchanged", serverUpdatedUtc }
///   GET    /api/rounds/{roundId}   → the stored round (including deleted ones, flagged)
///   DELETE /api/rounds/{roundId}   → 204, marks it deleted (kept so other phones learn about it)
/// Sending the same round again is safe: the copy with the later clientUpdatedUtc wins.
/// </summary>
public static class RoundEndpoints
{
    public static void MapRoundEndpoints(this WebApplication app)
    {
        var rounds = app.MapGroup("/api/rounds").RequireAuthorization();
        rounds.MapPut("/{roundId:guid}", Upload);
        rounds.MapGet("/{roundId:guid}", Get);
        rounds.MapDelete("/{roundId:guid}", Delete);
    }

    private static async Task<IResult> Upload(Guid roundId, RoundDto round, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var problems = RoundValidator.Validate(round);
        if (problems.Count > 0)
        {
            return Results.Json(new { error = "invalid_round", message = string.Join(" ", problems), problems },
                statusCode: StatusCodes.Status400BadRequest);
        }

        var archerId = user.ArcherId();
        await using var conn = await db.OpenAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(IsolationLevel.ReadCommitted, ct);

        // Lock the row (or the gap) so two uploads of the same round can't interleave.
        var existing = await conn.QuerySingleOrDefaultAsync<ExistingRound>(new CommandDefinition("""
            SELECT ArcherId, ClientUpdatedUtc, ServerUpdatedUtc
            FROM dbo.Round WITH (UPDLOCK, HOLDLOCK)
            WHERE RoundId = @roundId;
            """, new { roundId }, tx, cancellationToken: ct));

        if (existing is not null && existing.ArcherId != archerId)
        {
            // Someone else's round id: say "not found" rather than revealing it exists.
            return Results.Json(new { error = "not_found", message = "Round not found." }, statusCode: StatusCodes.Status404NotFound);
        }
        if (existing is not null && existing.ClientUpdatedUtc >= Trim(round.ClientUpdatedUtc))
        {
            // Same or older copy (a retry, or a stale phone): keep what we have.
            await tx.CommitAsync(ct);
            return Results.Ok(new { roundId, result = "unchanged", serverUpdatedUtc = existing.ServerUpdatedUtc });
        }

        var p = new
        {
            roundId,
            archerId,
            shootDate = round.ShootDate.ToDateTime(TimeOnly.MinValue),
            round.TargetCount,
            round.ArcherClass,
            round.AgeGroup,
            round.BowStyle,
            round.Status,
            round.TotalPoints,
            clientCreatedUtc = Trim(round.ClientCreatedUtc),
            clientUpdatedUtc = Trim(round.ClientUpdatedUtc),
        };
        var sql = existing is null
            ? """
              INSERT dbo.Round (RoundId, ArcherId, ShootDate, TargetCount, ArcherClass, AgeGroup, BowStyle, Status,
                                TotalPoints, IsDeleted, ClientCreatedUtc, ClientUpdatedUtc, ServerUpdatedUtc)
              VALUES (@roundId, @archerId, @shootDate, @TargetCount, @ArcherClass, @AgeGroup, @BowStyle, @Status,
                      @TotalPoints, 0, @clientCreatedUtc, @clientUpdatedUtc, SYSUTCDATETIME());
              """
            : """
              UPDATE dbo.Round
              SET ShootDate = @shootDate, TargetCount = @TargetCount, ArcherClass = @ArcherClass, AgeGroup = @AgeGroup,
                  BowStyle = @BowStyle, Status = @Status, TotalPoints = @TotalPoints, IsDeleted = 0,
                  ClientCreatedUtc = @clientCreatedUtc, ClientUpdatedUtc = @clientUpdatedUtc, ServerUpdatedUtc = SYSUTCDATETIME()
              WHERE RoundId = @roundId;
              DELETE dbo.TargetScore WHERE RoundId = @roundId;   -- animals go with them (cascade)
              """;
        await conn.ExecuteAsync(new CommandDefinition(sql, p, tx, cancellationToken: ct));

        await conn.ExecuteAsync(new CommandDefinition("""
            INSERT dbo.TargetScore (RoundId, TargetNo, TargetType, OverridePoints, Points)
            VALUES (@roundId, @TargetNo, @Type, @OverridePoints, @Points);
            """,
            round.Targets.Select(t => new { roundId, t.TargetNo, t.Type, t.OverridePoints, t.Points }),
            tx, cancellationToken: ct));

        await conn.ExecuteAsync(new CommandDefinition("""
            INSERT dbo.AnimalScore (RoundId, TargetNo, AnimalIndex, State, ArrowNo, Hit, Points)
            VALUES (@roundId, @TargetNo, @Index, @State, @ArrowNo, @Hit, @Points);
            """,
            round.Targets.SelectMany(t => t.Animals.Select(a =>
                new { roundId, t.TargetNo, a.Index, a.State, a.ArrowNo, a.Hit, a.Points })),
            tx, cancellationToken: ct));

        var serverUpdated = await conn.ExecuteScalarAsync<DateTime>(new CommandDefinition(
            "SELECT ServerUpdatedUtc FROM dbo.Round WHERE RoundId = @roundId;", new { roundId }, tx, cancellationToken: ct));

        await tx.CommitAsync(ct);
        return Results.Ok(new { roundId, result = "saved", serverUpdatedUtc = serverUpdated });
    }

    private static async Task<IResult> Get(Guid roundId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        var round = await RoundReader.ReadAsync(conn, roundId, ct);
        return round is not null && round.ArcherId == user.ArcherId()
            ? Results.Ok(round.Round)
            : Results.Json(new { error = "not_found", message = "Round not found." }, statusCode: StatusCodes.Status404NotFound);
    }

    private static async Task<IResult> Delete(Guid roundId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        // Only the owner's round; deleting something that was never uploaded is fine (nothing to do).
        await conn.ExecuteAsync(new CommandDefinition("""
            UPDATE dbo.Round
            SET IsDeleted = 1, ServerUpdatedUtc = SYSUTCDATETIME(),
                ClientUpdatedUtc = CASE WHEN ClientUpdatedUtc > SYSUTCDATETIME() THEN ClientUpdatedUtc ELSE SYSUTCDATETIME() END
            WHERE RoundId = @roundId AND ArcherId = @archerId AND IsDeleted = 0;
            """, new { roundId, archerId = user.ArcherId() }, cancellationToken: ct));
        return Results.NoContent();
    }

    /// <summary>SQL datetime2(3) keeps milliseconds; trim so comparisons with stored values are exact.</summary>
    private static DateTime Trim(DateTime utc)
    {
        var u = utc.Kind == DateTimeKind.Local ? utc.ToUniversalTime() : utc;
        return new DateTime(u.Ticks - u.Ticks % TimeSpan.TicksPerMillisecond, DateTimeKind.Utc);
    }

    private sealed record ExistingRound(Guid ArcherId, DateTime ClientUpdatedUtc, DateTime ServerUpdatedUtc);
}

/// <summary>Loads a stored round with its targets and animals.</summary>
public static class RoundReader
{
    public sealed record Loaded(Guid ArcherId, StoredRoundDto Round);

    public static async Task<Loaded?> ReadAsync(Microsoft.Data.SqlClient.SqlConnection conn, Guid roundId, CancellationToken ct)
    {
        using var multi = await conn.QueryMultipleAsync(new CommandDefinition("""
            SELECT RoundId, ArcherId, ShootDate, TargetCount, ArcherClass, AgeGroup, BowStyle, Status, TotalPoints,
                   IsDeleted, ClientCreatedUtc, ClientUpdatedUtc, ServerUpdatedUtc
            FROM dbo.Round WHERE RoundId = @roundId;
            SELECT TargetNo, TargetType, OverridePoints, Points
            FROM dbo.TargetScore WHERE RoundId = @roundId ORDER BY TargetNo;
            SELECT TargetNo, AnimalIndex, State, ArrowNo, Hit, Points
            FROM dbo.AnimalScore WHERE RoundId = @roundId ORDER BY TargetNo, AnimalIndex;
            """, new { roundId }, cancellationToken: ct));

        var r = await multi.ReadSingleOrDefaultAsync<RoundRow>();
        if (r is null) return null;
        var targets = (await multi.ReadAsync<TargetRow>()).ToList();
        var animals = (await multi.ReadAsync<AnimalRow>()).ToLookup(a => a.TargetNo);

        var dto = new StoredRoundDto(
            r.RoundId, DateOnly.FromDateTime(r.ShootDate), r.TargetCount, r.ArcherClass.Trim(), r.AgeGroup, r.BowStyle,
            r.Status, r.TotalPoints, r.IsDeleted, Utc(r.ClientCreatedUtc), Utc(r.ClientUpdatedUtc), Utc(r.ServerUpdatedUtc),
            targets.Select(t => new TargetDto(t.TargetNo, t.TargetType, t.OverridePoints, t.Points,
                animals[t.TargetNo].Select(a => new AnimalDto(a.AnimalIndex, a.State, a.ArrowNo, a.Hit, a.Points)).ToList()))
                .ToList());
        return new Loaded(r.ArcherId, dto);
    }

    private static DateTime Utc(DateTime d) => DateTime.SpecifyKind(d, DateTimeKind.Utc);

    private sealed record RoundRow(Guid RoundId, Guid ArcherId, DateTime ShootDate, short TargetCount, string ArcherClass,
        string AgeGroup, string BowStyle, string Status, int TotalPoints, bool IsDeleted,
        DateTime ClientCreatedUtc, DateTime ClientUpdatedUtc, DateTime ServerUpdatedUtc);

    private sealed record TargetRow(short TargetNo, string TargetType, short? OverridePoints, short Points);

    private sealed record AnimalRow(short TargetNo, byte AnimalIndex, string State, byte? ArrowNo, string? Hit, short Points);
}
