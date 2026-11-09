/*
    FieldArcheryScoring – schema version 8: password reset with no personal details (server 2.4).

    - Archer.RecoveryCodeHash: a hash (salted PBKDF2, like passwords) of the archer's recovery code. The code itself is
      shown once on the phone (when the account is made, after a reset, or from Profile) and never stored.
    - PasswordResetCode: a one-off code an Owner / Admin made for an archer who has lost their password and recovery
      code (checked in person). Hash only; works for 24 hours, once. One per archer (a new one replaces the old).

    Nothing personal is stored: no email or phone number.
    The fieldarchery_api login already reads and writes every table (009); nothing extra to grant.
    Run in SSMS on Mimir as an administrator (check the query window's status bar: not fieldarchery_api).
    Run this BEFORE publishing API 2.4. Safe to run again.
*/
USE FieldArcheryScoring;
GO

IF EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version >= 8)
BEGIN
    RAISERROR('Schema version 8 (password reset) is already applied. Nothing done.', 16, 1);
    SET NOEXEC ON;
END
GO
IF NOT EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version = 7)
BEGIN
    RAISERROR('Run 011_handicap.sql first.', 16, 1);
    SET NOEXEC ON;
END
GO

IF COL_LENGTH(N'dbo.Archer', N'RecoveryCodeHash') IS NULL
    ALTER TABLE dbo.Archer ADD
        RecoveryCodeHash   NVARCHAR(200) NULL,
        RecoveryCodeSetUtc DATETIME2(3)  NULL;
GO

IF OBJECT_ID(N'dbo.PasswordResetCode') IS NULL
CREATE TABLE dbo.PasswordResetCode (
    ArcherId     UNIQUEIDENTIFIER NOT NULL,
    CodeHash     NVARCHAR(200)    NOT NULL,
    ExpiresUtc   DATETIME2(3)     NOT NULL,
    CreatedUtc   DATETIME2(3)     NOT NULL CONSTRAINT DF_PasswordResetCode_CreatedUtc DEFAULT SYSUTCDATETIME(),
    CreatedById  UNIQUEIDENTIFIER NOT NULL,   -- the Owner / Admin who made it (no foreign key: kept if they're deleted)
    CONSTRAINT PK_PasswordResetCode PRIMARY KEY (ArcherId),
    CONSTRAINT FK_PasswordResetCode_Archer FOREIGN KEY (ArcherId) REFERENCES dbo.Archer (ArcherId) ON DELETE CASCADE
);
GO

INSERT dbo.SchemaVersion (Version, Description) VALUES (8, N'Password reset: Archer.RecoveryCodeHash, PasswordResetCode');
GO

SET NOEXEC OFF;
GO
SELECT MAX(Version) AS SchemaVersion FROM dbo.SchemaVersion;   -- should be 8
GO
