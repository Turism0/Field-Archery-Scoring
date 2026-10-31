/*
    NFAS Big Game Scorer – step 1c: check it works
    In SSMS, connect to sql.fnurble.co.uk as the nfas_api login (SQL Server Authentication,
    the password you set in script 001), then run this.

    Everything runs inside a transaction that is rolled back, so it leaves no data behind.
    Expected: three result sets (archer, round with targets/animals, friend view), then
    "All checks passed", and two deliberate failures caught (duplicate username, schema change).
*/
USE NfasScoring;
SET NOCOUNT ON;
SET XACT_ABORT OFF;

SELECT SYSTEM_USER AS ConnectedAs, DB_NAME() AS DatabaseName,
       (SELECT MAX(Version) FROM dbo.SchemaVersion) AS SchemaVersion;

BEGIN TRANSACTION;

DECLARE @a UNIQUEIDENTIFIER = NEWID(), @b UNIQUEIDENTIFIER = NEWID(), @r UNIQUEIDENTIFIER = NEWID();

INSERT dbo.Archer (ArcherId, Username, PasswordHash) VALUES
    (@a, N'test_archer_a', 'not-a-real-hash'),
    (@b, N'test_archer_b', 'not-a-real-hash');

-- Usernames are case-insensitive: this should fail.
BEGIN TRY
    INSERT dbo.Archer (Username, PasswordHash) VALUES (N'TEST_ARCHER_A', 'x');
    PRINT 'PROBLEM: duplicate username was allowed';
END TRY
BEGIN CATCH
    PRINT 'OK: duplicate username rejected (' + ERROR_MESSAGE() + ')';
END CATCH;

-- A round: target 1 standard (kill, 1st arrow = 20), target 2 predator/prey (20 + prey wound 2nd arrow = 10).
INSERT dbo.Round (RoundId, ArcherId, ShootDate, TargetCount, ArcherClass, AgeGroup, BowStyle, Status,
                  TotalPoints, ClientCreatedUtc, ClientUpdatedUtc)
VALUES (@r, @a, '2026-10-31', 2, 'G', 'ADULT', 'LB', 'COMPLETED', 50, SYSUTCDATETIME(), SYSUTCDATETIME());

INSERT dbo.TargetScore (RoundId, TargetNo, TargetType, OverridePoints, Points) VALUES
    (@r, 1, 'STANDARD', NULL, 20),
    (@r, 2, 'PREDATOR_PREY', NULL, 30);

INSERT dbo.AnimalScore (RoundId, TargetNo, AnimalIndex, State, ArrowNo, Hit, Points) VALUES
    (@r, 1, 0, 'SCORED', 1, 'KILL', 20),
    (@r, 2, 0, 'SCORED', 1, 'KILL', 20),
    (@r, 2, 1, 'SCORED', 2, 'WOUND', 10);

-- Friendship stored low/high; let SQL Server decide which ID is lower.
INSERT dbo.Friendship (ArcherLowId, ArcherHighId, RequestedById, Status, AcceptedUtc)
SELECT CASE WHEN @a < @b THEN @a ELSE @b END,
       CASE WHEN @a < @b THEN @b ELSE @a END,
       @a, 'ACCEPTED', SYSUTCDATETIME();

SELECT ArcherId, Username, CreatedUtc FROM dbo.Archer WHERE ArcherId IN (@a, @b);

SELECT r.ShootDate, r.TotalPoints, t.TargetNo, t.TargetType, t.Points AS TargetPoints,
       an.AnimalIndex, an.State, an.ArrowNo, an.Hit, an.Points AS AnimalPoints
FROM dbo.Round r
JOIN dbo.TargetScore t ON t.RoundId = r.RoundId
JOIN dbo.AnimalScore an ON an.RoundId = t.RoundId AND an.TargetNo = t.TargetNo
WHERE r.RoundId = @r
ORDER BY t.TargetNo, an.AnimalIndex;

-- B should see A as a friend, and A's finished round.
SELECT f.ArcherId AS Viewer, f.FriendId, fr.ShootDate, fr.TotalPoints
FROM dbo.vFriend f
JOIN dbo.Round fr ON fr.ArcherId = f.FriendId AND fr.Status = 'COMPLETED' AND fr.IsDeleted = 0
WHERE f.ArcherId = @b;

-- Deleting the round removes its targets and animals too.
DELETE dbo.Round WHERE RoundId = @r;
IF EXISTS (SELECT 1 FROM dbo.AnimalScore WHERE RoundId = @r)
    PRINT 'PROBLEM: animal scores left behind';
ELSE
    PRINT 'OK: deleting a round removes its scores';

ROLLBACK TRANSACTION;

-- The API login must NOT be able to change the schema.
BEGIN TRY
    EXEC (N'CREATE TABLE dbo.ShouldNotExist (Id INT)');
    PRINT 'PROBLEM: nfas_api was able to create a table';
    EXEC (N'DROP TABLE dbo.ShouldNotExist');
END TRY
BEGIN CATCH
    PRINT 'OK: nfas_api cannot change the schema (' + ERROR_MESSAGE() + ')';
END CATCH;

PRINT 'All checks passed if every line above starts with OK.';
