#Requires -Version 7
<#
    API – shoot location map and location page (API 1.7.03). Run in PowerShell 7 (pwsh):

        .\Test-LocationPage.ps1
        .\Test-LocationPage.ps1 -BaseUrl http://localhost:8085

    Three throwaway accounts (zz_test_…): A and B are friends, C isn't. Test locations in the North Sea.
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

function New-TestUser {
    $name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'location page tests' }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    [pscustomobject]@{ Name = $r.Body.username; Id = $r.Body.archerId; Token = $r.Body.token }
}

function Scored($index, $arrow, $hit, $points) { @{ index = $index; state = 'SCORED'; arrowNo = $arrow; hit = $hit; points = $points } }
# 2 targets = 3 animals: standard (20 or 24) + predator/prey 20 + 10.
function New-Round($LocationId, [string]$Bow, [int]$First, [string]$Status = 'COMPLETED') {
    $hit = if ($First -eq 24) { 'INNER_KILL' } else { 'KILL' }
    $now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    @{
        shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = 2
        archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = $Bow; status = $Status; totalPoints = $First + 30
        clientCreatedUtc = $now; clientUpdatedUtc = $now; locationId = $LocationId
        targets = @(
            @{ targetNo = 1; type = 'STANDARD'; overridePoints = $null; points = $First; animals = @((Scored 0 1 $hit $First)) }
            @{ targetNo = 2; type = 'PREDATOR_PREY'; overridePoints = $null; points = 30
               animals = @((Scored 0 1 'KILL' 20), (Scored 1 2 'WOUND' 10)) }
        )
    }
}

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status)). Is the FieldArcheryApi app pool started?" }
Expect 'API is version 1.7.03 or later' ([version]($r.Body.apiVersion -replace '\.0(\d)$', '.$1') -ge [version]'1.7.3') "(got $($r.Body.apiVersion) – publish the new build?)"

$A = New-TestUser; $B = New-TestUser; $C = New-TestUser
$null = Call POST '/api/friends/requests' @{ username = $B.Name } -Token $A.Token
$null = Call POST "/api/friends/$($A.Id)/accept" -Token $B.Token

$lat = 56.0 + (Get-Random -Minimum 0 -Maximum 1000) / 1000.0
$lon = 3.5 + (Get-Random -Minimum 0 -Maximum 1000) / 1000.0
$tag = -join ((97..122) | Get-Random -Count 5 | ForEach-Object { [char]$_ })

$r = Call POST '/api/locations' @{ name = "zz_test Page Club $tag"; latitude = $lat; longitude = $lon } -Token $A.Token
$L = $r.Body.locationId
$r = Call POST '/api/locations' @{ name = "zz_test Garden $tag"; latitude = $lat + 0.05; longitude = $lon; isPrivate = $true } -Token $A.Token
$P = $r.Body.locationId

# A: LB 50; B: LB 54 and an unfinished round; C (not a friend): BB 50. A also shoots at the private garden.
$null = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round $L 'LB' 20) -Token $A.Token
$null = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round $L 'LB' 24) -Token $B.Token
$null = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round $L 'LB' 20 'IN_PROGRESS') -Token $B.Token
$null = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round $L 'BB' 20) -Token $C.Token
$null = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round $P 'LB' 24) -Token $A.Token

# --- Map ------------------------------------------------------------------------------------------
$r = Call GET '/api/locations/map' -Token $A.Token
$club = @($r.Body.locations | Where-Object locationId -eq $L)[0]
Expect 'map lists the club with your rounds and everyone''s' ($club.myRounds -eq 1 -and $club.rounds -eq 3) "(mine $($club.myRounds), all $($club.rounds))"
Expect 'map shows your own private location' (@($r.Body.locations | Where-Object locationId -eq $P).Count -eq 1)
$r = Call GET '/api/locations/map' -Token $B.Token
Expect "map doesn't show someone else's private location" (@($r.Body.locations | Where-Object locationId -eq $P).Count -eq 0)
$club = @($r.Body.locations | Where-Object locationId -eq $L)[0]
Expect 'unfinished rounds not counted' ($club.myRounds -eq 1) "(B mine $($club.myRounds))"

# --- Location page --------------------------------------------------------------------------------
$r = Call GET "/api/locations/$L/page" -Token $A.Token
Expect 'page has the location' ($r.Status -eq 200 -and $r.Body.location.name -eq "zz_test Page Club $tag") "($($r.Status))"
Expect 'page lists your rounds there' (@($r.Body.myRounds).Count -eq 1 -and @($r.Body.myRounds)[0].totalPoints -eq 50)
$f = @($r.Body.friends)
Expect 'friends section: B with their finished round only' ($f.Count -eq 1 -and $f[0].username -eq $B.Name -and $f[0].rounds -eq 1 -and @($f[0].roundsList).Count -eq 1) "($($f.Count) friends)"
Expect "friend's average per animal (54 / 3 = 18)" ($f[0].averagePerAnimal -eq 18) "(got $($f[0].averagePerAnimal))"
Expect "non-friend C isn't in the friends section" (@($f | Where-Object username -eq $C.Name).Count -eq 0)
$lb = @($r.Body.bowStyles | Where-Object bowStyle -eq 'LB')[0]
$bb = @($r.Body.bowStyles | Where-Object bowStyle -eq 'BB')[0]
Expect 'all-archer stats: LB 2 archers, 2 rounds, best 54, average 52' ($lb.archers -eq 2 -and $lb.rounds -eq 2 -and $lb.bestTotal -eq 54 -and $lb.averageTotal -eq 52) "($($lb | ConvertTo-Json -Compress))"
Expect 'all-archer stats: LB average per animal (104 / 6 = 17.3)' ($lb.averagePerAnimal -eq 17.3) "(got $($lb.averagePerAnimal))"
Expect "all-archer stats include non-friends' rounds (BB 1)" ($bb.rounds -eq 1 -and $bb.archers -eq 1)
$cls = @($r.Body.bowClasses | Where-Object { $_.bowStyle -eq 'LB' })
if ($null -eq $r.Body.bowClasses) { Write-Host 'PROBLEM: no bowClasses (API 2.4 not published?)' -ForegroundColor Red; $script:problems++ }
else { Expect 'per class (2.4): LB rows add up to the LB total' (($cls | Measure-Object rounds -Sum).Sum -eq 2 -and @($cls | Where-Object { -not $_.archerClass -or -not $_.ageGroup }).Count -eq 0) "($($cls | ConvertTo-Json -Compress))" }

$roundId = @($f[0].roundsList)[0].roundId
$r = Call GET "/api/friends/$($B.Id)/rounds/$roundId" -Token $A.Token
Expect "a friend's round from the page opens as a scorecard" ($r.Status -eq 200 -and $r.Body.totalPoints -eq 54) "($($r.Status))"

$r = Call GET "/api/locations/$P/page" -Token $A.Token
Expect 'your private location page: your rounds, no friends or all-archer stats' ($r.Status -eq 200 -and @($r.Body.myRounds).Count -eq 1 -and @($r.Body.friends).Count -eq 0 -and @($r.Body.bowStyles).Count -eq 0) "($($r.Status))"
$r = Call GET "/api/locations/$P/page" -Token $B.Token
Expect "someone else's private location page: not found" ($r.Status -eq 404) "($($r.Status))"
$r = Call GET "/api/locations/$([guid]::NewGuid())/page" -Token $A.Token
Expect 'unknown location: not found' ($r.Status -eq 404) "($($r.Status))"

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All location page tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Remove the test accounts and locations with server/sql/900_delete_test_accounts.sql.'
