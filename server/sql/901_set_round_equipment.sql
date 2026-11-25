/*
    One-off (3.2.29, Pete): give an archer's older rounds of one bow style the bow and arrows they've since added in
    Equipment, e.g. every Compound Limited (CL) round of Turismo's.

    - Only rounds with no bow and no arrows yet are changed (rounds that already have equipment are left alone).
    - The bow: the archer's one (not deleted) bow set up for that bow style. The arrows: their one (not deleted) set of
      arrows linked to that bow. If there's more than one of either, the script lists them and stops: put the one you
      mean in @BowId / @ArrowId and run it again.
    - The rounds' edit times are moved to now, so phones and the web app pick up the change on their next sync.
      Sync your phone(s) first, so no unsent edits on them are overwritten.
    - @DryRun = 1 (the default) only shows what would change. Set it to 0 to make the change.

    Run in SSMS as admin.
*/
USE FieldArcheryScoring;
GO
SET NOCOUNT ON;

DECLARE @Username  NVARCHAR(50)     = N'Turismo';
DECLARE @BowStyle  VARCHAR(4)       = 'CL';
DECLARE @BowId     UNIQUEIDENTIFIER = NULL;   -- NULL: the only bow set up for @BowStyle
DECLARE @ArrowId   UNIQUEIDENTIFIER = NULL;   -- NULL: the only arrows linked to that bow
DECLARE @DryRun    BIT              = 1;      -- 1: show what would change; 0: change it

DECLARE @ArcherId UNIQUEIDENTIFIER = (SELECT ArcherId FROM dbo.Archer WHERE Username = @Username);
IF @ArcherId IS NULL BEGIN RAISERROR(N'No archer called %s.', 16, 1, @Username); RETURN; END

-- The bow
IF @BowId IS NULL
BEGIN
    DECLARE @bows TABLE (BowId UNIQUEIDENTIFIER, Make NVARCHAR(100), Model NVARCHAR(100), PoundageLb DECIMAL(4,1), BowStyles VARCHAR(100));
    INSERT @bows SELECT BowId, Make, Model, PoundageLb, BowStyles FROM dbo.Bow
    WHERE ArcherId = @ArcherId AND IsDeleted = 0 AND ',' + BowStyles + ',' LIKE '%,' + @BowStyle + ',%';
    DECLARE @bowCount INT = (SELECT COUNT(*) FROM @bows);
    IF @bowCount <> 1
    BEGIN
        SELECT 'Bows set up for ' + @BowStyle AS [Pick one: put its BowId in @BowId], * FROM @bows;
        RAISERROR(N'%s has %d bows set up for %s: set @BowId to the one you mean.', 16, 1, @Username, @bowCount, @BowStyle);
        RETURN;
    END
    SET @BowId = (SELECT BowId FROM @bows);
END
ELSE IF NOT EXISTS (SELECT 1 FROM dbo.Bow WHERE BowId = @BowId AND ArcherId = @ArcherId)
BEGIN RAISERROR(N'That @BowId isn''t one of %s''s bows.', 16, 1, @Username); RETURN; END

-- The arrows
IF @ArrowId IS NULL
BEGIN
    DECLARE @arrows TABLE (ArrowId UNIQUEIDENTIFIER, Make NVARCHAR(100), Model NVARCHAR(100), Spine VARCHAR(20));
    INSERT @arrows SELECT ArrowId, Make, Model, Spine FROM dbo.ArrowSet
    WHERE ArcherId = @ArcherId AND IsDeleted = 0 AND ',' + BowIds + ',' LIKE '%,' + LOWER(CONVERT(VARCHAR(36), @BowId)) + ',%';
    DECLARE @arrowCount INT = (SELECT COUNT(*) FROM @arrows);
    IF @arrowCount <> 1
    BEGIN
        SELECT 'Arrows linked to that bow' AS [Pick one: put its ArrowId in @ArrowId], * FROM @arrows;
        RAISERROR(N'%s has %d sets of arrows linked to that bow: set @ArrowId to the one you mean.', 16, 1, @Username, @arrowCount);
        RETURN;
    END
    SET @ArrowId = (SELECT ArrowId FROM @arrows);
END
ELSE IF NOT EXISTS (SELECT 1 FROM dbo.ArrowSet WHERE ArrowId = @ArrowId AND ArcherId = @ArcherId)
BEGIN RAISERROR(N'That @ArrowId isn''t one of %s''s sets of arrows.', 16, 1, @Username); RETURN; END

SELECT LTRIM(CONCAT(b.Make, ' ', b.Model)) AS Bow, LTRIM(CONCAT(a.Make, ' ', a.Model)) AS Arrows
FROM dbo.Bow b CROSS JOIN dbo.ArrowSet a WHERE b.BowId = @BowId AND a.ArrowId = @ArrowId;

-- The rounds
SELECT RoundId, ShootDate, RoundType, TotalPoints, Status
FROM dbo.Round
WHERE ArcherId = @ArcherId AND BowStyle = @BowStyle AND IsDeleted = 0 AND BowId IS NULL AND ArrowId IS NULL
ORDER BY ShootDate;
DECLARE @count INT = @@ROWCOUNT;

IF @DryRun = 1
BEGIN
    PRINT CONCAT(@count, ' ', @BowStyle, ' round(s) would get this bow and these arrows. Set @DryRun = 0 to do it.');
    RETURN;
END

BEGIN TRANSACTION;
UPDATE dbo.Round
SET BowId = @BowId, ArrowId = @ArrowId,
    ClientUpdatedUtc = SYSUTCDATETIME(), ServerUpdatedUtc = SYSUTCDATETIME()   -- so phones pick the change up
WHERE ArcherId = @ArcherId AND BowStyle = @BowStyle AND IsDeleted = 0 AND BowId IS NULL AND ArrowId IS NULL;
PRINT CONCAT(@@ROWCOUNT, ' ', @BowStyle, ' round(s) now have the bow and arrows. Sync your phone(s) to see it there.');
COMMIT;
