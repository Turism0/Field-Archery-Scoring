/*
    FieldArcheryScoring – schema version 15: equipment (API 3.2.24, Pete).

    - Bow: an archer's bows (type, make, model, poundage) and the bow styles each is shot in (comma-separated codes,
      e.g. 'LB,HT'). The phone makes the id. Deleted ones are kept, flagged, so other phones and the archer's old
      rounds still know them.
    - ArrowSet: an archer's arrows (make, model, length, arrow weight, spine, fletches, point weight) and the bows they're
      used with (comma-separated bow ids).
    - Round.BowId / Round.ArrowId: the bow and arrows a round was shot with. Only ever returned to the archer who owns the
      round, like notes: friends and Admins don't get them. Old apps don't send them; the API keeps what's stored.
    Both tables go with the account when it's deleted (cascade).

    The fieldarchery_api login already reads and writes every table (009); nothing extra to grant.
    Run in SSMS on Mimir as an administrator (check the query window's status bar: not fieldarchery_api).
    Run this BEFORE publishing API 3.2.24. Safe to run again.
*/
USE FieldArcheryScoring;
GO

IF EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version >= 15)
BEGIN
    RAISERROR('Schema version 15 (equipment) is already applied. Nothing done.', 16, 1);
    SET NOEXEC ON;
END
GO
IF NOT EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version = 14)
BEGIN
    RAISERROR('Run 018_group_member_app.sql first.', 16, 1);
    SET NOEXEC ON;
END
GO

IF OBJECT_ID(N'dbo.Bow') IS NULL
CREATE TABLE dbo.Bow (
    BowId            UNIQUEIDENTIFIER NOT NULL,
    ArcherId         UNIQUEIDENTIFIER NOT NULL,
    BowType          VARCHAR(12)      NOT NULL,
    Make             NVARCHAR(60)     NULL,
    Model            NVARCHAR(60)     NULL,
    PoundageLb       DECIMAL(4, 1)    NULL,
    BowStyles        VARCHAR(100)     NOT NULL,
    IsDeleted        BIT              NOT NULL CONSTRAINT DF_Bow_IsDeleted DEFAULT 0,
    ClientUpdatedUtc DATETIME2(3)     NOT NULL,
    ServerUpdatedUtc DATETIME2(3)     NOT NULL CONSTRAINT DF_Bow_ServerUpdatedUtc DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_Bow PRIMARY KEY (BowId),
    CONSTRAINT FK_Bow_Archer FOREIGN KEY (ArcherId) REFERENCES dbo.Archer (ArcherId) ON DELETE CASCADE,
    CONSTRAINT CK_Bow_BowType CHECK (BowType IN ('LONGBOW', 'FLATBOW', 'RECURVE', 'COMPOUND', 'CROSSBOW', 'HORSEBOW', 'SELFBOW', 'OTHER'))
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_Bow_Archer')
    CREATE INDEX IX_Bow_Archer ON dbo.Bow (ArcherId);
GO

IF OBJECT_ID(N'dbo.ArrowSet') IS NULL
CREATE TABLE dbo.ArrowSet (
    ArrowId          UNIQUEIDENTIFIER NOT NULL,
    ArcherId         UNIQUEIDENTIFIER NOT NULL,
    Make             NVARCHAR(60)     NULL,
    Model            NVARCHAR(60)     NULL,
    LengthIn         DECIMAL(4, 2)    NULL,
    ArrowGrains      DECIMAL(5, 1)    NULL,
    Spine            VARCHAR(10)      NULL,
    FletchCount      TINYINT          NULL,
    FletchMaterial   VARCHAR(8)       NULL,
    FletchLengthIn   DECIMAL(3, 2)    NULL,
    FletchColours    NVARCHAR(100)    NULL,
    PointGrains      DECIMAL(5, 1)    NULL,
    BowIds           VARCHAR(900)     NOT NULL,
    IsDeleted        BIT              NOT NULL CONSTRAINT DF_ArrowSet_IsDeleted DEFAULT 0,
    ClientUpdatedUtc DATETIME2(3)     NOT NULL,
    ServerUpdatedUtc DATETIME2(3)     NOT NULL CONSTRAINT DF_ArrowSet_ServerUpdatedUtc DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_ArrowSet PRIMARY KEY (ArrowId),
    CONSTRAINT FK_ArrowSet_Archer FOREIGN KEY (ArcherId) REFERENCES dbo.Archer (ArcherId) ON DELETE CASCADE,
    CONSTRAINT CK_ArrowSet_FletchMaterial CHECK (FletchMaterial IS NULL OR FletchMaterial IN ('FEATHER', 'VANE', 'OTHER'))
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ArrowSet_Archer')
    CREATE INDEX IX_ArrowSet_Archer ON dbo.ArrowSet (ArcherId);
GO

IF COL_LENGTH(N'dbo.Round', N'BowId') IS NULL
    ALTER TABLE dbo.Round ADD BowId UNIQUEIDENTIFIER NULL;
GO
IF COL_LENGTH(N'dbo.Round', N'ArrowId') IS NULL
    ALTER TABLE dbo.Round ADD ArrowId UNIQUEIDENTIFIER NULL;
GO

INSERT dbo.SchemaVersion (Version, Description) VALUES (15, N'Equipment: Bow, ArrowSet, Round.BowId/ArrowId');
GO

SET NOEXEC OFF;
GO
SELECT MAX(Version) AS SchemaVersion FROM dbo.SchemaVersion;   -- should be 15
GO
