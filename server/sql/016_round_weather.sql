/*
    FieldArcheryScoring – schema version 12: weather for rounds (server 3.2.17, Pete).

    The API looks up the weather for each finished round from Open-Meteo (https://open-meteo.com, free, no key) using the
    round's shoot location map pin and its date and times, and keeps it here. Nothing about the archer is sent: only the
    location's pin, a date and the hours. Phones download it with GET /api/rounds/weather.

    - RoundWeather: one row per round once it's been looked at. Latitude/Longitude/LocationId/ShootDate are what was used,
      so a round whose location or date changes (or whose location gets a map pin) is looked up again. Status OK (weather
      found), NO_PIN (no location, or one without a map pin: nothing to look up) or NONE (Open-Meteo had nothing).
      UpdatedUtc drives the phones' download.

    Run in SSMS on Mimir as an administrator (check the query window's status bar: not fieldarchery_api).
    Run this BEFORE publishing API 3.2.17. Safe to run again.
*/
USE FieldArcheryScoring;
GO

IF EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version >= 12)
BEGIN
    RAISERROR('Schema version 12 (round weather) is already applied. Nothing done.', 16, 1);
    SET NOEXEC ON;
END
GO
IF NOT EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version = 11)
BEGIN
    RAISERROR('Run 015_fun_shots.sql first.', 16, 1);
    SET NOEXEC ON;
END
GO

IF OBJECT_ID(N'dbo.RoundWeather') IS NULL
CREATE TABLE dbo.RoundWeather (
    RoundId     UNIQUEIDENTIFIER NOT NULL,
    Status      VARCHAR(8)       NOT NULL,
    LocationId  UNIQUEIDENTIFIER NULL,
    Latitude    DECIMAL(8, 5)    NULL,
    Longitude   DECIMAL(8, 5)    NULL,
    ShootDate   DATE             NOT NULL,
    FromUtc     DATETIME2(0)     NULL,     -- the hours averaged
    ToUtc       DATETIME2(0)     NULL,
    TempC       DECIMAL(4, 1)    NULL,     -- average temperature
    WindKph     DECIMAL(5, 1)    NULL,     -- average wind speed at 10 m
    GustKph     DECIMAL(5, 1)    NULL,     -- strongest gust
    RainMm      DECIMAL(5, 1)    NULL,     -- rain (all precipitation) in total
    WeatherCode SMALLINT         NULL,     -- WMO code, the worst hour
    UpdatedUtc  DATETIME2(3)     NOT NULL CONSTRAINT DF_RoundWeather_UpdatedUtc DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_RoundWeather PRIMARY KEY (RoundId),
    CONSTRAINT FK_RoundWeather_Round FOREIGN KEY (RoundId) REFERENCES dbo.Round (RoundId) ON DELETE CASCADE,
    CONSTRAINT CK_RoundWeather_Status CHECK (Status IN ('OK', 'NO_PIN', 'NONE'))
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_RoundWeather_UpdatedUtc')
    CREATE INDEX IX_RoundWeather_UpdatedUtc ON dbo.RoundWeather (UpdatedUtc, RoundId);
GO

INSERT dbo.SchemaVersion (Version, Description) VALUES (12, N'Round weather (Open-Meteo, from the location pin)');
GO

SET NOEXEC OFF;
GO
