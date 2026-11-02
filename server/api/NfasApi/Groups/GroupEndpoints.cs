using System.Data;
using System.Security.Claims;
using System.Text.Json;
using Dapper;
using Microsoft.Data.SqlClient;
using NfasApi.Auth;
using NfasApi.Data;
using NfasApi.Locations;
using NfasApi.Rounds;

namespace NfasApi.Groups;

/// <summary>
/// Group scoring (API 1.9.06). All calls need "Authorization: Bearer &lt;token&gt;".
///   PUT    /api/groups/{groupId}                     host: create or update the group (id and code made by the host's phone)
///          { code, method, roundType, targetCount, shootDate, locationId, status, members: [HOST, FRIEND and GUEST members],
///            order?: [every memberId in scoring order], scorers?: [{ cardMemberId, scorerMemberId }] }
///          → the group (as GET). 409 code_taken if another open group has the code.
///   POST   /api/groups/join  { code, groupId?, memberId, archerClass, ageGroup, bowStyle, roundId? }  → the group.
///          Joining again updates your details. A friend the host added who then joins becomes a joined member.
///   GET    /api/groups                               → { groups: [...] } groups you're in (last 30 days), newest first
///   GET    /api/groups/{groupId}?since=              → { group fields, me, members, entries (changed after since), cells, totals }
///   PUT    /api/groups/{groupId}/entries  { entries: [{ targetNo, cardMemberId, scorerMemberId, target, enteredUtc }] }
///          → { results: [{ targetNo, cardMemberId, state, points }] }  (state WAITING / MATCHED / MISMATCH)
///   DELETE /api/groups/{groupId}/members/{memberId}  host removes anyone but the host; a joined archer can leave.
/// Methods: SINGLE (one scorer, others watching), DOUBLE (two scorers, saved when both match), MULTI (3+ scorers, nobody
/// scores their own card, two per card, shared evenly), SELF (everyone scores their own). When the group is STARTED
/// without scorers, the usual ones are worked out (GroupRules.Default). Codes stop working when the group is closed or
/// 24 hours after it was first sent. Groups (with guests' names and scores) are deleted 30 days after they were made.
/// Members' own rounds still upload through /api/rounds (with groupId); friends' rounds through /api/offers.
/// </summary>
public static class GroupEndpoints
{
    public sealed record MemberIn(Guid MemberId, string? Kind, Guid? ArcherId, string? GuestName,
        string ArcherClass, string AgeGroup, string BowStyle, Guid? RoundId = null);

    public sealed record ScorerIn(Guid CardMemberId, Guid ScorerMemberId);

    public sealed record GroupIn(string? Code, string? Method, string? RoundType, int TargetCount, DateOnly ShootDate,
        Guid? LocationId, string? Status, IReadOnlyList<MemberIn>? Members, IReadOnlyList<Guid>? Order = null,
        IReadOnlyList<ScorerIn>? Scorers = null);

    public sealed record JoinIn(string? Code, Guid? GroupId, Guid MemberId, string ArcherClass, string AgeGroup, string BowStyle,
        Guid? RoundId = null);

    public sealed record EntryIn(int TargetNo, Guid CardMemberId, Guid ScorerMemberId, TargetDto? Target, DateTime EnteredUtc);

    public sealed record EntriesIn(IReadOnlyList<EntryIn>? Entries);

    private sealed record GroupRow(Guid GroupId, string Code, Guid HostId, string Method, string RoundType, short TargetCount,
        DateTime ShootDate, Guid? LocationId, string Status, DateTime CreatedUtc, DateTime UpdatedUtc);

    private sealed record MemberRow(Guid MemberId, string Kind, Guid? ArcherId, string? GuestName, string? Username,
        string ArcherClass, string AgeGroup, string BowStyle, short SortOrder, Guid? RoundId);

    private sealed record EntryRow(short TargetNo, Guid CardMemberId, Guid ScorerMemberId, string EntryJson, short Points,
        DateTime EnteredUtc, DateTime ServerUpdatedUtc);

    private static readonly HashSet<string> Classes = ["L", "G"];
    private static readonly HashSet<string> AgeGroups = ["ADULT", "JUNIOR", "CUB"];
    private static readonly HashSet<string> BowStyles = ["AFB", "BB", "BH", "CL", "XB", "TXB", "FS", "HT", "LB", "PV", "TD", "TB", "UL"];
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    public static void MapGroupEndpoints(this WebApplication app)
    {
        var groups = app.MapGroup("/api/groups").RequireAuthorization();
        groups.MapGet("/", Mine);
        groups.MapPost("/join", Join);
        groups.MapPut("/{groupId:guid}", Put);
        groups.MapGet("/{groupId:guid}", Get);
        groups.MapPut("/{groupId:guid}/entries", PutEntries);
        groups.MapDelete("/{groupId:guid}/members/{memberId:guid}", RemoveMember);
    }

    // ---- Create / update (host) -------------------------------------------------------------------------------

    private static async Task<IResult> Put(Guid groupId, GroupIn g, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var me = user.ArcherId();
        var code = g.Code?.Trim().ToUpperInvariant();
        var method = g.Method?.Trim().ToUpperInvariant() ?? "";
        var roundType = string.IsNullOrWhiteSpace(g.RoundType) ? RoundValidator.BigGame : g.RoundType.Trim().ToUpperInvariant();
        var status = string.IsNullOrWhiteSpace(g.Status) ? GroupRules.Open : g.Status.Trim().ToUpperInvariant();

        var problems = new List<string>();
        if (!GroupRules.IsCode(code)) problems.Add($"The code must be 6 characters from {GroupRules.CodeAlphabet}.");
        if (!GroupRules.Methods.Contains(method)) problems.Add("Method must be SINGLE, DOUBLE, MULTI or SELF.");
        if (roundType is not (RoundValidator.BigGame or RoundValidator.Woodsman)) problems.Add("Round type must be BIG_GAME or WOODSMAN.");
        if (status is not (GroupRules.Open or GroupRules.Started or GroupRules.Closed)) problems.Add("Status must be OPEN, STARTED or CLOSED.");
        if (g.TargetCount is < 1 or > RoundValidator.MaxTargets) problems.Add($"Target count must be 1 to {RoundValidator.MaxTargets}.");
        if (g.ShootDate < new DateOnly(2000, 1, 1) || g.ShootDate > DateOnly.FromDateTime(DateTime.UtcNow.AddDays(1)))
            problems.Add("Shoot date is out of range.");
        var sent = g.Members ?? [];
        foreach (var m in sent) problems.AddRange(MemberProblems(m.ArcherClass, m.AgeGroup, m.BowStyle));
        var kinds = sent.Select(m => m.Kind?.Trim().ToUpperInvariant()).ToList();
        if (kinds.Any(k => k is not (GroupRules.Host or GroupRules.Friend or GroupRules.Guest)))
            problems.Add("The host sends HOST, FRIEND and GUEST members; archers join themselves.");
        if (kinds.Count(k => k == GroupRules.Host) != 1) problems.Add("Send exactly one HOST member (you).");
        if (sent.Select(m => m.MemberId).Distinct().Count() != sent.Count) problems.Add("Member ids must be different.");
        for (var i = 0; i < sent.Count; i++)
        {
            var m = sent[i];
            if (kinds[i] == GroupRules.Guest && (m.GuestName?.Trim() is not { Length: >= 1 and <= 40 } || m.ArcherId is not null))
                problems.Add("A guest has a name (1 to 40 characters) and no account.");
            if (kinds[i] == GroupRules.Friend && (m.ArcherId is null || m.ArcherId == me))
                problems.Add("A friend member needs the friend's archer id.");
            if (kinds[i] == GroupRules.Host && m.ArcherId is { } h && h != me) problems.Add("The HOST member is you.");
        }
        if (problems.Count > 0) return Bad("invalid_group", problems);

        await using var conn = await db.OpenAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(IsolationLevel.Serializable, ct);

        var existing = await conn.QuerySingleOrDefaultAsync<GroupRow>(new CommandDefinition(
            $"SELECT {GroupCols} FROM dbo.ScoreGroup WITH (UPDLOCK, HOLDLOCK) WHERE GroupId = @groupId;", new { groupId }, tx, cancellationToken: ct));
        if (existing is not null && existing.HostId != me) return NotFound();
        if (existing is not null && existing.Status == GroupRules.Closed && status != GroupRules.Closed)
            return Error(409, "group_closed", "This group has been closed.");
        if (existing is not null && existing.Status != GroupRules.Open && (existing.Method != method || existing.RoundType != roundType))
            return Error(409, "group_started", "The method and round type can't change once scoring has started.");

        // Friends must be friends of the host.
        var friendIds = sent.Where((_, i) => kinds[i] == GroupRules.Friend).Select(m => m.ArcherId!.Value).Distinct().ToList();
        if (friendIds.Count > 0)
        {
            var ok = await conn.ExecuteScalarAsync<int>(new CommandDefinition(
                "SELECT COUNT(*) FROM dbo.vFriend WHERE ArcherId = @me AND FriendId IN @friendIds;", new { me, friendIds }, tx, cancellationToken: ct));
            if (ok != friendIds.Count) return Bad("invalid_group", ["Only your friends can be added by name; others join with the code."]);
        }

        Guid? locationId = g.LocationId is { } lid && await LocationEndpoints.CurrentAsync(conn, lid, tx, ct) is { } loc ? loc.LocationId : null;

        if (existing is null)
        {
            // Groups are temporary: tidy away old ones (with guests' names and scores) whenever a new one starts.
            await conn.ExecuteAsync(new CommandDefinition(
                "DELETE dbo.ScoreGroup WHERE CreatedUtc < @cutoff;", new { cutoff = DateTime.UtcNow - GroupRules.GroupLifetime }, tx, cancellationToken: ct));
        }
        if (existing is null || existing.Code != code)
        {
            var taken = await conn.ExecuteScalarAsync<int>(new CommandDefinition("""
                SELECT COUNT(*) FROM dbo.ScoreGroup
                WHERE Code = @code AND GroupId <> @groupId AND Status <> 'CLOSED' AND CreatedUtc > @since;
                """, new { code, groupId, since = DateTime.UtcNow - GroupRules.CodeLifetime }, tx, cancellationToken: ct));
            if (taken > 0) return Error(409, "code_taken", "Another group is using that code. Make a new one.");
        }

        var p = new { groupId, code, me, method, roundType, targetCount = (short)g.TargetCount,
            shootDate = g.ShootDate.ToDateTime(TimeOnly.MinValue), locationId, status };
        await conn.ExecuteAsync(new CommandDefinition(existing is null
            ? """
              INSERT dbo.ScoreGroup (GroupId, Code, HostId, Method, RoundType, TargetCount, ShootDate, LocationId, Status)
              VALUES (@groupId, @code, @me, @method, @roundType, @targetCount, @shootDate, @locationId, @status);
              """
            : """
              UPDATE dbo.ScoreGroup
              SET Code = @code, Method = @method, RoundType = @roundType, TargetCount = @targetCount, ShootDate = @shootDate,
                  LocationId = @locationId, Status = @status, UpdatedUtc = SYSUTCDATETIME()
              WHERE GroupId = @groupId;
              """, p, tx, cancellationToken: ct));

        // Host-managed members (HOST, FRIEND, GUEST) are replaced by what was sent; joined archers stay.
        var current = (await conn.QueryAsync<MemberRow>(new CommandDefinition(MembersSql, new { groupId }, tx, cancellationToken: ct))).ToList();
        var sentIds = sent.Select(m => m.MemberId).ToHashSet();
        var clash = current.FirstOrDefault(c => sentIds.Contains(c.MemberId) && c.Kind == GroupRules.Joined);
        if (clash is not null) return Bad("invalid_group", ["A member id belongs to an archer who joined."]);
        var gone = current.Where(c => c.Kind != GroupRules.Joined && !sentIds.Contains(c.MemberId)).Select(c => c.MemberId).ToList();
        await DeleteMembersAsync(conn, tx, groupId, gone, ct);

        var nextOrder = current.Where(c => !gone.Contains(c.MemberId)).Select(c => (int)c.SortOrder).DefaultIfEmpty(0).Max() + 1;
        var known = current.ToDictionary(c => c.MemberId);
        for (var i = 0; i < sent.Count; i++)
        {
            var m = sent[i];
            var order = known.TryGetValue(m.MemberId, out var was) ? was.SortOrder : (short)(kinds[i] == GroupRules.Host ? 0 : nextOrder++);
            try
            {
                await conn.ExecuteAsync(new CommandDefinition("""
                    MERGE dbo.ScoreGroupMember AS t
                    USING (SELECT @groupId AS GroupId, @MemberId AS MemberId) AS s
                    ON t.GroupId = s.GroupId AND t.MemberId = s.MemberId
                    WHEN MATCHED THEN UPDATE SET Kind = @kind, ArcherId = @archerId, GuestName = @guestName, ArcherClass = @ArcherClass,
                        AgeGroup = @AgeGroup, BowStyle = @BowStyle, RoundId = @RoundId
                    WHEN NOT MATCHED THEN INSERT (GroupId, MemberId, Kind, ArcherId, GuestName, ArcherClass, AgeGroup, BowStyle, SortOrder, RoundId)
                        VALUES (@groupId, @MemberId, @kind, @archerId, @guestName, @ArcherClass, @AgeGroup, @BowStyle, @order, @RoundId);
                    """,
                    new
                    {
                        groupId, m.MemberId, kind = kinds[i],
                        archerId = kinds[i] == GroupRules.Host ? me : kinds[i] == GroupRules.Friend ? m.ArcherId : null,
                        guestName = kinds[i] == GroupRules.Guest ? m.GuestName!.Trim() : null,
                        m.ArcherClass, m.AgeGroup, m.BowStyle, m.RoundId, order,
                    }, tx, cancellationToken: ct));
            }
            catch (SqlException ex) when (Db.IsDuplicateKey(ex))
            {
                return Bad("invalid_group", ["Someone is in the group twice."]);
            }
        }

        var members = (await conn.QueryAsync<MemberRow>(new CommandDefinition(MembersSql, new { groupId }, tx, cancellationToken: ct))).ToList();

        if (g.Order is { } wanted)
        {
            if (!wanted.OrderBy(x => x).SequenceEqual(members.Select(m => m.MemberId).OrderBy(x => x)))
                return Bad("invalid_group", ["The order must list every member once."]);
            await conn.ExecuteAsync(new CommandDefinition(
                "UPDATE dbo.ScoreGroupMember SET SortOrder = @order WHERE GroupId = @groupId AND MemberId = @memberId;",
                wanted.Select((id, i) => new { groupId, memberId = id, order = (short)i }), tx, cancellationToken: ct));
            members = (await conn.QueryAsync<MemberRow>(new CommandDefinition(MembersSql, new { groupId }, tx, cancellationToken: ct))).ToList();
        }

        var rules = members.Select(m => new GroupRules.Member(m.MemberId, m.Kind, m.SortOrder)).ToList();
        if (g.Scorers is { } scorersIn)
        {
            var scorers = scorersIn.Select(s => new GroupRules.Scorer(s.CardMemberId, s.ScorerMemberId)).ToList();
            var bad = GroupRules.Validate(method, rules, scorers);
            if (bad.Count > 0) return Bad("invalid_scorers", bad);
            await ReplaceScorersAsync(conn, tx, groupId, scorers, ct);
        }
        else if (status == GroupRules.Started)
        {
            if (await FillScorersAsync(conn, tx, groupId, method, rules, mustWork: true, ct) is { Count: > 0 } bad)
                return Bad("invalid_scorers", bad);
        }

        await tx.CommitAsync(ct);
        return await GroupResult(conn, groupId, user, null, ct);
    }

    // ---- Join -------------------------------------------------------------------------------------------------

    private static async Task<IResult> Join(JoinIn j, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var me = user.ArcherId();
        var code = j.Code?.Trim().ToUpperInvariant();
        if (!GroupRules.IsCode(code)) return Error(404, "no_such_group", "No group has that code. Check it with the host.");
        var problems = MemberProblems(j.ArcherClass, j.AgeGroup, j.BowStyle);
        if (problems.Count > 0) return Bad("invalid_member", problems);

        await using var conn = await db.OpenAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(IsolationLevel.Serializable, ct);
        var group = await conn.QueryFirstOrDefaultAsync<GroupRow>(new CommandDefinition($"""
            SELECT TOP (1) {GroupCols} FROM dbo.ScoreGroup WITH (UPDLOCK, HOLDLOCK)
            WHERE Code = @code AND Status <> 'CLOSED' AND CreatedUtc > @since AND (@groupId IS NULL OR GroupId = @groupId)
            ORDER BY CreatedUtc DESC;
            """, new { code, groupId = j.GroupId, since = DateTime.UtcNow - GroupRules.CodeLifetime }, tx, cancellationToken: ct));
        if (group is null) return Error(404, "no_such_group", "No open group has that code. Check it with the host.");

        var mine = await conn.QuerySingleOrDefaultAsync<MemberRow>(new CommandDefinition(
            MembersSql.Replace("ORDER BY", "AND m.ArcherId = @me ORDER BY"), new { groupId = group.GroupId, me }, tx, cancellationToken: ct));
        if (mine is { Kind: GroupRules.Host }) return Error(409, "is_host", "You're the host of this group.");

        if (mine is not null)
        {
            // Joining again (details changed), or a friend the host added joining on their own phone.
            await conn.ExecuteAsync(new CommandDefinition("""
                UPDATE dbo.ScoreGroupMember
                SET Kind = 'JOINED', ArcherClass = @ArcherClass, AgeGroup = @AgeGroup, BowStyle = @BowStyle,
                    RoundId = COALESCE(@RoundId, RoundId)
                WHERE GroupId = @groupId AND MemberId = @memberId;
                """, new { groupId = group.GroupId, memberId = mine.MemberId, j.ArcherClass, j.AgeGroup, j.BowStyle, j.RoundId },
                tx, cancellationToken: ct));
        }
        else
        {
            try
            {
                await conn.ExecuteAsync(new CommandDefinition("""
                    INSERT dbo.ScoreGroupMember (GroupId, MemberId, Kind, ArcherId, ArcherClass, AgeGroup, BowStyle, SortOrder, RoundId)
                    SELECT @groupId, @MemberId, 'JOINED', @me, @ArcherClass, @AgeGroup, @BowStyle,
                           ISNULL(MAX(SortOrder), 0) + 1, @RoundId
                    FROM dbo.ScoreGroupMember WHERE GroupId = @groupId;
                    """, new { groupId = group.GroupId, j.MemberId, me, j.ArcherClass, j.AgeGroup, j.BowStyle, j.RoundId },
                    tx, cancellationToken: ct));
            }
            catch (SqlException ex) when (Db.IsDuplicateKey(ex))
            {
                return Error(409, "member_id_taken", "That member id is already used in this group. Make a new one.");
            }
        }

        if (group.Status == GroupRules.Started)
        {
            var rules = (await conn.QueryAsync<MemberRow>(new CommandDefinition(MembersSql, new { groupId = group.GroupId }, tx, cancellationToken: ct)))
                .Select(m => new GroupRules.Member(m.MemberId, m.Kind, m.SortOrder)).ToList();
            await FillScorersAsync(conn, tx, group.GroupId, group.Method, rules, mustWork: false, ct);
        }
        await conn.ExecuteAsync(new CommandDefinition(
            "UPDATE dbo.ScoreGroup SET UpdatedUtc = SYSUTCDATETIME() WHERE GroupId = @groupId;", new { groupId = group.GroupId }, tx, cancellationToken: ct));
        await tx.CommitAsync(ct);
        return await GroupResult(conn, group.GroupId, user, null, ct);
    }

    // ---- Read -------------------------------------------------------------------------------------------------

    private static async Task<IResult> Mine(ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        var rows = await conn.QueryAsync(new CommandDefinition($"""
            SELECT g.GroupId, g.Code, g.Method, g.RoundType, g.TargetCount, CAST(g.ShootDate AS datetime2(3)) AS ShootDate,
                   g.Status, g.CreatedUtc, g.LocationId, {LocationEndpoints.VisibleNameSql} AS LocationName,
                   m.MemberId, m.Kind, h.Username AS HostUsername,
                   (SELECT COUNT(*) FROM dbo.ScoreGroupMember x WHERE x.GroupId = g.GroupId) AS Members
            FROM dbo.ScoreGroupMember m
            JOIN dbo.ScoreGroup g ON g.GroupId = m.GroupId
            JOIN dbo.Archer h ON h.ArcherId = g.HostId
            LEFT JOIN dbo.ShootLocation l ON l.LocationId = g.LocationId
            WHERE m.ArcherId = @viewer AND g.CreatedUtc > @cutoff
            ORDER BY g.CreatedUtc DESC;
            """, new { viewer = user.ArcherId(), viewerIsAdmin = user.IsAdmin(), cutoff = DateTime.UtcNow - GroupRules.GroupLifetime },
            cancellationToken: ct));
        return Results.Ok(new
        {
            groups = rows.Select(r => new
            {
                groupId = (Guid)r.GroupId, code = ((string)r.Code).Trim(), method = (string)r.Method, roundType = (string)r.RoundType,
                targetCount = (int)(short)r.TargetCount, shootDate = DateOnly.FromDateTime((DateTime)r.ShootDate), status = (string)r.Status,
                createdUtc = Utc((DateTime)r.CreatedUtc), locationId = (Guid?)r.LocationId, locationName = (string?)r.LocationName,
                myMemberId = (Guid)r.MemberId, myKind = (string)r.Kind, hostUsername = (string)r.HostUsername, members = (int)r.Members,
            }),
        });
    }

    private static async Task<IResult> Get(Guid groupId, DateTime? since, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        return await GroupResult(conn, groupId, user, since, ct);
    }

    /// <summary>The group as a member sees it. Anyone not in it gets "not found".</summary>
    private static async Task<IResult> GroupResult(SqlConnection conn, Guid groupId, ClaimsPrincipal user, DateTime? since, CancellationToken ct)
    {
        var me = user.ArcherId();
        // The database's clock (entries are stamped by it), so "since" works even if Odin's and Mimir's clocks differ.
        var serverTime = DateTime.SpecifyKind(await conn.ExecuteScalarAsync<DateTime>(new CommandDefinition(
            "SELECT CAST(SYSUTCDATETIME() AS datetime2(3));", cancellationToken: ct)), DateTimeKind.Utc);
        var group = await conn.QuerySingleOrDefaultAsync<GroupRow>(new CommandDefinition(
            $"SELECT {GroupCols} FROM dbo.ScoreGroup WHERE GroupId = @groupId;", new { groupId }, cancellationToken: ct));
        if (group is null) return NotFound();
        var members = (await conn.QueryAsync<MemberRow>(new CommandDefinition(MembersSql, new { groupId }, cancellationToken: ct))).ToList();
        var mine = members.FirstOrDefault(m => m.ArcherId == me);
        if (mine is null) return NotFound();

        var locationName = group.LocationId is null ? null : await conn.ExecuteScalarAsync<string>(new CommandDefinition(
            $"SELECT {LocationEndpoints.VisibleNameSql} FROM dbo.ShootLocation l WHERE l.LocationId = @id;",
            new { id = group.LocationId, viewer = me, viewerIsAdmin = user.IsAdmin() }, cancellationToken: ct));

        var scorers = (await ScorersAsync(conn, null, groupId, ct)).ToLookup(s => s.CardMemberId, s => s.ScorerMemberId);
        var entries = (await conn.QueryAsync<EntryRow>(new CommandDefinition("""
            SELECT TargetNo, CardMemberId, ScorerMemberId, EntryJson, Points, EnteredUtc, ServerUpdatedUtc
            FROM dbo.ScoreEntry WHERE GroupId = @groupId ORDER BY TargetNo, CardMemberId, ScorerMemberId;
            """, new { groupId }, cancellationToken: ct))).ToList();
        var cells = Cells(entries, scorers);
        var since3 = since is { } s ? RoundEndpoints.TrimUtc(s) : (DateTime?)null;

        return Results.Ok(new
        {
            group.GroupId,
            code = group.Code.Trim(),
            group.Method,
            group.RoundType,
            targetCount = (int)group.TargetCount,
            shootDate = DateOnly.FromDateTime(group.ShootDate),
            group.LocationId,
            locationName,
            group.Status,
            createdUtc = Utc(group.CreatedUtc),
            updatedUtc = Utc(group.UpdatedUtc),
            codeExpiresUtc = Utc(group.CreatedUtc + GroupRules.CodeLifetime),
            hostArcherId = group.HostId,
            me = new { mine.MemberId, mine.Kind },
            members = members.Select(m => new
            {
                m.MemberId, m.Kind, m.ArcherId,
                name = m.Kind == GroupRules.Guest ? m.GuestName : m.Username,
                archerClass = m.ArcherClass.Trim(), m.AgeGroup, m.BowStyle, sortOrder = (int)m.SortOrder,
                // Round ids are only shown to the host and the archer themselves.
                roundId = group.HostId == me || m.ArcherId == me ? m.RoundId : null,
                scorers = scorers[m.MemberId].ToList(),
            }),
            entries = entries.Where(e => since3 is null || e.ServerUpdatedUtc > since3).Select(e => new
            {
                targetNo = (int)e.TargetNo, e.CardMemberId, e.ScorerMemberId,
                target = JsonSerializer.Deserialize<TargetDto>(e.EntryJson, Json),
                points = (int)e.Points, enteredUtc = Utc(e.EnteredUtc), serverUpdatedUtc = Utc(e.ServerUpdatedUtc),
            }),
            cells,
            totals = members.Select(m => new
            {
                m.MemberId,
                points = cells.Where(c => c.CardMemberId == m.MemberId && c.State == Matched).Sum(c => c.Points ?? 0),
                targetsDone = cells.Count(c => c.CardMemberId == m.MemberId && c.State == Matched),
            }),
            serverTimeUtc = serverTime,
        });
    }

    // ---- Entries ----------------------------------------------------------------------------------------------

    private const string Waiting = "WAITING";
    private const string Matched = "MATCHED";
    private const string Mismatch = "MISMATCH";

    public sealed record Cell(int TargetNo, Guid CardMemberId, string State, int? Points);

    /// <summary>
    /// Each card's score on each target: MATCHED when every scorer for the card has entered the same score (one scorer:
    /// as soon as it's entered), MISMATCH when two entries differ, WAITING when a scorer still has to enter it.
    /// Entries from someone no longer scoring the card are ignored. Before scorers are set, any single entry counts.
    /// </summary>
    private static List<Cell> Cells(List<EntryRow> entries, ILookup<Guid, Guid> scorers) =>
        entries.GroupBy(e => (e.TargetNo, e.CardMemberId)).Select(g =>
        {
            var assigned = scorers[g.Key.CardMemberId].ToList();
            var counted = assigned.Count == 0 ? g.Take(1).ToList() : g.Where(e => assigned.Contains(e.ScorerMemberId)).ToList();
            var needed = Math.Max(1, assigned.Count);
            if (counted.Count < needed) return new Cell(g.Key.TargetNo, g.Key.CardMemberId, Waiting, null);
            var keys = counted.Select(e => GroupRules.MatchKey(JsonSerializer.Deserialize<TargetDto>(e.EntryJson, Json)!)).Distinct().Count();
            return keys == 1
                ? new Cell(g.Key.TargetNo, g.Key.CardMemberId, Matched, counted[0].Points)
                : new Cell(g.Key.TargetNo, g.Key.CardMemberId, Mismatch, null);
        }).OrderBy(c => c.TargetNo).ThenBy(c => c.CardMemberId).ToList();

    private static async Task<IResult> PutEntries(Guid groupId, EntriesIn body, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var me = user.ArcherId();
        var list = body.Entries ?? [];
        if (list.Count is 0 or > 500) return Bad("invalid_entries", ["Send 1 to 500 entries."]);

        await using var conn = await db.OpenAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(IsolationLevel.ReadCommitted, ct);
        var group = await conn.QuerySingleOrDefaultAsync<GroupRow>(new CommandDefinition(
            $"SELECT {GroupCols} FROM dbo.ScoreGroup WITH (UPDLOCK) WHERE GroupId = @groupId;", new { groupId }, tx, cancellationToken: ct));
        if (group is null) return NotFound();
        var members = (await conn.QueryAsync<MemberRow>(new CommandDefinition(MembersSql, new { groupId }, tx, cancellationToken: ct))).ToList();
        var mine = members.FirstOrDefault(m => m.ArcherId == me);
        if (mine is null) return NotFound();
        if (!GroupRules.HasPhone(mine.Kind)) return Error(403, "not_a_scorer", "Join the group on your phone to score.");

        var scorers = (await ScorersAsync(conn, tx, groupId, ct)).ToLookup(s => s.CardMemberId, s => s.ScorerMemberId);
        var memberIds = members.Select(m => m.MemberId).ToHashSet();
        var problems = new List<string>();
        foreach (var e in list)
        {
            var where = $"Target {e.TargetNo}";
            if (e.TargetNo < 1 || e.TargetNo > group.TargetCount) { problems.Add($"{where}: the group has {group.TargetCount} targets."); continue; }
            if (!memberIds.Contains(e.CardMemberId) || !memberIds.Contains(e.ScorerMemberId)) { problems.Add($"{where}: not a member of the group."); continue; }
            // Your own entries; the host's phone may also pass on entries it received from other phones (no signal).
            if (e.ScorerMemberId != mine.MemberId && mine.Kind != GroupRules.Host) { problems.Add($"{where}: you can only send your own entries."); continue; }
            var assigned = scorers[e.CardMemberId].ToList();
            if (assigned.Count > 0 && !assigned.Contains(e.ScorerMemberId)) { problems.Add($"{where}: that scorer doesn't score this card."); continue; }
            if (assigned.Count == 0 && members.First(m => m.MemberId == e.ScorerMemberId).Kind is not (GroupRules.Host or GroupRules.Joined))
            { problems.Add($"{where}: that scorer can't score."); continue; }
            if (e.Target is null || e.Target.TargetNo != e.TargetNo) { problems.Add($"{where}: the target's number must match."); continue; }
            problems.AddRange(RoundValidator.ValidateEntry(group.RoundType, e.Target));
        }
        if (problems.Count > 0) return Bad("invalid_entries", problems.Distinct().ToList());

        foreach (var e in list)
        {
            await conn.ExecuteAsync(new CommandDefinition("""
                MERGE dbo.ScoreEntry AS t
                USING (SELECT @groupId AS GroupId, @targetNo AS TargetNo, @CardMemberId AS CardMemberId, @ScorerMemberId AS ScorerMemberId) AS s
                ON t.GroupId = s.GroupId AND t.TargetNo = s.TargetNo AND t.CardMemberId = s.CardMemberId AND t.ScorerMemberId = s.ScorerMemberId
                WHEN MATCHED AND t.EnteredUtc < @enteredUtc THEN
                    UPDATE SET EntryJson = @json, Points = @points, EnteredUtc = @enteredUtc, ServerUpdatedUtc = SYSUTCDATETIME()
                WHEN NOT MATCHED THEN
                    INSERT (GroupId, TargetNo, CardMemberId, ScorerMemberId, EntryJson, Points, EnteredUtc)
                    VALUES (@groupId, @targetNo, @CardMemberId, @ScorerMemberId, @json, @points, @enteredUtc);
                """,
                new
                {
                    groupId, targetNo = (short)e.TargetNo, e.CardMemberId, e.ScorerMemberId,
                    json = JsonSerializer.Serialize(e.Target, Json), points = (short)e.Target!.Points,
                    enteredUtc = RoundEndpoints.TrimUtc(e.EnteredUtc),
                }, tx, cancellationToken: ct));
        }
        await conn.ExecuteAsync(new CommandDefinition(
            "UPDATE dbo.ScoreGroup SET UpdatedUtc = SYSUTCDATETIME() WHERE GroupId = @groupId;", new { groupId }, tx, cancellationToken: ct));

        var all = (await conn.QueryAsync<EntryRow>(new CommandDefinition("""
            SELECT TargetNo, CardMemberId, ScorerMemberId, EntryJson, Points, EnteredUtc, ServerUpdatedUtc
            FROM dbo.ScoreEntry WHERE GroupId = @groupId;
            """, new { groupId }, tx, cancellationToken: ct))).ToList();
        await tx.CommitAsync(ct);

        var touched = list.Select(e => (e.TargetNo, e.CardMemberId)).ToHashSet();
        var results = Cells(all, scorers).Where(c => touched.Contains((c.TargetNo, c.CardMemberId)));
        return Results.Ok(new { results });
    }

    // ---- Members ----------------------------------------------------------------------------------------------

    private static async Task<IResult> RemoveMember(Guid groupId, Guid memberId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var me = user.ArcherId();
        await using var conn = await db.OpenAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(IsolationLevel.ReadCommitted, ct);
        var group = await conn.QuerySingleOrDefaultAsync<GroupRow>(new CommandDefinition(
            $"SELECT {GroupCols} FROM dbo.ScoreGroup WITH (UPDLOCK) WHERE GroupId = @groupId;", new { groupId }, tx, cancellationToken: ct));
        if (group is null) return NotFound();
        var members = (await conn.QueryAsync<MemberRow>(new CommandDefinition(MembersSql, new { groupId }, tx, cancellationToken: ct))).ToList();
        var target = members.FirstOrDefault(m => m.MemberId == memberId);
        var isHost = group.HostId == me;
        if (target is null || (!isHost && target.ArcherId != me)) return NotFound();
        if (target.Kind == GroupRules.Host) return Error(409, "is_host", "The host can't leave; close the group instead.");

        await DeleteMembersAsync(conn, tx, groupId, [memberId], ct);
        if (group.Status == GroupRules.Started)
        {
            // Anyone who lost a scorer gets the usual ones again.
            var rules = members.Where(m => m.MemberId != memberId).Select(m => new GroupRules.Member(m.MemberId, m.Kind, m.SortOrder)).ToList();
            await FillScorersAsync(conn, tx, groupId, group.Method, rules, mustWork: false, ct);
        }
        await conn.ExecuteAsync(new CommandDefinition(
            "UPDATE dbo.ScoreGroup SET UpdatedUtc = SYSUTCDATETIME() WHERE GroupId = @groupId;", new { groupId }, tx, cancellationToken: ct));
        await tx.CommitAsync(ct);
        return Results.NoContent();
    }

    // ---- Helpers ----------------------------------------------------------------------------------------------

    private const string GroupCols =
        "GroupId, Code, HostId, Method, RoundType, TargetCount, CAST(ShootDate AS datetime2(3)) AS ShootDate, LocationId, Status, CreatedUtc, UpdatedUtc";

    private const string MembersSql = """
        SELECT m.MemberId, m.Kind, m.ArcherId, m.GuestName, a.Username, m.ArcherClass, m.AgeGroup, m.BowStyle, m.SortOrder, m.RoundId
        FROM dbo.ScoreGroupMember m LEFT JOIN dbo.Archer a ON a.ArcherId = m.ArcherId
        WHERE m.GroupId = @groupId
        ORDER BY m.SortOrder, m.AddedUtc;
        """;

    private static async Task<List<GroupRules.Scorer>> ScorersAsync(SqlConnection conn, IDbTransaction? tx, Guid groupId, CancellationToken ct) =>
        (await conn.QueryAsync<GroupRules.Scorer>(new CommandDefinition(
            "SELECT CardMemberId, ScorerMemberId FROM dbo.ScoreGroupScorer WHERE GroupId = @groupId;", new { groupId }, tx, cancellationToken: ct)))
        .ToList();

    private static async Task ReplaceScorersAsync(SqlConnection conn, IDbTransaction tx, Guid groupId, List<GroupRules.Scorer> scorers, CancellationToken ct)
    {
        await conn.ExecuteAsync(new CommandDefinition("DELETE dbo.ScoreGroupScorer WHERE GroupId = @groupId;", new { groupId }, tx, cancellationToken: ct));
        await conn.ExecuteAsync(new CommandDefinition(
            "INSERT dbo.ScoreGroupScorer (GroupId, CardMemberId, ScorerMemberId) VALUES (@groupId, @CardMemberId, @ScorerMemberId);",
            scorers.Select(s => new { groupId, s.CardMemberId, s.ScorerMemberId }), tx, cancellationToken: ct));
    }

    /// <summary>
    /// If the scorers set don't fit the members any more (someone joined or left), put back the usual ones.
    /// Returns problems if the method can't work (only when <paramref name="mustWork"/>; otherwise the scorers are left as they are).
    /// </summary>
    private static async Task<List<string>> FillScorersAsync(SqlConnection conn, IDbTransaction tx, Guid groupId, string method,
        List<GroupRules.Member> members, bool mustWork, CancellationToken ct)
    {
        var current = await ScorersAsync(conn, tx, groupId, ct);
        if (current.Count > 0 && GroupRules.Validate(method, members, current).Count == 0) return [];
        var (scorers, problems) = GroupRules.Default(method, members);
        if (problems.Count > 0) return mustWork ? problems : [];
        await ReplaceScorersAsync(conn, tx, groupId, scorers, ct);
        return [];
    }

    private static async Task DeleteMembersAsync(SqlConnection conn, IDbTransaction tx, Guid groupId, List<Guid> ids, CancellationToken ct)
    {
        if (ids.Count == 0) return;
        await conn.ExecuteAsync(new CommandDefinition("""
            DELETE dbo.ScoreEntry WHERE GroupId = @groupId AND (CardMemberId IN @ids OR ScorerMemberId IN @ids);
            DELETE dbo.ScoreGroupScorer WHERE GroupId = @groupId AND (CardMemberId IN @ids OR ScorerMemberId IN @ids);
            DELETE dbo.ScoreGroupMember WHERE GroupId = @groupId AND MemberId IN @ids;
            """, new { groupId, ids }, tx, cancellationToken: ct));
    }

    private static List<string> MemberProblems(string? cls, string? age, string? bow)
    {
        var p = new List<string>();
        if (cls is null || !Classes.Contains(cls)) p.Add("Class must be L or G.");
        if (age is null || !AgeGroups.Contains(age)) p.Add("Unknown age group.");
        if (bow is null || !BowStyles.Contains(bow)) p.Add("Unknown bow style.");
        return p;
    }

    private static DateTime Utc(DateTime d) => DateTime.SpecifyKind(d, DateTimeKind.Utc);

    private static IResult Bad(string code, List<string> problems) =>
        Results.Json(new { error = code, message = string.Join(" ", problems.Distinct()), problems }, statusCode: 400);

    private static IResult Error(int status, string code, string message) => Results.Json(new { error = code, message }, statusCode: status);

    private static IResult NotFound() => Error(404, "no_such_group", "That group isn't available.");
}
