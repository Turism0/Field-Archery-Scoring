/*
    FieldArcheryScoring – schema version 16: "not the same place" answers for private locations (API 3.2.33, Pete).

    - LocationNotSame: an archer's private location (LocationId) and a similar public location (PublicLocationId) they
      said is a different place. The app then stops suggesting merging the one into the other (location page banner and
      the red number on the Locations tile). A new similar public location is still suggested.
    No foreign keys (two to ShootLocation can't both cascade); the API removes rows when either location is deleted or
    the private one is merged, and when the account is deleted.

    The fieldarchery_api login already reads and writes every table (009); nothing extra to grant.
    Run in SSMS on Mimir as an administrator (check the query window's status bar: not fieldarchery_api).
    Run this BEFORE publishing API 3.2.33. Safe to run again.
*/
USE FieldArcheryScoring;
GO

IF EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version >= 16)
BEGIN
    RAISERROR('Schema version 16 (LocationNotSame) is already applied. Nothing done.', 16, 1);
    SET NOEXEC ON;
END
GO
IF NOT EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version = 15)
BEGIN
    RAISERROR('Run 019_equipment.sql first.', 16, 1);
    SET NOEXEC ON;
END
GO

IF OBJECT_ID(N'dbo.LocationNotSame') IS NULL
CREATE TABLE dbo.LocationNotSame (
    LocationId       UNIQUEIDENTIFIER NOT NULL,   -- the archer's private location
    PublicLocationId UNIQUEIDENTIFIER NOT NULL,   -- the public one they said is a different place
    AnsweredUtc      DATETIME2(3)     NOT NULL CONSTRAINT DF_LocationNotSame_AnsweredUtc DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_LocationNotSame PRIMARY KEY (LocationId, PublicLocationId)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_LocationNotSame_Public')
    CREATE INDEX IX_LocationNotSame_Public ON dbo.LocationNotSame (PublicLocationId);
GO

INSERT dbo.SchemaVersion (Version, Description) VALUES (16, N'LocationNotSame: private location vs similar public one, not the same place');
GO

SET NOEXEC OFF;
GO
SELECT MAX(Version) AS SchemaVersion FROM dbo.SchemaVersion;   -- should be 16
GO
