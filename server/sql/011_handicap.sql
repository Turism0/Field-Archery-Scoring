/*
    FieldArcheryScoring – schema version 7: handicap (server 2.2.01).

    - ChampEvent: one row per NFAS championship (Nationals, 3D Championships) and year, as last read from nfas.net:
      whether results were found, targets per day (the results don't say: worked out by the job, or set by an Admin),
      a fingerprint of the results so changes are spotted.
    - ChampResult: one row per archer per championship. No names: NameKey is a keyed hash (HMAC-SHA256) of the tidied
      name, only used to recognise the same archer in other years. Club, bow style, class, placing, day scores, 24s
      as published.
    - HandicapModel / HandicapFactor: each fit of the handicap, its test results and its factors (bow style, change per
      10 years, 3D, class, bow × ladies), with 90% ranges. One model is ACTIVE: that's what the app downloads.
    - HandicapJobRequest: runs of the job (FieldArcheryJobs on Odin) and "Fetch now" requests from the Admin menu.

    The API and the job both use the fieldarchery_api login (read / write only, from 009); nothing extra to grant.
    Run in SSMS on Mimir as an administrator (check the query window's status bar: not fieldarchery_api).
    Run this BEFORE publishing API 2.2.01. Safe to run again.
*/
USE FieldArcheryScoring;
GO

IF EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version >= 7)
BEGIN
    RAISERROR('Schema version 7 (handicap) is already applied. Nothing done.', 16, 1);
    SET NOEXEC ON;
END
GO
IF NOT EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version = 6)
BEGIN
    RAISERROR('Run 008_group_scoring.sql first.', 16, 1);
    SET NOEXEC ON;
END
GO

IF OBJECT_ID(N'dbo.ChampEvent') IS NULL
CREATE TABLE dbo.ChampEvent (
    EventId        INT IDENTITY(1, 1) NOT NULL,
    Champs         VARCHAR(10)      NOT NULL,           -- NATIONALS or 3DS
    [Year]         SMALLINT         NOT NULL,
    Status         VARCHAR(10)      NOT NULL,           -- RESULTS, PLACINGS (positions only, no scores), NONE
    ResultCount    INT              NOT NULL CONSTRAINT DF_ChampEvent_ResultCount DEFAULT 0,
    TargetsPerDay  TINYINT          NULL,
    TargetsSource  VARCHAR(10)      NULL,               -- RULE, AUTO, DOUBTFUL (job) or ADMIN (set by hand, always wins)
    TargetsNote    NVARCHAR(300)    NULL,
    TargetsSetById UNIQUEIDENTIFIER NULL,
    ContentHash    BINARY(32)       NULL,
    CheckedUtc     DATETIME2(3)     NOT NULL,
    ChangedUtc     DATETIME2(3)     NULL,
    CONSTRAINT PK_ChampEvent PRIMARY KEY (EventId),
    CONSTRAINT UQ_ChampEvent UNIQUE (Champs, [Year]),
    CONSTRAINT FK_ChampEvent_SetBy FOREIGN KEY (TargetsSetById) REFERENCES dbo.Archer (ArcherId) ON DELETE SET NULL,
    CONSTRAINT CK_ChampEvent_Champs CHECK (Champs IN ('NATIONALS', '3DS')),
    CONSTRAINT CK_ChampEvent_Status CHECK (Status IN ('RESULTS', 'PLACINGS', 'NONE')),
    CONSTRAINT CK_ChampEvent_Targets CHECK (TargetsPerDay IS NULL OR TargetsPerDay BETWEEN 10 AND 60),
    CONSTRAINT CK_ChampEvent_Source CHECK (TargetsSource IS NULL OR TargetsSource IN ('RULE', 'AUTO', 'DOUBTFUL', 'ADMIN'))
);
GO

IF OBJECT_ID(N'dbo.ChampResult') IS NULL
CREATE TABLE dbo.ChampResult (
    ResultId  INT IDENTITY(1, 1) NOT NULL,
    EventId   INT            NOT NULL,
    Style     VARCHAR(4)     NOT NULL,     -- AFB, BB, BH, CL, FS, HT, LB, PV, TB, TD, TXB, UL, XB
    Class     VARCHAR(2)     NOT NULL,     -- G, L, JB, JG, CB (U12 boys), CG (U12 girls)
    Position  SMALLINT       NOT NULL,
    NameKey   BINARY(32)     NOT NULL,
    Club      NVARCHAR(100)  NOT NULL,
    Day1      SMALLINT       NOT NULL,
    Day2      SMALLINT       NOT NULL,
    Total     SMALLINT       NOT NULL,
    Count24   SMALLINT       NULL,
    CONSTRAINT PK_ChampResult PRIMARY KEY (ResultId),
    CONSTRAINT FK_ChampResult_Event FOREIGN KEY (EventId) REFERENCES dbo.ChampEvent (EventId) ON DELETE CASCADE,
    CONSTRAINT CK_ChampResult_Class CHECK (Class IN ('G', 'L', 'JB', 'JG', 'CB', 'CG'))
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ChampResult_Event')
    CREATE INDEX IX_ChampResult_Event ON dbo.ChampResult (EventId);
GO

IF OBJECT_ID(N'dbo.HandicapModel') IS NULL
CREATE TABLE dbo.HandicapModel (
    ModelId         INT IDENTITY(1, 1) NOT NULL,
    FittedUtc       DATETIME2(3)     NOT NULL CONSTRAINT DF_HandicapModel_FittedUtc DEFAULT SYSUTCDATETIME(),
    Status          VARCHAR(10)      NOT NULL,          -- ACTIVE, CANDIDATE (waiting: tests worse), RETIRED
    DataHash        BINARY(32)       NOT NULL,          -- what it was fitted on (results + target counts)
    DataThroughYear SMALLINT         NOT NULL,
    Events          INT              NOT NULL,
    DayScores       INT              NOT NULL,
    Archers         INT              NOT NULL,
    HoldoutRmse     FLOAT            NOT NULL,          -- points per day, predicting hidden results
    SwitcherBias    FLOAT            NOT NULL,          -- archers on 2+ bows: systematic bias per bow (SDs)
    Note            NVARCHAR(400)    NULL,
    ActivatedUtc    DATETIME2(3)     NULL,
    ActivatedById   UNIQUEIDENTIFIER NULL,              -- null = by the job (tests passed)
    CONSTRAINT PK_HandicapModel PRIMARY KEY (ModelId),
    CONSTRAINT FK_HandicapModel_ActivatedBy FOREIGN KEY (ActivatedById) REFERENCES dbo.Archer (ArcherId) ON DELETE SET NULL,
    CONSTRAINT CK_HandicapModel_Status CHECK (Status IN ('ACTIVE', 'CANDIDATE', 'RETIRED'))
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_HandicapModel_Active')
    CREATE UNIQUE INDEX UX_HandicapModel_Active ON dbo.HandicapModel (Status) WHERE Status = 'ACTIVE';
GO

IF OBJECT_ID(N'dbo.HandicapFactor') IS NULL
CREATE TABLE dbo.HandicapFactor (
    ModelId INT          NOT NULL,
    Kind    VARCHAR(8)   NOT NULL,     -- BOW, SLOPE, BOW3D, CLASS, BOWSEX, SPREAD, EVENT
    Code    VARCHAR(16)  NOT NULL,     -- bow code, class (e.g. F-ADULT), or event (NATIONALS 2019)
    Value   FLOAT        NOT NULL,
    Low     FLOAT        NULL,
    High    FLOAT        NULL,
    CONSTRAINT PK_HandicapFactor PRIMARY KEY (ModelId, Kind, Code),
    CONSTRAINT FK_HandicapFactor_Model FOREIGN KEY (ModelId) REFERENCES dbo.HandicapModel (ModelId) ON DELETE CASCADE
);
GO

IF OBJECT_ID(N'dbo.HandicapJobRequest') IS NULL
CREATE TABLE dbo.HandicapJobRequest (
    RequestId     INT IDENTITY(1, 1) NOT NULL,
    Kind          VARCHAR(10)      NOT NULL,            -- FETCH (read nfas.net, refit if anything changed) or FIT
    RequestedUtc  DATETIME2(3)     NOT NULL CONSTRAINT DF_HandicapJobRequest_RequestedUtc DEFAULT SYSUTCDATETIME(),
    RequestedById UNIQUEIDENTIFIER NULL,                -- null = the weekly schedule
    StartedUtc    DATETIME2(3)     NULL,
    FinishedUtc   DATETIME2(3)     NULL,
    Outcome       NVARCHAR(1000)   NULL,
    Failed        BIT              NOT NULL CONSTRAINT DF_HandicapJobRequest_Failed DEFAULT 0,
    CONSTRAINT PK_HandicapJobRequest PRIMARY KEY (RequestId),
    CONSTRAINT FK_HandicapJobRequest_By FOREIGN KEY (RequestedById) REFERENCES dbo.Archer (ArcherId) ON DELETE SET NULL,
    CONSTRAINT CK_HandicapJobRequest_Kind CHECK (Kind IN ('FETCH', 'FIT'))
);
GO

INSERT dbo.SchemaVersion (Version, Description) VALUES (7, N'Handicap: ChampEvent, ChampResult, HandicapModel, HandicapFactor, HandicapJobRequest');
GO

SET NOEXEC OFF;
GO
SELECT MAX(Version) AS SchemaVersion FROM dbo.SchemaVersion;   -- should be 7
GO
