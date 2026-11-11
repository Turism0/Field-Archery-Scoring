#Requires -Version 7
<#
    API – shoot locations with a name but no map pin (API 3.0, schema 10). Run in PowerShell 7 (pwsh):

        .\Test-NameOnlyLocations.ps1
        .\Test-NameOnlyLocations.ps1 -BaseUrl http://localhost:8085

    For historical scores where only the shoot's name is known. Two throwaway accounts (zz_test_…) and test
    locations (zz_test…). Every line should start with OK.
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

function New-User([string]$Device) {
    $name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = $Device }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    $r.Body.token
}

# A finished 3-target Big Game round at a location, every target a kill on arrow 1 (20).
function New-Round($LocationId) {
    $now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    $targets = 1..3 | ForEach-Object {
        @{ targetNo = $_; type = 'STANDARD'; overridePoints = $null; points = 20
           animals = @(@{ index = 0; state = 'SCORED'; arrowNo = 1; hit = 'KILL'; points = 20 }) }
    }
    @{ roundType = 'BIG_GAME'; shootDate = '2012-06-10'; targetCount = 3; archerClass = 'G'; ageGroup = 'ADULT'
       bowStyle = 'LB'; status = 'COMPLETED'; totalPoints = 60; clientCreatedUtc = $now; clientUpdatedUtc = $now
       locationId = $LocationId; targets = @($targets) }
}

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status))." }
Expect 'database is schema version 10 or later (014 run)' ($r.Body.schemaVersion -ge 10) "(got $($r.Body.schemaVersion))"

$a = New-User 'name-only location tests A'
$b = New-User 'name-only location tests B'
$suffix = -join ((97..122) | Get-Random -Count 5 | ForEach-Object { [char]$_ })
$name = "zz_test Nationals $suffix"

$r = Call POST '/api/locations' @{ name = $name; noPin = $true } -Token $a
Expect 'a name-only location is made' ($r.Status -eq 201 -and $r.Body.noPin -eq $true) "($($r.Status) $($r.Body.message))"
$id = $r.Body.locationId

$r = Call POST '/api/locations' @{ name = "zz_test Bad $suffix"; noPin = $true; latitude = 54.5; longitude = 1.5 } -Token $a
Expect 'no pin with a position is refused' ($r.Status -eq 400 -and $r.Body.error -eq 'invalid_position') "($($r.Status) $($r.Body.error))"
$r = Call POST '/api/locations' @{ name = "zz_test Bad $suffix" } -Token $a
Expect 'no position without noPin is still refused' ($r.Status -eq 400) "($($r.Status))"
$r = Call POST '/api/locations' @{ name = $name; noPin = $true } -Token $b
Expect 'the same name again is name_taken' ($r.Status -eq 409 -and $r.Body.existing.locationId -eq $id) "($($r.Status) $($r.Body.error))"

$r = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round $id) -Token $a
Expect 'a round is saved at it' ($r.Status -in 200, 201) "($($r.Status) $($r.Body.message))"

$r = Call GET '/api/locations/named' -Token $b
$row = $r.Body.locations | Where-Object locationId -eq $id
Expect 'another archer sees it in the named list' ($r.Status -eq 200 -and $row -and $row.rounds -eq 1 -and $row.myRounds -eq 0) "($($r.Status) $($row | ConvertTo-Json -Compress))"
$r = Call GET '/api/locations/named' -Token $a
$row = $r.Body.locations | Where-Object locationId -eq $id
Expect 'and its creator, with their round' ($row -and $row.myRounds -eq 1) "($($row | ConvertTo-Json -Compress))"

$r = Call GET '/api/locations/map' -Token $a
Expect "it isn't on the map" ($r.Status -eq 200 -and -not ($r.Body.locations | Where-Object locationId -eq $id)) "($($r.Status))"
$r = Call GET '/api/locations/nearby?lat=0&lon=0&radius=10000' -Token $a
Expect 'nearby still works (name-only ones never match)' ($r.Status -eq 200 -and -not ($r.Body.locations | Where-Object locationId -eq $id)) "($($r.Status))"

$r = Call GET "/api/locations/$id/page" -Token $b
Expect 'its page opens with no position, and others can''t place it' ($r.Status -eq 200 -and $null -eq $r.Body.location.latitude -and $r.Body.location.canPlace -eq $false) "($($r.Status) $($r.Body.location | ConvertTo-Json -Compress))"
$r = Call GET "/api/locations/$id/page" -Token $a
Expect 'its creator can place it' ($r.Body.location.canPlace -eq $true) "($($r.Body.location | ConvertTo-Json -Compress))"

$r = Call PUT "/api/locations/$id/position" @{ latitude = 54.71234; longitude = 2.81234 } -Token $b
Expect 'someone else can''t put it on the map' ($r.Status -eq 403 -and $r.Body.error -eq 'not_creator') "($($r.Status) $($r.Body.error))"
$r = Call PUT "/api/locations/$id/position" @{ latitude = 95; longitude = 2.8 } -Token $a
Expect 'a bad position is refused' ($r.Status -eq 400) "($($r.Status))"
$r = Call PUT "/api/locations/$id/position" @{ latitude = 54.71234; longitude = 2.81234 } -Token $a
Expect 'its creator puts it on the map' ($r.Status -eq 200 -and $r.Body.latitude -eq 54.71234) "($($r.Status) $($r.Body.message))"
$r = Call PUT "/api/locations/$id/position" @{ latitude = 54.7; longitude = 2.8 } -Token $a
Expect 'once only (already_placed)' ($r.Status -eq 409 -and $r.Body.error -eq 'already_placed') "($($r.Status) $($r.Body.error))"

$r = Call GET '/api/locations/map' -Token $b
Expect 'now it is on the map' ($null -ne ($r.Body.locations | Where-Object locationId -eq $id)) "($($r.Status))"
$r = Call GET '/api/locations/nearby?lat=54.7123&lon=2.8123' -Token $b
Expect 'and is found nearby' ($null -ne ($r.Body.locations | Where-Object locationId -eq $id)) "($($r.Status))"
$r = Call GET '/api/locations/named' -Token $a
Expect 'and has left the named list' (-not ($r.Body.locations | Where-Object locationId -eq $id)) ''

$r = Call PUT "/api/locations/$([guid]::NewGuid())/position" @{ latitude = 54.7; longitude = 2.8 } -Token $a
Expect 'an unknown location is 404' ($r.Status -eq 404) "($($r.Status))"

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All name-only location tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Remove the test accounts and locations with server/sql/900_delete_test_accounts.sql.'
