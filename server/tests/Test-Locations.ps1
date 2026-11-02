#Requires -Version 7
<#
    NFAS API – shoot location tests (roadmap step 2a). Run in PowerShell 7 (pwsh):

        .\Test-Locations.ps1 -Username pete                                  # against https://fieldarchery.fnurble.co.uk
        .\Test-Locations.ps1 -Username pete -BaseUrl http://localhost:8085   # on Odin, straight to IIS

    -Username is the Owner (or an Admin) account, for the rename and merge tests; it asks for the password.
    Creates a throwaway account (zz_test_…) and test locations (zz_test …) out in the North Sea, so
    they can't clash with real clubs. Every line should start with OK.
    Tidy up afterwards with server/sql/900_delete_test_accounts.sql (removes test locations too).
#>
param(
    [Parameter(Mandatory)][string]$Username,
    [string]$BaseUrl = 'https://fieldarchery.fnurble.co.uk'
)

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

function Scored($index, $arrow, $hit, $points) { @{ index = $index; state = 'SCORED'; arrowNo = $arrow; hit = $hit; points = $points } }

# 1 target, kill 1st arrow (20). Each call is a newer edit than the last.
function New-Round($LocationId) {
    $script:clock = if ($script:clock) { $script:clock.AddSeconds(1) } else { [datetime]::UtcNow }
    $now = $script:clock.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    @{
        shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = 1
        archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'LB'; status = 'COMPLETED'; totalPoints = 20
        clientCreatedUtc = $now; clientUpdatedUtc = $now; locationId = $LocationId
        targets = @(@{ targetNo = 1; type = 'STANDARD'; overridePoints = $null; points = 20; animals = @((Scored 0 1 'KILL' 20)) })
    }
}

function Nearby([double]$Lat, [double]$Lon, $Radius = $null, [string]$Token) {
    $q = "lat=$($Lat.ToString([cultureinfo]::InvariantCulture))&lon=$($Lon.ToString([cultureinfo]::InvariantCulture))"
    if ($Radius) { $q += "&radius=$Radius" }
    Call GET "/api/locations/nearby?$q" -Token $Token
}

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status)). Is the NfasApi app pool started? (server\api\NfasApi\Update-Api.ps1 starts it)" }
Expect 'API is version 1.5.01 or later' ([version]($r.Body.apiVersion -replace '\.0(\d)$', '.$1') -ge [version]'1.5.1') "(got $($r.Body.apiVersion) – publish the new build?)"
Expect 'database is schema version 3 or later (005_shoot_locations.sql run)' ($r.Body.schemaVersion -ge 3) "(got $($r.Body.schemaVersion))"

$password = Read-Host "Password for $Username (Owner or Admin)" -MaskInput
$r = Call POST '/api/auth/login' @{ username = $Username; password = $password; deviceName = 'Location test script' }
if ($r.Status -ne 200) { throw "Sign-in failed ($($r.Status)): $($r.Body.message)" }
$admin = $r.Body.token

$name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
$r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'location tests' }
if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
$A = $r.Body.token

try {
    # A random spot in the North Sea, ~100 km off the coast. 0.0027° of latitude ≈ 300 m.
    $lat = 54.0 + (Get-Random -Minimum 0 -Maximum 1000) / 1000.0
    $lon = 2.5 + (Get-Random -Minimum 0 -Maximum 1000) / 1000.0
    $tag = -join ((97..122) | Get-Random -Count 5 | ForEach-Object { [char]$_ })

    # --- Nearby and naming ------------------------------------------------------------------------
    $r = Nearby $lat $lon -Token $A
    Expect 'nothing near an empty spot' ($r.Status -eq 200 -and @($r.Body.locations).Count -eq 0) "($($r.Status), $(@($r.Body.locations).Count))"
    Expect 'catchment is about a quarter mile' ($r.Body.catchmentMetres -ge 350 -and $r.Body.catchmentMetres -le 450) "(got $($r.Body.catchmentMetres))"

    $r = Call POST '/api/locations' @{ name = "  zz_test   Bowmen $tag "; latitude = $lat; longitude = $lon } -Token $A
    $L1 = $r.Body.locationId
    Expect 'a User names a new location' ($r.Status -eq 201 -and $L1) "($($r.Status) $($r.Body.message))"
    Expect 'name is tidied (spaces trimmed and squashed)' ($r.Body.name -eq "zz_test Bowmen $tag") "(got '$($r.Body.name)')"

    $r = Nearby ($lat + 0.0027) $lon -Token $A
    $first = @($r.Body.locations)[0]
    Expect 'found from 300 m away' ($first.locationId -eq $L1 -and $first.distanceMetres -ge 250 -and $first.distanceMetres -le 350) "($($first.distanceMetres) m)"
    $r = Nearby ($lat + 0.0108) $lon -Token $A
    Expect 'not found from 1.2 km away (outside the catchment)' (@($r.Body.locations).Count -eq 0)
    $r = Nearby ($lat + 0.0108) $lon 5000 -Token $A
    Expect "found from 1.2 km with a 5 km radius (for 'Not here?')" (@($r.Body.locations | Where-Object locationId -eq $L1).Count -eq 1)

    $r = Call POST '/api/locations' @{ name = "ZZ_TEST BOWMEN $tag"; latitude = $lat + 0.01; longitude = $lon } -Token $A
    Expect 'same name (any case) refused, offering the existing one' ($r.Status -eq 409 -and $r.Body.error -eq 'name_taken' -and $r.Body.existing.locationId -eq $L1) "($($r.Status) $($r.Body.error))"
    $r = Call POST '/api/locations' @{ name = 'x'; latitude = $lat; longitude = $lon } -Token $A
    Expect 'one-letter name refused' ($r.Status -eq 400 -and $r.Body.error -eq 'invalid_name') "($($r.Status))"
    $r = Call POST '/api/locations' @{ name = "zz_test Nowhere $tag"; latitude = 100; longitude = $lon } -Token $A
    Expect 'impossible position refused' ($r.Status -eq 400 -and $r.Body.error -eq 'invalid_position') "($($r.Status))"
    $r = Nearby 0 0
    Expect 'no token: refused' ($r.Status -eq 401) "($($r.Status))"

    # "Not here?": a second location 200 m away is allowed.
    $r = Call POST '/api/locations' @{ name = "zz_test Duplicate $tag"; latitude = $lat + 0.0018; longitude = $lon } -Token $A
    $L2 = $r.Body.locationId
    Expect 'a second location nearby can be named' ($r.Status -eq 201) "($($r.Status) $($r.Body.message))"
    $r = Nearby $lat $lon -Token $A
    $ids = @($r.Body.locations | ForEach-Object locationId)
    Expect 'both listed, nearest first' ($ids.Count -eq 2 -and $ids[0] -eq $L1 -and $ids[1] -eq $L2) "($($ids -join ', '))"

    # --- Rounds carry the location ----------------------------------------------------------------
    $R1 = [guid]::NewGuid(); $R2 = [guid]::NewGuid(); $R3 = [guid]::NewGuid()
    $r = Call PUT "/api/rounds/$R1" (New-Round $L1) -Token $A
    Expect 'round uploaded with a location' ($r.Status -eq 200) "($($r.Status) $($r.Body.message))"
    $r = Call GET "/api/rounds/$R1" -Token $A
    Expect 'round comes back with location id and name' ($r.Body.locationId -eq $L1 -and $r.Body.locationName -eq "zz_test Bowmen $tag") "($($r.Body.locationName))"
    $r = Call PUT "/api/rounds/$R2" (New-Round $L2) -Token $A
    $r = Call PUT "/api/rounds/$R3" (New-Round ([guid]::NewGuid())) -Token $A
    Expect 'round with an unknown location id still saved' ($r.Status -eq 200) "($($r.Status) $($r.Body.message))"
    $r = Call GET "/api/rounds/$R3" -Token $A
    Expect '...with no location' ($null -eq $r.Body.locationId) "($($r.Body.locationId))"
    $r = Call PUT "/api/rounds/$([guid]::NewGuid())" ((New-Round $null)) -Token $A
    Expect 'round with no location saved' ($r.Status -eq 200) "($($r.Status))"

    # --- Admin: users can't ------------------------------------------------------------------------
    $r = Call GET '/api/admin/locations' -Token $A
    Expect 'a User cannot list locations for admin' ($r.Status -eq 403 -and $r.Body.error -eq 'not_admin') "($($r.Status))"
    $r = Call PUT "/api/admin/locations/$L1" @{ name = "zz_test Hacked $tag" } -Token $A
    Expect 'a User cannot rename' ($r.Status -eq 403) "($($r.Status))"
    $r = Call POST "/api/admin/locations/$L2/merge" @{ intoId = $L1 } -Token $A
    Expect 'a User cannot merge' ($r.Status -eq 403) "($($r.Status))"

    # --- Admin: list, rename, merge ----------------------------------------------------------------
    $r = Call GET '/api/admin/locations' -Token $admin
    $l1Row = @($r.Body.locations | Where-Object locationId -eq $L1)[0]
    Expect 'admin list shows the location, who named it and its rounds' ($r.Status -eq 200 -and $l1Row.createdBy -eq $name -and $l1Row.rounds -eq 1) "($($r.Status), $($l1Row.createdBy), $($l1Row.rounds))"

    $r = Call PUT "/api/admin/locations/$L1" @{ name = "zz_test Renamed $tag" } -Token $admin
    Expect 'admin renames a location' ($r.Status -eq 200 -and $r.Body.name -eq "zz_test Renamed $tag") "($($r.Status) $($r.Body.message))"
    $r = Call GET "/api/rounds/$R1" -Token $A
    Expect 'rounds show the new name' ($r.Body.locationName -eq "zz_test Renamed $tag") "($($r.Body.locationName))"
    $r = Call PUT "/api/admin/locations/$L2" @{ name = "zz_test renamed $tag" } -Token $admin
    Expect "can't rename to another location's name" ($r.Status -eq 409 -and $r.Body.error -eq 'name_taken') "($($r.Status))"

    $r = Call POST "/api/admin/locations/$L1/merge" @{ intoId = $L1 } -Token $admin
    Expect "can't merge into itself" ($r.Status -eq 400) "($($r.Status))"
    $r = Call POST "/api/admin/locations/$L2/merge" @{ intoId = $L1 } -Token $admin
    Expect 'admin merges the duplicate; its round moves' ($r.Status -eq 200 -and $r.Body.roundsMoved -eq 1 -and $r.Body.locationId -eq $L1) "($($r.Status) $($r.Body.message) moved $($r.Body.roundsMoved))"
    $r = Call GET "/api/rounds/$R2" -Token $A
    Expect "the duplicate's round is now at the kept location" ($r.Body.locationId -eq $L1) "($($r.Body.locationId))"
    $r = Call POST "/api/admin/locations/$L2/merge" @{ intoId = $L1 } -Token $admin
    Expect "can't merge an already merged location" ($r.Status -eq 404) "($($r.Status))"
    $r = Nearby $lat $lon -Token $A
    Expect 'merged duplicate no longer listed nearby' (@($r.Body.locations | Where-Object locationId -eq $L2).Count -eq 0 -and @($r.Body.locations).Count -eq 1)

    $r = Call POST '/api/locations/resolve' @{ ids = @($L2, $L1, [guid]::NewGuid()) } -Token $A
    $map = @{}; foreach ($x in @($r.Body.locations)) { $map[$x.requestedId] = $x }
    Expect "resolve sends the merged id to the kept one" ($map[$L2].locationId -eq $L1 -and $map[$L2].name -eq "zz_test Renamed $tag") "($($map[$L2].locationId))"
    Expect 'resolve leaves unknown ids out' (@($r.Body.locations).Count -eq 2) "($(@($r.Body.locations).Count))"

    $r = Call PUT "/api/rounds/$R2" (New-Round $L2) -Token $A
    $r = Call GET "/api/rounds/$R2" -Token $A
    Expect 'a phone still sending the old id is stored at the kept location' ($r.Body.locationId -eq $L1) "($($r.Body.locationId))"
}
finally {
    $null = Call POST '/api/auth/logout' -Token $admin
    $null = Call POST '/api/auth/logout' -Token $A
}

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All location tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Remove the test account and locations with server/sql/900_delete_test_accounts.sql.'
