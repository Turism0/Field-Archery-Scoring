/*
    FieldArcheryScoring – schema version 3: shoot locations (roadmap step 2a).

    ShootLocation: one row per shoot ground, with a name and the position where it was first named.
    The phone takes a GPS fix when a round starts and asks the API for locations within about half a
    mile; if there are none, the archer names a new one. Rounds store only the location id, never the
    archer's own position.

    Admins can rename a location, and merge a duplicate into another: the duplicate's rounds move to the
    one kept, and the duplicate stays as a pointer (MergedIntoId) so phones still holding its id are
    sent to the right place.

    Run in SSMS on Mimir as an administrator (check the query window's status bar: not nfas_api).
    Run this BEFORE publishing API 1.5.01. Safe to run again: it does nothing once schema version 3 is there.
*/
USE FieldArcheryScoring;
GO

IF EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version >= 3)
BEGIN
    RAISERROR('Schema version 3 (shoot locations) is already applied. Nothing done.', 16, 1);
    SET NOEXEC ON;
END
GO
IF NOT EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version = 2)
BEGIN
    RAISERROR('Run 004_roles.sql first.', 16, 1);
    SET NOEXEC ON;
END
GO

-- Each part checks first, so if something stops it part-way you can fix it and run the whole script again.
IF OBJECT_ID(N'dbo.ShootLocation') IS NULL
CREATE TABLE dbo.ShootLocation (
    LocationId    UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_ShootLocation_LocationId DEFAULT NEWSEQUENTIALID(),
    -- Case-insensitive: "Bowmen of Foo" and "bowmen of foo" are the same name.
    Name          NVARCHAR(60) COLLATE Latin1_General_100_CI_AS NOT NULL,
    -- Where it was first named, to about a metre. Used to find the nearest location to a phone.
    Latitude      DECIMAL(8, 5)    NOT NULL,
    Longitude     DECIMAL(8, 5)    NOT NULL,
    CreatedUtc    DATETIME2(3)     NOT NULL CONSTRAINT DF_ShootLocation_CreatedUtc DEFAULT SYSUTCDATETIME(),
    -- Audit only (no foreign key), so removing an account never blocks on the locations it named.
    CreatedById   UNIQUEIDENTIFIER NULL,
    RenamedUtc    DATETIME2(3)     NULL,
    RenamedById   UNIQUEIDENTIFIER NULL,
    -- Set when an admin merges this duplicate into another location.
    MergedIntoId  UNIQUEIDENTIFIER NULL,
    MergedUtc     DATETIME2(3)     NULL,
    MergedById    UNIQUEIDENTIFIER NULL,
    CONSTRAINT PK_ShootLocation PRIMARY KEY (LocationId),
    CONSTRAINT FK_ShootLocation_MergedInto FOREIGN KEY (MergedIntoId) REFERENCES dbo.ShootLocation (LocationId),
    CONSTRAINT CK_ShootLocation_Name      CHECK (LEN(Name) BETWEEN 2 AND 60),
    CONSTRAINT CK_ShootLocation_Latitude  CHECK (Latitude BETWEEN -90 AND 90),
    CONSTRAINT CK_ShootLocation_Longitude CHECK (Longitude BETWEEN -180 AND 180),
    CONSTRAINT CK_ShootLocation_NotSelf   CHECK (MergedIntoId IS NULL OR MergedIntoId <> LocationId)
);
GO

-- No two live locations with the same name (merged ones don't count).
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_ShootLocation_Name')
    CREATE UNIQUE INDEX UX_ShootLocation_Name ON dbo.ShootLocation (Name) WHERE MergedIntoId IS NULL;
GO

IF COL_LENGTH(N'dbo.Round', N'LocationId') IS NULL
    ALTER TABLE dbo.Round ADD LocationId UNIQUEIDENTIFIER NULL
        CONSTRAINT FK_Round_ShootLocation REFERENCES dbo.ShootLocation (LocationId);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_Round_Location')
    CREATE INDEX IX_Round_Location ON dbo.Round (LocationId) WHERE LocationId IS NOT NULL;
GO

INSERT dbo.SchemaVersion (Version, Description) VALUES (3, N'Shoot locations');
GO

SET NOEXEC OFF;
GO
SELECT MAX(Version) AS SchemaVersion FROM dbo.SchemaVersion;   -- should be 3
GO
