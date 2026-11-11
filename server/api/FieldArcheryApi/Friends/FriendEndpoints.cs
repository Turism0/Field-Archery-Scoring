using System.Security.Claims;
using Dapper;
using Microsoft.Data.SqlClient;
using FieldArcheryApi.Auth;
using FieldArcheryApi.Data;
using FieldArcheryApi.Locations;
using FieldArcheryApi.Rounds;

namespace FieldArcheryApi.Friends;

/// <summary>
/// Friends (step 7a). Request by username; the other archer accepts; then each can see the other's
/// FINISHED rounds, read-only. All calls need "Authorization: Bearer &lt;token&gt;".
///   GET    /api/friends                              → { friends: [...summary], incoming: [...], outgoing: [...] }
///          (each friend has recent: their latest 20 finished Big Game rounds with each bow style, for their personal
///          handicaps (API 2.9; 10 in all before): { shootDate, targetCount, animalCount, archerClass, ageGroup, bowStyle,
///          totalPoints, targetsScored })
///   POST   /api/friends/requests        { username } → 201 { result: "requested" } or 200 { result: "accepted" }
///                                                      (accepts straight away if they had already asked you)
///   POST   /api/friends/{archerId}/accept            → 200, accept their request
///   DELETE /api/friends/{archerId}                   → 204, decline, cancel or unfriend
///   GET    /api/friends/{archerId}/rounds            → { rounds: [...] } their finished rounds, newest first
///   GET    /api/friends/{archerId}/rounds/{roundId}  → one finished round with targets and animals
/// Anything about someone who isn't an accepted friend is reported as not found.
/// </summary>
public static class FriendEndpoints
{
    public static void MapFriendEndpoints(this WebApplication app)
    {
        var friends = app.MapGroup("/api/friends").RequireAuthorization();
        friends.MapGet("/", List);
        friends.MapPost("/requests", Request);
        friends.MapPost("/{archerId:guid}/accept", Accept);
        friends.MapDelete("/{archerId:guid}", Remove);
        friends.MapGet("/{archerId:guid}/rounds", FriendRounds);
        friends.MapGet("/{archerId:guid}/rounds/{roundId:guid}", FriendRound);
    }

    public sealed record UsernameRequest(string? Username);

    /// <summary>Rounds / best / average are Big Game (out of 24 per animal); Woodsman has its own count and best.</summary>
    public sealed record FriendSummary(
        Guid ArcherId, string Username, DateTime? FriendsSinceUtc,
        int Rounds, int? BestTotal, DateTime? LastShootDate, double? AveragePerAnimal,
        int WoodsmanRounds, int? WoodsmanBest);

    private sealed record RecentRow(Guid ArcherId, DateTime ShootDate, short TargetCount, int AnimalCount, string ArcherClass,
        string AgeGroup, string BowStyle, int TotalPoints, int TargetsScored, int FunShots = 0, int FunPoints = 0);

    public sealed record PendingRequest(Guid ArcherId, string Username, DateTime RequestedUtc, bool Outgoing);

    public sealed record FriendRoundSummary(
        Guid RoundId, DateTime ShootDate, short TargetCount, string ArcherClass, string AgeGroup, string BowStyle, int TotalPoints,
        string? LocationName, string RoundType, int AnimalCount = 0, int PredatorPrey = 0, int Fans = 0, int FunShots = 0, int FunPoints = 0);

    // The pair is stored once, lower ArcherId first. SQL Server decides "lower" (its own uniqueidentifier order).
    private const string PairWhere = """
        ArcherLowId  = CASE WHEN @me < @other THEN @me ELSE @other END
        AND ArcherHighId = CASE WHEN @me < @other THEN @other ELSE @me END
        """;

    private static async Task<IResult> List(ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var me = user.ArcherId();
        await using var conn = await db.OpenAsync(ct);

        // Friends with a summary of their finished rounds. The average is per animal over finished rounds
        // (predator/prey = 2, fan = n), so it's out of 24.
        // API 3.1: fun shots are left out of these (their points aren't in TotalPoints here).
        var friends = (await conn.QueryAsync<FriendSummary>(new CommandDefinition($"""
            WITH fr AS (SELECT FriendId, AcceptedUtc FROM dbo.vFriend WHERE ArcherId = @me),
            done AS (
                SELECT r.RoundId, r.ArcherId, {RoundShape.StatsPointsSql("r")} AS TotalPoints, r.ShootDate
                FROM dbo.Round r JOIN fr ON fr.FriendId = r.ArcherId
                WHERE r.Status = 'COMPLETED' AND r.IsDeleted = 0 AND r.RoundType = 'BIG_GAME'),
            wd AS (
                SELECT r.ArcherId, COUNT(*) AS WoodsmanRounds, MAX(r.TotalPoints) AS WoodsmanBest, MAX(r.ShootDate) AS LastShootDate
                FROM dbo.Round r JOIN fr ON fr.FriendId = r.ArcherId
                WHERE r.Status = 'COMPLETED' AND r.IsDeleted = 0 AND r.RoundType = 'WOODSMAN'
                GROUP BY r.ArcherId),
            rs AS (
                SELECT ArcherId, COUNT(*) AS Rounds, MAX(TotalPoints) AS BestTotal,
                       MAX(ShootDate) AS LastShootDate, SUM(CAST(TotalPoints AS bigint)) AS Points
                FROM done GROUP BY ArcherId),
            an AS (
                SELECT d.ArcherId, COUNT(*) AS Animals
                FROM dbo.AnimalScore s JOIN done d ON d.RoundId = s.RoundId
                GROUP BY d.ArcherId)
            SELECT a.ArcherId, a.Username, fr.AcceptedUtc AS FriendsSinceUtc,
                   ISNULL(rs.Rounds, 0) AS Rounds, rs.BestTotal,
                   CAST(CASE WHEN wd.LastShootDate > rs.LastShootDate OR rs.LastShootDate IS NULL THEN wd.LastShootDate
                             ELSE rs.LastShootDate END AS datetime2(3)) AS LastShootDate,
                   CASE WHEN an.Animals > 0 THEN CAST(rs.Points AS float) / an.Animals END AS AveragePerAnimal,
                   ISNULL(wd.WoodsmanRounds, 0) AS WoodsmanRounds, wd.WoodsmanBest
            FROM fr
            JOIN dbo.Archer a ON a.ArcherId = fr.FriendId
            LEFT JOIN rs ON rs.ArcherId = a.ArcherId
            LEFT JOIN an ON an.ArcherId = a.ArcherId
            LEFT JOIN wd ON wd.ArcherId = a.ArcherId
            ORDER BY a.Username;
            """, new { me }, cancellationToken: ct))).ToList();

        // Each friend's latest 20 finished Big Game rounds with each bow style: the phone works out their personal
        // handicap per bow (API 2.9; was the latest 10 for the rating out of 1000). targetsScored: targets with a score
        // entered (an override, or an animal that isn't still to score); only rounds with every target scored count.
        // API 3.1: funShots / funPoints so the phone can leave fun shots out of the handicap.
        var recent = (await conn.QueryAsync<RecentRow>(new CommandDefinition($"""
            WITH fr AS (SELECT FriendId FROM dbo.vFriend WHERE ArcherId = @me),
            ranked AS (
                SELECT r.RoundId, r.ArcherId, r.ShootDate, r.TargetCount, r.ArcherClass, r.AgeGroup, r.BowStyle, r.TotalPoints,
                       ROW_NUMBER() OVER (PARTITION BY r.ArcherId, r.BowStyle ORDER BY r.ShootDate DESC, r.ClientCreatedUtc DESC) AS n
                FROM dbo.Round r JOIN fr ON fr.FriendId = r.ArcherId
                WHERE r.Status = 'COMPLETED' AND r.IsDeleted = 0 AND r.RoundType = 'BIG_GAME')
            SELECT k.ArcherId, CAST(k.ShootDate AS datetime2(3)) AS ShootDate, k.TargetCount,
                   (SELECT COUNT(*) FROM dbo.AnimalScore s WHERE s.RoundId = k.RoundId) AS AnimalCount,
                   k.ArcherClass, k.AgeGroup, k.BowStyle, k.TotalPoints,
                   (SELECT COUNT(*) FROM dbo.TargetScore t WHERE t.RoundId = k.RoundId
                      AND (t.OverridePoints IS NOT NULL
                           OR EXISTS (SELECT 1 FROM dbo.AnimalScore s WHERE s.RoundId = t.RoundId AND s.TargetNo = t.TargetNo AND s.State <> 'PENDING'))) AS TargetsScored,
                   (SELECT COUNT(*) FROM dbo.TargetScore t WHERE t.RoundId = k.RoundId AND t.TargetType = 'FUN') AS FunShots,
                   {RoundShape.FunPointsSql("k")} AS FunPoints
            FROM ranked k WHERE k.n <= 20;
            """, new { me }, cancellationToken: ct))).ToLookup(x => x.ArcherId);

        var pending = (await conn.QueryAsync<PendingRequest>(new CommandDefinition("""
            SELECT a.ArcherId, a.Username, f.RequestedUtc,
                   CAST(CASE WHEN f.RequestedById = @me THEN 1 ELSE 0 END AS bit) AS Outgoing
            FROM dbo.Friendship f
            JOIN dbo.Archer a ON a.ArcherId = CASE WHEN f.ArcherLowId = @me THEN f.ArcherHighId ELSE f.ArcherLowId END
            WHERE f.Status = 'PENDING' AND (f.ArcherLowId = @me OR f.ArcherHighId = @me)
            ORDER BY f.RequestedUtc DESC;
            """, new { me }, cancellationToken: ct))).ToList();

        return Results.Ok(new
        {
            friends = friends.Select(f => new
            {
                f.ArcherId, f.Username, FriendsSinceUtc = Utc(f.FriendsSinceUtc), f.Rounds, f.BestTotal, f.LastShootDate,
                AveragePerAnimal = f.AveragePerAnimal is { } avg ? Math.Round(avg, 1) : (double?)null,
                f.WoodsmanRounds, f.WoodsmanBest,
                recent = recent[f.ArcherId].Select(x => new
                {
                    shootDate = DateOnly.FromDateTime(x.ShootDate), targetCount = (int)x.TargetCount, animalCount = x.AnimalCount,
                    archerClass = x.ArcherClass.Trim(), ageGroup = x.AgeGroup, bowStyle = x.BowStyle, totalPoints = x.TotalPoints,
                    targetsScored = x.TargetsScored, funShots = x.FunShots, funPoints = x.FunPoints,
                }),
            }),
            incoming = pending.Where(p => !p.Outgoing).Select(p => p with { RequestedUtc = Utc(p.RequestedUtc)!.Value }),
            outgoing = pending.Where(p => p.Outgoing).Select(p => p with { RequestedUtc = Utc(p.RequestedUtc)!.Value }),
        });
    }

    private static async Task<IResult> Request(UsernameRequest req, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var me = user.ArcherId();
        var username = req.Username?.Trim() ?? "";
        if (username.Length == 0) return Error(400, "missing_username", "Enter your friend's username.");

        await using var conn = await db.OpenAsync(ct);
        var other = await conn.QuerySingleOrDefaultAsync<Guid?>(new CommandDefinition(
            "SELECT ArcherId FROM dbo.Archer WHERE Username = @username AND IsDisabled = 0;", new { username }, cancellationToken: ct));
        if (other is null) return Error(404, "no_such_user", $"There's no archer called {username}.");
        if (other == me) return Error(400, "cannot_friend_self", "You can't add yourself as a friend.");

        await using var tx = (SqlTransaction)await conn.BeginTransactionAsync(ct);
        var existing = await conn.QuerySingleOrDefaultAsync<Existing>(new CommandDefinition($"""
            SELECT Status, RequestedById FROM dbo.Friendship WITH (UPDLOCK, HOLDLOCK) WHERE {PairWhere};
            """, new { me, other }, tx, cancellationToken: ct));

        if (existing is { Status: "ACCEPTED" })
            return Error(409, "already_friends", $"You're already friends with {username}.");
        if (existing is { Status: "PENDING" } && existing.RequestedById == me)
            return Error(409, "already_requested", $"You've already asked {username}. Waiting for them to accept.");

        if (existing is { Status: "PENDING" })
        {
            // They had already asked us: asking back means yes.
            await conn.ExecuteAsync(new CommandDefinition($"""
                UPDATE dbo.Friendship SET Status = 'ACCEPTED', AcceptedUtc = SYSUTCDATETIME() WHERE {PairWhere};
                """, new { me, other }, tx, cancellationToken: ct));
            await tx.CommitAsync(ct);
            return Results.Ok(new { result = "accepted", message = $"You and {username} are now friends." });
        }

        await conn.ExecuteAsync(new CommandDefinition("""
            INSERT dbo.Friendship (ArcherLowId, ArcherHighId, RequestedById, Status)
            VALUES (CASE WHEN @me < @other THEN @me ELSE @other END,
                    CASE WHEN @me < @other THEN @other ELSE @me END,
                    @me, 'PENDING');
            """, new { me, other }, tx, cancellationToken: ct));
        await tx.CommitAsync(ct);
        return Results.Json(new { result = "requested", message = $"Friend request sent to {username}." }, statusCode: 201);
    }

    private static async Task<IResult> Accept(Guid archerId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var me = user.ArcherId();
        await using var conn = await db.OpenAsync(ct);
        // Only a request THEY sent to us can be accepted.
        var changed = await conn.ExecuteAsync(new CommandDefinition($"""
            UPDATE dbo.Friendship SET Status = 'ACCEPTED', AcceptedUtc = SYSUTCDATETIME()
            WHERE {PairWhere} AND Status = 'PENDING' AND RequestedById = @other;
            """, new { me, other = archerId }, cancellationToken: ct));
        return changed == 1
            ? Results.Ok(new { result = "accepted" })
            : Error(404, "no_request", "There's no friend request from that archer.");
    }

    private static async Task<IResult> Remove(Guid archerId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        await conn.ExecuteAsync(new CommandDefinition($"DELETE dbo.Friendship WHERE {PairWhere};",
            new { me = user.ArcherId(), other = archerId }, cancellationToken: ct));
        return Results.NoContent();
    }

    private static async Task<IResult> FriendRounds(Guid archerId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        if (!await AreFriendsAsync(conn, user.ArcherId(), archerId, ct)) return NotFriends();

        var rounds = await conn.QueryAsync<FriendRoundSummary>(new CommandDefinition($"""
            SELECT r.RoundId, CAST(r.ShootDate AS datetime2(3)) AS ShootDate, r.TargetCount, r.ArcherClass, r.AgeGroup,
                   r.BowStyle, r.TotalPoints, {LocationEndpoints.VisibleNameSql} AS LocationName, r.RoundType,
                   {RoundShape.ColumnsSql}
            FROM dbo.Round r LEFT JOIN dbo.ShootLocation l ON l.LocationId = r.LocationId
            WHERE r.ArcherId = @archerId AND r.Status = 'COMPLETED' AND r.IsDeleted = 0
            ORDER BY r.ShootDate DESC, r.ClientCreatedUtc DESC;
            """, new { archerId, viewer = user.ArcherId(), viewerIsAdmin = user.IsAdmin() }, cancellationToken: ct));

        return Results.Ok(new
        {
            rounds = rounds.Select(r => new
            {
                r.RoundId,
                shootDate = DateOnly.FromDateTime(r.ShootDate),
                targetCount = (int)r.TargetCount,
                archerClass = r.ArcherClass.Trim(),
                r.AgeGroup,
                r.BowStyle,
                r.TotalPoints,
                r.LocationName,
                r.RoundType,
                r.AnimalCount,
                r.PredatorPrey,
                r.Fans,
                r.FunShots,
                r.FunPoints,
            }),
        });
    }

    private static async Task<IResult> FriendRound(Guid archerId, Guid roundId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        if (!await AreFriendsAsync(conn, user.ArcherId(), archerId, ct)) return NotFriends();

        var loaded = await RoundReader.ReadAsync(conn, roundId, user.ArcherId(), user.IsAdmin(), ct);
        return loaded is { } l && l.ArcherId == archerId && l.Round.Status == "COMPLETED" && !l.Round.IsDeleted
            ? Results.Ok(l.Round)
            : Error(404, "not_found", "Round not found.");
    }

    private static async Task<bool> AreFriendsAsync(SqlConnection conn, Guid me, Guid other, CancellationToken ct) =>
        await conn.ExecuteScalarAsync<int>(new CommandDefinition(
            "SELECT COUNT(*) FROM dbo.vFriend WHERE ArcherId = @me AND FriendId = @other;",
            new { me, other }, cancellationToken: ct)) > 0;

    private static IResult NotFriends() =>
        Error(404, "not_friends", "You can only see the rounds of archers who have accepted you as a friend.");

    private static IResult Error(int status, string code, string message) =>
        Results.Json(new { error = code, message }, statusCode: status);

    private static DateTime? Utc(DateTime? d) => d is { } v ? DateTime.SpecifyKind(v, DateTimeKind.Utc) : null;

    private sealed record Existing(string Status, Guid RequestedById);
}
