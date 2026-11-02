#Requires -Version 7
<#
    NFAS API – Woodsman rounds (API 1.9.01). Run in PowerShell 7 (pwsh):

        .\Test-Woodsman.ps1
        .\Test-Woodsman.ps1 -BaseUrl http://localhost:8085

    Woodsman: one animal per target, shoot until a kill, up to 3 arrows. Wound 5 on any arrow,
    kill 30 / 20 / 10 on arrow 1 / 2 / 3, miss 0. Arrows are sent in order: W, K, M (e.g. "WK" = 25).
    Two throwaway accounts (zz_test_…), friends, and a test location in the North Sea.
    Every line should start with OK. Tidy up afterwards with server/sql/900_delete_test_accounts.sql.
#>
param([string]$BaseUrl = 'https://nfas.fnurble.co.uk')

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
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'woodsman tests' }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    [pscustomobject]@{ Name = $r.Body.username; Id = $r.Body.archerId; Token = $r.Body.token }
}

function Points([string]$Arrows) {
    [int]$p = 5 * @($Arrows.ToCharArray() | Where-Object { $_ -eq 'W' }).Count
    $k = $Arrows.IndexOf('K'); if ($k -ge 0) { $p += 30 - $k * 10 }
    $p
}

# A Woodsman round from a list of arrow strings; $Points / $Type / $Override let a test break one target.
function New-Woodsman([string[]]$Arrows, $LocationId = $null, [hashtable]$Break = @{}) {
    $now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    $targets = @(); $i = 0; $total = 0
    foreach ($a in $Arrows) {
        $i++
        $t = @{ targetNo = $i; type = 'WOODSMAN'; overridePoints = $null; points = (Points $a); arrows = $a; animals = @() }
        if ($Break.ContainsKey($i)) { foreach ($k in $Break[$i].Keys) { $t[$k] = $Break[$i][$k] } }
        $targets += $t
        $total += [int]$t.points
    }
    @{
        roundType = 'WOODSMAN'; shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = $Arrows.Count
        archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'LB'; status = 'COMPLETED'
        totalPoints = $total
        clientCreatedUtc = $now; clientUpdatedUtc = $now; locationId = $LocationId
        targets = $targets
    }
}

function Scored($index, $arrow, $hit, $points) { @{ index = $index; state = 'SCORED'; arrowNo = $arrow; hit = $hit; points = $points } }

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status)). Is the NfasApi app pool started?" }
Expect 'API is version 1.9.01 or later' ([version]($r.Body.apiVersion -replace '\.0(\d)$', '.$1') -ge [version]'1.9.1') "(got $($r.Body.apiVersion) – publish the new build?)"
Expect 'database is schema version 5 or later (007 run)' ($r.Body.schemaVersion -ge 5) "(got $($r.Body.schemaVersion))"

$A = New-TestUser; $B = New-TestUser
$r = Call POST '/api/friends/requests' @{ username = $B.Name } -Token $A.Token
Expect 'A asks B to be friends' ($r.Status -in 200, 201) "($($r.Status) $($r.Body.error) $($r.Body.message))"
$r = Call POST "/api/friends/$($A.Id)/accept" -Token $B.Token
Expect 'B accepts' ($r.Status -eq 200) "($($r.Status) $($r.Body.error) $($r.Body.message))"
$lat = 57.0 + (Get-Random -Minimum 0 -Maximum 1000) / 1000.0; $lon = 4.0 + (Get-Random -Minimum 0 -Maximum 1000) / 1000.0
$tag = -join ((97..122) | Get-Random -Count 5 | ForEach-Object { [char]$_ })
$L = (Call POST '/api/locations' @{ name = "zz_test Woods $tag"; latitude = $lat; longitude = $lon } -Token $A.Token).Body.locationId

# --- Scoring rules --------------------------------------------------------------------------------
# K 30, WK 25, MWK 15, WWW 15, MMM 0, WWK 20, MK 20 = 125
$arrows = @('K', 'WK', 'MWK', 'WWW', 'MMM', 'WWK', 'MK')
$id = [guid]::NewGuid()
$r = Call PUT "/api/rounds/$id" (New-Woodsman $arrows $L) -Token $A.Token
Expect 'Woodsman round saved (K 30, WK 25, MWK 15, WWW 15, MMM 0, WWK 20, MK 20)' ($r.Status -eq 200) "($($r.Status) $($r.Body.message)$($r.Body.title) $($r.Body.detail))"
$r = Call GET "/api/rounds/$id" -Token $A.Token
Expect 'comes back as WOODSMAN with total 125' ($r.Body.roundType -eq 'WOODSMAN' -and $r.Body.totalPoints -eq 125) "($($r.Body.roundType) $($r.Body.totalPoints))"
Expect 'arrows come back as sent' ((@($r.Body.targets | ForEach-Object arrows) -join ',') -eq ($arrows -join ',')) "($(@($r.Body.targets | ForEach-Object arrows) -join ','))"

$bad = @(
    @{ What = 'arrow after a kill (KW)'; Break = @{ 1 = @{ arrows = 'KW'; points = 35 } } }
    @{ What = 'four arrows (WWWW)'; Break = @{ 1 = @{ arrows = 'WWWW'; points = 20 } } }
    @{ What = 'unknown letter (X)'; Break = @{ 1 = @{ arrows = 'X'; points = 0 } } }
    @{ What = 'wrong points (WK as 30)'; Break = @{ 2 = @{ points = 30 } } }
    @{ What = 'a Big Game target type in a Woodsman round'; Break = @{ 1 = @{ type = 'STANDARD' } } }
    @{ What = 'animals on a Woodsman target'; Break = @{ 1 = @{ animals = @((Scored 0 1 'KILL' 20)) } } }
    @{ What = 'override over 30'; Break = @{ 1 = @{ overridePoints = 31; points = 31 } } }
)
# (Not $b: PowerShell names ignore case, so $b would overwrite the test account $B.)
foreach ($case in $bad) {
    $body = New-Woodsman @('K', 'WK') -Break $case.Break
    $r = Call PUT "/api/rounds/$([guid]::NewGuid())" $body -Token $A.Token
    Expect "refused: $($case.What)" ($r.Status -eq 400 -and $r.Body.error -eq 'invalid_round') "($($r.Status) $($r.Body.message))"
}
$r = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Woodsman @('K', 'WK') -Break @{ 1 = @{ overridePoints = 28; points = 28 } }) -Token $A.Token
Expect 'override of 28 accepted' ($r.Status -eq 200) "($($r.Status) $($r.Body.message))"

# Big Game rounds can't use Woodsman targets or arrows; no round type = Big Game (older apps).
$now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
$bg = @{
    shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = 1; archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'LB'
    status = 'COMPLETED'; totalPoints = 20; clientCreatedUtc = $now; clientUpdatedUtc = $now; locationId = $L
    targets = @(@{ targetNo = 1; type = 'STANDARD'; overridePoints = $null; points = 20; animals = @((Scored 0 1 'KILL' 20)) })
}
$bgId = [guid]::NewGuid()
$r = Call PUT "/api/rounds/$bgId" $bg -Token $A.Token
$r = Call GET "/api/rounds/$bgId" -Token $A.Token
Expect 'a round with no type is Big Game' ($r.Body.roundType -eq 'BIG_GAME') "($($r.Body.roundType))"
$bg2 = $bg.Clone(); $bg2.targets = @(@{ targetNo = 1; type = 'WOODSMAN'; overridePoints = $null; points = 30; arrows = 'K'; animals = @() }); $bg2.totalPoints = 30
$r = Call PUT "/api/rounds/$([guid]::NewGuid())" $bg2 -Token $A.Token
Expect 'refused: a Woodsman target in a Big Game round' ($r.Status -eq 400) "($($r.Status))"
$bg3 = $bg.Clone(); $bg3.targets = @(@{ targetNo = 1; type = 'STANDARD'; overridePoints = $null; points = 20; arrows = 'K'; animals = @((Scored 0 1 'KILL' 20)) })
$r = Call PUT "/api/rounds/$([guid]::NewGuid())" $bg3 -Token $A.Token
Expect 'refused: arrows on a Big Game target' ($r.Status -eq 400) "($($r.Status))"
$bg4 = $bg.Clone(); $bg4.roundType = 'CLOUT'
$r = Call PUT "/api/rounds/$([guid]::NewGuid())" $bg4 -Token $A.Token
Expect 'refused: unknown round type' ($r.Status -eq 400) "($($r.Status))"

# --- Friends and the location page keep the types apart -------------------------------------------
$r = Call GET '/api/friends' -Token $B.Token
$why = "HTTP $($r.Status) $($r.Body.error) $($r.Body.message)$($r.Body.title)"
$f = @($r.Body.friends | Where-Object archerId -eq $A.Id)[0]
Expect "friend summary: Big Game 1 round (best 20), Woodsman 2 rounds (best 125)" ($f.rounds -eq 1 -and $f.bestTotal -eq 20 -and $f.woodsmanRounds -eq 2 -and $f.woodsmanBest -eq 125) "($($f | ConvertTo-Json -Compress)) $why"
$r = Call GET "/api/friends/$($A.Id)/rounds" -Token $B.Token
$why = "HTTP $($r.Status) $($r.Body.error) $($r.Body.message)$($r.Body.title)"
Expect "friend's rounds list says which type each is" (@($r.Body.rounds | Where-Object roundType -eq 'WOODSMAN').Count -eq 2 -and @($r.Body.rounds | Where-Object roundType -eq 'BIG_GAME').Count -eq 1) "($(@($r.Body.rounds).Count) rounds) $why"
$r = Call GET "/api/friends/$($A.Id)/rounds/$id" -Token $B.Token
Expect "friend's Woodsman scorecard has the arrows" ($r.Body.roundType -eq 'WOODSMAN' -and @($r.Body.targets)[1].arrows -eq 'WK') "(HTTP $($r.Status) $($r.Body.error) $($r.Body.message)$($r.Body.title))"

$r = Call GET "/api/locations/$L/page" -Token $B.Token
$why = "HTTP $($r.Status) $($r.Body.error) $($r.Body.message)$($r.Body.title)"
$w = @($r.Body.bowStyles | Where-Object roundType -eq 'WOODSMAN')[0]
$g = @($r.Body.bowStyles | Where-Object roundType -eq 'BIG_GAME')[0]
Expect 'location stats: Woodsman and Big Game in separate rows' ($w -and $g) "($(@($r.Body.bowStyles).Count) rows) $why"
Expect 'Woodsman average per target (125 / 7 = 17.9)' ($w.averagePerAnimal -eq 17.9) "(got $($w.averagePerAnimal))"
$wf = @($r.Body.friends | Where-Object roundType -eq 'WOODSMAN')[0]
Expect 'friends section: a Woodsman row for A' ($wf.username -eq $A.Name -and $wf.rounds -eq 1) "($($wf | ConvertTo-Json -Compress))"

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All Woodsman tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Remove the test accounts and location with server/sql/900_delete_test_accounts.sql.'
