#Requires -Version 7
<#
    API – round notes (API 3.2.21, schema 13). Run in PowerShell 7 (pwsh):

        .\Test-RoundNotes.ps1
        .\Test-RoundNotes.ps1 -BaseUrl http://localhost:8085

    A round's notes are kept with it and only ever returned to the archer who owns it (not to friends). Notes up to 500
    characters; "" clears them; a round sent without notes (older apps) keeps what's stored.
    Two throwaway accounts (zz_test_…). Every line should start with OK.
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

function New-TestUser {
    $name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'notes tests' }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    [pscustomobject]@{ Name = $r.Body.username; Id = $r.Body.archerId; Token = $r.Body.token }
}

# A finished 3-target Big Game round, every target a kill on arrow 1 (20). $Notes = 'NOT SENT' leaves the field out.
function New-Round($Notes, [datetime]$Updated = [datetime]::UtcNow) {
    $created = '2026-11-21T09:00:00.000Z'
    $targets = 1..3 | ForEach-Object {
        @{ targetNo = $_; type = 'STANDARD'; overridePoints = $null; points = 20
           animals = @(@{ index = 0; state = 'SCORED'; arrowNo = 1; hit = 'KILL'; points = 20 }) }
    }
    $round = @{ roundType = 'BIG_GAME'; shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = 3; archerClass = 'G'
                ageGroup = 'ADULT'; bowStyle = 'LB'; status = 'COMPLETED'; totalPoints = 60; clientCreatedUtc = $created
                clientUpdatedUtc = $Updated.ToString('yyyy-MM-ddTHH:mm:ss.fffZ'); targets = @($targets) }
    if ($Notes -ne 'NOT SENT') { $round.notes = $Notes }
    $round
}

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status))." }
Expect 'database is schema version 13 or later (017 run)' ($r.Body.schemaVersion -ge 13) "(got $($r.Body.schemaVersion))"

$A = New-TestUser; $B = New-TestUser
$id = [guid]::NewGuid()
$t = [datetime]::UtcNow

$r = Call PUT "/api/rounds/$id" (New-Round '  Windy, new arrows. Missed the bear twice.  ' $t) -Token $A.Token
Expect 'a round with notes is saved' ($r.Status -in 200, 201) "($($r.Status) $($r.Body.message))"
$r = Call GET "/api/rounds/$id" -Token $A.Token
Expect 'and comes back to its archer with the notes (trimmed)' ($r.Body.notes -eq 'Windy, new arrows. Missed the bear twice.') "(got '$($r.Body.notes)')"

# An older app (no notes field) changing the round keeps the notes.
$r = Call PUT "/api/rounds/$id" (New-Round 'NOT SENT' $t.AddMinutes(1)) -Token $A.Token
$r = Call GET "/api/rounds/$id" -Token $A.Token
Expect 'a change from an older app (no notes sent) keeps them' ($r.Body.notes -eq 'Windy, new arrows. Missed the bear twice.') "(got '$($r.Body.notes)')"

# Friends don't see them.
$null = Call POST '/api/friends/requests' @{ username = $B.Name } -Token $A.Token
$null = Call POST "/api/friends/$($A.Id)/accept" -Token $B.Token
$r = Call GET "/api/friends/$($A.Id)/rounds/$id" -Token $B.Token
Expect 'a friend can see the round' ($r.Status -eq 200) "($($r.Status))"
Expect "but not its notes" ($null -eq $r.Body.notes) "(got '$($r.Body.notes)')"

# "" clears them.
$r = Call PUT "/api/rounds/$id" (New-Round '' $t.AddMinutes(2)) -Token $A.Token
$r = Call GET "/api/rounds/$id" -Token $A.Token
Expect 'empty notes clear them' ($null -eq $r.Body.notes) "(got '$($r.Body.notes)')"

$r = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round ('x' * 500)) -Token $A.Token
Expect '500 characters are fine' ($r.Status -in 200, 201) "($($r.Status) $($r.Body.message))"
$r = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round ('x' * 501)) -Token $A.Token
Expect '501 characters are refused' ($r.Status -eq 400) "($($r.Status))"

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All round notes tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Tidy up: run server/sql/900_delete_test_accounts.sql.'
exit $script:problems
