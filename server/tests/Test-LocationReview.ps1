#Requires -Version 7
<#
    API – private locations and possible-duplicate alerts (API 2.0.17: private locations are only their creator's;
    Admins see one only when it clashes with a public location). Run in PowerShell 7 (pwsh):

        .\Test-LocationReview.ps1 -Username pete
        .\Test-LocationReview.ps1 -Username pete -BaseUrl http://localhost:8085

    -Username is the Owner (or an Admin); it asks for the password. Uses two throwaway accounts and test
    locations (zz_test …) in the North Sea. Every line should start with OK.
    Tidy up afterwards with server/sql/900_delete_test_accounts.sql.
#>
param(
    [Parameter(Mandatory)][string]$Username,
    [string]$BaseUrl = 'https://fieldarchery.fnurble.co.uk',
    # 3.1.01: Run-AllTests asks once and passes it in; on its own the script asks.
    [securestring]$OwnerPassword
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
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status)). Is the FieldArcheryApi app pool started?" }
Expect 'API is version 2.0.17 or later' ([version]($r.Body.apiVersion -replace '\.0(\d)$', '.$1') -ge [version]'2.0.17') "(got $($r.Body.apiVersion) – publish the new build?)"
Expect 'database is schema version 4 or later (006 run)' ($r.Body.schemaVersion -ge 4) "(got $($r.Body.schemaVersion))"

$password = if ($OwnerPassword) { ConvertFrom-SecureString $OwnerPassword -AsPlainText } else { Read-Host "Password for $Username (Owner or Admin)" -MaskInput }
$r = Call POST '/api/auth/login' @{ username = $Username; password = $password; deviceName = 'Location review test script' }
if ($r.Status -ne 200) { throw "Sign-in failed ($($r.Status)): $($r.Body.message)" }
$admin = $r.Body.token
$isOwner = (Call GET '/api/me' -Token $admin).Body.role -eq 'OWNER'
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

    # --- Admins don't see private locations ------------------------------------------------------------
    $r = Call POST '/api/locations/resolve' @{ ids = @($aHome) } -Token $admin
    Expect "Admins see 'Private location' too" (@($r.Body.locations)[0].name -eq 'Private location') "($(@($r.Body.locations)[0].name))"
    $r = Call GET '/api/locations/map' -Token $admin
    Expect "it isn't on an Admin's map" (@($r.Body.locations | Where-Object locationId -eq $aHome).Count -eq 0)
    $r = Call GET "/api/locations/$aHome/page" -Token $admin
    Expect "an Admin can't open its page" ($r.Status -eq 404) "($($r.Status))"
    $r = Call GET '/api/admin/locations' -Token $admin
    $row = @($r.Body.locations | Where-Object locationId -eq $aHome)[0]
    if ($isOwner) {
        Expect 'the Owner sees it exists in the admin list, name and position hidden' ($row -and $row.hidden -and $row.name -eq 'Private location' -and $null -eq $row.latitude) "($($row.name) $($row.latitude))"
    } else {
        Expect "an Admin doesn't see it in the admin list" ($null -eq $row)
    }
    $r = Call PUT "/api/admin/locations/$aHome" @{ name = "zz_test Renamed $tag" } -Token $admin
    Expect "an Admin can't rename someone else's private location" ($r.Status -eq 404) "($($r.Status))"
    $r = Call GET '/api/admin/locations/duplicates' -Token $admin
    $pair = @($r.Body.pairs | Where-Object { @($_.a.locationId, $_.b.locationId) -contains $aHome -and @($_.a.locationId, $_.b.locationId) -contains $bHome })[0]
    Expect "two private locations (A's and B's Homes) aren't flagged to Admins" ($null -eq $pair)

    # --- A private location clashing with a public one --------------------------------------------------
    $r = Call POST '/api/locations' @{ name = "zz_test Club $tag"; latitude = $lat + 0.0005; longitude = $lon } -Token $B.Token
    $club = $r.Body.locationId
    Expect 'B names a public club 55 m from the Homes' ($r.Status -eq 201) "($($r.Status) $($r.Body.message))"
    $r = Call GET '/api/admin/locations/duplicates' -Token $A.Token
    Expect 'a User cannot see possible duplicates' ($r.Status -eq 403) "($($r.Status))"
    $r = Call GET '/api/admin/locations/duplicates' -Token $admin
    $pair = @($r.Body.pairs | Where-Object { @($_.a.locationId, $_.b.locationId) -contains $aHome -and @($_.a.locationId, $_.b.locationId) -contains $club })[0]
    $priv = if ($pair.a.locationId -eq $aHome) { $pair.a } else { $pair.b }
    Expect "A's private Home and the public club are flagged (nearby)" ($pair -and $pair.reasons -contains 'nearby') "($($pair.reasons -join ', '))"
    Expect '...and Admins see the private name there' ($priv.isPrivate -and $priv.name -eq "zz_test Home $tag") "($($priv.name))"

    $r = Call POST "/api/admin/locations/$club/merge" @{ intoId = $aHome } -Token $admin
    Expect "nothing can be merged into someone else's private location" ($r.Status -eq 404) "($($r.Status))"
    $r = Call POST '/api/admin/locations/duplicates/approve' @{ aId = $bHome; bId = $club } -Token $admin
    Expect "Admin marks B's private Home and the club as different places" ($r.Status -eq 204) "($($r.Status) $($r.Body.message))"
    $r = Call POST "/api/admin/locations/$aHome/merge" @{ intoId = $club } -Token $admin
    Expect "Admin merges A's private Home into the club (a flagged clash)" ($r.Status -eq 200 -and $r.Body.roundsMoved -eq 1) "($($r.Status) $($r.Body.roundsMoved) $($r.Body.message))"
    $r = Call GET "/api/rounds/$round" -Token $A.Token
    Expect "A's round is now at the club" ($r.Body.locationId -eq $club -and $r.Body.locationName -eq "zz_test Club $tag") "($($r.Body.locationName))"
    $r = Call POST "/api/admin/locations/$bHome/merge" @{ intoId = $club } -Token $admin
    Expect "B's private Home (approved, so no longer a clash) can't be merged" ($r.Status -eq 404) "($($r.Status))"

    # --- Approving public pairs (similar names far apart) ------------------------------------------------
    $r = Call POST '/api/locations' @{ name = "zz_test Bowmen of Thorn$tag"; latitude = $lat + 0.09; longitude = $lon } -Token $A.Token
    $s1 = $r.Body.locationId
    $r = Call POST '/api/locations' @{ name = "zz_test Thorn$tag Archers"; latitude = $lat - 0.09; longitude = $lon } -Token $B.Token
    $s2 = $r.Body.locationId
    $r = Call GET '/api/admin/locations/duplicates' -Token $admin
    $sim = @($r.Body.pairs | Where-Object { @($_.a.locationId, $_.b.locationId) -contains $s1 -and @($_.a.locationId, $_.b.locationId) -contains $s2 })[0]
    Expect "'Bowmen of X' and 'X Archers' 20 km apart are flagged by name" ($sim -and $sim.reasons -notcontains 'nearby' -and ($sim.reasons -contains 'same_name' -or $sim.reasons -contains 'similar_name')) "($($sim.reasons -join ', '))"

    $r = Call GET '/api/me' -Token $admin
    $before = $r.Body.duplicateLocations
    Expect '/api/me gives Admins a count of possible duplicates' ($before -ge 1) "(got $before)"
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
    # A public location far away with a clearly different name (the shared "zz_test" and tag made the Thorn ones
    # similar enough to B's Home to count as a clash).
    $tag2 = -join ((97..122) | Get-Random -Count 5 | ForEach-Object { [char]$_ })
    $r = Call POST '/api/locations' @{ name = "zz_test Riverside Meadow Longbow Society $tag2"; latitude = $lat - 0.3; longitude = $lon } -Token $A.Token
    $far = $r.Body.locationId
    $r = Call POST '/api/admin/locations/duplicates/approve' @{ aId = $bHome; bId = $far } -Token $admin
    Expect "a private location can't be approved against one it doesn't clash with" ($r.Status -eq 404) "($($r.Status))"

    # --- Only the creator can make a location private -------------------------------------------------
    $r = Call PUT "/api/admin/locations/$s2" @{ name = "zz_test Thorn$tag Archers"; isPrivate = $true } -Token $admin
    Expect "an Admin can't make someone else's location private" ($r.Status -eq 403 -and $r.Body.error -eq 'not_creator') "($($r.Status) $($r.Body.error))"
}
finally {
    $null = Call POST '/api/auth/logout' -Token $admin
}

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All location review tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Remove the test accounts and locations with server/sql/900_delete_test_accounts.sql.'
