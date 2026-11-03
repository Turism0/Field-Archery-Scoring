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
///   GET  /api/locations/nearby?lat=&amp;lon=&amp;radius=   → { locations: [{ locationId, name, distanceMetres }] } nearest first;
///                                                    radius in metres, default the catchment (about a quarter mile), max 10 km
///   POST /api/locations          { name, latitude, longitude, isPrivate? } → 201 { locationId, name }; 409 name_taken with the existing one
///   POST /api/locations/resolve  { ids: [...] }   → { locations: [{ requestedId, locationId, name }] } current id and name
///                                                    for ids a phone holds (follows merges; unknown ids left out)
///  Admins and Owner:
///   GET  /api/admin/locations                     → { locations: [{ locationId, name, latitude, longitude, createdUtc, createdBy, rounds }] }
///   PUT  /api/admin/locations/{id}   { name, isPrivate? } → { locationId, name }; 409 name_taken
///   GET  /api/admin/locations/duplicates          → { pairs: [{ a, b, distanceMetres, reasons: [nearby|same_name|similar_name] }] }
///   POST /api/admin/locations/duplicates/approve { aId, bId } → 204: different places, stop flagging them
///   POST /api/admin/locations/{id}/merge { intoId } → { locationId, name, roundsMoved }: the duplicate's rounds move to intoId
/// </summary>
public static class LocationEndpoints
{
    public const int MinName = 2;
    public const int MaxName = 60;
    private const int MaxRadius = 10_000;

    public sealed record NewLocation(string? Name, double? Latitude, double? Longitude, bool? IsPrivate = null);
    public sealed record ResolveRequest(IReadOnlyList<Guid>? Ids);
    public sealed record RenameRequest(string? Name, bool? IsPrivate = null);
    public sealed record ApproveRequest(Guid? AId, Guid? BId);
    public sealed record MergeRequest(Guid? IntoId);

    private sealed record LocationRow(Guid LocationId, string Name, decimal Latitude, decimal Longitude);
    private sealed record AdminRow(Guid LocationId, string Name, decimal Latitude, decimal Longitude,
        DateTime CreatedUtc, string? CreatedBy, int Rounds, bool IsPrivate);

    /// <summary>What other archers see instead of a private location's name.</summary>
    public const string PrivateName = "Private location";

    /// <summary>
    /// SQL for a location's name as the viewer may see it ("l" = ShootLocation, @viewer / @viewerIsAdmin bound).
    /// Private locations show their name only to the archer who named them and to Admins.
    /// </summary>
    public const string VisibleNameSql =
        "CASE WHEN l.IsPrivate = 1 AND @viewerIsAdmin = 0 AND (l.CreatedById IS NULL OR l.CreatedById <> @viewer) " +
        "THEN N'" + PrivateName + "' ELSE l.Name END";

    public static void MapLocationEndpoints(this WebApplication app)
    {
        var locations = app.MapGroup("/api/locations").RequireAuthorization();
        locations.MapGet("/nearby", Nearby);
        locations.MapPost("/", Create);
        locations.MapPost("/resolve", Resolve);

        var admin = app.MapGroup("/api/admin/locations").RequireAuthorization();
        admin.MapGet("/", AdminList);
        admin.MapPut("/{locationId:guid}", Rename);
        admin.MapPost("/{locationId:guid}/merge", Merge);
        admin.MapGet("/duplicates", Duplicates);
        admin.MapPost("/duplicates/approve", Approve);
    }

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
        if (PositionProblem(req.Latitude, req.Longitude) is { } bad) return bad;
        var name = CleanName(req.Name);
        if (NameProblem(name) is { } badName) return badName;

        await using var conn = await db.OpenAsync(ct);
        try
        {
            var id = await conn.QuerySingleAsync<Guid>(new CommandDefinition("""
                INSERT dbo.ShootLocation (Name, Latitude, Longitude, CreatedById, IsPrivate)
                OUTPUT inserted.LocationId
                VALUES (@name, @lat, @lon, @by, @isPrivate);
                """,
                new
                {
                    name, lat = Round5(req.Latitude!.Value), lon = Round5(req.Longitude!.Value), by = user.ArcherId(),
                    isPrivate = req.IsPrivate == true,
                },
                cancellationToken: ct));
            return Results.Created($"/api/locations/{id}", new { locationId = id, name, isPrivate = req.IsPrivate == true });
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
            ORDER BY l.Name;
            """, cancellationToken: ct));
        return Results.Ok(new
        {
            locations = rows.Select(r => new
            {
                r.LocationId, r.Name, latitude = (double)r.Latitude, longitude = (double)r.Longitude,
                createdUtc = DateTime.SpecifyKind(r.CreatedUtc, DateTimeKind.Utc), r.CreatedBy, r.Rounds, r.IsPrivate,
            }),
        });
    }

    private static async Task<IResult> Rename(Guid locationId, RenameRequest req, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        if (!user.IsAdmin()) return NotAdmin();
        var name = CleanName(req.Name);
        if (NameProblem(name) is { } badName) return badName;

        await using var conn = await db.OpenAsync(ct);
        try
        {
            var changed = await conn.ExecuteAsync(new CommandDefinition("""
                UPDATE dbo.ShootLocation
                SET Name = @name, IsPrivate = COALESCE(@isPrivate, IsPrivate), RenamedUtc = SYSUTCDATETIME(), RenamedById = @by
                WHERE LocationId = @locationId AND MergedIntoId IS NULL;
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

    private static async Task<IResult> Merge(Guid locationId, MergeRequest req, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        if (!user.IsAdmin()) return NotAdmin();
        if (req.IntoId is not { } intoId) return Error(400, "missing_into", "Choose the location to merge into.");
        if (intoId == locationId) return Error(400, "same_location", "A location can't be merged into itself.");

        await using var conn = await db.OpenAsync(ct);
        await using var tx = await conn.BeginTransactionAsync(ct);
        var live = (await conn.QueryAsync<Guid>(new CommandDefinition("""
            SELECT LocationId FROM dbo.ShootLocation WITH (UPDLOCK, HOLDLOCK)
            WHERE LocationId IN (@locationId, @intoId) AND MergedIntoId IS NULL;
            """, new { locationId, intoId }, tx, cancellationToken: ct))).ToHashSet();
        if (!live.Contains(locationId) || !live.Contains(intoId))
        {
            await tx.RollbackAsync(ct);
            return Error(404, "no_such_location", "Both locations must exist and not already be merged.");
        }

        var moved = await conn.ExecuteAsync(new CommandDefinition(
            "UPDATE dbo.Round SET LocationId = @intoId WHERE LocationId = @locationId;",
            new { locationId, intoId }, tx, cancellationToken: ct));
        await conn.ExecuteAsync(new CommandDefinition("""
            -- Anything merged into the duplicate earlier now points straight at the one kept.
            UPDATE dbo.ShootLocation SET MergedIntoId = @intoId WHERE MergedIntoId = @locationId;
            UPDATE dbo.ShootLocation SET MergedIntoId = @intoId, MergedUtc = SYSUTCDATETIME(), MergedById = @by
            WHERE LocationId = @locationId;
            """, new { locationId, intoId, by = user.ArcherId() }, tx, cancellationToken: ct));
        var name = await conn.ExecuteScalarAsync<string>(new CommandDefinition(
            "SELECT Name FROM dbo.ShootLocation WHERE LocationId = @intoId;", new { intoId }, tx, cancellationToken: ct));
        await tx.CommitAsync(ct);
        return Results.Ok(new { locationId = intoId, name, roundsMoved = moved });
    }

    /// <summary>Possible duplicates for Admins: pairs nearby or with the same / a similar name, not yet approved.</summary>
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
            latitude = (double)l.Latitude, longitude = (double)l.Longitude,
        };
    }

    /// <summary>An Admin says two locations are different places: stop flagging them.</summary>
    private static async Task<IResult> Approve(ApproveRequest req, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        if (!user.IsAdmin()) return NotAdmin();
        if (req.AId is not { } a || req.BId is not { } b || a == b)
        {
            return Error(400, "invalid_pair", "Choose two different locations.");
        }
        await using var conn = await db.OpenAsync(ct);
        var live = await conn.ExecuteScalarAsync<int>(new CommandDefinition(
            "SELECT COUNT(*) FROM dbo.ShootLocation WHERE LocationId IN (@a, @b) AND MergedIntoId IS NULL;",
            new { a, b }, cancellationToken: ct));
        if (live != 2) return Error(404, "no_such_location", "Both locations must exist and not be merged.");

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
