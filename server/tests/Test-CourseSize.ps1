#Requires -Version 7
<#
    NFAS API – course size and round make-up on location pages and friends' rounds (API 2.0.02). Run in PowerShell 7:

        .\Test-CourseSize.ps1
        .\Test-CourseSize.ps1 -BaseUrl http://localhost:8085

    A location's course size is the number of targets on the latest finished round there that's a usual size
    (36 / 40, or 18 / 20 for a half course), else the latest round's count rounded to one of those. Averages are also
    given as a whole course of that size: average per animal × course size. Rounds say how many animals they had and
    how many predator/prey and fan targets.
    Two throwaway accounts (zz_test_…, friends) and test locations in the North Sea.
    Every line should start with OK. Tidy up afterwards with server/sql/900_delete_test_accounts.sql.
#>
param([string]$BaseUrl = 'https://fieldarchery.fnurble.co.uk')

$ErrorActionPreference = 'Stop'
$script:problems = 0

function Call([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null) {
    $params = @{
        Method = $Method; Uri = "$BaseUrl$Path"; SkipHttpErrorCheck = $true
        StatusCodeVariable = 'status'; ContentType = 'application/json'
    }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Depth 10) }
    if ($Token) { $params.Headers = @{ Authorization = "Bearer $Token" } }
    $result = Invoke-RestMethod @params
    [pscustomobject]@{ Status = [int]$status; Body = $result }
}

function Expect([string]$What, [bool]$Ok, $Detail = '') {
    if ($Ok) { Write-Host "OK: $What" -ForegroundColor Green }
    else     { Write-Host "PROBLEM: $What $Detail" -ForegroundColor Red; $script:problems++ }
}

function Why($r) { "($($r.Status) $($r.Body.error) $($r.Body.message)$($r.Body.title) $($r.Body.detail))" }

function New-TestUser {
    $name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'course size tests' }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    [pscustomobject]@{ Name = $r.Body.username; Id = $r.Body.archerId; Token = $r.Body.token }
}

# (Not "Kill": PowerShell already uses that name for Stop-Process.)
function New-Kill([int]$Index) { @{ index = $Index; state = 'SCORED'; arrowNo = 1; hit = 'KILL'; points = 20 } }

# A finished LB round: every animal killed with the first arrow (20). Target 1 can be a predator/prey (20 + 20)
# or a fan of 3 (60). Returns the round with its total and animals.
function New-Round($LocationId, [int]$Targets, [int]$DaysAgo, [string]$First = 'STANDARD') {
    $now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    $list = @(); $total = 0; $animals = 0
    for ($n = 1; $n -le $Targets; $n++) {
        $type = if ($n -eq 1) { $First } else { 'STANDARD' }
        $count = switch ($type) { 'PREDATOR_PREY' { 2 } 'FAN' { 3 } default { 1 } }
        $a = @(0..($count - 1) | ForEach-Object { New-Kill $_ })
        $list += @{ targetNo = $n; type = $type; overridePoints = $null; points = 20 * $count; animals = $a }
        $total += 20 * $count; $animals += $count
    }
    [pscustomobject]@{
        Total = $total; Animals = $animals
        Body = @{
            shootDate = (Get-Date).AddDays(-$DaysAgo).ToString('yyyy-MM-dd'); targetCount = $Targets
            archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'LB'; status = 'COMPLETED'; totalPoints = $total
            clientCreatedUtc = $now; clientUpdatedUtc = $now; locationId = $LocationId; targets = $list
        }
    }
}

function Upload($Round, $User) {
    $r = Call PUT "/api/rounds/$([guid]::NewGuid())" $Round.Body -Token $User.Token
    if ($r.Status -ne 200) { throw "Couldn't upload a test round $(Why $r)" }
}

function New-Location($User, [string]$Name) {
    $lat = 55.0 + (Get-Random -Minimum 0 -Maximum 1000) / 1000.0
    $lon = 3.0 + (Get-Random -Minimum 0 -Maximum 1000) / 1000.0
    $r = Call POST '/api/locations' @{ name = $Name; latitude = $lat; longitude = $lon } -Token $User.Token
    if ($r.Status -ne 201) { throw "Couldn't make a test location $(Why $r)" }
    $r.Body.locationId
}

function LB($Page) { @($Page.bowStyles | Where-Object { $_.bowStyle -eq 'LB' -and $_.roundType -eq 'BIG_GAME' })[0] }

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status)). Is the NfasApi app pool started?" }
Expect 'API is version 2.0.02 or later' ([version]($r.Body.apiVersion -replace '\.0(\d)$', '.$1') -ge [version]'2.0.2') "(got $($r.Body.apiVersion) – publish the new build?)"

$A = New-TestUser; $B = New-TestUser
$null = Call POST '/api/friends/requests' @{ username = $B.Name } -Token $A.Token
$r = Call POST "/api/friends/$($A.Id)/accept" -Token $B.Token
Expect 'A and B are friends' ($r.Status -eq 200) (Why $r)
$tag = -join ((97..122) | Get-Random -Count 5 | ForEach-Object { [char]$_ })

# --- Course size follows the latest round of a usual size ---------------------------------------------
$L = New-Location $A "zz_test Course $tag"
$r = Call GET "/api/locations/$L/page" -Token $A.Token
Expect 'no rounds yet: no course size' ($r.Status -eq 200 -and $null -eq $r.Body.location.courseTargets) (Why $r)

$r40 = New-Round $L 40 10
Upload $r40 $A
$r = Call GET "/api/locations/$L/page" -Token $A.Token
Expect 'a 40-target round: the course is 40' ($r.Body.location.courseTargets -eq 40) "($($r.Body.location.courseTargets))"
Expect '40-target average 800 (20 per animal)' ((LB $r.Body).courseAverage -eq 800 -and $r.Body.friends.Count -eq 0) "($((LB $r.Body).courseAverage))"

$r36 = New-Round $L 36 2 'PREDATOR_PREY'
Upload $r36 $A
$r = Call GET "/api/locations/$L/page" -Token $A.Token
Expect 'a later 36-target round: the course is now 36' ($r.Body.location.courseTargets -eq 36) "($($r.Body.location.courseTargets))"
$per = ($r40.Total + $r36.Total) / ($r40.Animals + $r36.Animals)
Expect "course average is per animal × 36 ($([math]::Round($per * 36)))" ((LB $r.Body).courseAverage -eq [math]::Round($per * 36)) "($((LB $r.Body).courseAverage))"

$r10 = New-Round $L 10 0 'FAN'
Upload $r10 $A
$r = Call GET "/api/locations/$L/page" -Token $A.Token
Expect 'a later 10-target round (not a usual size) leaves it at 36' ($r.Body.location.courseTargets -eq 36) "($($r.Body.location.courseTargets))"
$per = ($r40.Total + $r36.Total + $r10.Total) / ($r40.Animals + $r36.Animals + $r10.Animals)
Expect "fan animals count in the average ($([math]::Round($per * 36)))" ((LB $r.Body).courseAverage -eq [math]::Round($per * 36)) "($((LB $r.Body).courseAverage))"

# --- Round make-up ---------------------------------------------------------------------------------
$pp = @($r.Body.myRounds | Where-Object targetCount -eq 36)[0]
Expect 'the 36-target round: 37 animals, 1 predator/prey, no fans' ($pp.animalCount -eq 37 -and $pp.predatorPrey -eq 1 -and $pp.fans -eq 0) "($($pp.animalCount) $($pp.predatorPrey) $($pp.fans))"
$fan = @($r.Body.myRounds | Where-Object targetCount -eq 10)[0]
Expect 'the 10-target round: 12 animals, 1 fan' ($fan.animalCount -eq 12 -and $fan.fans -eq 1 -and $fan.predatorPrey -eq 0) "($($fan.animalCount) $($fan.predatorPrey) $($fan.fans))"
$r = Call GET "/api/friends/$($A.Id)/rounds" -Token $B.Token
$pp = @($r.Body.rounds | Where-Object targetCount -eq 36)[0]
Expect "friends' round lists say the same (37 animals, 1 predator/prey)" ($pp.animalCount -eq 37 -and $pp.predatorPrey -eq 1) "($($pp.animalCount) $($pp.predatorPrey))"
$r = Call GET "/api/locations/$L/page" -Token $B.Token
$f = @($r.Body.friends)[0]
Expect "friend's summary has a course average too" ($null -ne $f.courseAverage -and @($f.roundsList | Where-Object predatorPrey -eq 1).Count -eq 1) "($($f.courseAverage))"

# --- Only unusual sizes: rounded to the nearest -------------------------------------------------------
$L2 = New-Location $A "zz_test Odd Course $tag"
Upload (New-Round $L2 37 1) $A
$r = Call GET "/api/locations/$L2/page" -Token $A.Token
Expect 'only a 37-target round: taken as 36' ($r.Body.location.courseTargets -eq 36) "($($r.Body.location.courseTargets))"
$L3 = New-Location $A "zz_test Half Course $tag"
Upload (New-Round $L3 19 1) $A
$r = Call GET "/api/locations/$L3/page" -Token $A.Token
Expect 'only a 19-target round: taken as a half course of 20' ($r.Body.location.courseTargets -eq 20) "($($r.Body.location.courseTargets))"

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All course size tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Tidy up: run server/sql/900_delete_test_accounts.sql in SSMS.'
