/*
    Removes accounts created by the scripts in server/tests (usernames starting zz_test_),
    with their tokens, rounds, scores, groups and round requests, and test shoot locations (names starting zz_test). Safe to run any time, as admin or nfas_api.
*/
USE FieldArcheryScoring;
GO
DECLARE @test TABLE (ArcherId UNIQUEIDENTIFIER PRIMARY KEY);
INSERT @test SELECT ArcherId FROM dbo.Archer WHERE Username LIKE N'zz[_]test[_]%';

DELETE f FROM dbo.Friendship f
WHERE f.ArcherLowId IN (SELECT ArcherId FROM @test) OR f.ArcherHighId IN (SELECT ArcherId FROM @test);

-- Test locations (named zz_test…): unhook rounds and merge pointers, then remove them.
IF OBJECT_ID(N'dbo.ShootLocation') IS NOT NULL
BEGIN
    DECLARE @loc TABLE (LocationId UNIQUEIDENTIFIER PRIMARY KEY);
    INSERT @loc SELECT LocationId FROM dbo.ShootLocation WHERE Name LIKE N'zz[_]test%';
    UPDATE dbo.Round SET LocationId = NULL WHERE LocationId IN (SELECT LocationId FROM @loc);
    IF OBJECT_ID(N'dbo.ScoreGroup') IS NOT NULL
        UPDATE dbo.ScoreGroup SET LocationId = NULL WHERE LocationId IN (SELECT LocationId FROM @loc);
    IF OBJECT_ID(N'dbo.LocationPairApproval') IS NOT NULL
        DELETE dbo.LocationPairApproval
        WHERE LocationLowId IN (SELECT LocationId FROM @loc) OR LocationHighId IN (SELECT LocationId FROM @loc);
    UPDATE dbo.ShootLocation SET MergedIntoId = NULL WHERE MergedIntoId IN (SELECT LocationId FROM @loc);
    DELETE dbo.ShootLocation WHERE LocationId IN (SELECT LocationId FROM @loc);
    PRINT CONCAT(@@ROWCOUNT, ' test location(s) deleted.');
END

-- Group scoring (schema v6): requests to or from test accounts, and test accounts in other people's groups.
-- Groups hosted by test accounts go with the account (cascade).
IF OBJECT_ID(N'dbo.RoundOffer') IS NOT NULL
    DELETE dbo.RoundOffer
    WHERE ForArcherId IN (SELECT ArcherId FROM @test) OR FromArcherId IN (SELECT ArcherId FROM @test);
IF OBJECT_ID(N'dbo.ScoreGroupMember') IS NOT NULL
    DELETE dbo.ScoreGroupMember WHERE ArcherId IN (SELECT ArcherId FROM @test);

DELETE a FROM dbo.Archer a WHERE a.ArcherId IN (SELECT ArcherId FROM @test);   -- tokens and rounds cascade

PRINT CONCAT(@@ROWCOUNT, ' test account(s) deleted.');
GO
