// Weather for rounds (3.2.17, Pete). A background job in the API looks up the weather for each finished round from
// Open-Meteo (https://open-meteo.com: free, no key; "Weather data by Open-Meteo.com") using the round's shoot location
// map pin, its date and the hours it was shot, and keeps it in dbo.RoundWeather (sql/016_round_weather.sql). Only the
// location's pin, a date and hours are sent: nothing about the archer, and never a phone's own position.
//
// Which hours: from when the round was started on the phone to its last change, if both were on the shoot date (at most
// 6 hours); otherwise (paper and earlier rounds) from 10:00 UK time for about 5 minutes a target (1 to 6 hours).
// Rounds in the last 60 days use Open-Meteo's forecast API (it keeps recent days), older ones the historical archive.
// A round whose location, pin or date changes is looked up again; one Open-Meteo had nothing for yet (NONE, the archive
// is a few days behind) is tried again daily for 10 days. Rounds with no location, or one with no pin, are NO_PIN.
//
// It runs every few minutes while the API is running (appsettings.json "Weather": { "Enabled", "IntervalMinutes" }),
// and straight after a round is uploaded. IIS stops the API when nobody's used it for a while; it carries on when it
// next starts.

using System.Globalization;
using System.Text.Json;
using Dapper;
using FieldArcheryApi.Data;

namespace FieldArcheryApi.Weather;

public sealed class WeatherService(Db db, IHttpClientFactory httpFactory, IConfiguration config, ILogger<WeatherService> log)
    : BackgroundService
{
    private static readonly SemaphoreSlim Wake = new(0);

    /// <summary>Looks for rounds to do now (e.g. one was just uploaded) rather than at the next interval.</summary>
    public static void Kick()
    {
        if (Wake.CurrentCount == 0) Wake.Release();
    }

    private const int Batch = 40;
    private static readonly TimeZoneInfo Uk = FindUk();

    protected override async Task ExecuteAsync(CancellationToken stop)
    {
        if (!config.GetValue("Weather:Enabled", true)) return;
        var interval = TimeSpan.FromMinutes(Math.Max(1, config.GetValue("Weather:IntervalMinutes", 10)));
        await Task.Delay(TimeSpan.FromSeconds(20), stop);   // let the API finish starting
        while (!stop.IsCancellationRequested)
        {
            try
            {
                // Keep going while there's a full batch (catching up on older rounds), then wait.
                while (await RunOnceAsync(stop) == Batch && !stop.IsCancellationRequested) await Task.Delay(TimeSpan.FromSeconds(2), stop);
            }
            catch (OperationCanceledException) when (stop.IsCancellationRequested) { return; }
            catch (Exception ex)
            {
                log.LogWarning(ex, "Weather lookup failed; trying again later");
            }
            try { await Wake.WaitAsync(interval, stop); }
            catch (OperationCanceledException) { return; }
        }
    }

    private sealed record Todo(Guid RoundId, DateTime ShootDate, short TargetCount, DateTime ClientCreatedUtc, DateTime ClientUpdatedUtc,
        Guid? LocationId, decimal? Latitude, decimal? Longitude);

    /// <summary>Does up to one batch; returns how many rounds it looked at.</summary>
    private async Task<int> RunOnceAsync(CancellationToken ct)
    {
        await using var conn = await db.OpenAsync(ct);
        var todo = (await conn.QueryAsync<Todo>(new CommandDefinition($"""
            SELECT TOP ({Batch}) r.RoundId, CAST(r.ShootDate AS datetime2(0)) AS ShootDate, r.TargetCount, r.ClientCreatedUtc,
                   r.ClientUpdatedUtc, r.LocationId, l.Latitude, l.Longitude
            FROM dbo.Round r
            LEFT JOIN dbo.ShootLocation l ON l.LocationId = r.LocationId
            LEFT JOIN dbo.RoundWeather w ON w.RoundId = r.RoundId
            WHERE r.Status = 'COMPLETED' AND r.IsDeleted = 0 AND r.ShootDate <= CAST(SYSUTCDATETIME() AS date)
              AND (w.RoundId IS NULL
                   OR w.ShootDate <> r.ShootDate
                   OR ISNULL(CONVERT(varchar(36), w.LocationId), '') <> ISNULL(CONVERT(varchar(36), r.LocationId), '')
                   OR ISNULL(w.Latitude, 999) <> ISNULL(l.Latitude, 999)
                   OR ISNULL(w.Longitude, 999) <> ISNULL(l.Longitude, 999)
                   OR (w.Status = 'NONE' AND w.UpdatedUtc < DATEADD(day, -1, SYSUTCDATETIME())
                       AND r.ShootDate >= DATEADD(day, -10, CAST(SYSUTCDATETIME() AS date))))
            ORDER BY r.ShootDate DESC, r.RoundId;
            """, cancellationToken: ct))).ToList();
        if (todo.Count == 0) return 0;

        var cache = new Dictionary<string, Hourly?>();
        foreach (var t in todo)
        {
            var date = DateOnly.FromDateTime(t.ShootDate);
            if (t.Latitude is not { } lat || t.Longitude is not { } lon)
            {
                await SaveAsync(conn, t, date, "NO_PIN", null, null, null, ct);
                continue;
            }
            var (from, to) = Window(t, date);
            var key = $"{lat}|{lon}|{date:yyyy-MM-dd}";
            if (!cache.TryGetValue(key, out var hourly))
            {
                hourly = await FetchAsync(lat, lon, date, ct);   // throws when Open-Meteo can't be reached: stop this run
                cache[key] = hourly;
            }
            var w = hourly?.Average(from, to);
            await SaveAsync(conn, t, date, w is null ? "NONE" : "OK", from, to, w, ct);
        }
        log.LogInformation("Weather looked up for {Count} rounds", todo.Count);
        return todo.Count;
    }

    private static async Task SaveAsync(Microsoft.Data.SqlClient.SqlConnection conn, Todo t, DateOnly date, string status,
        DateTime? from, DateTime? to, Values? w, CancellationToken ct)
    {
        await conn.ExecuteAsync(new CommandDefinition("""
            MERGE dbo.RoundWeather AS d
            USING (SELECT @RoundId AS RoundId) AS s ON d.RoundId = s.RoundId
            WHEN MATCHED THEN UPDATE SET Status = @status, LocationId = @LocationId, Latitude = @Latitude, Longitude = @Longitude,
                ShootDate = @date, FromUtc = @from, ToUtc = @to, TempC = @tempC, WindKph = @windKph, GustKph = @gustKph,
                RainMm = @rainMm, WeatherCode = @code, UpdatedUtc = SYSUTCDATETIME()
            WHEN NOT MATCHED THEN INSERT (RoundId, Status, LocationId, Latitude, Longitude, ShootDate, FromUtc, ToUtc, TempC, WindKph,
                GustKph, RainMm, WeatherCode)
                VALUES (@RoundId, @status, @LocationId, @Latitude, @Longitude, @date, @from, @to, @tempC, @windKph, @gustKph, @rainMm, @code);
            """, new
        {
            t.RoundId, status, t.LocationId, t.Latitude, t.Longitude, date = date.ToDateTime(TimeOnly.MinValue), from, to,
            tempC = Round1(w?.TempC), windKph = Round1(w?.WindKph), gustKph = Round1(w?.GustKph), rainMm = Round1(w?.RainMm),
            code = w?.Code is { } c ? (short?)c : null,
        }, cancellationToken: ct));
    }

    private static decimal? Round1(double? v) => v is { } x ? Math.Round((decimal)x, 1) : null;

    /// <summary>The UTC hours the round was shot (see the notes at the top).</summary>
    internal static (DateTime From, DateTime To) Window(DateTime createdUtc, DateTime updatedUtc, int targets, DateOnly date)
    {
        var created = DateTime.SpecifyKind(createdUtc, DateTimeKind.Utc);
        var updated = DateTime.SpecifyKind(updatedUtc, DateTimeKind.Utc);
        DateTime start;
        if (DateOnly.FromDateTime(TimeZoneInfo.ConvertTimeFromUtc(created, Uk)) == date) start = created;
        else start = TimeZoneInfo.ConvertTimeToUtc(DateTime.SpecifyKind(date.ToDateTime(new TimeOnly(10, 0)), DateTimeKind.Unspecified), Uk);
        DateTime end;
        if (start == created && DateOnly.FromDateTime(TimeZoneInfo.ConvertTimeFromUtc(updated, Uk)) == date && updated > start.AddMinutes(30))
            end = updated < start.AddHours(6) ? updated : start.AddHours(6);
        else
            end = start.AddMinutes(Math.Clamp(targets * 5, 60, 360));
        return (start, end);
    }

    private static (DateTime From, DateTime To) Window(Todo t, DateOnly date) =>
        Window(t.ClientCreatedUtc, t.ClientUpdatedUtc, t.TargetCount, date);

    internal sealed record Values(double? TempC, double? WindKph, double? GustKph, double? RainMm, int? Code);

    /// <summary>One day of Open-Meteo's hourly values (UTC hours).</summary>
    internal sealed class Hourly(List<DateTime> times, List<double?> temp, List<double?> rain, List<double?> code,
        List<double?> wind, List<double?> gust)
    {
        /// <summary>Averages over the hours from..to (whole hours the round touched); null when there's nothing there.</summary>
        public Values? Average(DateTime from, DateTime to)
        {
            var first = new DateTime(from.Year, from.Month, from.Day, from.Hour, 0, 0, DateTimeKind.Utc);
            var idx = Enumerable.Range(0, times.Count).Where(i => times[i] >= first && times[i] <= to).ToList();
            static List<double> Vals(List<double?> l, List<int> ix) => ix.Select(i => i < l.Count ? l[i] : null).OfType<double>().ToList();
            var t = Vals(temp, idx); var r = Vals(rain, idx); var c = Vals(code, idx); var w = Vals(wind, idx); var g = Vals(gust, idx);
            if (t.Count + r.Count + c.Count + w.Count + g.Count == 0) return null;
            return new Values(
                t.Count > 0 ? t.Average() : null, w.Count > 0 ? w.Average() : null, g.Count > 0 ? g.Max() : null,
                r.Count > 0 ? r.Sum() : null, c.Count > 0 ? (int)c.Max() : null);
        }

        public static Hourly? Parse(string json)
        {
            using var doc = JsonDocument.Parse(json);
            if (!doc.RootElement.TryGetProperty("hourly", out var h) || !h.TryGetProperty("time", out var timeEl)) return null;
            var times = timeEl.EnumerateArray()
                .Select(e => DateTime.SpecifyKind(DateTime.Parse(e.GetString()!, CultureInfo.InvariantCulture), DateTimeKind.Utc)).ToList();
            List<double?> Read(string name) => h.TryGetProperty(name, out var a)
                ? a.EnumerateArray().Select(e => e.ValueKind == JsonValueKind.Number ? e.GetDouble() : (double?)null).ToList()
                : [];
            return new Hourly(times, Read("temperature_2m"), Read("precipitation"), Read("weather_code"),
                Read("wind_speed_10m"), Read("wind_gusts_10m"));
        }
    }

    private async Task<Hourly?> FetchAsync(decimal lat, decimal lon, DateOnly date, CancellationToken ct)
    {
        var recent = DateOnly.FromDateTime(DateTime.UtcNow).DayNumber - date.DayNumber <= 60;
        var host = recent ? "https://api.open-meteo.com/v1/forecast" : "https://archive-api.open-meteo.com/v1/archive";
        var d = date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        var url = $"{host}?latitude={lat.ToString(CultureInfo.InvariantCulture)}&longitude={lon.ToString(CultureInfo.InvariantCulture)}" +
                  $"&start_date={d}&end_date={d}&hourly=temperature_2m,precipitation,weather_code,wind_speed_10m,wind_gusts_10m" +
                  "&timezone=GMT&wind_speed_unit=kmh";
        var client = httpFactory.CreateClient("weather");
        using var resp = await client.GetAsync(url, ct);
        if ((int)resp.StatusCode == 400) return null;   // e.g. a date the archive doesn't have
        resp.EnsureSuccessStatusCode();
        return Hourly.Parse(await resp.Content.ReadAsStringAsync(ct));
    }

    private static TimeZoneInfo FindUk()
    {
        try { return TimeZoneInfo.FindSystemTimeZoneById("Europe/London"); }
        catch (TimeZoneNotFoundException) { return TimeZoneInfo.FindSystemTimeZoneById("GMT Standard Time"); }
    }
}
