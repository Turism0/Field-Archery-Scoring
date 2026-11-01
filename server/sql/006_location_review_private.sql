/*
    NfasScoring – schema version 4: private shoot locations and approved look-alike pairs.

    - ShootLocation.IsPrivate: only the archer who named it (and Admins) can see it. Others see
      "Private location" on friends' scorecards; it's left out of the map and all-archer stats.
      Names only have to be unique among public locations now (two people can each have "Home").
    - LocationPairApproval: two locations an Admin has checked and says are different places, so
      they stop showing as possible duplicates (same or similar name, or within the catchment).

    Run in SSMS on Mimir as an administrator (check the query window's status bar: not nfas_api).
    Run this BEFORE publishing API 1.7.01. Safe to run again.
*/
USE NfasScoring;
GO

IF EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version >= 4)
BEGIN
    RAISERROR('Schema version 4 is already applied. Nothing done.', 16, 1);
    SET NOEXEC ON;
END
GO
IF NOT EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version = 3)
BEGIN
    RAISERROR('Run 005_shoot_locations.sql first.', 16, 1);
    SET NOEXEC ON;
END
GO

-- Each part checks first, so if something stops it part-way you can fix it and run the whole script again.
IF COL_LENGTH(N'dbo.ShootLocation', N'IsPrivate') IS NULL
    ALTER TABLE dbo.ShootLocation ADD IsPrivate BIT NOT NULL CONSTRAINT DF_ShootLocation_IsPrivate DEFAULT 0;
GO

-- Name unique among live PUBLIC locations only.
IF EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_ShootLocation_Name'
           AND object_id = OBJECT_ID(N'dbo.ShootLocation') AND filter_definition NOT LIKE '%IsPrivate%')
    DROP INDEX UX_ShootLocation_Name ON dbo.ShootLocation;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_ShootLocation_Name' AND object_id = OBJECT_ID(N'dbo.ShootLocation'))
    CREATE UNIQUE INDEX UX_ShootLocation_Name ON dbo.ShootLocation (Name) WHERE MergedIntoId IS NULL AND IsPrivate = 0;
GO

IF OBJECT_ID(N'dbo.LocationPairApproval') IS NULL
CREATE TABLE dbo.LocationPairApproval (
    -- The pair is stored once, lower id first (SQL Server's own uniqueidentifier order).
    LocationLowId  UNIQUEIDENTIFIER NOT NULL,
    LocationHighId UNIQUEIDENTIFIER NOT NULL,
    ApprovedById   UNIQUEIDENTIFIER NULL,     -- audit only
    ApprovedUtc    DATETIME2(3)     NOT NULL CONSTRAINT DF_LocationPairApproval_ApprovedUtc DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_LocationPairApproval PRIMARY KEY (LocationLowId, LocationHighId),
    CONSTRAINT FK_LocationPairApproval_Low  FOREIGN KEY (LocationLowId)  REFERENCES dbo.ShootLocation (LocationId),
    CONSTRAINT FK_LocationPairApproval_High FOREIGN KEY (LocationHighId) REFERENCES dbo.ShootLocation (LocationId),
    CONSTRAINT CK_LocationPairApproval_Order CHECK (LocationLowId < LocationHighId)
);
GO

INSERT dbo.SchemaVersion (Version, Description) VALUES (4, N'Private shoot locations; approved look-alike location pairs');
GO

SET NOEXEC OFF;
GO
SELECT MAX(Version) AS SchemaVersion FROM dbo.SchemaVersion;   -- should be 4
GO
