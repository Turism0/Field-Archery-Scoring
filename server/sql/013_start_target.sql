/*
    FieldArcheryScoring – schema version 9: the target a round started on (server 2.7).

    - Round.StartTarget: optional; the archer's note of the first target shot (e.g. 12 on a 36-target course).
      Targets keep their own numbers; this only shows on the scorecard.
    - ScoreGroup.StartTarget: the same for a live group, set by the host for everyone.

    The fieldarchery_api login already reads and writes every table (009); nothing extra to grant.
    Run in SSMS on Mimir as an administrator (check the query window's status bar: not fieldarchery_api).
    Run this BEFORE publishing API 2.7. Safe to run again.
*/
USE FieldArcheryScoring;
GO

IF EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version >= 9)
BEGIN
    RAISERROR('Schema version 9 (start target) is already applied. Nothing done.', 16, 1);
    SET NOEXEC ON;
END
GO
IF NOT EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version = 8)
BEGIN
    RAISERROR('Run 012_password_reset.sql first.', 16, 1);
    SET NOEXEC ON;
END
GO

IF COL_LENGTH(N'dbo.Round', N'StartTarget') IS NULL
    ALTER TABLE dbo.Round ADD StartTarget SMALLINT NULL
        CONSTRAINT CK_Round_StartTarget CHECK (StartTarget IS NULL OR StartTarget >= 1);
GO
IF COL_LENGTH(N'dbo.ScoreGroup', N'StartTarget') IS NULL
    ALTER TABLE dbo.ScoreGroup ADD StartTarget SMALLINT NULL
        CONSTRAINT CK_ScoreGroup_StartTarget CHECK (StartTarget IS NULL OR StartTarget >= 1);
GO

INSERT dbo.SchemaVersion (Version, Description) VALUES (9, N'Start target: Round.StartTarget, ScoreGroup.StartTarget');
GO

SET NOEXEC OFF;
GO
SELECT MAX(Version) AS SchemaVersion FROM dbo.SchemaVersion;   -- should be 9
GO
