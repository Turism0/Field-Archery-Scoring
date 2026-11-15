#Requires -Version 7
<#
    API – older apps keep working (3.2.15, Pete). Run in PowerShell 7 (pwsh) after every API update, before telling
    anyone it's live:

        .\Test-OldApps.ps1
        .\Test-OldApps.ps1 -BaseUrl http://localhost:8085

    Plays the part of the oldest app still in use: it sends what that app sends (an old X-App-Version; a round without any of the fields added since: roundType, startTarget, locationId, groupId) and checks every
    field that app reads is still in the replies, with the same name and kind of value. See docs/API_COMPATIBILITY.md.
    Keeping old apps working is preferred, not required: if MinVersions has been raised (a deliberate breaking change), an
    app below it must get the clean 426 update_required, so it shows "Update required for online features"; that's OK.
    A PROBLEM line means an old app would go wrong rather than be told to update.
    Two throwaway accounts (zz_test_…). Tidy up afterwards with server/sql/900_delete_test_accounts.sql.
#>
param([string]$BaseUrl = 'https://fieldarchery.fnurble.co.uk')

$ErrorActionPreference = 'Stop'
$script:problems = 0

function Call([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null, [hashtable]$Headers = @{}) {
    $h = @{} + $Headers
    if ($Token) { $h.Authorization = "Bearer $Token" }
    $params = @{
        Method = $Method; Uri = "$BaseUrl$Path"; SkipHttpErrorCheck = $true
        StatusCodeVariable = 'status'; ContentType = 'application/json'; Headers = $h
    }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Depth 10) }
    $result = Invoke-RestMethod @params
    [pscustomobject]@{ Status = [int]$status; Body = $result }
}

function Expect([string]$What, [bool]$Ok, $Detail = '') {
    if ($Ok) { Write-Host "OK: $What" -ForegroundColor Green }
    else     { Write-Host "PROBLEM: $What $Detail" -ForegroundColor Red; $script:problems++ }
}

# Every field an old app reads must still be there.
function Has($Object, [string[]]$Names) {
    if ($null -eq $Object) { return $false }
    $have = $Object.PSObject.Properties.Name
    foreach ($n in $Names) { if ($have -notcontains $n) { Write-Host "   missing: $n" -ForegroundColor Yellow; return $false } }
    $true
}

# A round as the first sync-capable apps sent it: none of the fields added since.
function Old-Round {
    $now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    @{ shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = 1; archerClass = 'G'; ageGroup = 'ADULT'
       bowStyle = 'LB'; status = 'COMPLETED'; totalPoints = 20; clientCreatedUtc = $now; clientUpdatedUtc = $now
       targets = @(@{ targetNo = 1; type = 'STANDARD'; overridePoints = $null; points = 20
                      animals = @(@{ index = 0; state = 'SCORED'; arrowNo = 1; hit = 'KILL'; points = 20 }) }) }
}

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
Expect 'health answers with apiVersion' ($r.Status -eq 200 -and $r.Body.apiVersion) "($($r.Status))"

# Versions from 3.2.05 on send X-App-Version (all Play installs do). Apps before that send none and can't be turned
# away, so they aren't tested; there are none on Play.
foreach ($header in @{ 'X-App-Version' = 'android/66' }, @{ 'X-App-Version' = 'web/3.2.05' }) {
    $label = if ($header.Count) { $header['X-App-Version'] } else { 'no version header' }
    $name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'old app test' } -Headers $header
    if ($r.Status -eq 426) {
        Expect "[$label] turned away with update_required (MinVersions raised): it shows Update required" ($r.Body.error -eq 'update_required') "($($r.Body | ConvertTo-Json -Compress))"
        continue
    }
    Expect "[$label] sign up still works, reply has archerId, username, token, expiresUtc" ($r.Status -eq 201 -and (Has $r.Body 'archerId', 'username', 'token', 'expiresUtc')) "($($r.Status) $($r.Body.message))"
    if ($r.Status -ne 201) { continue }
    $token = $r.Body.token

    $r = Call POST '/api/auth/login' @{ username = $name; password = 'Test-password-123'; deviceName = 'old app test' } -Headers $header
    Expect "[$label] sign in still works" ($r.Status -eq 200 -and (Has $r.Body 'archerId', 'username', 'token')) "($($r.Status))"

    $id = [guid]::NewGuid()
    $r = Call PUT "/api/rounds/$id" (Old-Round) -Token $token -Headers $header
    Expect "[$label] a round without the newer fields is accepted" ($r.Status -eq 200 -and $r.Body.result -eq 'saved') "($($r.Status) $($r.Body.message))"

    $r = Call GET '/api/rounds' -Token $token -Headers $header
    $round = @($r.Body.rounds | Where-Object roundId -eq $id)[0]
    Expect "[$label] download list keeps rounds and more" ($r.Status -eq 200 -and (Has $r.Body 'rounds')) "($($r.Status))"
    Expect "[$label] a downloaded round has every field old apps read" (Has $round 'roundId', 'shootDate', 'targetCount', 'archerClass',
        'ageGroup', 'bowStyle', 'status', 'totalPoints', 'isDeleted', 'clientCreatedUtc', 'clientUpdatedUtc', 'serverUpdatedUtc', 'targets')
    Expect "[$label] its targets and animals too" ((Has $round.targets[0] 'targetNo', 'type', 'points', 'animals') -and
        (Has $round.targets[0].animals[0] 'state', 'arrowNo', 'hit', 'points'))
    Expect "[$label] old values are unchanged (archerClass G, status COMPLETED, type STANDARD, hit KILL)" (
        $round.archerClass -eq 'G' -and $round.status -eq 'COMPLETED' -and $round.targets[0].type -eq 'STANDARD' -and $round.targets[0].animals[0].hit -eq 'KILL')

    $r = Call GET "/api/rounds/$id" -Token $token -Headers $header
    Expect "[$label] reading one round still works" ($r.Status -eq 200 -and $r.Body.totalPoints -eq 20) "($($r.Status))"

    $r = Call GET '/api/friends' -Token $token -Headers $header
    Expect "[$label] friends list still answers" ($r.Status -eq 200) "($($r.Status))"

    $r = Call DELETE "/api/rounds/$id" -Token $token -Headers $header
    Expect "[$label] deleting a round still works" ($r.Status -in 200, 204) "($($r.Status))"

    $null = Call POST '/api/auth/logout' -Token $token -Headers $header
}

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'Older apps are fine with this API.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s): phones that haven't updated would go wrong. Keep the change compatible, or raise MinVersions." -ForegroundColor Red; exit 1 }
