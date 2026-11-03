/*
    Field Archery Scoring – step 1a: database and API login
    Run on Mimir (sql.fnurble.co.uk) in SSMS as a sysadmin (e.g. sa or your admin login).

    BEFORE RUNNING: replace the password below with a long random one (24+ characters).
    Port 1433 is reachable from the internet, so this login must have a strong password.
    Keep the password out of GitHub – it goes in the API's settings on Odin later.
*/
USE master;
GO

IF DB_ID(N'FieldArcheryScoring') IS NULL
BEGIN
    CREATE DATABASE FieldArcheryScoring;
END
GO

-- Login used only by the API on Odin. Read/write on FieldArcheryScoring, nothing else.
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = N'nfas_api')
BEGIN
    CREATE LOGIN nfas_api
        WITH PASSWORD = N'CHANGE_ME_to_a_long_random_password',
             DEFAULT_DATABASE = FieldArcheryScoring,
             CHECK_POLICY = ON,
             CHECK_EXPIRATION = OFF;
END
GO

USE FieldArcheryScoring;
GO

IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = N'nfas_api')
BEGIN
    CREATE USER nfas_api FOR LOGIN nfas_api;
END
GO

-- Read and write data only: no CREATE/ALTER/DROP, so the API can't change the schema.
ALTER ROLE db_datareader ADD MEMBER nfas_api;
ALTER ROLE db_datawriter ADD MEMBER nfas_api;
GO

PRINT 'Database FieldArcheryScoring and login nfas_api ready.';
GO
