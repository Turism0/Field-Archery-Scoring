/*
    FieldArcheryScoring – schema version 5: round types (roadmap step 3). First new type: Woodsman.

    - Round.RoundType: BIG_GAME (every existing round) or WOODSMAN.
    - TargetScore.TargetType gains WOODSMAN: one animal, shoot until a kill, up to 3 arrows.
    - TargetScore.Arrows: a Woodsman target's arrows in order, one letter each:
        W = wound (5), K = kill (30 / 20 / 10 on arrow 1 / 2 / 3, and shooting stops), M = miss (0).
      e.g. "K" = 30, "WK" = 25, "MWK" = 15, "WWW" = 15. Big Game targets leave it empty (they use AnimalScore).
    The API checks the full rules; the database checks the letters and the type.

    Run in SSMS on Mimir as an administrator (check the query window's status bar: not nfas_api).
    Run this BEFORE publishing API 1.9.01. Safe to run again.
*/
USE FieldArcheryScoring;
GO

IF EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version >= 5)
BEGIN
    RAISERROR('Schema version 5 (round types) is already applied. Nothing done.', 16, 1);
    SET NOEXEC ON;
END
GO
IF NOT EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version = 4)
BEGIN
    RAISERROR('Run 006_location_review_private.sql first.', 16, 1);
    SET NOEXEC ON;
END
GO

-- Each part checks first, so if something stops it part-way you can fix it and run the whole script again.
IF COL_LENGTH(N'dbo.Round', N'RoundType') IS NULL
    ALTER TABLE dbo.Round ADD RoundType VARCHAR(16) NOT NULL CONSTRAINT DF_Round_RoundType DEFAULT 'BIG_GAME';
GO
IF OBJECT_ID(N'dbo.CK_Round_RoundType') IS NULL
    ALTER TABLE dbo.Round ADD CONSTRAINT CK_Round_RoundType CHECK (RoundType IN ('BIG_GAME', 'WOODSMAN'));
GO

IF COL_LENGTH(N'dbo.TargetScore', N'Arrows') IS NULL
    ALTER TABLE dbo.TargetScore ADD Arrows VARCHAR(3) NULL;
GO
IF OBJECT_ID(N'dbo.CK_TargetScore_Arrows') IS NULL
    ALTER TABLE dbo.TargetScore ADD CONSTRAINT CK_TargetScore_Arrows
        CHECK (Arrows IS NULL OR Arrows COLLATE Latin1_General_BIN2 NOT LIKE '%[^WMK]%');
GO

-- Allow the new target type.
IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = N'CK_TargetScore_Type' AND definition NOT LIKE '%WOODSMAN%')
    ALTER TABLE dbo.TargetScore DROP CONSTRAINT CK_TargetScore_Type;
GO
IF OBJECT_ID(N'dbo.CK_TargetScore_Type') IS NULL
    ALTER TABLE dbo.TargetScore ADD CONSTRAINT CK_TargetScore_Type
        CHECK (TargetType IN ('STANDARD', 'PREDATOR_PREY', 'FAN', 'WOODSMAN'));
GO

INSERT dbo.SchemaVersion (Version, Description) VALUES (5, N'Round types: Woodsman (Round.RoundType, TargetScore.Arrows)');
GO

SET NOEXEC OFF;
GO
SELECT MAX(Version) AS SchemaVersion FROM dbo.SchemaVersion;   -- should be 5
GO
