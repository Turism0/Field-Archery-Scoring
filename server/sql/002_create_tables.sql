/*
    NFAS Big Game Scorer – step 1b: tables (schema version 1)
    Run on Mimir in SSMS as a sysadmin, AFTER 001_create_database_and_login.sql.

    Shared tables keyed by ArcherId (not one table per user).
    No real names or dates of birth are stored – those stay on the phone.
    Codes match the app's enums (domain/Archer.kt, domain/Scoring.kt).
*/
USE NfasScoring;
GO

IF OBJECT_ID(N'dbo.SchemaVersion') IS NOT NULL
BEGIN
    RAISERROR('Tables already exist (SchemaVersion found). Nothing done.', 16, 1);
    SET NOEXEC ON;   -- skip the rest of the script
END
GO

---------------------------------------------------------------------------
-- Schema version, so later changes can be applied as numbered scripts
---------------------------------------------------------------------------
CREATE TABLE dbo.SchemaVersion (
    Version      INT           NOT NULL CONSTRAINT PK_SchemaVersion PRIMARY KEY,
    Description  NVARCHAR(200) NOT NULL,
    AppliedUtc   DATETIME2(3)  NOT NULL CONSTRAINT DF_SchemaVersion_AppliedUtc DEFAULT SYSUTCDATETIME()
);
GO

---------------------------------------------------------------------------
-- Archer: one row per account. Username only, no real name.
---------------------------------------------------------------------------
CREATE TABLE dbo.Archer (
    ArcherId          UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_Archer_ArcherId DEFAULT NEWSEQUENTIALID(),
    -- Case-insensitive collation: "Pete" and "pete" are the same username.
    Username          NVARCHAR(30) COLLATE Latin1_General_100_CI_AS NOT NULL,
    -- Salted, slow hash made by the API (ASP.NET Core PasswordHasher, PBKDF2). Never the password itself.
    PasswordHash      VARCHAR(200)     NOT NULL,
    CreatedUtc        DATETIME2(3)     NOT NULL CONSTRAINT DF_Archer_CreatedUtc DEFAULT SYSUTCDATETIME(),
    LastLoginUtc      DATETIME2(3)     NULL,
    -- Brute-force protection: the API locks the account for a while after repeated wrong passwords.
    FailedLoginCount  INT              NOT NULL CONSTRAINT DF_Archer_FailedLoginCount DEFAULT 0,
    LockoutUntilUtc   DATETIME2(3)     NULL,
    IsDisabled        BIT              NOT NULL CONSTRAINT DF_Archer_IsDisabled DEFAULT 0,
    CONSTRAINT PK_Archer PRIMARY KEY (ArcherId),
    CONSTRAINT UQ_Archer_Username UNIQUE (Username),
    -- 3–30 characters: letters, numbers, underscore, dot, hyphen.
    CONSTRAINT CK_Archer_Username CHECK (
        LEN(Username) BETWEEN 3 AND 30
        -- Binary collation so accented letters don't slip through the A-Z range.
        AND Username COLLATE Latin1_General_100_BIN2 NOT LIKE N'%[^A-Za-z0-9_.-]%'
    )
);
GO

---------------------------------------------------------------------------
-- AuthToken: one row per signed-in phone. The phone keeps the token, not the password.
-- Only a SHA-256 hash of the token is stored, so a database leak can't be used to sign in.
---------------------------------------------------------------------------
CREATE TABLE dbo.AuthToken (
    TokenId      UNIQUEIDENTIFIER NOT NULL CONSTRAINT DF_AuthToken_TokenId DEFAULT NEWSEQUENTIALID(),
    ArcherId     UNIQUEIDENTIFIER NOT NULL,
    TokenHash    BINARY(32)       NOT NULL,
    DeviceName   NVARCHAR(100)    NULL,
    CreatedUtc   DATETIME2(3)     NOT NULL CONSTRAINT DF_AuthToken_CreatedUtc DEFAULT SYSUTCDATETIME(),
    LastUsedUtc  DATETIME2(3)     NULL,
    ExpiresUtc   DATETIME2(3)     NOT NULL,
    RevokedUtc   DATETIME2(3)     NULL,
    CONSTRAINT PK_AuthToken PRIMARY KEY (TokenId),
    CONSTRAINT UQ_AuthToken_TokenHash UNIQUE (TokenHash),
    CONSTRAINT FK_AuthToken_Archer FOREIGN KEY (ArcherId) REFERENCES dbo.Archer (ArcherId) ON DELETE CASCADE
);
CREATE INDEX IX_AuthToken_ArcherId ON dbo.AuthToken (ArcherId);
GO

---------------------------------------------------------------------------
-- Round: one row per round. RoundId is made on the phone, so a retried upload
-- updates the same row instead of creating a duplicate.
---------------------------------------------------------------------------
CREATE TABLE dbo.Round (
    RoundId           UNIQUEIDENTIFIER NOT NULL,
    ArcherId          UNIQUEIDENTIFIER NOT NULL,
    ShootDate         DATE             NOT NULL,
    TargetCount       SMALLINT         NOT NULL,
    ArcherClass       CHAR(1)          NOT NULL,   -- L = Ladies, G = Gents
    AgeGroup          VARCHAR(10)      NOT NULL,
    BowStyle          VARCHAR(4)       NOT NULL,
    Status            VARCHAR(12)      NOT NULL,
    TotalPoints       INT              NOT NULL,
    IsDeleted         BIT              NOT NULL CONSTRAINT DF_Round_IsDeleted DEFAULT 0,
    -- Times from the phone. ClientUpdatedUtc decides which copy wins if the same round is uploaded twice.
    ClientCreatedUtc  DATETIME2(3)     NOT NULL,
    ClientUpdatedUtc  DATETIME2(3)     NOT NULL,
    -- Set by the API on every change; lets a phone download "everything changed since X".
    ServerUpdatedUtc  DATETIME2(3)     NOT NULL CONSTRAINT DF_Round_ServerUpdatedUtc DEFAULT SYSUTCDATETIME(),
    RowVer            ROWVERSION,
    CONSTRAINT PK_Round PRIMARY KEY (RoundId),
    CONSTRAINT FK_Round_Archer FOREIGN KEY (ArcherId) REFERENCES dbo.Archer (ArcherId) ON DELETE CASCADE,
    CONSTRAINT CK_Round_TargetCount CHECK (TargetCount BETWEEN 1 AND 100),
    CONSTRAINT CK_Round_ArcherClass CHECK (ArcherClass IN ('L', 'G')),
    CONSTRAINT CK_Round_AgeGroup    CHECK (AgeGroup IN ('ADULT', 'JUNIOR', 'CUB')),
    CONSTRAINT CK_Round_BowStyle    CHECK (BowStyle IN ('AFB','BB','BH','CL','XB','TXB','FS','HT','LB','PV','TD','TB','UL')),
    CONSTRAINT CK_Round_Status      CHECK (Status IN ('IN_PROGRESS', 'COMPLETED')),
    CONSTRAINT CK_Round_TotalPoints CHECK (TotalPoints >= 0)
);
-- My rounds / a friend's rounds, newest first.
CREATE INDEX IX_Round_Archer_ShootDate ON dbo.Round (ArcherId, ShootDate DESC)
    INCLUDE (Status, TotalPoints, IsDeleted);
-- Download changes since the last sync.
CREATE INDEX IX_Round_Archer_ServerUpdated ON dbo.Round (ArcherId, ServerUpdatedUtc);
GO

---------------------------------------------------------------------------
-- TargetScore: one row per target (peg) in a round.
---------------------------------------------------------------------------
CREATE TABLE dbo.TargetScore (
    RoundId         UNIQUEIDENTIFIER NOT NULL,
    TargetNo        SMALLINT         NOT NULL,
    TargetType      VARCHAR(16)      NOT NULL,
    OverridePoints  SMALLINT         NULL,      -- manual score; replaces the calculated one when set
    Points          SMALLINT         NOT NULL,  -- score that counts (override if set)
    CONSTRAINT PK_TargetScore PRIMARY KEY (RoundId, TargetNo),
    CONSTRAINT FK_TargetScore_Round FOREIGN KEY (RoundId) REFERENCES dbo.Round (RoundId) ON DELETE CASCADE,
    CONSTRAINT CK_TargetScore_TargetNo CHECK (TargetNo BETWEEN 1 AND 100),
    CONSTRAINT CK_TargetScore_Type     CHECK (TargetType IN ('STANDARD', 'PREDATOR_PREY', 'FAN')),
    CONSTRAINT CK_TargetScore_Override CHECK (OverridePoints IS NULL OR OverridePoints >= 0),
    CONSTRAINT CK_TargetScore_Points   CHECK (Points >= 0)
);
GO

---------------------------------------------------------------------------
-- AnimalScore: one row per animal on a target (1 standard, 2 predator/prey, 2–6 fan).
---------------------------------------------------------------------------
CREATE TABLE dbo.AnimalScore (
    RoundId      UNIQUEIDENTIFIER NOT NULL,
    TargetNo     SMALLINT         NOT NULL,
    AnimalIndex  TINYINT          NOT NULL,   -- 0 = first animal (predator on a predator/prey)
    State        VARCHAR(8)       NOT NULL,
    ArrowNo      TINYINT          NULL,
    Hit          VARCHAR(10)      NULL,
    Points       SMALLINT         NOT NULL,
    CONSTRAINT PK_AnimalScore PRIMARY KEY (RoundId, TargetNo, AnimalIndex),
    CONSTRAINT FK_AnimalScore_Target FOREIGN KEY (RoundId, TargetNo)
        REFERENCES dbo.TargetScore (RoundId, TargetNo) ON DELETE CASCADE,
    CONSTRAINT CK_AnimalScore_Index  CHECK (AnimalIndex BETWEEN 0 AND 5),
    CONSTRAINT CK_AnimalScore_State  CHECK (State IN ('PENDING', 'MISSED', 'SCORED')),
    CONSTRAINT CK_AnimalScore_Arrow  CHECK (ArrowNo IS NULL OR ArrowNo BETWEEN 1 AND 3),
    CONSTRAINT CK_AnimalScore_Hit    CHECK (Hit IS NULL OR Hit IN ('INNER_KILL', 'KILL', 'WOUND')),
    CONSTRAINT CK_AnimalScore_Points CHECK (Points IN (0, 4, 8, 10, 14, 16, 20, 24)),
    -- Scored animals have an arrow and hit; pending/missed ones have neither and score 0.
    CONSTRAINT CK_AnimalScore_Shape CHECK (
        (State = 'SCORED' AND ArrowNo IS NOT NULL AND Hit IS NOT NULL AND Points > 0)
        OR (State <> 'SCORED' AND ArrowNo IS NULL AND Hit IS NULL AND Points = 0)
    ),
    -- Inner kill only counts on the 1st arrow.
    CONSTRAINT CK_AnimalScore_InnerKill CHECK (Hit IS NULL OR Hit <> 'INNER_KILL' OR ArrowNo = 1)
);
GO

---------------------------------------------------------------------------
-- Friendship: one row per pair of archers, stored with the lower ArcherId first
-- so a pair can only exist once whoever asked. Declining or removing deletes the row.
---------------------------------------------------------------------------
CREATE TABLE dbo.Friendship (
    ArcherLowId    UNIQUEIDENTIFIER NOT NULL,
    ArcherHighId   UNIQUEIDENTIFIER NOT NULL,
    RequestedById  UNIQUEIDENTIFIER NOT NULL,   -- which of the two sent the request
    Status         VARCHAR(10)      NOT NULL,   -- PENDING until the other archer accepts
    RequestedUtc   DATETIME2(3)     NOT NULL CONSTRAINT DF_Friendship_RequestedUtc DEFAULT SYSUTCDATETIME(),
    AcceptedUtc    DATETIME2(3)     NULL,
    CONSTRAINT PK_Friendship PRIMARY KEY (ArcherLowId, ArcherHighId),
    -- No cascade here: SQL Server won't allow two cascading paths to Archer.
    -- The API removes an archer's friendships before deleting the archer.
    CONSTRAINT FK_Friendship_Low  FOREIGN KEY (ArcherLowId)  REFERENCES dbo.Archer (ArcherId),
    CONSTRAINT FK_Friendship_High FOREIGN KEY (ArcherHighId) REFERENCES dbo.Archer (ArcherId),
    -- Ordering is SQL Server's own uniqueidentifier ordering; the API lets SQL do the comparison.
    CONSTRAINT CK_Friendship_Order     CHECK (ArcherLowId < ArcherHighId),
    CONSTRAINT CK_Friendship_Requester CHECK (RequestedById IN (ArcherLowId, ArcherHighId)),
    CONSTRAINT CK_Friendship_Status    CHECK (Status IN ('PENDING', 'ACCEPTED')),
    CONSTRAINT CK_Friendship_Accepted  CHECK (Status <> 'ACCEPTED' OR AcceptedUtc IS NOT NULL)
);
CREATE INDEX IX_Friendship_High ON dbo.Friendship (ArcherHighId);
GO

---------------------------------------------------------------------------
-- Accepted friends in both directions: SELECT FriendId FROM dbo.vFriend WHERE ArcherId = @me
---------------------------------------------------------------------------
CREATE OR ALTER VIEW dbo.vFriend
AS
    SELECT ArcherLowId AS ArcherId, ArcherHighId AS FriendId, AcceptedUtc
    FROM dbo.Friendship WHERE Status = 'ACCEPTED'
    UNION ALL
    SELECT ArcherHighId, ArcherLowId, AcceptedUtc
    FROM dbo.Friendship WHERE Status = 'ACCEPTED';
GO

INSERT dbo.SchemaVersion (Version, Description) VALUES (1, N'Initial schema: archers, tokens, rounds, scores, friendships');
GO

SET NOEXEC OFF;
GO
PRINT 'NfasScoring schema version 1 created.';
GO
