#Requires -Version 7
<#
    NFAS – check the app's Friends screens with a test friend. Run in PowerShell 7 (pwsh):

        .\Test-FriendView.ps1 -Username pete
        .\Test-FriendView.ps1 -Username pete -BaseUrl http://localhost:8085

    Doesn't need your password: it creates a throwaway friend (zz_test_…) instead.
    1. The test friend shoots two finished rounds (50 and 54) and leaves one unfinished.
    2. It sends YOU a friend request          → accept it on the phone.
    3. Check its summary, rounds and scorecards on the phone (read-only, unfinished round hidden).
    4. Remove it as a friend on the phone      → the script checks the server agrees.
    It pauses after each step. Remove the test account afterwards with server/sql/900_delete_test_accounts.sql.
#>
param(
    [Parameter(Mandatory)][string]$Username,
    [string]$BaseUrl = 'https://fieldarchery.fnurble.co.uk'
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

function Pause-ForPhone([string]$WhatToDo) {
    Write-Host ''
    Write-Host 'On the phone (Home → Friends, pull down to refresh):' -ForegroundColor Cyan
    $WhatToDo -split "`n" | ForEach-Object { Write-Host "  $_" -ForegroundColor Cyan }
    Read-Host 'Press Enter when done'
}

function Scored($index, $arrow, $hit, $points) { @{ index = $index; state = 'SCORED'; arrowNo = $arrow; hit = $hit; points = $points } }

# 2 targets: standard (20 kill or 24 inner kill, 1st arrow) + predator/prey (20 kill + 10 wound 2nd arrow).
function New-Round([string]$Status, [int]$FirstPoints, [int]$DaysAgo) {
    $first = if ($FirstPoints -eq 24) { Scored 0 1 'INNER_KILL' 24 } else { Scored 0 1 'KILL' 20 }
    $now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    @{
        shootDate = (Get-Date).AddDays(-$DaysAgo).ToString('yyyy-MM-dd'); targetCount = 2
        archerClass = 'L'; ageGroup = 'ADULT'; bowStyle = 'HT'; status = $Status; totalPoints = $FirstPoints + 30
        clientCreatedUtc = $now; clientUpdatedUtc = $now
        targets = @(
            @{ targetNo = 1; type = 'STANDARD'; overridePoints = $null; points = $FirstPoints; animals = @($first) }
            @{ targetNo = 2; type = 'PREDATOR_PREY'; overridePoints = $null; points = 30
               animals = @((Scored 0 1 'KILL' 20), (Scored 1 2 'WOUND' 10)) }
        )
    }
}

function Get-Friends([string]$Token) {
    $r = Call GET '/api/friends' -Token $Token
    if ($r.Status -ne 200) { throw "Listing friends failed ($($r.Status)): $($r.Body.message)" }
    $r.Body
}

# --- 1. Create the test friend and its rounds --------------------------------------------------------
$name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
$r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'friend view test' }
if ($r.Status -ne 201) { throw "Couldn't create the test friend ($($r.Status)): $($r.Body.message)" }
$friend = $r.Body
Write-Host "Created test friend $($friend.username) at $BaseUrl" -ForegroundColor Green

try {
    foreach ($round in @(
        @{ Status = 'COMPLETED';   First = 20; DaysAgo = 14 },   # 50
        @{ Status = 'COMPLETED';   First = 24; DaysAgo = 7 },    # 54
        @{ Status = 'IN_PROGRESS'; First = 20; DaysAgo = 0 }     # must stay hidden
    )) {
        $r = Call PUT "/api/rounds/$([guid]::NewGuid())" (New-Round $round.Status $round.First $round.DaysAgo) -Token $friend.token
        if ($r.Status -ne 200) { throw "Round upload failed ($($r.Status)): $($r.Body.message) $($r.Body.problems -join '; ')" }
    }
    Write-Host 'It has 2 finished rounds (50 two weeks ago, 54 a week ago) and 1 unfinished round today.' -ForegroundColor Green

    # --- 2. Friend request to you --------------------------------------------------------------------
    $r = Call POST '/api/friends/requests' @{ username = $Username } -Token $friend.token
    if ($r.Status -eq 404) { throw "No account called '$Username' on the server." }
    if ($r.Status -ne 201) { throw "Friend request failed ($($r.Status)): $($r.Body.message)" }
    Write-Host "Sent you a friend request from $($friend.username)." -ForegroundColor Green
    Pause-ForPhone "Under requests you should see $($friend.username) with Accept / Decline. Tap Accept."

    $f = Get-Friends $friend.token
    $me = @($f.friends | Where-Object { $_.username -eq $Username })
    if ($me.Count -eq 0) { throw "The server doesn't show you as friends yet. Did Accept work? (still pending: $(@($f.outgoing).Count))" }
    Write-Host 'Server agrees: you are now friends.' -ForegroundColor Green

    # --- 3. Check what the phone shows ---------------------------------------------------------------
    Pause-ForPhone @"
$($friend.username) is in your friends list: 2 rounds, best 54, avg 17.3 per animal.
Tap it: two rounds (54 a week ago, 50 two weeks ago), Ladies, Adult, Hunting Tackle. No round for today.
Open the 54: target 1 inner kill 24, target 2 predator/prey 30 (tap to expand: kill 20, wound 10).
No Edit button, and tapping a target doesn't open scoring.
"@

    # --- 4. Remove the friend on the phone -----------------------------------------------------------
    Pause-ForPhone "Open $($friend.username), ⋮ → Remove friend. It should go from your list."
    $f = Get-Friends $friend.token
    if (@($f.friends | Where-Object { $_.username -eq $Username }).Count -eq 0) {
        Write-Host 'Server agrees: no longer friends.' -ForegroundColor Green
    } else {
        Write-Host 'PROBLEM: the server still shows you as friends.' -ForegroundColor Red
    }

    Write-Host "`nDone. Remove the test account with server/sql/900_delete_test_accounts.sql." -ForegroundColor Green
}
finally {
    $null = Call POST '/api/auth/logout' -Token $friend.token
}
