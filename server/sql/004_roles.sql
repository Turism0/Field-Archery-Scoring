/*
    NfasScoring – schema version 2: account roles (step 1 of the roadmap in docs/DESIGN.md).

    Every account gets a Role: USER (default), ADMIN or OWNER. There can only ever be one OWNER.
    Only the Owner can make or remove Admins (the API enforces that); Admins will be able to edit
    shoot location names once those exist.

    BEFORE RUNNING: put the Owner's username (your account) in @OwnerUsername below.
    Run in SSMS on Mimir as an administrator (not nfas_api). Safe to run again: it does nothing
    once schema version 2 is there. Run this BEFORE publishing API 1.4.03.
*/
USE NfasScoring;
GO

IF EXISTS (SELECT 1 FROM dbo.SchemaVersion WHERE Version >= 2)
BEGIN
    RAISERROR('Schema version 2 (roles) is already applied. Nothing done.', 16, 1);
    SET NOEXEC ON;
END
GO

-- Each part checks first, so if the Owner username was wrong you can fix it and run the whole script again.
IF COL_LENGTH(N'dbo.Archer', N'Role') IS NULL
    ALTER TABLE dbo.Archer ADD
        Role            VARCHAR(10)      NOT NULL CONSTRAINT DF_Archer_Role DEFAULT 'USER',
        -- Who last changed this account's role and when (null = never changed).
        RoleChangedUtc  DATETIME2(3)     NULL,
        RoleChangedById UNIQUEIDENTIFIER NULL;
GO

IF OBJECT_ID(N'dbo.CK_Archer_Role') IS NULL
    ALTER TABLE dbo.Archer ADD CONSTRAINT CK_Archer_Role CHECK (Role IN ('USER', 'ADMIN', 'OWNER'));
GO

-- Only one account may ever be the Owner.
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'UX_Archer_OneOwner' AND object_id = OBJECT_ID(N'dbo.Archer'))
    CREATE UNIQUE INDEX UX_Archer_OneOwner ON dbo.Archer (Role) WHERE Role = 'OWNER';
GO

DECLARE @OwnerUsername NVARCHAR(30) = N'PUT_YOUR_USERNAME_HERE';

BEGIN TRAN;
UPDATE dbo.Archer SET Role = 'OWNER', RoleChangedUtc = SYSUTCDATETIME() WHERE Username = @OwnerUsername;
IF @@ROWCOUNT <> 1
BEGIN
    ROLLBACK;
    RAISERROR('No account with that username. Set @OwnerUsername to your username and run the whole script again.', 16, 1);
END
ELSE
BEGIN
    INSERT dbo.SchemaVersion (Version, Description) VALUES (2, N'Account roles: USER / ADMIN / OWNER');
    COMMIT;
    PRINT CONCAT('Schema version 2 applied. Owner: ', @OwnerUsername);
END
GO

SET NOEXEC OFF;
GO

-- Check: should show your account as OWNER and everyone else as USER.
SELECT Username, Role, RoleChangedUtc FROM dbo.Archer ORDER BY CASE Role WHEN 'OWNER' THEN 0 WHEN 'ADMIN' THEN 1 ELSE 2 END, Username;
GO
