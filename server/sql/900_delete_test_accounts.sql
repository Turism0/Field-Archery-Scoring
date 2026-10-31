/*
    Removes accounts created by server/tests/Test-Accounts.ps1 (usernames starting zz_test_),
    with their tokens, rounds and scores. Safe to run any time, as admin or nfas_api.
*/
USE NfasScoring;
GO
DECLARE @test TABLE (ArcherId UNIQUEIDENTIFIER PRIMARY KEY);
INSERT @test SELECT ArcherId FROM dbo.Archer WHERE Username LIKE N'zz[_]test[_]%';

DELETE f FROM dbo.Friendship f
WHERE f.ArcherLowId IN (SELECT ArcherId FROM @test) OR f.ArcherHighId IN (SELECT ArcherId FROM @test);

DELETE a FROM dbo.Archer a WHERE a.ArcherId IN (SELECT ArcherId FROM @test);   -- tokens and rounds cascade

PRINT CONCAT(@@ROWCOUNT, ' test account(s) deleted.');
GO
