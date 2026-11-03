/*
    Field Archery Scoring – permissions for the new API login fieldarchery_api (replaces nfas_api).

    The API only reads and writes data: membership of db_datareader and db_datawriter in the app's database, nothing
    else (no CREATE / ALTER / DROP, so the API can't change the schema; that's what 003_test_as_api_login.sql checks).
    The same as nfas_api has.

    Run in SSMS on Mimir as an administrator (check the query window's status bar: not nfas_api or fieldarchery_api).
    The login fieldarchery_api must already exist (Security → Logins). Safe to run again.

    The database was renamed from NfasScoring to FieldArcheryScoring by 010_rename_database.sql (users and permissions go
    with it), so run 010 first if it hasn't been.
    No schema change: SchemaVersion stays at 6.
*/
USE master;
GO

IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'fieldarchery_api' AND type = 'S')
BEGIN
    RAISERROR('The SQL login fieldarchery_api doesn''t exist yet. Create it first (SQL Server authentication, strong password).', 16, 1);
    SET NOEXEC ON;
END
GO

-- Open in the app's database by default, and don't expire the password (the API on Odin can't change it).
-- A login made with "User must change password at next login" ticked can't have that until the password is set
-- again (the API could never sign in with it either), so say how instead of failing.
IF CAST(LOGINPROPERTY(N'fieldarchery_api', 'IsMustChange') AS int) = 1
BEGIN
    ALTER LOGIN fieldarchery_api WITH DEFAULT_DATABASE = FieldArcheryScoring;
    PRINT 'NOTE: fieldarchery_api has "must change password" set, so the API can''t sign in yet.';
    PRINT '      Set the password again on its own (your password in place of the placeholder; this clears it),';
    PRINT '      then run this script again:';
    PRINT '      ALTER LOGIN fieldarchery_api WITH PASSWORD = N''the-long-password'';';
END
ELSE
    ALTER LOGIN fieldarchery_api WITH DEFAULT_DATABASE = FieldArcheryScoring, CHECK_POLICY = ON, CHECK_EXPIRATION = OFF;
GO

USE FieldArcheryScoring;
GO

IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = N'fieldarchery_api')
    CREATE USER fieldarchery_api FOR LOGIN fieldarchery_api;
ELSE
    ALTER USER fieldarchery_api WITH LOGIN = fieldarchery_api;   -- re-links it if the login was made again
GO

IF IS_ROLEMEMBER('db_datareader', 'fieldarchery_api') = 0 ALTER ROLE db_datareader ADD MEMBER fieldarchery_api;
IF IS_ROLEMEMBER('db_datawriter', 'fieldarchery_api') = 0 ALTER ROLE db_datawriter ADD MEMBER fieldarchery_api;
GO

-- Anything more than read / write would let the API change the schema: take it away if it was given by mistake.
IF IS_ROLEMEMBER('db_owner', 'fieldarchery_api') = 1 ALTER ROLE db_owner DROP MEMBER fieldarchery_api;
IF IS_ROLEMEMBER('db_ddladmin', 'fieldarchery_api') = 1 ALTER ROLE db_ddladmin DROP MEMBER fieldarchery_api;
GO

SET NOEXEC OFF;
GO

-- Check: the login's settings (MustChange 0, Expiration 0) and its roles (db_datareader and db_datawriter only).
SELECT name AS Login, default_database_name AS DefaultDatabase,
       CAST(LOGINPROPERTY(name, 'IsMustChange') AS int) AS MustChange, is_expiration_checked AS Expiration
FROM master.sys.sql_logins WHERE name = N'fieldarchery_api';

SELECT r.name AS RoleFor_fieldarchery_api
FROM sys.database_role_members m
JOIN sys.database_principals r ON r.principal_id = m.role_principal_id
JOIN sys.database_principals u ON u.principal_id = m.member_principal_id
WHERE u.name = N'fieldarchery_api'
ORDER BY r.name;
GO
