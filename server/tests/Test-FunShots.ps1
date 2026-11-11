#Requires -Version 7
<#
    API – fun shots (API 3.1, schema 11). Run in PowerShell 7 (pwsh):

        .\Test-FunShots.ps1
        .\Test-FunShots.ps1 -BaseUrl http://localhost:8085

    A fun shot is a Big Game target (type FUN) whose score is typed in: no animals, the score in overridePoints. It
    counts in the round total but is left out of the location page stats. One throwaway account (zz_test_…) and a test
    location (zz_test…). Every line should start with OK.
    Tidy up afterwards with server/sql/900_delete_test_accounts.sql.
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

function New-KillTarget([int]$n) {
    @{ targetNo = $n; type = 'STANDARD'; overridePoints = $null; points = 20
       animals = @(@{ index = 0; state = 'SCORED'; arrowNo = 1; hit = 'KILL'; points = 20 }) }
}
function New-FunTarget([int]$n, $points) {
    @{ targetNo = $n; type = 'FUN'; overridePoints = $points; points = $(if ($null -eq $points) { 0 } else { $points }); animals = @() }
}
# A finished 3-target Big Game round: two kills (40) and target 3 as given.
function New-Round($third, $LocationId = $null, [string]$Type = 'BIG_GAME') {
    $now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    $targets = @((New-KillTarget 1), (New-KillTarget 2), $third)
    @{ roundType = $Type; shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = 3; archerClass = 'G'; ageGroup = 'ADULT'
       bowStyle = 'LB'; status = 'COMPLETED'; totalPoints = ($targets | ForEach-Object { $_.points } | Measure-Object -Sum).Sum
       clientCreatedUtc = $now; clientUpdatedUtc = $now; locationId = $LocationId; targets = $targets }
}

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status))." }
Expect 'database is schema version 11 or later (015 run)' ($r.Body.schemaVersion -ge 11) "(got $($r.Body.schemaVersion))"

$name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
$r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'fun shot tests' }
if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
$token = $r.Body.token

$r = Call POST '/api/locations' @{ name = "zz_test Fun $name"; noPin = $true } -Token $token
Expect 'a test location' ($r.Status -eq 201) "($($r.Status) $($r.Body.message))"
$loc = $r.Body.locationId

$id = [guid]::NewGuid()
$r = Call PUT "/api/rounds/$id" (New-Round (New-FunTarget 3 45) $loc) -Token $token
Expect 'a round with a fun shot of 45 is saved' ($r.Status -in 200, 201) "($($r.Status) $($r.Body.message) $($r.Body.problems -join '; '))"
$r = Call GET "/api/rounds/$id" -Token $token
$t3 = $r.Body.targets | Where-Object targetNo -eq 3
Expect 'and comes back as a fun shot, total 85' ($t3.type -eq 'FUN' -and $t3.overridePoints -eq 45 -and $r.Body.totalPoints -eq 85) "($($t3 | ConvertTo-Json -Compress) $($r.Body.totalPoints))"

$r = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round (New-FunTarget 3 0)) -Token $token
Expect 'a fun shot of 0 is fine' ($r.Status -in 200, 201) "($($r.Status) $($r.Body.problems -join '; '))"
$r = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round (New-FunTarget 3 999)) -Token $token
Expect 'a fun shot of 999 is fine' ($r.Status -in 200, 201) "($($r.Status) $($r.Body.problems -join '; '))"
$r = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round (New-FunTarget 3 1000)) -Token $token
Expect 'a fun shot over 999 is refused' ($r.Status -eq 400) "($($r.Status))"
$bad = New-FunTarget 3 45; $bad.animals = @(@{ index = 0; state = 'PENDING'; arrowNo = $null; hit = $null; points = 0 })
$r = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round $bad) -Token $token
Expect 'a fun shot with an animal is refused' ($r.Status -eq 400) "($($r.Status))"
$bad = New-FunTarget 3 45; $bad.points = 40
$r = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round $bad) -Token $token
Expect "a fun shot whose points don't match its score is refused" ($r.Status -eq 400) "($($r.Status))"
$r = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round (New-FunTarget 3 $null)) -Token $token
Expect 'an unscored fun shot (no score yet) is saved as 0' ($r.Status -in 200, 201) "($($r.Status) $($r.Body.problems -join '; '))"

$r = Call GET "/api/locations/$loc/page" -Token $token
$lb = $r.Body.bowStyles | Where-Object bowStyle -eq 'LB'
Expect 'the location page leaves the fun shot out (best 40, 20 per animal)' ($lb.bestTotal -eq 40 -and $lb.averagePerAnimal -eq 20) "($($lb | ConvertTo-Json -Compress))"
$mine = $r.Body.myRounds | Select-Object -First 1
Expect 'your round there shows its total, 85, with funShots 1 / funPoints 45' ($mine.totalPoints -eq 85 -and $mine.funShots -eq 1 -and $mine.funPoints -eq 45) "($($mine | ConvertTo-Json -Compress))"

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All fun shot tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Remove the test accounts and locations with server/sql/900_delete_test_accounts.sql.'
