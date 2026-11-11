/*
    FieldArcheryScoring – schema version 11: fun shots (server 3.1).

    A fun shot is a Big Game target with unusual scoring (a novelty shot a club adds): its score is typed in. It
    counts in the round total but is left out of every stat (averages, bests, handicaps, location and friends' stats).

    - TargetScore.TargetType gains FUN: no AnimalScore rows, the score in OverridePoints (and Points).

    Run in SSMS on Mimir as an administrator (check the query window's status bar: not fieldarchery_api).
    Run this BEFORE publishing API 3.1. Safe to run again.
*/
USE FieldArcheryScoring;
GO

IF EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version >= 11)
BEGIN
    RAISERROR('Schema version 11 (fun shots) is already applied. Nothing done.', 16, 1);
    SET NOEXEC ON;
END
GO
IF NOT EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version = 10)
BEGIN
    RAISERROR('Run 014_location_no_pin.sql first.', 16, 1);
    SET NOEXEC ON;
END
GO

IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = N'CK_TargetScore_Type' AND definition NOT LIKE '%FUN%')
    ALTER TABLE dbo.TargetScore DROP CONSTRAINT CK_TargetScore_Type;
GO
IF OBJECT_ID(N'dbo.CK_TargetScore_Type') IS NULL
    ALTER TABLE dbo.TargetScore ADD CONSTRAINT CK_TargetScore_Type
        CHECK (TargetType IN ('STANDARD', 'PREDATOR_PREY', 'FAN', 'WOODSMAN', 'FUN'));
GO

INSERT dbo.SchemaVersion (Version, Description) VALUES (11, N'Fun shots: TargetScore.TargetType FUN (score typed in, left out of stats)');
GO

SET NOEXEC OFF;
GO
SELECT MAX(Version) AS SchemaVersion FROM dbo.SchemaVersion;   -- should be 11
GO
