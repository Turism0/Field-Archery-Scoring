#Requires -Version 7
<#
    API – the target a round started on (API 2.7, schema 9). Run in PowerShell 7 (pwsh):

        .\Test-StartTarget.ps1
        .\Test-StartTarget.ps1 -BaseUrl http://localhost:8085

    A round's startTarget is an optional note (1 to the number of targets) kept with the round, and a live group's
    is set by the host. One throwaway account (zz_test_…). Every line should start with OK.
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

# A finished 3-target Big Game round, every target a kill on arrow 1 (20).
function New-Round($StartTarget) {
    $now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    $targets = 1..3 | ForEach-Object {
        @{ targetNo = $_; type = 'STANDARD'; overridePoints = $null; points = 20
           animals = @(@{ index = 0; state = 'SCORED'; arrowNo = 1; hit = 'KILL'; points = 20 }) }
    }
    @{ roundType = 'BIG_GAME'; shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = 3; archerClass = 'G'; ageGroup = 'ADULT'
       bowStyle = 'LB'; status = 'COMPLETED'; totalPoints = 60; clientCreatedUtc = $now; clientUpdatedUtc = $now
       startTarget = $StartTarget; targets = @($targets) }
}

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status))." }
Expect 'database is schema version 9 or later (013 run)' ($r.Body.schemaVersion -ge 9) "(got $($r.Body.schemaVersion))"

$name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
$r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'start target tests' }
if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
$token = $r.Body.token

$id = [guid]::NewGuid()
$r = Call PUT "/api/rounds/$id" (New-Round 2) -Token $token
Expect 'a round that started at target 2 is saved' ($r.Status -in 200, 201) "($($r.Status) $($r.Body.message))"
$r = Call GET "/api/rounds/$id" -Token $token
Expect 'and comes back with startTarget 2' ($r.Body.startTarget -eq 2) "(got $($r.Body.startTarget))"

$id2 = [guid]::NewGuid()
$r = Call PUT "/api/rounds/$id2" (New-Round $null) -Token $token
$r = Call GET "/api/rounds/$id2" -Token $token
Expect 'no start target stays empty' ($r.Status -eq 200 -and $null -eq $r.Body.startTarget) "($($r.Status) $($r.Body.startTarget))"

$r = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round 4) -Token $token
Expect 'a start target past the last target is refused' ($r.Status -eq 400) "($($r.Status))"
$r = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round 0) -Token $token
Expect 'start target 0 is refused' ($r.Status -eq 400) "($($r.Status))"

$alphabet = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'
$code = -join (1..6 | ForEach-Object { $alphabet[(Get-Random -Maximum $alphabet.Length)] })
$gid = [guid]::NewGuid()
$host1 = @{ memberId = [guid]::NewGuid().ToString(); kind = 'HOST'; archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'LB' }
$g = @{ code = $code; method = 'SINGLE'; roundType = 'BIG_GAME'; targetCount = 3; shootDate = (Get-Date).ToString('yyyy-MM-dd')
        status = 'OPEN'; members = @($host1); startTarget = 3 }
$r = Call PUT "/api/groups/$gid" $g -Token $token
Expect 'a live group with start target 3' ($r.Status -eq 200 -and $r.Body.startTarget -eq 3) "($($r.Status) $($r.Body.message) $($r.Body.startTarget))"
$g.startTarget = 9
$r = Call PUT "/api/groups/$gid" $g -Token $token
Expect 'a group start target past the last target is refused' ($r.Status -eq 400) "($($r.Status))"
$g.startTarget = $null
$r = Call PUT "/api/groups/$gid" $g -Token $token
Expect 'and it can be cleared' ($r.Status -eq 200 -and $null -eq $r.Body.startTarget) "($($r.Status) $($r.Body.startTarget))"
$g.status = 'CLOSED'
Call PUT "/api/groups/$gid" $g -Token $token | Out-Null

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All start target tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Remove the test accounts with server/sql/900_delete_test_accounts.sql.'
