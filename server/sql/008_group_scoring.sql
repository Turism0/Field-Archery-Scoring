/*
    FieldArcheryScoring – schema version 6: group scoring (roadmap step 4).

    - ScoreGroup: a group shooting the same round together. The host's phone makes the id and the
      6-character join code (so a group can start with no signal). Method SINGLE (one scorer, others
      watching), DOUBLE (two scorers, saved when both match), MULTI (3+ scorers, nobody scores their own
      card, two scorers per card) or SELF (everyone scores their own).
    - ScoreGroupMember: HOST, JOINED (joined on their own phone), FRIEND (added by the host, gets a request
      to accept) or GUEST (name only, no account).
    - ScoreGroupScorer: who scores each card.
    - ScoreEntry: each scorer's entry for a card on a target (matched by the API). Live group data is
      temporary: the API deletes groups (with guests' names and scores) 30 days after they were made.
    - RoundOffer: a round scored for a friend, waiting for them to accept (then it becomes their round).
    - Round.GroupId, Round.ScoredById.

    Run in SSMS on Mimir as an administrator (check the query window's status bar: not nfas_api).
    Run this BEFORE publishing API 1.9.06. Safe to run again.
*/
USE FieldArcheryScoring;
GO

IF EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version >= 6)
BEGIN
    RAISERROR('Schema version 6 (group scoring) is already applied. Nothing done.', 16, 1);
    SET NOEXEC ON;
END
GO
IF NOT EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version = 5)
BEGIN
    RAISERROR('Run 007_round_types.sql first.', 16, 1);
    SET NOEXEC ON;
END
GO

-- Each part checks first, so if something stops it part-way you can fix it and run the whole script again.
IF OBJECT_ID(N'dbo.ScoreGroup') IS NULL
CREATE TABLE dbo.ScoreGroup (
    GroupId      UNIQUEIDENTIFIER NOT NULL,
    Code         CHAR(6)          NOT NULL,
    HostId       UNIQUEIDENTIFIER NOT NULL,
    Method       VARCHAR(8)       NOT NULL,
    RoundType    VARCHAR(16)      NOT NULL,
    TargetCount  SMALLINT         NOT NULL,
    ShootDate    DATE             NOT NULL,
    LocationId   UNIQUEIDENTIFIER NULL,
    Status       VARCHAR(8)       NOT NULL CONSTRAINT DF_ScoreGroup_Status DEFAULT 'OPEN',
    CreatedUtc   DATETIME2(3)     NOT NULL CONSTRAINT DF_ScoreGroup_CreatedUtc DEFAULT SYSUTCDATETIME(),
    UpdatedUtc   DATETIME2(3)     NOT NULL CONSTRAINT DF_ScoreGroup_UpdatedUtc DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_ScoreGroup PRIMARY KEY (GroupId),
    CONSTRAINT FK_ScoreGroup_Host FOREIGN KEY (HostId) REFERENCES dbo.Archer (ArcherId) ON DELETE CASCADE,
    CONSTRAINT FK_ScoreGroup_Location FOREIGN KEY (LocationId) REFERENCES dbo.ShootLocation (LocationId),
    CONSTRAINT CK_ScoreGroup_Method CHECK (Method IN ('SINGLE', 'DOUBLE', 'MULTI', 'SELF')),
    CONSTRAINT CK_ScoreGroup_RoundType CHECK (RoundType IN ('BIG_GAME', 'WOODSMAN')),
    CONSTRAINT CK_ScoreGroup_Status CHECK (Status IN ('OPEN', 'STARTED', 'CLOSED')),
    CONSTRAINT CK_ScoreGroup_Targets CHECK (TargetCount BETWEEN 1 AND 100),
    CONSTRAINT CK_ScoreGroup_Code CHECK (Code COLLATE Latin1_General_BIN2 NOT LIKE '%[^ABCDEFGHJKMNPQRSTUVWXYZ23456789]%')
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ScoreGroup_Code')
    CREATE INDEX IX_ScoreGroup_Code ON dbo.ScoreGroup (Code, CreatedUtc);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ScoreGroup_Created')
    CREATE INDEX IX_ScoreGroup_Created ON dbo.ScoreGroup (CreatedUtc);
GO

IF OBJECT_ID(N'dbo.ScoreGroupMember') IS NULL
CREATE TABLE dbo.ScoreGroupMember (
    GroupId      UNIQUEIDENTIFIER NOT NULL,
    MemberId     UNIQUEIDENTIFIER NOT NULL,
    Kind         VARCHAR(8)       NOT NULL,
    ArcherId     UNIQUEIDENTIFIER NULL,       -- HOST / JOINED / FRIEND
    GuestName    NVARCHAR(40)     NULL,       -- GUEST
    ArcherClass  CHAR(1)          NOT NULL,
    AgeGroup     VARCHAR(10)      NOT NULL,
    BowStyle     VARCHAR(4)       NOT NULL,
    SortOrder    SMALLINT         NOT NULL,
    RoundId      UNIQUEIDENTIFIER NULL,       -- the member's round (made by their phone, or the host's)
    AddedUtc     DATETIME2(3)     NOT NULL CONSTRAINT DF_ScoreGroupMember_AddedUtc DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_ScoreGroupMember PRIMARY KEY (GroupId, MemberId),
    CONSTRAINT FK_ScoreGroupMember_Group FOREIGN KEY (GroupId) REFERENCES dbo.ScoreGroup (GroupId) ON DELETE CASCADE,
    -- No cascade: the group already cascades from Archer (the host).
    CONSTRAINT FK_ScoreGroupMember_Archer FOREIGN KEY (ArcherId) REFERENCES dbo.Archer (ArcherId),
    CONSTRAINT CK_ScoreGroupMember_Kind CHECK (Kind IN ('HOST', 'JOINED', 'FRIEND', 'GUEST')),
    CONSTRAINT CK_ScoreGroupMember_Who CHECK (
        (Kind = 'GUEST' AND ArcherId IS NULL AND GuestName IS NOT NULL)
        OR (Kind <> 'GUEST' AND ArcherId IS NOT NULL AND GuestName IS NULL)),
    CONSTRAINT CK_ScoreGroupMember_ArcherClass CHECK (ArcherClass IN ('L', 'G'))
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_ScoreGroupMember_Archer')
    CREATE UNIQUE INDEX UX_ScoreGroupMember_Archer ON dbo.ScoreGroupMember (GroupId, ArcherId) WHERE ArcherId IS NOT NULL;
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ScoreGroupMember_ArcherId')
    CREATE INDEX IX_ScoreGroupMember_ArcherId ON dbo.ScoreGroupMember (ArcherId) WHERE ArcherId IS NOT NULL;
GO

-- Who scores which card (CardMemberId = the archer being scored). Both are members of the group (checked by the API).
IF OBJECT_ID(N'dbo.ScoreGroupScorer') IS NULL
CREATE TABLE dbo.ScoreGroupScorer (
    GroupId         UNIQUEIDENTIFIER NOT NULL,
    CardMemberId    UNIQUEIDENTIFIER NOT NULL,
    ScorerMemberId  UNIQUEIDENTIFIER NOT NULL,
    CONSTRAINT PK_ScoreGroupScorer PRIMARY KEY (GroupId, CardMemberId, ScorerMemberId),
    CONSTRAINT FK_ScoreGroupScorer_Group FOREIGN KEY (GroupId) REFERENCES dbo.ScoreGroup (GroupId) ON DELETE CASCADE
);
GO

-- One entry per scorer per card per target; a re-entry replaces it.
IF OBJECT_ID(N'dbo.ScoreEntry') IS NULL
CREATE TABLE dbo.ScoreEntry (
    GroupId           UNIQUEIDENTIFIER NOT NULL,
    TargetNo          SMALLINT         NOT NULL,
    CardMemberId      UNIQUEIDENTIFIER NOT NULL,
    ScorerMemberId    UNIQUEIDENTIFIER NOT NULL,
    EntryJson         NVARCHAR(2000)   NOT NULL,   -- the target as the app sends it (type, animals or arrows, points)
    Points            SMALLINT         NOT NULL,
    EnteredUtc        DATETIME2(3)     NOT NULL,   -- on the phone
    ServerUpdatedUtc  DATETIME2(3)     NOT NULL CONSTRAINT DF_ScoreEntry_ServerUpdatedUtc DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_ScoreEntry PRIMARY KEY (GroupId, TargetNo, CardMemberId, ScorerMemberId),
    CONSTRAINT FK_ScoreEntry_Group FOREIGN KEY (GroupId) REFERENCES dbo.ScoreGroup (GroupId) ON DELETE CASCADE
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_ScoreEntry_Updated')
    CREATE INDEX IX_ScoreEntry_Updated ON dbo.ScoreEntry (GroupId, ServerUpdatedUtc);
GO

-- A finished round scored for a friend, waiting for them. Kept apart from dbo.Round so it's in nobody's
-- history or stats until accepted.
IF OBJECT_ID(N'dbo.RoundOffer') IS NULL
CREATE TABLE dbo.RoundOffer (
    RoundId       UNIQUEIDENTIFIER NOT NULL,
    ForArcherId   UNIQUEIDENTIFIER NOT NULL,
    FromArcherId  UNIQUEIDENTIFIER NOT NULL,
    GroupId       UNIQUEIDENTIFIER NULL,       -- no foreign key: groups are deleted after 30 days, offers aren't
    RoundJson     NVARCHAR(MAX)    NOT NULL,
    RoundType     VARCHAR(16)      NOT NULL,
    ShootDate     DATE             NOT NULL,
    TargetCount   SMALLINT         NOT NULL,
    ArcherClass   CHAR(1)          NOT NULL,
    AgeGroup      VARCHAR(10)      NOT NULL,
    BowStyle      VARCHAR(4)       NOT NULL,
    TotalPoints   INT              NOT NULL,
    LocationId    UNIQUEIDENTIFIER NULL,
    CreatedUtc    DATETIME2(3)     NOT NULL CONSTRAINT DF_RoundOffer_CreatedUtc DEFAULT SYSUTCDATETIME(),
    UpdatedUtc    DATETIME2(3)     NOT NULL CONSTRAINT DF_RoundOffer_UpdatedUtc DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_RoundOffer PRIMARY KEY (RoundId),
    -- No cascade: SQL Server won't allow two cascading paths to Archer.
    CONSTRAINT FK_RoundOffer_For  FOREIGN KEY (ForArcherId)  REFERENCES dbo.Archer (ArcherId),
    CONSTRAINT FK_RoundOffer_From FOREIGN KEY (FromArcherId) REFERENCES dbo.Archer (ArcherId),
    CONSTRAINT CK_RoundOffer_NotSelf CHECK (ForArcherId <> FromArcherId)
);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_RoundOffer_For')
    CREATE INDEX IX_RoundOffer_For ON dbo.RoundOffer (ForArcherId, CreatedUtc);
GO
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'IX_RoundOffer_From')
    CREATE INDEX IX_RoundOffer_From ON dbo.RoundOffer (FromArcherId);
GO

IF COL_LENGTH(N'dbo.Round', N'GroupId') IS NULL
    ALTER TABLE dbo.Round ADD GroupId UNIQUEIDENTIFIER NULL;   -- no foreign key: groups are deleted after 30 days
GO
IF COL_LENGTH(N'dbo.Round', N'ScoredById') IS NULL
    ALTER TABLE dbo.Round ADD ScoredById UNIQUEIDENTIFIER NULL; -- who scored it, for an accepted request (no FK, kept if they leave)
GO

INSERT dbo.SchemaVersion (Version, Description) VALUES (6, N'Group scoring: ScoreGroup, members, scorers, entries, RoundOffer');
GO

SET NOEXEC OFF;
GO
SELECT MAX(Version) AS SchemaVersion FROM dbo.SchemaVersion;   -- should be 6
GO
