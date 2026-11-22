/*
    FieldArcheryScoring – schema version 14: which app each live group member uses (API 3.2.23, Pete).

    - ScoreGroupMember.App: 'android' or 'web' for the host and archers who joined on their own device (from the app's
      X-App-Version header); NULL for friends and guests the host added, and for members from before this script. The
      phones use it to say who's on the web app, which can't share scores over Bluetooth with no signal.

    The fieldarchery_api login already reads and writes every table (009); nothing extra to grant.
    Run in SSMS on Mimir as an administrator (check the query window's status bar: not fieldarchery_api).
    Run this BEFORE publishing API 3.2.23. Safe to run again.
*/
USE FieldArcheryScoring;
GO

IF EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version >= 14)
BEGIN
    RAISERROR('Schema version 14 (group member app) is already applied. Nothing done.', 16, 1);
    SET NOEXEC ON;
END
GO
IF NOT EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version = 13)
BEGIN
    RAISERROR('Run 017_round_notes.sql first.', 16, 1);
    SET NOEXEC ON;
END
GO

IF COL_LENGTH(N'dbo.ScoreGroupMember', N'App') IS NULL
    ALTER TABLE dbo.ScoreGroupMember ADD App VARCHAR(8) NULL
        CONSTRAINT CK_ScoreGroupMember_App CHECK (App IS NULL OR App IN ('android', 'web'));
GO

INSERT dbo.SchemaVersion (Version, Description) VALUES (14, N'Group member app: ScoreGroupMember.App');
GO

SET NOEXEC OFF;
GO
SELECT MAX(Version) AS SchemaVersion FROM dbo.SchemaVersion;   -- should be 14
GO
