/*
    FieldArcheryScoring – schema version 10: shoot locations with a name but no map pin (server 3.0).

    For historical scores where the shoot's name is known but not where it was (e.g. a Nationals years ago): the
    location's name shows on scorecards and it has a location page, but it isn't on the map and phones never match
    it by position. Its creator or an Admin can put it on the map later.

    - ShootLocation.Latitude / Longitude may be NULL, both together (CK_ShootLocation_Pin).
      The range checks are dropped and made again around the change (SQL Server won't alter a column a check uses).

    The fieldarchery_api login already reads and writes every table (009); nothing extra to grant.
    Run in SSMS on Mimir as an administrator (check the query window's status bar: not fieldarchery_api).
    Run this BEFORE publishing API 3.0. Safe to run again.
*/
USE FieldArcheryScoring;
GO

IF EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version >= 10)
BEGIN
    RAISERROR('Schema version 10 (locations without a map pin) is already applied. Nothing done.', 16, 1);
    SET NOEXEC ON;
END
GO
IF NOT EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version = 9)
BEGIN
    RAISERROR('Run 013_start_target.sql first.', 16, 1);
    SET NOEXEC ON;
END
GO

IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = N'CK_ShootLocation_Latitude')
    ALTER TABLE dbo.ShootLocation DROP CONSTRAINT CK_ShootLocation_Latitude;
IF EXISTS (SELECT 1 FROM sys.check_constraints WHERE name = N'CK_ShootLocation_Longitude')
    ALTER TABLE dbo.ShootLocation DROP CONSTRAINT CK_ShootLocation_Longitude;
GO
ALTER TABLE dbo.ShootLocation ALTER COLUMN Latitude DECIMAL(8, 5) NULL;
ALTER TABLE dbo.ShootLocation ALTER COLUMN Longitude DECIMAL(8, 5) NULL;
GO
ALTER TABLE dbo.ShootLocation ADD
    CONSTRAINT CK_ShootLocation_Latitude  CHECK (Latitude IS NULL OR Latitude BETWEEN -90 AND 90),
    CONSTRAINT CK_ShootLocation_Longitude CHECK (Longitude IS NULL OR Longitude BETWEEN -180 AND 180),
    -- A pin is both or neither.
    CONSTRAINT CK_ShootLocation_Pin       CHECK ((Latitude IS NULL AND Longitude IS NULL) OR (Latitude IS NOT NULL AND Longitude IS NOT NULL));
GO

INSERT dbo.SchemaVersion (Version, Description) VALUES (10, N'Shoot locations without a map pin: ShootLocation.Latitude / Longitude nullable');
GO

SET NOEXEC OFF;
GO
SELECT MAX(Version) AS SchemaVersion FROM dbo.SchemaVersion;   -- should be 10
GO
