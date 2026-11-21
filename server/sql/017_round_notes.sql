/*
    FieldArcheryScoring – schema version 13: round notes (API 3.2.21, Pete).

    - Round.Notes: optional free text the archer types when starting a round or later in Round details (up to 500
      characters). Only ever returned to the archer who owns the round: friends and Admins viewing a scorecard through
      the API don't get it. Old apps don't send notes; the API keeps what's stored when a round arrives without them.

    The fieldarchery_api login already reads and writes every table (009); nothing extra to grant.
    Run in SSMS on Mimir as an administrator (check the query window's status bar: not fieldarchery_api).
    Run this BEFORE publishing API 3.2.21. Safe to run again.
*/
USE FieldArcheryScoring;
GO

IF EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version >= 13)
BEGIN
    RAISERROR('Schema version 13 (round notes) is already applied. Nothing done.', 16, 1);
    SET NOEXEC ON;
END
GO
IF NOT EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version = 12)
BEGIN
    RAISERROR('Run 016_round_weather.sql first.', 16, 1);
    SET NOEXEC ON;
END
GO

IF COL_LENGTH(N'dbo.Round', N'Notes') IS NULL
    ALTER TABLE dbo.Round ADD Notes NVARCHAR(500) NULL;
GO

INSERT dbo.SchemaVersion (Version, Description) VALUES (13, N'Round notes: Round.Notes');
GO

SET NOEXEC OFF;
GO
SELECT MAX(Version) AS SchemaVersion FROM dbo.SchemaVersion;   -- should be 13
GO
