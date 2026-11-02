#Requires -Version 7
<#
    NFAS API – rounds scored for a friend (API 1.9.06). Run in PowerShell 7 (pwsh):

        .\Test-Offers.ps1
        .\Test-Offers.ps1 -BaseUrl http://localhost:8085

    A round scored for a friend arrives as a request; it only becomes their round when they accept.
    Three throwaway accounts (zz_test_…): A and B are friends, C isn't.
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
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'offer tests' }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    [pscustomobject]@{ Name = $r.Body.username; Id = $r.Body.archerId; Token = $r.Body.token }
}

# A two-target Big Game round: kill 20 and inner kill 24 = 44.
function New-Round([string]$Status = 'COMPLETED') {
    $now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    @{
        shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = 2; archerClass = 'L'; ageGroup = 'ADULT'; bowStyle = 'HT'
        status = $Status; totalPoints = 44; clientCreatedUtc = $now; clientUpdatedUtc = $now
        targets = @(
            @{ targetNo = 1; type = 'STANDARD'; overridePoints = $null; points = 20; animals = @(@{ index = 0; state = 'SCORED'; arrowNo = 1; hit = 'KILL'; points = 20 }) }
            @{ targetNo = 2; type = 'STANDARD'; overridePoints = $null; points = 24; animals = @(@{ index = 0; state = 'SCORED'; arrowNo = 1; hit = 'INNER_KILL'; points = 24 }) }
        )
    }
}

function Offer($Id, $For, $Round, $From) { Call PUT "/api/offers/$Id" @{ forArcherId = $For.Id; round = $Round } -Token $From.Token }

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status)). Is the NfasApi app pool started?" }
Expect 'API is version 1.9.06 or later' ([version]($r.Body.apiVersion -replace '\.0(\d)$', '.$1') -ge [version]'1.9.6') "(got $($r.Body.apiVersion) – publish the new build?)"
Expect 'database is schema version 6 or later (008 run)' ($r.Body.schemaVersion -ge 6) "(got $($r.Body.schemaVersion))"

$A = New-TestUser; $B = New-TestUser; $C = New-TestUser
$r = Call POST '/api/friends/requests' @{ username = $B.Name } -Token $A.Token
$r = Call POST "/api/friends/$($A.Id)/accept" -Token $B.Token
Expect 'A and B are friends' ($r.Status -eq 200) (Why $r)

$id = [guid]::NewGuid()
$r = Offer $id $B (New-Round) $A
Expect 'A sends B a round they scored' ($r.Status -eq 200 -and $r.Body.result -eq 'offered') (Why $r)
$r = Offer ([guid]::NewGuid()) $C (New-Round) $A
Expect 'not to someone who isn''t a friend' ($r.Status -eq 404) (Why $r)
$r = Offer ([guid]::NewGuid()) $A (New-Round) $A
Expect 'not to yourself' ($r.Status -eq 400) (Why $r)
$r = Offer ([guid]::NewGuid()) $B (New-Round 'IN_PROGRESS') $A
Expect 'only finished rounds' ($r.Status -eq 400) (Why $r)

$r = Call GET '/api/me' -Token $B.Token
Expect 'B''s badge count is 1' ($r.Body.roundOffers -eq 1) "($($r.Body.roundOffers))"
$r = Call GET '/api/offers' -Token $B.Token
$got = @($r.Body.received)
Expect 'B sees the request from A (44 points)' ($got.Count -eq 1 -and $got[0].fromUsername -eq $A.Name -and $got[0].totalPoints -eq 44) (Why $r)
$r = Call GET '/api/offers' -Token $A.Token
Expect 'A sees it as sent' (@($r.Body.sent).Count -eq 1 -and @($r.Body.sent)[0].forUsername -eq $B.Name) (Why $r)
$r = Call GET "/api/offers/$id" -Token $B.Token
Expect 'B can open the round before accepting' ($r.Status -eq 200 -and @($r.Body.round.targets).Count -eq 2) (Why $r)
$r = Call GET "/api/offers/$id" -Token $C.Token
Expect 'C can''t' ($r.Status -eq 404) (Why $r)
$r = Call GET "/api/rounds/$id" -Token $B.Token
Expect 'not in B''s rounds yet' ($r.Status -eq 404) (Why $r)
$r = Call GET "/api/friends/$($B.Id)/rounds" -Token $A.Token
Expect 'not in B''s rounds as A sees them either' (@($r.Body.rounds | Where-Object roundId -eq $id.ToString()).Count -eq 0) (Why $r)

$changed = New-Round; $changed.bowStyle = 'LB'
$r = Offer $id $B $changed $A
Expect 'A can correct it before B answers' ($r.Status -eq 200) (Why $r)
$r = Call POST "/api/offers/$id/accept" -Token $C.Token
Expect 'C can''t accept it' ($r.Status -eq 404) (Why $r)
$r = Call POST "/api/offers/$id/accept" -Token $B.Token
Expect 'B accepts' ($r.Status -eq 200 -and $r.Body.result -eq 'accepted') (Why $r)
$r = Call GET "/api/rounds/$id" -Token $B.Token
Expect 'now B''s round (the corrected one: LB, 44)' ($r.Status -eq 200 -and $r.Body.bowStyle -eq 'LB' -and $r.Body.totalPoints -eq 44) (Why $r)
$r = Call GET '/api/me' -Token $B.Token
Expect 'badge back to 0' ($r.Body.roundOffers -eq 0) "($($r.Body.roundOffers))"
$r = Call GET '/api/rounds' -Token $B.Token
Expect 'B''s next download includes it' (@($r.Body.rounds | Where-Object roundId -eq $id.ToString()).Count -eq 1) (Why $r)
$r = Offer $id $B (New-Round) $A
Expect 'A can''t send it again once accepted' ($r.Status -eq 409) (Why $r)

$id2 = [guid]::NewGuid()
$r = Offer $id2 $B (New-Round) $A
$r = Call POST "/api/offers/$id2/decline" -Token $B.Token
Expect 'B declines another' ($r.Status -eq 204) (Why $r)
$r = Call POST "/api/offers/$id2/accept" -Token $B.Token
Expect 'a declined one is gone' ($r.Status -eq 404) (Why $r)

$id3 = [guid]::NewGuid()
$r = Offer $id3 $B (New-Round) $A
$r = Call DELETE "/api/offers/$id3" -Token $B.Token
Expect 'B can''t withdraw A''s request' ($r.Status -eq 404) (Why $r)
$r = Call DELETE "/api/offers/$id3" -Token $A.Token
Expect 'A withdraws one' ($r.Status -eq 204) (Why $r)
$r = Call GET '/api/offers' -Token $B.Token
Expect 'nothing left waiting for B' (@($r.Body.received).Count -eq 0) (Why $r)

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All request tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Tidy up: run server/sql/900_delete_test_accounts.sql in SSMS.'
