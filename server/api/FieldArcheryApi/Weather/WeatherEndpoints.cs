// Downloading round weather (3.2.17). All calls need "Authorization: Bearer <token>".
//   GET /api/rounds/weather?since=<utc>&afterId=<roundId>
//     → { weather: [{ roundId, updatedUtc, weather: { tempC, windKph, gustKph, rainMm, code } | null }], more }
//     Your rounds whose weather changed after the cursor, oldest change first, 500 at a time (keep going while "more").
//     weather is null when there's none (no location, no map pin, or nothing from Open-Meteo yet).
// See WeatherService for how it's looked up.

using System.Security.Claims;
using Dapper;
using FieldArcheryApi.Auth;
using FieldArcheryApi.Data;

namespace FieldArcheryApi.Weather;

public static class WeatherEndpoints
{
    private const int Page = 500;

    private sealed record Row(Guid RoundId, DateTime UpdatedUtc, string Status, decimal? TempC, decimal? WindKph, decimal? GustKph,
        decimal? RainMm, short? WeatherCode);

    public static void MapWeatherEndpoints(this WebApplication app)
    {
        app.MapGet("/api/rounds/weather", Changed).RequireAuthorization();
    }

    private static async Task<IResult> Changed(DateTime? since, Guid? afterId, ClaimsPrincipal user, Db db, CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        var rows = (await conn.QueryAsync<Row>(new CommandDefinition($"""
            SELECT TOP ({Page + 1}) w.RoundId, w.UpdatedUtc, w.Status, w.TempC, w.WindKph, w.GustKph, w.RainMm, w.WeatherCode
            FROM dbo.RoundWeather w JOIN dbo.Round r ON r.RoundId = w.RoundId
            WHERE r.ArcherId = @me
              AND (@since IS NULL OR w.UpdatedUtc > @since OR (w.UpdatedUtc = @since AND w.RoundId > @afterId))
            ORDER BY w.UpdatedUtc, w.RoundId;
            """, new { me = user.ArcherId(), since = since?.ToUniversalTime(), afterId = afterId ?? Guid.Empty }, cancellationToken: ct))).ToList();
        var more = rows.Count > Page;
        WeatherService.Kick();   // a phone is asking: look for rounds to do
        return Results.Ok(new
        {
            weather = rows.Take(Page).Select(r => new
            {
                r.RoundId,
                updatedUtc = DateTime.SpecifyKind(r.UpdatedUtc, DateTimeKind.Utc),
                weather = r.Status == "OK"
                    ? new { tempC = (double?)r.TempC, windKph = (double?)r.WindKph, gustKph = (double?)r.GustKph, rainMm = (double?)r.RainMm, code = (int?)r.WeatherCode }
                    : null,
            }),
            more,
        });
    }
}
