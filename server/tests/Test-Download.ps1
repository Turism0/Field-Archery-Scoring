#Requires -Version 7
<#
    NFAS – step 6 download test, acting as a "second phone" on YOUR account. Run in PowerShell 7 (pwsh):

        .\Test-Download.ps1 -Username pete
        .\Test-Download.ps1 -Username pete -BaseUrl http://localhost:8085

    1. Signs in and lists every round the server holds for you (compare with the phone's home screen).
    2. Adds a TEST round to your account  → the phone should download it.
    3. Edits it (total 74 → 70)           → the phone should update it.
    4. Deletes it                          → the phone should remove it.
    It pauses after each step so you can check the phone, then signs this script out.

    The phone downloads when the app starts (and after any change or sign-in). To make it check:
    close the app fully (swipe it away from recent apps) and open it again.
#>
param(
    [Parameter(Mandatory)][string]$Username,
    [string]$BaseUrl = 'https://nfas.fnurble.co.uk'
)

$ErrorActionPreference = 'Stop'

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

function Iso($value) {
    # PowerShell turns ISO dates into DateTime objects; turn them back into exact ISO text.
    if ($value -is [datetime]) { $value.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ') } else { $value }
}

function Pause-ForPhone([string]$WhatToCheck) {
    Write-Host ''
    Write-Host 'On the phone: close the app fully (swipe it away), open it again, and check:' -ForegroundColor Cyan
    Write-Host "  $WhatToCheck" -ForegroundColor Cyan
    Read-Host 'Press Enter when you have checked'
}

function Get-AllRounds([string]$Token) {
    $all = @(); $since = $null; $afterId = $null
    do {
        $query = if ($since) { "?since=$([uri]::EscapeDataString($since))&afterId=$afterId&limit=200" } else { '?limit=200' }
        $r = Call GET "/api/rounds$query" -Token $Token
        if ($r.Status -ne 200) { throw "Listing rounds failed ($($r.Status)): $($r.Body.message)" }
        $page = @($r.Body.rounds)
        $all += $page
        if ($page.Count -gt 0) { $since = Iso $page[-1].serverUpdatedUtc; $afterId = $page[-1].roundId }
    } while ($r.Body.more)
    $all
}

function Scored($index, $arrow, $hit, $points) { @{ index = $index; state = 'SCORED'; arrowNo = $arrow; hit = $hit; points = $points } }
function Missed($index) { @{ index = $index; state = 'MISSED'; arrowNo = $null; hit = $null; points = 0 } }

function New-TestRound([datetime]$CreatedUtc, [datetime]$UpdatedUtc, [int]$FirstTargetPoints) {
    $first = if ($FirstTargetPoints -eq 20) { Scored 0 1 'KILL' 20 } else { Scored 0 1 'WOUND' 16 }
    @{
        shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = 3
        archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'LB'; status = 'COMPLETED'
        totalPoints = $FirstTargetPoints + 54
        clientCreatedUtc = $CreatedUtc.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
        clientUpdatedUtc = $UpdatedUtc.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
        targets = @(
            @{ targetNo = 1; type = 'STANDARD'; overridePoints = $null; points = $FirstTargetPoints; animals = @($first) }
            @{ targetNo = 2; type = 'PREDATOR_PREY'; overridePoints = $null; points = 30
               animals = @((Scored 0 1 'KILL' 20), (Scored 1 2 'WOUND' 10)) }
            @{ targetNo = 3; type = 'FAN'; overridePoints = $null; points = 24
               animals = @((Scored 0 1 'INNER_KILL' 24), (Missed 1)) }
        )
    }
}

# --- Sign in --------------------------------------------------------------------------------------
$password = Read-Host "Password for $Username" -MaskInput
$r = Call POST '/api/auth/login' @{ username = $Username; password = $password; deviceName = 'Download test script' }
if ($r.Status -ne 200) { throw "Sign-in failed ($($r.Status)): $($r.Body.message)" }
$token = $r.Body.token
Write-Host "Signed in as $($r.Body.username) at $BaseUrl" -ForegroundColor Green

try {
    # --- 1. What the server holds ------------------------------------------------------------------
    $rounds = Get-AllRounds $token
    $live = @($rounds | Where-Object { -not $_.isDeleted })
    Write-Host "`nThe server has $($live.Count) round(s) for you ($(@($rounds).Count - $live.Count) deleted):"
    $live | Sort-Object shootDate -Descending |
        Format-Table @{n='Date';e={$_.shootDate}}, @{n='Total';e={$_.totalPoints}}, @{n='Targets';e={$_.targetCount}},
                     @{n='Class';e={"$($_.archerClass) $($_.ageGroup) $($_.bowStyle)"}}, @{n='Status';e={$_.status}} -AutoSize
    Write-Host 'These should match the finished rounds on the phone (all marked Backed up).'
    Read-Host 'Press Enter to add a test round to your account'

    # --- 2. Add a test round -----------------------------------------------------------------------
    $id = [guid]::NewGuid()
    $created = [datetime]::UtcNow
    $r = Call PUT "/api/rounds/$id" (New-TestRound $created $created 20) -Token $token
    if ($r.Status -ne 200) { throw "Upload failed ($($r.Status)): $($r.Body.message)" }
    Write-Host "Added test round: today, Gents Adult LB, 3 targets, total 74." -ForegroundColor Green
    Pause-ForPhone "a new round dated today, total 74, 3 targets, marked Backed up. Open it: target 2 is predator/prey (20 + 10), target 3 a fan (24 + miss)."

    # --- 3. Edit it ---------------------------------------------------------------------------------
    $r = Call PUT "/api/rounds/$id" (New-TestRound $created ([datetime]::UtcNow) 16) -Token $token
    if ($r.Status -ne 200 -or $r.Body.result -ne 'saved') { throw "Edit failed ($($r.Status)): $($r.Body.message)" }
    Write-Host 'Edited the test round on the server: target 1 is now a wound (16), total 70.' -ForegroundColor Green
    Pause-ForPhone 'the test round now shows total 70.'

    # --- 4. Delete it -------------------------------------------------------------------------------
    $r = Call DELETE "/api/rounds/$id" -Token $token
    if ($r.Status -ne 204) { throw "Delete failed ($($r.Status)): $($r.Body.message)" }
    Write-Host 'Deleted the test round on the server.' -ForegroundColor Green
    Pause-ForPhone 'the test round has gone from the home screen.'

    Write-Host "`nDone. If all three showed up on the phone, downloading works." -ForegroundColor Green
}
finally {
    # Sign this script out so its token can't be reused.
    $null = Call POST '/api/auth/logout' -Token $token
}
