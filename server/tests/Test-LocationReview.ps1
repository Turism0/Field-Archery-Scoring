#Requires -Version 7
<#
    NFAS API – private locations and possible-duplicate alerts (API 1.7.01). Run in PowerShell 7 (pwsh):

        .\Test-LocationReview.ps1 -Username pete
        .\Test-LocationReview.ps1 -Username pete -BaseUrl http://localhost:8085

    -Username is the Owner (or an Admin); it asks for the password. Uses two throwaway accounts and test
    locations (zz_test …) in the North Sea. Every line should start with OK.
    Tidy up afterwards with server/sql/900_delete_test_accounts.sql.
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

function New-TestUser {
    $name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'location review tests' }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    [pscustomobject]@{ Name = $r.Body.username; Id = $r.Body.archerId; Token = $r.Body.token }
}

function Nearby([double]$Lat, [double]$Lon, [string]$Token) {
    $inv = [cultureinfo]::InvariantCulture
    Call GET "/api/locations/nearby?lat=$($Lat.ToString($inv))&lon=$($Lon.ToString($inv))" -Token $Token
}

function Scored($index, $arrow, $hit, $points) { @{ index = $index; state = 'SCORED'; arrowNo = $arrow; hit = $hit; points = $points } }
function New-Round($LocationId) {
    $now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    @{
        shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = 1
        archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'LB'; status = 'COMPLETED'; totalPoints = 20
        clientCreatedUtc = $now; clientUpdatedUtc = $now; locationId = $LocationId
        targets = @(@{ targetNo = 1; type = 'STANDARD'; overridePoints = $null; points = 20; animals = @((Scored 0 1 'KILL' 20)) })
    }
}

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status)). Is the NfasApi app pool started?" }
Expect 'API is version 1.7.01 or later' ([version]($r.Body.apiVersion -replace '\.0(\d)$', '.$1') -ge [version]'1.7.1') "(got $($r.Body.apiVersion) – publish the new build?)"
Expect 'database is schema version 4 or later (006 run)' ($r.Body.schemaVersion -ge 4) "(got $($r.Body.schemaVersion))"

$password = Read-Host "Password for $Username (Owner or Admin)" -MaskInput
$r = Call POST '/api/auth/login' @{ username = $Username; password = $password; deviceName = 'Location review test script' }
if ($r.Status -ne 200) { throw "Sign-in failed ($($r.Status)): $($r.Body.message)" }
$admin = $r.Body.token
$A = New-TestUser; $B = New-TestUser

try {
    $lat = 55.0 + (Get-Random -Minimum 0 -Maximum 1000) / 1000.0
    $lon = 3.0 + (Get-Random -Minimum 0 -Maximum 1000) / 1000.0
    $tag = -join ((97..122) | Get-Random -Count 5 | ForEach-Object { [char]$_ })

    # --- Private locations --------------------------------------------------------------------------
    $r = Call POST '/api/locations' @{ name = "zz_test Home $tag"; latitude = $lat; longitude = $lon; isPrivate = $true } -Token $A.Token
    $aHome = $r.Body.locationId
    Expect 'A names a private location' ($r.Status -eq 201 -and $r.Body.isPrivate -eq $true) "($($r.Status) $($r.Body.message))"
    $r = Nearby $lat $lon $A.Token
    Expect 'A finds their own private location nearby' (@($r.Body.locations | Where-Object locationId -eq $aHome).Count -eq 1)
    $r = Nearby $lat $lon $B.Token
    Expect "B doesn't see A's private location nearby" (@($r.Body.locations | Where-Object locationId -eq $aHome).Count -eq 0)
    $r = Nearby $lat $lon $admin
    Expect "it isn't auto-matched for an Admin shooting there either" (@($r.Body.locations | Where-Object locationId -eq $aHome).Count -eq 0)

    $r = Call POST '/api/locations' @{ name = "zz_test Home $tag"; latitude = $lat + 0.0009; longitude = $lon; isPrivate = $true } -Token $B.Token
    $bHome = $r.Body.locationId
    Expect 'B can use the same name for their own private location' ($r.Status -eq 201) "($($r.Status) $($r.Body.message))"

    $round = [guid]::NewGuid()
    $null = Call PUT "/api/rounds/$round" (New-Round $aHome) -Token $A.Token
    $r = Call GET "/api/rounds/$round" -Token $A.Token
    Expect 'A sees the private name on their own round' ($r.Body.locationName -eq "zz_test Home $tag") "($($r.Body.locationName))"
    $null = Call POST '/api/friends/requests' @{ username = $B.Name } -Token $A.Token
    $null = Call POST "/api/friends/$($A.Id)/accept" -Token $B.Token
    $r = Call GET "/api/friends/$($A.Id)/rounds" -Token $B.Token
    Expect "a friend sees 'Private location' in the rounds list" (@($r.Body.rounds)[0].locationName -eq 'Private location') "($(@($r.Body.rounds)[0].locationName))"
    $r = Call GET "/api/friends/$($A.Id)/rounds/$round" -Token $B.Token
    Expect "...and on the scorecard" ($r.Body.locationName -eq 'Private location') "($($r.Body.locationName))"
    $r = Call POST '/api/locations/resolve' @{ ids = @($aHome) } -Token $B.Token
    Expect "resolve doesn't reveal the name to others" (@($r.Body.locations)[0].name -eq 'Private location') "($(@($r.Body.locations)[0].name))"

    # --- Possible duplicates ------------------------------------------------------------------------
    $r = Call GET '/api/admin/locations/duplicates' -Token $A.Token
    Expect 'a User cannot see possible duplicates' ($r.Status -eq 403) "($($r.Status))"
    $r = Call GET '/api/admin/locations/duplicates' -Token $admin
    $pair = @($r.Body.pairs | Where-Object { @($_.a.locationId, $_.b.locationId) -contains $aHome -and @($_.a.locationId, $_.b.locationId) -contains $bHome })[0]
    Expect 'the two Homes are flagged (nearby, same name)' ($pair -and $pair.reasons -contains 'nearby' -and $pair.reasons -contains 'same_name') "($($pair.reasons -join ', '))"
    Expect 'admins see private locations in the list' ($pair.a.isPrivate -and $pair.b.isPrivate)

    # Similar names far apart: "Bowmen of Foo" v "Foo Archers", 10 km apart.
    $r = Call POST '/api/locations' @{ name = "zz_test Bowmen of Thorn$tag"; latitude = $lat + 0.09; longitude = $lon } -Token $A.Token
    $s1 = $r.Body.locationId
    $r = Call POST '/api/locations' @{ name = "zz_test Thorn$tag Archers"; latitude = $lat - 0.09; longitude = $lon } -Token $B.Token
    $s2 = $r.Body.locationId
    $r = Call GET '/api/admin/locations/duplicates' -Token $admin
    $sim = @($r.Body.pairs | Where-Object { @($_.a.locationId, $_.b.locationId) -contains $s1 -and @($_.a.locationId, $_.b.locationId) -contains $s2 })[0]
    Expect "'Bowmen of X' and 'X Archers' 20 km apart are flagged by name" ($sim -and $sim.reasons -notcontains 'nearby' -and ($sim.reasons -contains 'same_name' -or $sim.reasons -contains 'similar_name')) "($($sim.reasons -join ', '))"

    $r = Call GET '/api/me' -Token $admin
    $before = $r.Body.duplicateLocations
    Expect '/api/me gives Admins a count of possible duplicates' ($before -ge 2) "(got $before)"
    $r = Call GET '/api/me' -Token $A.Token
    Expect '...and Users 0' ($r.Body.duplicateLocations -eq 0) "(got $($r.Body.duplicateLocations))"

    $r = Call POST '/api/admin/locations/duplicates/approve' @{ aId = $s1; bId = $s2 } -Token $admin
    Expect 'Admin approves a pair as different places' ($r.Status -eq 204) "($($r.Status) $($r.Body.message))"
    $r = Call POST '/api/admin/locations/duplicates/approve' @{ aId = $s2; bId = $s1 } -Token $admin
    Expect 'approving again (either order) is harmless' ($r.Status -eq 204) "($($r.Status))"
    $r = Call GET '/api/admin/locations/duplicates' -Token $admin
    Expect "the approved pair isn't flagged any more" (@($r.Body.pairs | Where-Object { @($_.a.locationId, $_.b.locationId) -contains $s1 -and @($_.a.locationId, $_.b.locationId) -contains $s2 }).Count -eq 0)
    $r = Call GET '/api/me' -Token $admin
    Expect 'the count goes down by one' ($r.Body.duplicateLocations -eq $before - 1) "(was $before, now $($r.Body.duplicateLocations))"
    $r = Call POST '/api/admin/locations/duplicates/approve' @{ aId = $s1; bId = $s1 } -Token $A.Token
    Expect 'a User cannot approve' ($r.Status -eq 403) "($($r.Status))"

    # --- Admin can make a location public / private --------------------------------------------------
    $r = Call PUT "/api/admin/locations/$aHome" @{ name = "zz_test Home $tag"; isPrivate = $false } -Token $admin
    Expect 'Admin makes a private location public' ($r.Status -eq 200) "($($r.Status) $($r.Body.message))"
    $r = Nearby $lat $lon $B.Token
    Expect 'now B finds it nearby' (@($r.Body.locations | Where-Object locationId -eq $aHome).Count -eq 1)
}
finally {
    $null = Call POST '/api/auth/logout' -Token $admin
}

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All location review tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Remove the test accounts and locations with server/sql/900_delete_test_accounts.sql.'
