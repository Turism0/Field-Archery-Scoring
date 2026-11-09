using System.Data;
using System.Security.Claims;
using Dapper;
using FieldArcheryApi.Auth;
using FieldArcheryApi.Data;
using FieldArcheryApi.Locations;

namespace FieldArcheryApi.Rounds;

/// <summary>
/// Round upload (step 5a). All calls need "Authorization: Bearer &lt;token&gt;" and only touch the signer's own rounds.
///   PUT    /api/rounds/{roundId}   full round JSON → 200 { roundId, result: "saved" | "unchanged", serverUpdatedUtc }
///   GET    /api/rounds/{roundId}   → the stored round (including deleted ones, flagged)
///   DELETE /api/rounds/{roundId}   → 204, marks it deleted (kept so other phones learn about it)
///   GET    /api/rounds?since=&amp;afterId=&amp;limit=   → { rounds: [...], more } own rounds changed after the cursor,
///          oldest change first, deleted ones included (step 6: download to a new or second phone)
/// Sending the same round again is safe: the copy with the later clientUpdatedUtc wins.
/// </summary>
public static class RoundEndpoints
{
    public static void MapRoundEndpoints(this WebApplication app)
    {
        var rounds = app.MapGroup("/api/rounds").RequireAuthorization();
        rounds.MapGet("/", ListChanged);
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

        // A group id is kept if the archer is in that group (or the group has already been deleted: they're temporary).
        Guid? groupId = round.GroupId is { } gid
            && await conn.ExecuteScalarAsync<int>(new CommandDefinition("""
                SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM dbo.ScoreGroup WHERE GroupId = @gid)
                              OR EXISTS (SELECT 1 FROM dbo.ScoreGroupMember WHERE GroupId = @gid AND ArcherId = @archerId)
                            THEN 1 ELSE 0 END;
                """, new { gid, archerId }, tx, cancellationToken: ct)) == 1
            ? gid
            : null;

        var serverUpdated = await RoundWriter.SaveAsync(conn, tx, roundId, round, archerId, existing is not null, groupId, null, ct);
        await tx.CommitAsync(ct);
        return Results.Ok(new { roundId, result = "saved", serverUpdatedUtc = serverUpdated });
    }

    /// <summary>SQL datetime2(3) keeps milliseconds; trim so comparisons with stored values are exact.</summary>
    internal static DateTime TrimUtc(DateTime utc) => Trim(utc);

    private static async Task<IResult> Get(Guid roundId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        var round = await RoundReader.ReadAsync(conn, roundId, user.ArcherId(), user.IsAdmin(), ct);
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

    private static async Task<IResult> ListChanged(
        DateTime? since, Guid? afterId, int? limit, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var take = Math.Clamp(limit ?? 50, 1, 200);
        await using var conn = await db.OpenAsync(ct);
        // Keyset paging on (ServerUpdatedUtc, RoundId) so pages never skip or repeat.
        var ids = (await conn.QueryAsync<Guid>(new CommandDefinition("""
            SELECT TOP (@take) RoundId
            FROM dbo.Round
            WHERE ArcherId = @archerId
              AND (@since IS NULL
                   OR ServerUpdatedUtc > @since
                   OR (ServerUpdatedUtc = @since AND @afterId IS NOT NULL AND RoundId > @afterId))
            ORDER BY ServerUpdatedUtc, RoundId;
            """,
            new { take = take + 1, archerId = user.ArcherId(), since = since.HasValue ? Trim(since.Value) : (DateTime?)null, afterId },
            cancellationToken: ct))).ToList();

        var more = ids.Count > take;
        if (more) ids.RemoveAt(ids.Count - 1);
        var rounds = await RoundReader.ReadManyAsync(conn, ids, user.ArcherId(), user.IsAdmin(), ct);
        return Results.Ok(new { rounds, more });
    }

    /// <summary>SQL datetime2(3) keeps milliseconds; trim so comparisons with stored values are exact.</summary>
    private static DateTime Trim(DateTime utc)
    {
        var u = utc.Kind == DateTimeKind.Local ? utc.ToUniversalTime() : utc;
        return new DateTime(u.Ticks - u.Ticks % TimeSpan.TicksPerMillisecond, DateTimeKind.Utc);
    }

    private sealed record ExistingRound(Guid ArcherId, DateTime ClientUpdatedUtc, DateTime ServerUpdatedUtc);
}

/// <summary>Writes a checked round (insert or full replace) for an archer. Used by uploads and accepted requests.</summary>
public static class RoundWriter
{
    public static async Task<DateTime> SaveAsync(
        Microsoft.Data.SqlClient.SqlConnection conn, System.Data.Common.DbTransaction tx, Guid roundId, RoundDto round,
        Guid archerId, bool exists, Guid? groupId, Guid? scoredById, CancellationToken ct)
    {
        // Store the location the id stands for now (it may have been merged); unknown → none.
        Guid? locationId = round.LocationId is { } lid
            && await LocationEndpoints.CurrentAsync(conn, lid, tx, ct) is { } loc
            ? loc.LocationId
            : null;

        var p = new
        {
            roundId,
            locationId,
            archerId,
            shootDate = round.ShootDate.ToDateTime(TimeOnly.MinValue),
            round.TargetCount,
            round.ArcherClass,
            round.AgeGroup,
            round.BowStyle,
            round.Status,
            round.TotalPoints,
            clientCreatedUtc = RoundEndpoints.TrimUtc(round.ClientCreatedUtc),
            clientUpdatedUtc = RoundEndpoints.TrimUtc(round.ClientUpdatedUtc),
            roundType = RoundValidator.TypeOf(round),
            groupId,
            scoredById,
            startTarget = round.StartTarget is { } st ? (short?)st : null,
        };
        var sql = !exists
            ? """
              INSERT dbo.Round (RoundId, ArcherId, ShootDate, TargetCount, ArcherClass, AgeGroup, BowStyle, Status,
                                TotalPoints, IsDeleted, ClientCreatedUtc, ClientUpdatedUtc, ServerUpdatedUtc, LocationId, RoundType,
                                GroupId, ScoredById, StartTarget)
              VALUES (@roundId, @archerId, @shootDate, @TargetCount, @ArcherClass, @AgeGroup, @BowStyle, @Status,
                      @TotalPoints, 0, @clientCreatedUtc, @clientUpdatedUtc, SYSUTCDATETIME(), @locationId, @roundType,
                      @groupId, @scoredById, @startTarget);
              """
            : """
              UPDATE dbo.Round
              SET ShootDate = @shootDate, TargetCount = @TargetCount, ArcherClass = @ArcherClass, AgeGroup = @AgeGroup,
                  BowStyle = @BowStyle, Status = @Status, TotalPoints = @TotalPoints, IsDeleted = 0,
                  ClientCreatedUtc = @clientCreatedUtc, ClientUpdatedUtc = @clientUpdatedUtc, ServerUpdatedUtc = SYSUTCDATETIME(),
                  LocationId = @locationId, RoundType = @roundType, StartTarget = @startTarget,
                  GroupId = COALESCE(@groupId, GroupId), ScoredById = COALESCE(@scoredById, ScoredById)
              WHERE RoundId = @roundId;
              DELETE dbo.TargetScore WHERE RoundId = @roundId;   -- animals go with them (cascade)
              """;
        await conn.ExecuteAsync(new CommandDefinition(sql, p, tx, cancellationToken: ct));

        await conn.ExecuteAsync(new CommandDefinition("""
            INSERT dbo.TargetScore (RoundId, TargetNo, TargetType, OverridePoints, Points, Arrows)
            VALUES (@roundId, @TargetNo, @Type, @OverridePoints, @Points, @arrows);
            """,
            round.Targets.Select(t => new
            {
                roundId, t.TargetNo, t.Type, t.OverridePoints, t.Points,
                arrows = t.Type == RoundValidator.Woodsman ? (t.Arrows ?? "") : null,
            }),
            tx, cancellationToken: ct));

        await conn.ExecuteAsync(new CommandDefinition("""
            INSERT dbo.AnimalScore (RoundId, TargetNo, AnimalIndex, State, ArrowNo, Hit, Points)
            VALUES (@roundId, @TargetNo, @Index, @State, @ArrowNo, @Hit, @Points);
            """,
            round.Targets.SelectMany(t => t.Animals.Select(a =>
                new { roundId, t.TargetNo, a.Index, a.State, a.ArrowNo, a.Hit, a.Points })),
            tx, cancellationToken: ct));

        return await conn.ExecuteScalarAsync<DateTime>(new CommandDefinition(
            "SELECT ServerUpdatedUtc FROM dbo.Round WHERE RoundId = @roundId;", new { roundId }, tx, cancellationToken: ct));
    }
}

/// <summary>Loads a stored round with its targets and animals.</summary>
public static class RoundReader
{
    public sealed record Loaded(Guid ArcherId, StoredRoundDto Round);

    public static async Task<Loaded?> ReadAsync(
        Microsoft.Data.SqlClient.SqlConnection conn, Guid roundId, Guid viewer, bool viewerIsAdmin, CancellationToken ct)
    {
        using var multi = await conn.QueryMultipleAsync(new CommandDefinition($"""
            SELECT r.RoundId, r.ArcherId, r.ShootDate, r.TargetCount, r.ArcherClass, r.AgeGroup, r.BowStyle, r.Status,
                   r.TotalPoints, r.IsDeleted, r.ClientCreatedUtc, r.ClientUpdatedUtc, r.ServerUpdatedUtc,
                   r.LocationId, {LocationEndpoints.VisibleNameSql} AS LocationName, r.RoundType, r.StartTarget
            FROM dbo.Round r LEFT JOIN dbo.ShootLocation l ON l.LocationId = r.LocationId
            WHERE r.RoundId = @roundId;
            SELECT TargetNo, TargetType, OverridePoints, Points, Arrows
            FROM dbo.TargetScore WHERE RoundId = @roundId ORDER BY TargetNo;
            SELECT TargetNo, AnimalIndex, State, ArrowNo, Hit, Points
            FROM dbo.AnimalScore WHERE RoundId = @roundId ORDER BY TargetNo, AnimalIndex;
            """, new { roundId, viewer, viewerIsAdmin }, cancellationToken: ct));

        var r = await multi.ReadSingleOrDefaultAsync<RoundRow>();
        if (r is null) return null;
        var targets = (await multi.ReadAsync<TargetRow>()).ToList();
        var animals = (await multi.ReadAsync<AnimalRow>()).ToLookup(a => a.TargetNo);

        var dto = new StoredRoundDto(
            r.RoundId, DateOnly.FromDateTime(r.ShootDate), r.TargetCount, r.ArcherClass.Trim(), r.AgeGroup, r.BowStyle,
            r.Status, r.TotalPoints, r.IsDeleted, Utc(r.ClientCreatedUtc), Utc(r.ClientUpdatedUtc), Utc(r.ServerUpdatedUtc),
            targets.Select(t => new TargetDto(t.TargetNo, t.TargetType, t.OverridePoints, t.Points,
                animals[t.TargetNo].Select(a => new AnimalDto(a.AnimalIndex, a.State, a.ArrowNo, a.Hit, a.Points)).ToList(), t.Arrows))
                .ToList(),
            r.LocationId, r.LocationName, r.RoundType, r.StartTarget);
        return new Loaded(r.ArcherId, dto);
    }

    /// <summary>Loads several rounds in three queries, returned in the order of <paramref name="ids"/>.</summary>
    public static async Task<List<StoredRoundDto>> ReadManyAsync(
        Microsoft.Data.SqlClient.SqlConnection conn, IReadOnlyList<Guid> ids, Guid viewer, bool viewerIsAdmin, CancellationToken ct)
    {
        if (ids.Count == 0) return [];
        using var multi = await conn.QueryMultipleAsync(new CommandDefinition($"""
            SELECT r.RoundId, r.ArcherId, r.ShootDate, r.TargetCount, r.ArcherClass, r.AgeGroup, r.BowStyle, r.Status,
                   r.TotalPoints, r.IsDeleted, r.ClientCreatedUtc, r.ClientUpdatedUtc, r.ServerUpdatedUtc,
                   r.LocationId, {LocationEndpoints.VisibleNameSql} AS LocationName, r.RoundType, r.StartTarget
            FROM dbo.Round r LEFT JOIN dbo.ShootLocation l ON l.LocationId = r.LocationId
            WHERE r.RoundId IN @ids;
            SELECT RoundId, TargetNo, TargetType, OverridePoints, Points, Arrows
            FROM dbo.TargetScore WHERE RoundId IN @ids ORDER BY RoundId, TargetNo;
            SELECT RoundId, TargetNo, AnimalIndex, State, ArrowNo, Hit, Points
            FROM dbo.AnimalScore WHERE RoundId IN @ids ORDER BY RoundId, TargetNo, AnimalIndex;
            """, new { ids, viewer, viewerIsAdmin }, cancellationToken: ct));

        var rounds = (await multi.ReadAsync<RoundRow>()).ToDictionary(r => r.RoundId);
        var targets = (await multi.ReadAsync<TargetRowWithRound>()).ToLookup(t => t.RoundId);
        var animals = (await multi.ReadAsync<AnimalRowWithRound>()).ToLookup(a => (a.RoundId, a.TargetNo));

        return ids.Where(rounds.ContainsKey).Select(id =>
        {
            var r = rounds[id];
            return new StoredRoundDto(
                r.RoundId, DateOnly.FromDateTime(r.ShootDate), r.TargetCount, r.ArcherClass.Trim(), r.AgeGroup, r.BowStyle,
                r.Status, r.TotalPoints, r.IsDeleted, Utc(r.ClientCreatedUtc), Utc(r.ClientUpdatedUtc), Utc(r.ServerUpdatedUtc),
                targets[id].Select(t => new TargetDto(t.TargetNo, t.TargetType, t.OverridePoints, t.Points,
                    animals[(id, t.TargetNo)].Select(a => new AnimalDto(a.AnimalIndex, a.State, a.ArrowNo, a.Hit, a.Points)).ToList(), t.Arrows))
                    .ToList(),
                r.LocationId, r.LocationName, r.RoundType, r.StartTarget);
        }).ToList();
    }

    private static DateTime Utc(DateTime d) => DateTime.SpecifyKind(d, DateTimeKind.Utc);

    private sealed record TargetRowWithRound(Guid RoundId, short TargetNo, string TargetType, short? OverridePoints, short Points, string? Arrows);

    private sealed record AnimalRowWithRound(Guid RoundId, short TargetNo, byte AnimalIndex, string State, byte? ArrowNo, string? Hit, short Points);

    private sealed record RoundRow(Guid RoundId, Guid ArcherId, DateTime ShootDate, short TargetCount, string ArcherClass,
        string AgeGroup, string BowStyle, string Status, int TotalPoints, bool IsDeleted,
        DateTime ClientCreatedUtc, DateTime ClientUpdatedUtc, DateTime ServerUpdatedUtc,
        Guid? LocationId, string? LocationName, string RoundType, short? StartTarget);

    private sealed record TargetRow(short TargetNo, string TargetType, short? OverridePoints, short Points, string? Arrows);

    private sealed record AnimalRow(short TargetNo, byte AnimalIndex, string State, byte? ArrowNo, string? Hit, short Points);
}
