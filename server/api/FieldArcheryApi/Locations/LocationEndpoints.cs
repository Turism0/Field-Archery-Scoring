using System.Security.Claims;
using Dapper;
using Microsoft.Data.SqlClient;
using FieldArcheryApi.Auth;
using FieldArcheryApi.Data;

namespace FieldArcheryApi.Locations;

/// <summary>
/// Shoot locations (roadmap step 2a). All calls need "Authorization: Bearer &lt;token&gt;".
/// The phone's position is only used to answer the request; it is never stored. A location's stored
/// position is where it was first named.
///   POST /api/locations/nearby { latitude, longitude, radiusMetres? }  (API 2.4.02; position kept out of the logs) → as GET:
///   GET  /api/locations/nearby?lat=&amp;lon=&amp;radius=   → { locations: [{ locationId, name, distanceMetres }] } nearest first;
///                                                    radius in metres, default the catchment (about a quarter mile), max 10 km
///   POST /api/locations          { name, latitude, longitude, isPrivate? } → 201 { locationId, name }; 409 name_taken with the existing one
///                                (API 3.2.25: with checkSimilar: true, 409 similar_exists { matches: [{ locationId, name, isPrivate,
///                                distanceMetres, reasons: [same_name|similar_name|nearby] }] } when a location you can use has the
///                                same or a similar name or is within 300 m; send again without it to make the new one anyway)
///                                (API 3.0: { name, noPin: true, isPrivate? } makes a name-only location: no map pin)
///   GET  /api/locations/named    (API 3.0) → { locations: [{ locationId, name, isPrivate, myRounds, rounds }] } name-only locations
///                                you can see (public, and your own private ones), by name: for choosing one for a round
///   PUT  /api/locations/{id}/position { latitude, longitude } (API 3.0) → { locationId, name, latitude, longitude }: puts a
///                                name-only location on the map; its creator or an Admin; 409 already_placed if it has a pin
///                                (API 3.2.14: { latitude, longitude, move: true } moves a location already on the map; Admins
///                                and the Owner only)
///   PUT  /api/locations/{id}/private { isPrivate, confirm? } (API 3.2.12) → { locationId, name, isPrivate, roundsCleared }:
///                                the archer who named it makes it private or public. Making it private when other archers
///                                have rounds there → 409 others_shot_here { archers, rounds } unless confirm: true; then it's
///                                taken off their rounds (and groups / requests). Making it public → 409 name_taken if a public
///                                location already has the name.
///   DELETE /api/locations/{id}   (API 3.2.16) → { locationId, name, roundsCleared }: the archer who named it, or the
///                                Owner (any location). Every round there (anyone's) loses its location, as do groups and
///                                requests; duplicates merged into it go too.
///   POST /api/locations/resolve  { ids: [...] }   → { locations: [{ requestedId, locationId, name }] } current id and name
///                                                    for ids a phone holds (follows merges; unknown ids left out)
///  Admins and Owner:
///   GET  /api/admin/locations                     → { locations: [{ locationId, name, latitude, longitude, createdUtc, createdBy, rounds }] }
///                                                    (latitude / longitude null for a name-only location, API 3.0)
///   PUT  /api/admin/locations/{id}   { name, isPrivate? } → { locationId, name }; 409 name_taken
///   GET  /api/admin/locations/duplicates          → { pairs: [{ a, b, distanceMetres, reasons: [nearby|same_name|similar_name] }] }
///                                                    (API 3.0: distanceMetres null when either has no pin; then names only)
///   POST /api/admin/locations/duplicates/approve { aId, bId } → 204: different places, stop flagging them
///   POST /api/admin/locations/{id}/merge { intoId } → { locationId, name, roundsMoved }: the duplicate's rounds move to intoId
///   (3.2.32) GET  /api/locations/{id}/merge-targets → { locations: [{ locationId, name, isPrivate, distanceMetres, reasons }] }:
///            public locations your private location {id} could be merged into (the same name, a similar name within
///            500 m or with no pin, or within 300 m: the new-location suggestion rules). Only for the archer who named it.
///   (3.2.32) POST /api/locations/{id}/merge-into { intoId } → { locationId, name, roundsMoved }: merges your private location
///            into one of those public ones (its rounds move there). 404 not yours / not private; 409 not_similar.
/// </summary>
public static class LocationEndpoints
{
    public const int MinName = 2;
    public const int MaxName = 60;
    private const int MaxRadius = 10_000;

    public sealed record NewLocation(string? Name, double? Latitude, double? Longitude, bool? IsPrivate = null, bool? NoPin = null,
        bool? CheckSimilar = null);

    /// <summary>3.2.25 (Pete): a new location this close to an existing one is suggested to be that one instead.</summary>
    public const int SimilarMetres = 300;
    /// <summary>3.2.28 (Pete, "option 5"): a similar (not the same) name only counts within this, or when either has no pin.</summary>
    public const int SimilarNameMetres = 500;
    public sealed record PositionRequest(double? Latitude, double? Longitude, bool? Move = null);
    public sealed record ResolveRequest(IReadOnlyList<Guid>? Ids);
    public sealed record RenameRequest(string? Name, bool? IsPrivate = null);
    public sealed record PrivacyRequest(bool? IsPrivate, bool? Confirm = null);
    public sealed record ApproveRequest(Guid? AId, Guid? BId);
    public sealed record MergeRequest(Guid? IntoId);

    private sealed record LocationRow(Guid LocationId, string Name, decimal Latitude, decimal Longitude);
    private sealed record AdminRow(Guid LocationId, string Name, decimal? Latitude, decimal? Longitude,
        DateTime CreatedUtc, string? CreatedBy, int Rounds, bool IsPrivate);

    /// <summary>What other archers see instead of a private location's name.</summary>
    public const string PrivateName = "Private location";

    /// <summary>
    /// SQL for a location's name as the viewer may see it ("l" = ShootLocation, @viewer / @viewerIsAdmin bound).
    /// Private locations show their name only to the archer who named them (2.0.17: not Admins either).
    /// </summary>
    public const string VisibleNameSql =
        "CASE WHEN l.IsPrivate = 1 AND (l.CreatedById IS NULL OR l.CreatedById <> @viewer) " +
        "THEN N'" + PrivateName + "' ELSE l.Name END";

    public static void MapLocationEndpoints(this WebApplication app)
    {
        var locations = app.MapGroup("/api/locations").RequireAuthorization();
        locations.MapGet("/nearby", Nearby);
        // API 2.4.02: the same with the position in the body, so it isn't written to web server logs (app 2.4.02 uses this).
        locations.MapPost("/nearby", (NearbyIn n, ClaimsPrincipal user, Db db, IConfiguration config, CancellationToken ct) =>
            Nearby(n.Latitude, n.Longitude, n.RadiusMetres, user, db, config, ct));
        locations.MapPost("/", Create);
        locations.MapPost("/resolve", Resolve);
        locations.MapGet("/named", Named);
        locations.MapPut("/{locationId:guid}/position", Place);
        locations.MapPut("/{locationId:guid}/private", SetPrivate);
        locations.MapDelete("/{locationId:guid}", Delete);
        locations.MapGet("/{locationId:guid}/merge-targets", MergeTargets);   // 3.2.32
        locations.MapPost("/{locationId:guid}/merge-into", MergeInto);       // 3.2.32

        var admin = app.MapGroup("/api/admin/locations").RequireAuthorization();
        admin.MapGet("/", AdminList);
        admin.MapPut("/{locationId:guid}", Rename);
        admin.MapPost("/{locationId:guid}/merge", Merge);
        admin.MapGet("/duplicates", Duplicates);
        admin.MapPost("/duplicates/approve", Approve);
    }

    public sealed record NearbyIn(double? Latitude, double? Longitude, int? RadiusMetres);

    private static async Task<IResult> Nearby(double? lat, double? lon, int? radius, ClaimsPrincipal user, Db db, IConfiguration config, CancellationToken ct)
    {
        if (PositionProblem(lat, lon) is { } bad) return bad;
        var within = Math.Clamp(radius ?? Catchment(config), 1, MaxRadius);

        await using var conn = await db.OpenAsync(ct);
        // Rough box first (cheap), then the exact distance. A degree of latitude is ~111 km everywhere.
        var dLat = within / 111_000.0;
        var dLon = within / (111_000.0 * Math.Max(Math.Cos(lat!.Value * Math.PI / 180), 0.01));
        var rows = await conn.QueryAsync<LocationRow>(new CommandDefinition("""
            SELECT LocationId, Name, Latitude, Longitude
            FROM dbo.ShootLocation
            WHERE MergedIntoId IS NULL
              AND (IsPrivate = 0 OR CreatedById = @me)   -- someone else's private location is never matched
              AND Latitude IS NOT NULL                    -- name-only locations (3.0) are never matched by position
              AND Latitude BETWEEN @minLat AND @maxLat
              AND Longitude BETWEEN @minLon AND @maxLon;
            """,
            new
            {
                minLat = (decimal)(lat.Value - dLat), maxLat = (decimal)(lat.Value + dLat),
                minLon = (decimal)(lon!.Value - dLon), maxLon = (decimal)(lon.Value + dLon),
                me = user.ArcherId(),
            }, cancellationToken: ct));

        var near = rows
            .Select(r => new { r.LocationId, r.Name, distanceMetres = (int)Math.Round(Metres(lat.Value, lon.Value, (double)r.Latitude, (double)r.Longitude)) })
            .Where(r => r.distanceMetres <= within)
            .OrderBy(r => r.distanceMetres)
            .ToList();
        return Results.Ok(new { locations = near, catchmentMetres = Catchment(config) });
    }

    private static async Task<IResult> Create(NewLocation req, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        // API 3.0: a name-only location has no position at all.
        var noPin = req.NoPin == true;
        if (noPin && (req.Latitude is not null || req.Longitude is not null))
            return Error(400, "invalid_position", "A location with no map pin can't have a position.");
        if (!noPin && PositionProblem(req.Latitude, req.Longitude) is { } bad) return bad;
        var name = CleanName(req.Name);
        if (NameProblem(name) is { } badName) return badName;

        await using var conn = await db.OpenAsync(ct);
        // 3.2.25 (Pete): people were making duplicates. Apps that ask (checkSimilar) are told about public locations (and
        // their own private ones) with the same or a similar name, or within 300 m, so they can use one of those instead;
        // the archer can still go ahead (sent again without checkSimilar).
        if (req.CheckSimilar == true)
        {
            var matches = await SimilarAsync(conn, name, noPin ? null : req.Latitude, noPin ? null : req.Longitude, user.ArcherId(), ct);
            if (matches.Count > 0)
            {
                return Results.Json(new
                {
                    error = "similar_exists",
                    message = matches.Count == 1 ? $"There's already a location that might be this one: {matches[0].name}."
                        : "There are already locations that might be this one.",
                    matches,
                }, statusCode: StatusCodes.Status409Conflict);
            }
        }
        try
        {
            var id = await conn.QuerySingleAsync<Guid>(new CommandDefinition("""
                INSERT dbo.ShootLocation (Name, Latitude, Longitude, CreatedById, IsPrivate)
                OUTPUT inserted.LocationId
                VALUES (@name, @lat, @lon, @by, @isPrivate);
                """,
                new
                {
                    name, lat = noPin ? (decimal?)null : Round5(req.Latitude!.Value), lon = noPin ? (decimal?)null : Round5(req.Longitude!.Value),
                    by = user.ArcherId(), isPrivate = req.IsPrivate == true,
                },
                cancellationToken: ct));
            return Results.Created($"/api/locations/{id}", new { locationId = id, name, isPrivate = req.IsPrivate == true, noPin });
        }
        catch (SqlException ex) when (Db.IsDuplicateKey(ex))
        {
            return await NameTaken(conn, name, ct);
        }
    }

    private static async Task<IResult> Resolve(ResolveRequest req, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var ids = (req.Ids ?? []).Distinct().Take(500).ToList();
        if (ids.Count == 0) return Results.Ok(new { locations = Array.Empty<object>() });

        await using var conn = await db.OpenAsync(ct);
        var result = new List<object>();
        foreach (var id in ids)
        {
            if (await CurrentAsync(conn, id, null, ct) is { } loc)
            {
                var name = await conn.ExecuteScalarAsync<string>(new CommandDefinition(
                    $"SELECT {VisibleNameSql} FROM dbo.ShootLocation l WHERE l.LocationId = @locationId;",
                    new { locationId = loc.LocationId, viewer = user.ArcherId(), viewerIsAdmin = user.IsAdmin() },
                    cancellationToken: ct));
                result.Add(new { requestedId = id, loc.LocationId, name });
            }
        }
        return Results.Ok(new { locations = result });
    }

    private sealed record NamedRow(Guid LocationId, string Name, bool IsPrivate, int MyRounds, int Rounds);

    /// <summary>
    /// Name-only locations (3.0) you can see – public ones and your own private ones – for choosing one for a round:
    /// they aren't on the map. myRounds / rounds count finished rounds (yours / everyone's).
    /// </summary>
    private static async Task<IResult> Named(ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        var rows = await conn.QueryAsync<NamedRow>(new CommandDefinition("""
            WITH done AS (
                SELECT LocationId, ArcherId FROM dbo.Round
                WHERE LocationId IS NOT NULL AND Status = 'COMPLETED' AND IsDeleted = 0)
            SELECT l.LocationId, l.Name, l.IsPrivate,
                   (SELECT COUNT(*) FROM done d WHERE d.LocationId = l.LocationId AND d.ArcherId = @me) AS MyRounds,
                   (SELECT COUNT(*) FROM done d WHERE d.LocationId = l.LocationId) AS Rounds
            FROM dbo.ShootLocation l
            WHERE l.MergedIntoId IS NULL AND l.Latitude IS NULL
              AND (l.IsPrivate = 0 OR l.CreatedById = @me)
            ORDER BY l.Name;
            """, new { me = user.ArcherId() }, cancellationToken: ct));
        return Results.Ok(new { locations = rows });
    }

    /// <summary>
    /// Puts a name-only location on the map (3.0): its creator or an Admin (not someone else's private location).
    /// From then on it's an ordinary location: on the map and matched by position.
    /// </summary>
    private static async Task<IResult> Place(Guid locationId, PositionRequest req, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        if (PositionProblem(req.Latitude, req.Longitude) is { } bad) return bad;
        await using var conn = await db.OpenAsync(ct);
        var loc = await conn.QuerySingleOrDefaultAsync<PlaceRow>(new CommandDefinition(
            "SELECT Name, Latitude, IsPrivate, CreatedById FROM dbo.ShootLocation WHERE LocationId = @locationId AND MergedIntoId IS NULL;",
            new { locationId }, cancellationToken: ct));
        var mine = loc is not null && loc.CreatedById == user.ArcherId();
        if (loc is null || (loc.IsPrivate && !mine))
            return Error(404, "no_such_location", "There's no location with that id (or it was merged into another).");
        // 3.2.14 (Pete): Admins and the Owner can move a location that's already on the map (e.g. pinned in the car park).
        var move = req.Move == true;
        if (move && !user.IsAdmin()) return Error(403, "not_admin", "Only an Admin can move a location on the map.");
        if (!mine && !user.IsAdmin())
            return Error(403, "not_creator", "Only the archer who named a location, or an Admin, can put it on the map.");
        if (loc.Latitude is not null && !move) return Error(409, "already_placed", "That location is already on the map.");
        var lat = Round5(req.Latitude!.Value);
        var lon = Round5(req.Longitude!.Value);
        await conn.ExecuteAsync(new CommandDefinition(
            "UPDATE dbo.ShootLocation SET Latitude = @lat, Longitude = @lon WHERE LocationId = @locationId AND (Latitude IS NULL OR @move = 1);",
            new { locationId, lat, lon, move }, cancellationToken: ct));
        return Results.Ok(new { locationId, name = loc.Name, latitude = (double)lat, longitude = (double)lon });
    }

    private sealed record PlaceRow(string Name, decimal? Latitude, bool IsPrivate, Guid? CreatedById);

    /// <summary>
    /// Admin list (2.0.17): public locations and your own private ones. The Owner also sees other people's private locations
    /// (3.2.31, Pete: with their names and positions, "othersPrivate": true; before, hidden) but can't change them; Admins
    /// don't see them at all. "hidden" is kept (always false now) for older apps.
    /// </summary>
    private static async Task<IResult> AdminList(ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        if (!user.IsAdmin()) return NotAdmin();
        await using var conn = await db.OpenAsync(ct);
        var rows = await conn.QueryAsync<AdminRow>(new CommandDefinition("""
            SELECT l.LocationId, l.Name, l.Latitude, l.Longitude, l.CreatedUtc, a.Username AS CreatedBy,
                   (SELECT COUNT(*) FROM dbo.Round r WHERE r.LocationId = l.LocationId AND r.IsDeleted = 0) AS Rounds,
                   l.IsPrivate
            FROM dbo.ShootLocation l
            LEFT JOIN dbo.Archer a ON a.ArcherId = l.CreatedById
            WHERE l.MergedIntoId IS NULL
              AND (l.IsPrivate = 0 OR l.CreatedById = @me OR @isOwner = 1)
            ORDER BY CASE WHEN l.IsPrivate = 1 AND (l.CreatedById IS NULL OR l.CreatedById <> @me) THEN 1 ELSE 0 END, l.Name;
            """, new { me = user.ArcherId(), isOwner = user.IsOwner() }, cancellationToken: ct));
        var me = user.ArcherId();
        var mineIds = (await conn.QueryAsync<Guid>(new CommandDefinition(
            "SELECT LocationId FROM dbo.ShootLocation WHERE CreatedById = @me AND IsPrivate = 1;", new { me }, cancellationToken: ct))).ToHashSet();
        return Results.Ok(new
        {
            locations = rows.Select(r =>
            {
                var othersPrivate = r.IsPrivate && !mineIds.Contains(r.LocationId);   // only ever the Owner's list
                return new
                {
                    r.LocationId,
                    name = r.Name,
                    latitude = (double?)r.Latitude,
                    longitude = (double?)r.Longitude,
                    createdUtc = DateTime.SpecifyKind(r.CreatedUtc, DateTimeKind.Utc), r.CreatedBy, r.Rounds, r.IsPrivate,
                    hidden = false,
                    othersPrivate,
                };
            }),
        });
    }

    private static async Task<IResult> Rename(Guid locationId, RenameRequest req, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        if (!user.IsAdmin()) return NotAdmin();
        var name = CleanName(req.Name);
        if (NameProblem(name) is { } badName) return badName;

        await using var conn = await db.OpenAsync(ct);
        if (req.IsPrivate == true)
        {
            var creator = await conn.ExecuteScalarAsync<Guid?>(new CommandDefinition(
                "SELECT CreatedById FROM dbo.ShootLocation WHERE LocationId = @locationId AND MergedIntoId IS NULL;",
                new { locationId }, cancellationToken: ct));
            if (creator != user.ArcherId())
                return Error(403, "not_creator", "Only the archer who named a location can make it private.");
            // 3.2.12: that takes it off other archers' rounds, so it's done from the location's page, which asks first.
            var (archers, rounds) = await OthersHereAsync(conn, locationId, user.ArcherId(), null, ct);
            if (rounds > 0)
                return Results.Json(new
                {
                    error = "others_shot_here", archers, rounds,
                    message = "Other archers have shot here. Make it private from the location's own page, which asks first.",
                }, statusCode: StatusCodes.Status409Conflict);
        }
        try
        {
            var changed = await conn.ExecuteAsync(new CommandDefinition("""
                UPDATE dbo.ShootLocation
                SET Name = @name, IsPrivate = COALESCE(@isPrivate, IsPrivate), RenamedUtc = SYSUTCDATETIME(), RenamedById = @by
                WHERE LocationId = @locationId AND MergedIntoId IS NULL
                  -- Someone else's private location isn't an Admin's to see or change, and only its creator can make a
                  -- location private (it then disappears for everyone else, Admins included).
                  AND (IsPrivate = 0 OR CreatedById = @by)
                  AND (@isPrivate IS NULL OR @isPrivate = 0 OR CreatedById = @by);
                """, new { locationId, name, isPrivate = req.IsPrivate, by = user.ArcherId() }, cancellationToken: ct));
            return changed == 1
                ? Results.Ok(new { locationId, name, isPrivate = req.IsPrivate })
                : Error(404, "no_such_location", "There's no location with that id (or it was merged into another).");
        }
        catch (SqlException ex) when (Db.IsDuplicateKey(ex))
        {
            return await NameTaken(conn, name, ct);
        }
    }

    /// <summary>
    /// Private or public (3.2.12, Pete): the archer who named a location changes it from its page. Making it private when
    /// other archers have rounds there needs confirm: true (the app warns and asks first); it's then taken off their rounds,
    /// so their scorecards no longer show it, and off groups and round requests from them. Their rounds are marked as
    /// changed (a millisecond later) so their phones download the round without the location.
    /// </summary>
    private static async Task<IResult> SetPrivate(Guid locationId, PrivacyRequest req, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        if (req.IsPrivate is not { } makePrivate) return Error(400, "invalid_request", "Say whether it's private (isPrivate).");
        var me = user.ArcherId();
        await using var conn = await db.OpenAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(ct);
        var loc = await conn.QuerySingleOrDefaultAsync<(string Name, bool IsPrivate, Guid? CreatedById)>(new CommandDefinition("""
            SELECT Name, IsPrivate, CreatedById FROM dbo.ShootLocation WITH (UPDLOCK, HOLDLOCK)
            WHERE LocationId = @locationId AND MergedIntoId IS NULL;
            """, new { locationId }, tx, cancellationToken: ct));
        if (loc.Name is null || (loc.IsPrivate && loc.CreatedById != me))
            return Error(404, "no_such_location", "There's no location with that id (or it was merged into another).");
        if (loc.CreatedById != me)
            return Error(403, "not_creator", "Only the archer who named a location can make it private or public.");
        if (loc.IsPrivate == makePrivate)
            return Results.Ok(new { locationId, name = loc.Name, isPrivate = makePrivate, roundsCleared = 0 });

        var cleared = 0;
        if (makePrivate)
        {
            var (archers, rounds) = await OthersHereAsync(conn, locationId, me, tx, ct);
            if (rounds > 0 && req.Confirm != true)
            {
                return Results.Json(new
                {
                    error = "others_shot_here", archers, rounds,
                    message = $"{Plural(archers, "other archer")} {(archers == 1 ? "has" : "have")} {Plural(rounds, "round")} here. " +
                              "Making it private takes it off their scorecards.",
                }, statusCode: StatusCodes.Status409Conflict);
            }
            await conn.ExecuteAsync(new CommandDefinition(
                "UPDATE dbo.ShootLocation SET IsPrivate = 1 WHERE LocationId = @locationId;", new { locationId }, tx, cancellationToken: ct));
            cleared = await conn.ExecuteAsync(new CommandDefinition("""
                UPDATE dbo.Round
                SET LocationId = NULL, ClientUpdatedUtc = DATEADD(millisecond, 1, ClientUpdatedUtc), ServerUpdatedUtc = SYSUTCDATETIME()
                WHERE LocationId = @locationId AND ArcherId <> @me;
                """, new { locationId, me }, tx, cancellationToken: ct));
            await conn.ExecuteAsync(new CommandDefinition("""
                UPDATE dbo.ScoreGroup SET LocationId = NULL, UpdatedUtc = SYSUTCDATETIME() WHERE LocationId = @locationId AND HostId <> @me;
                UPDATE dbo.RoundOffer SET LocationId = NULL, UpdatedUtc = SYSUTCDATETIME() WHERE LocationId = @locationId AND FromArcherId <> @me;
                """, new { locationId, me }, tx, cancellationToken: ct));
        }
        else
        {
            try
            {
                await conn.ExecuteAsync(new CommandDefinition(
                    "UPDATE dbo.ShootLocation SET IsPrivate = 0 WHERE LocationId = @locationId;", new { locationId }, tx, cancellationToken: ct));
            }
            catch (SqlException ex) when (Db.IsDuplicateKey(ex))
            {
                await tx.RollbackAsync(ct);
                return await NameTaken(conn, loc.Name, ct);
            }
        }
        await tx.CommitAsync(ct);
        return Results.Ok(new { locationId, name = loc.Name, isPrivate = makePrivate, roundsCleared = cleared });
    }

    /// <summary>
    /// Deletes a location (3.2.16, Pete): the archer who named it, or the Owner (any location; Admins only their own).
    /// Rounds there keep everything but the location, so the archer can give them a new one. Their rounds are marked as
    /// changed a millisecond later so phones download them without it at the next sync. Duplicates that were merged
    /// into it are deleted with it (otherwise they'd come back as locations of their own).
    /// </summary>
    private static async Task<IResult> Delete(Guid locationId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var me = user.ArcherId();
        await using var conn = await db.OpenAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(ct);
        var loc = await conn.QuerySingleOrDefaultAsync<(string Name, bool IsPrivate, Guid? CreatedById)>(new CommandDefinition("""
            SELECT Name, IsPrivate, CreatedById FROM dbo.ShootLocation WITH (UPDLOCK, HOLDLOCK)
            WHERE LocationId = @locationId AND MergedIntoId IS NULL;
            """, new { locationId }, tx, cancellationToken: ct));
        var mine = loc.Name is not null && loc.CreatedById == me;
        if (loc.Name is null || (loc.IsPrivate && !mine && !user.IsOwner()))
            return Error(404, "no_such_location", "There's no location with that id (or it was merged into another).");
        if (!mine && !user.IsOwner())
            return Error(403, "not_creator", "Only the archer who named a location, or the Owner, can delete it.");

        var cleared = await conn.ExecuteScalarAsync<int>(new CommandDefinition("""
            DECLARE @gone TABLE (LocationId UNIQUEIDENTIFIER PRIMARY KEY);
            WITH chain AS (
                SELECT LocationId FROM dbo.ShootLocation WHERE LocationId = @locationId
                UNION ALL
                SELECT l.LocationId FROM dbo.ShootLocation l JOIN chain c ON l.MergedIntoId = c.LocationId)
            INSERT @gone SELECT LocationId FROM chain OPTION (MAXRECURSION 100);

            UPDATE dbo.ScoreGroup SET LocationId = NULL, UpdatedUtc = SYSUTCDATETIME() WHERE LocationId IN (SELECT LocationId FROM @gone);
            UPDATE dbo.RoundOffer SET LocationId = NULL, UpdatedUtc = SYSUTCDATETIME() WHERE LocationId IN (SELECT LocationId FROM @gone);
            DELETE dbo.LocationPairApproval
            WHERE LocationLowId IN (SELECT LocationId FROM @gone) OR LocationHighId IN (SELECT LocationId FROM @gone);
            UPDATE dbo.ShootLocation SET MergedIntoId = NULL WHERE LocationId IN (SELECT LocationId FROM @gone);

            UPDATE dbo.Round
            SET LocationId = NULL, ClientUpdatedUtc = DATEADD(millisecond, 1, ClientUpdatedUtc), ServerUpdatedUtc = SYSUTCDATETIME()
            WHERE LocationId IN (SELECT LocationId FROM @gone);
            DECLARE @rounds INT = @@ROWCOUNT;

            DELETE dbo.ShootLocation WHERE LocationId IN (SELECT LocationId FROM @gone);
            SELECT @rounds;
            """, new { locationId }, tx, cancellationToken: ct));
        await tx.CommitAsync(ct);
        return Results.Ok(new { locationId, name = loc.Name, roundsCleared = cleared });
    }

    private static string Plural(int n, string what) => n == 1 ? $"1 {what}" : $"{n} {what}s";

    /// <summary>Other archers' rounds at a location (not deleted): how many archers, how many rounds.</summary>
    public static async Task<(int Archers, int Rounds)> OthersHereAsync(
        SqlConnection conn, Guid locationId, Guid me, System.Data.IDbTransaction? tx, CancellationToken ct) =>
        await conn.QuerySingleAsync<(int, int)>(new CommandDefinition("""
            SELECT COUNT(DISTINCT ArcherId), COUNT(*) FROM dbo.Round
            WHERE LocationId = @locationId AND ArcherId <> @me AND IsDeleted = 0;
            """, new { locationId, me }, tx, cancellationToken: ct));

    /// <summary>
    /// Whether an archer's round can be at a location (3.2.12): not when it's someone else's private location (e.g. a
    /// phone that hadn't heard yet uploads a round still showing it).
    /// </summary>
    public static async Task<bool> UsableByAsync(
        SqlConnection conn, Guid locationId, Guid archerId, System.Data.IDbTransaction? tx, CancellationToken ct) =>
        await conn.ExecuteScalarAsync<int>(new CommandDefinition("""
            SELECT COUNT(*) FROM dbo.ShootLocation
            WHERE LocationId = @locationId AND (IsPrivate = 0 OR CreatedById = @archerId);
            """, new { locationId, archerId }, tx, cancellationToken: ct)) > 0;

    /// <summary>
    /// Merge a duplicate into the location kept. Someone else's private location can only be merged when it's flagged
    /// as a possible duplicate of the location it's merged into (2.0.17), and nothing is ever merged into someone
    /// else's private location (that would hide everyone's rounds there).
    /// </summary>
    private static async Task<IResult> Merge(Guid locationId, MergeRequest req, ClaimsPrincipal user, Db db, IConfiguration config, CancellationToken ct)
    {
        if (!user.IsAdmin()) return NotAdmin();
        if (req.IntoId is not { } intoId) return Error(400, "missing_into", "Choose the location to merge into.");
        if (intoId == locationId) return Error(400, "same_location", "A location can't be merged into itself.");

        await using var conn = await db.OpenAsync(ct);
        var conflict = await DuplicateFinder.IsPairAsync(conn, Catchment(config), locationId, intoId, ct);
        await using var tx = await conn.BeginTransactionAsync(ct);
        var live = (await conn.QueryAsync<(Guid LocationId, bool OthersPrivate)>(new CommandDefinition("""
            SELECT LocationId,
                   CAST(CASE WHEN IsPrivate = 1 AND (CreatedById IS NULL OR CreatedById <> @me) THEN 1 ELSE 0 END AS bit) AS OthersPrivate
            FROM dbo.ShootLocation WITH (UPDLOCK, HOLDLOCK)
            WHERE LocationId IN (@locationId, @intoId) AND MergedIntoId IS NULL;
            """, new { locationId, intoId, me = user.ArcherId() }, tx, cancellationToken: ct))).ToDictionary(r => r.LocationId, r => r.OthersPrivate);
        var ok = live.TryGetValue(locationId, out var fromPrivate) && live.TryGetValue(intoId, out var intoPrivate)
            && !intoPrivate && (!fromPrivate || conflict);
        if (!ok)
        {
            await tx.RollbackAsync(ct);
            return Error(404, "no_such_location", "Both locations must exist and not already be merged.");
        }

        var (moved, name) = await MoveAsync(conn, tx, locationId, intoId, user.ArcherId(), ct);
        await tx.CommitAsync(ct);
        return Results.Ok(new { locationId = intoId, name, roundsMoved = moved });
    }

    /// <summary>The merge itself: rounds move, the duplicate (and anything merged into it) points at the one kept.</summary>
    private static async Task<(int Moved, string Name)> MoveAsync(
        SqlConnection conn, System.Data.Common.DbTransaction tx, Guid locationId, Guid intoId, Guid by, CancellationToken ct)
    {
        var moved = await conn.ExecuteAsync(new CommandDefinition(
            "UPDATE dbo.Round SET LocationId = @intoId WHERE LocationId = @locationId;",
            new { locationId, intoId }, tx, cancellationToken: ct));
        await conn.ExecuteAsync(new CommandDefinition("""
            -- Anything merged into the duplicate earlier now points straight at the one kept.
            UPDATE dbo.ShootLocation SET MergedIntoId = @intoId WHERE MergedIntoId = @locationId;
            UPDATE dbo.ShootLocation SET MergedIntoId = @intoId, MergedUtc = SYSUTCDATETIME(), MergedById = @by
            WHERE LocationId = @locationId;
            """, new { locationId, intoId, by }, tx, cancellationToken: ct));
        var name = await conn.ExecuteScalarAsync<string>(new CommandDefinition(
            "SELECT Name FROM dbo.ShootLocation WHERE LocationId = @intoId;", new { intoId }, tx, cancellationToken: ct));
        return (moved, name ?? "");
    }

    private sealed record OwnPrivate(string Name, decimal? Latitude, decimal? Longitude);

    /// <summary>3.2.32: your own private location (live), or null.</summary>
    private static Task<OwnPrivate?> OwnPrivateAsync(SqlConnection conn, Guid locationId, Guid me, System.Data.Common.DbTransaction? tx, CancellationToken ct) =>
        conn.QuerySingleOrDefaultAsync<OwnPrivate>(new CommandDefinition("""
            SELECT Name, Latitude, Longitude FROM dbo.ShootLocation
            WHERE LocationId = @locationId AND MergedIntoId IS NULL AND IsPrivate = 1 AND CreatedById = @me;
            """, new { locationId, me }, tx, cancellationToken: ct));

    /// <summary>3.2.32 (Pete): public locations similar to your private one (the new-location suggestion rules).</summary>
    private static async Task<List<SimilarMatch>> TargetsAsync(SqlConnection conn, Guid locationId, OwnPrivate own, Guid me, CancellationToken ct) =>
        (await SimilarAsync(conn, own.Name, (double?)own.Latitude, (double?)own.Longitude, me, ct))
            .Where(m => !m.isPrivate && m.locationId != locationId).ToList();

    private static async Task<IResult> MergeTargets(Guid locationId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        var me = user.ArcherId();
        await using var conn = await db.OpenAsync(ct);
        if (await OwnPrivateAsync(conn, locationId, me, null, ct) is not { } own)
            return Error(404, "no_such_location", "That isn't one of your private locations.");
        return Results.Ok(new { locations = await TargetsAsync(conn, locationId, own, me, ct) });
    }

    /// <summary>3.2.32 (Pete): the archer who named a private location merges it into a similar public one.</summary>
    private static async Task<IResult> MergeInto(Guid locationId, MergeRequest req, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        if (req.IntoId is not { } intoId) return Error(400, "missing_into", "Choose the location to merge into.");
        var me = user.ArcherId();
        await using var conn = await db.OpenAsync(ct);
        if (await OwnPrivateAsync(conn, locationId, me, null, ct) is not { } own)
            return Error(404, "no_such_location", "That isn't one of your private locations.");
        if (!(await TargetsAsync(conn, locationId, own, me, ct)).Any(m => m.locationId == intoId))
            return Error(409, "not_similar", "You can only merge it into a public location with the same or a similar name, or nearby.");
        await using var tx = await conn.BeginTransactionAsync(ct);
        // Still yours and private, and the other still public, now that they're locked.
        var still = await conn.ExecuteScalarAsync<int>(new CommandDefinition("""
            SELECT COUNT(*) FROM dbo.ShootLocation WITH (UPDLOCK, HOLDLOCK)
            WHERE MergedIntoId IS NULL AND ((LocationId = @locationId AND IsPrivate = 1 AND CreatedById = @me)
                                            OR (LocationId = @intoId AND IsPrivate = 0));
            """, new { locationId, intoId, me }, tx, cancellationToken: ct));
        if (still != 2)
        {
            await tx.RollbackAsync(ct);
            return Error(404, "no_such_location", "Both locations must exist and not already be merged.");
        }
        var (moved, name) = await MoveAsync(conn, tx, locationId, intoId, me, ct);
        await tx.CommitAsync(ct);
        return Results.Ok(new { locationId = intoId, name, roundsMoved = moved });
    }

    /// <summary>
    /// Possible duplicates for Admins: pairs nearby or with the same / a similar name, not yet approved. A private
    /// location appears (with its name) only when it clashes with a public one (2.0.17).
    /// </summary>
    private static async Task<IResult> Duplicates(ClaimsPrincipal user, Db db, IConfiguration config, CancellationToken ct)
    {
        if (!user.IsAdmin()) return NotAdmin();
        await using var conn = await db.OpenAsync(ct);
        var pairs = await DuplicateFinder.FindAsync(conn, Catchment(config), ct);
        return Results.Ok(new
        {
            pairs = pairs.Select(p => new { a = Summary(p.A), b = Summary(p.B), p.DistanceMetres, p.Reasons }),
        });

        static object Summary(DuplicateFinder.Loc l) => new
        {
            l.LocationId, l.Name, l.IsPrivate, l.CreatedBy, l.Rounds,
            latitude = (double?)l.Latitude, longitude = (double?)l.Longitude,
        };
    }

    /// <summary>An Admin says two locations are different places: stop flagging them.</summary>
    private static async Task<IResult> Approve(ApproveRequest req, ClaimsPrincipal user, Db db, IConfiguration config, CancellationToken ct)
    {
        if (!user.IsAdmin()) return NotAdmin();
        if (req.AId is not { } a || req.BId is not { } b || a == b)
        {
            return Error(400, "invalid_pair", "Choose two different locations.");
        }
        await using var conn = await db.OpenAsync(ct);
        var rows = (await conn.QueryAsync<bool>(new CommandDefinition(
            "SELECT IsPrivate FROM dbo.ShootLocation WHERE LocationId IN (@a, @b) AND MergedIntoId IS NULL;",
            new { a, b }, cancellationToken: ct))).ToList();
        // Two public locations, or a private one flagged against a public one (2.0.17).
        var okPair = rows.Count == 2 && (!rows.Any(p => p) || (rows.Count(p => p) == 1 && await DuplicateFinder.IsPairAsync(conn, Catchment(config), a, b, ct)));
        if (!okPair) return Error(404, "no_such_location", "Both locations must exist and not be merged.");

        await conn.ExecuteAsync(new CommandDefinition("""
            DECLARE @low UNIQUEIDENTIFIER = CASE WHEN @a < @b THEN @a ELSE @b END;
            DECLARE @high UNIQUEIDENTIFIER = CASE WHEN @a < @b THEN @b ELSE @a END;
            IF NOT EXISTS (SELECT 1 FROM dbo.LocationPairApproval WHERE LocationLowId = @low AND LocationHighId = @high)
                INSERT dbo.LocationPairApproval (LocationLowId, LocationHighId, ApprovedById) VALUES (@low, @high, @by);
            """, new { a, b, by = user.ArcherId() }, cancellationToken: ct));
        return Results.NoContent();
    }

    /// <summary>For /api/me: how many possible duplicates are waiting for an Admin.</summary>
    public static async Task<int> DuplicateCountAsync(SqlConnection conn, IConfiguration config, CancellationToken ct) =>
        (await DuplicateFinder.FindAsync(conn, Catchment(config), ct)).Count;

    /// <summary>The live location an id now stands for (following merges), or null if unknown.</summary>
    public static async Task<(Guid LocationId, string Name)?> CurrentAsync(
        SqlConnection conn, Guid id, System.Data.IDbTransaction? tx, CancellationToken ct)
    {
        var current = id;
        for (var hops = 0; hops < 10; hops++)
        {
            var row = await conn.QuerySingleOrDefaultAsync<(string Name, Guid? MergedIntoId)>(new CommandDefinition(
                "SELECT Name, MergedIntoId FROM dbo.ShootLocation WHERE LocationId = @current;",
                new { current }, tx, cancellationToken: ct));
            if (row.Name is null) return null;
            if (row.MergedIntoId is not { } next) return (current, row.Name);
            current = next;
        }
        return null;
    }

    public sealed record SimilarMatch(Guid locationId, string name, bool isPrivate, int? distanceMetres, IReadOnlyList<string> reasons);

    private sealed record SimilarRow(Guid LocationId, string Name, decimal? Latitude, decimal? Longitude, bool IsPrivate);

    /// <summary>
    /// 3.2.25: live locations you can use (public, and your own private ones) that might be the one being named: the same
    /// or a similar name (the Admin duplicate rules), or within [SimilarMetres] of its pin. Best matches first, at most 5.
    /// </summary>
    public static async Task<List<SimilarMatch>> SimilarAsync(SqlConnection conn, string name, double? lat, double? lon, Guid me, CancellationToken ct)
    {
        var rows = await conn.QueryAsync<SimilarRow>(new CommandDefinition("""
            SELECT LocationId, Name, Latitude, Longitude, IsPrivate
            FROM dbo.ShootLocation
            WHERE MergedIntoId IS NULL AND (IsPrivate = 0 OR CreatedById = @me);
            """, new { me }, cancellationToken: ct));
        var key = DuplicateFinder.Key(name);
        var found = new List<SimilarMatch>();
        foreach (var r in rows)
        {
            int? metres = lat is { } la && lon is { } lo && r.Latitude is { } rLat && r.Longitude is { } rLon
                ? (int)Math.Round(Metres(la, lo, (double)rLat, (double)rLon)) : null;
            var reasons = new List<string>();
            var k = DuplicateFinder.Key(r.Name);
            if (k == key) reasons.Add("same_name");
            else if (CloseName(k, key) && (metres is null || metres <= SimilarNameMetres)) reasons.Add("similar_name");
            if (metres <= SimilarMetres) reasons.Add("nearby");
            if (reasons.Count > 0) found.Add(new SimilarMatch(r.LocationId, r.Name, r.IsPrivate, metres, reasons));
        }
        return found.OrderByDescending(m => m.reasons.Count).ThenBy(m => m.distanceMetres ?? int.MaxValue).ThenBy(m => m.name).Take(5).ToList();
    }

    /// <summary>
    /// 3.2.28: DuplicateFinder.Similar for suggestions (the Admin duplicate finder keeps its own): one name inside the
    /// other only counts when the shorter is at least half the longer ("sherwood" in "sherwoodforest" yes; "test" in a
    /// longer name, no); typos as before. The same as the apps' LocationMatch.closeName / closeName.
    /// </summary>
    public static bool CloseName(string a, string b)
    {
        if (a.Length == 0 || b.Length == 0) return false;
        int shorter = Math.Min(a.Length, b.Length), longer = Math.Max(a.Length, b.Length);
        if (shorter >= 4 && shorter * 2 >= longer && (a.Contains(b) || b.Contains(a))) return true;
        return DuplicateFinder.NearlySame(a, b);   // typos
    }

    private static async Task<IResult> NameTaken(SqlConnection conn, string name, CancellationToken ct)
    {
        var existing = await conn.QuerySingleOrDefaultAsync<(Guid LocationId, string Name)>(new CommandDefinition(
            "SELECT LocationId, Name FROM dbo.ShootLocation WHERE Name = @name AND MergedIntoId IS NULL AND IsPrivate = 0;",
            new { name }, cancellationToken: ct));
        return Results.Json(new
        {
            error = "name_taken",
            message = $"There's already a location called \"{existing.Name ?? name}\".",
            existing = existing.Name is null ? null : new { existing.LocationId, existing.Name },
        }, statusCode: StatusCodes.Status409Conflict);
    }

    /// <summary>A quarter mile by default (402 m); "Locations:CatchmentMetres" in appsettings.json.</summary>
    private static int Catchment(IConfiguration config) => config.GetValue("Locations:CatchmentMetres", 402);

    /// <summary>Great-circle distance in metres (haversine).</summary>
    public static double Metres(double lat1, double lon1, double lat2, double lon2)
    {
        const double r = 6_371_000;
        double Rad(double d) => d * Math.PI / 180;
        var dLat = Rad(lat2 - lat1);
        var dLon = Rad(lon2 - lon1);
        var a = Math.Sin(dLat / 2) * Math.Sin(dLat / 2) +
                Math.Cos(Rad(lat1)) * Math.Cos(Rad(lat2)) * Math.Sin(dLon / 2) * Math.Sin(dLon / 2);
        return 2 * r * Math.Asin(Math.Min(1, Math.Sqrt(a)));
    }

    private static decimal Round5(double v) => Math.Round((decimal)v, 5, MidpointRounding.AwayFromZero);

    /// <summary>Trims and squashes runs of spaces; control characters removed.</summary>
    private static string CleanName(string? name) =>
        string.Join(' ', new string((name ?? "").Where(c => !char.IsControl(c)).ToArray())
            .Split(' ', StringSplitOptions.RemoveEmptyEntries));

    private static IResult? NameProblem(string name) =>
        name.Length is >= MinName and <= MaxName
            ? null
            : Error(400, "invalid_name", $"Location names are {MinName} to {MaxName} characters.");

    private static IResult? PositionProblem(double? lat, double? lon) =>
        lat is >= -90 and <= 90 && lon is >= -180 and <= 180
            ? null
            : Error(400, "invalid_position", "Latitude must be -90 to 90 and longitude -180 to 180.");

    private static IResult NotAdmin() => Error(403, "not_admin", "This needs an admin account.");

    private static IResult Error(int status, string code, string message) =>
        Results.Json(new { error = code, message }, statusCode: status);
}
