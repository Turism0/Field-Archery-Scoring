/*
    Field Archery Scoring – renames the database NfasScoring to FieldArcheryScoring.

    A rename needs the database to itself (otherwise: error 5030, "could not be exclusively locked"), so BEFORE running:
      1. On Odin, stop the API so it lets go:   Stop-WebAppPool FieldArcheryApi
      2. In SSMS, close every query window that's using NfasScoring (run this from a new window).
    This script then puts the database in single-user mode (disconnecting anything left, e.g. Object Explorer's
    connection), renames it, opens it to everyone again, and points the API logins' default database at the new name.
    Users, permissions, tables and data all move with it. No schema change: SchemaVersion stays at 6.

    AFTERWARDS on Odin: change Database=NfasScoring to Database=FieldArcheryScoring in
    C:\inetpub\fieldarchery-api\appsettings.Production.json, then Start-WebAppPool FieldArcheryApi.

    Run in SSMS on Mimir as an administrator. Safe to run again (does nothing once renamed).
*/
USE master;
GO

IF DB_ID(N'FieldArcheryScoring') IS NOT NULL
BEGIN
    PRINT 'Already renamed: FieldArcheryScoring exists. Nothing done.';
    SET NOEXEC ON;
END
GO
IF DB_ID(N'NfasScoring') IS NULL
BEGIN
    RAISERROR('There''s no database called NfasScoring to rename.', 16, 1);
    SET NOEXEC ON;
END
GO

-- Anything still connected is rolled back and disconnected.
ALTER DATABASE NfasScoring SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
GO
ALTER DATABASE NfasScoring MODIFY NAME = FieldArcheryScoring;
GO
ALTER DATABASE FieldArcheryScoring SET MULTI_USER;
GO

SET NOEXEC OFF;
GO

-- If a step above failed after single-user mode, this makes sure it isn't left that way.
IF DB_ID(N'NfasScoring') IS NOT NULL AND DATABASEPROPERTYEX(N'NfasScoring', 'UserAccess') = 'SINGLE_USER'
    ALTER DATABASE NfasScoring SET MULTI_USER;
GO

IF DB_ID(N'FieldArcheryScoring') IS NOT NULL
BEGIN
    IF EXISTS (SELECT 1 FROM sys.sql_logins WHERE name = N'fieldarchery_api')
        ALTER LOGIN fieldarchery_api WITH DEFAULT_DATABASE = FieldArcheryScoring;
    IF EXISTS (SELECT 1 FROM sys.sql_logins WHERE name = N'nfas_api')
        ALTER LOGIN nfas_api WITH DEFAULT_DATABASE = FieldArcheryScoring;
END
GO

-- Check: FieldArcheryScoring, MULTI_USER, schema 6; the logins' default database.
SELECT name, user_access_desc, state_desc FROM sys.databases WHERE name IN (N'NfasScoring', N'FieldArcheryScoring');
SELECT name, default_database_name FROM sys.sql_logins WHERE name IN (N'fieldarchery_api', N'nfas_api');
IF DB_ID(N'FieldArcheryScoring') IS NOT NULL
    EXEC (N'SELECT MAX(Version) AS SchemaVersion FROM FieldArcheryScoring.dbo.SchemaVersion;');
GO
