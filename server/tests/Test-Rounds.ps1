#Requires -Version 7
<#
    API – step 5a round upload tests. Run in PowerShell 7 (pwsh):

        .\Test-Rounds.ps1                                   # against https://fieldarchery.fnurble.co.uk
        .\Test-Rounds.ps1 -BaseUrl http://localhost:8085    # on Odin, straight to IIS

    Creates two throwaway accounts (zz_test_…) and one round. Remove them afterwards with
    server/sql/900_delete_test_accounts.sql. Every line should start with OK.
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
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'round tests' }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    $r.Body.token
}

function Scored($index, $arrow, $hit, $points) { @{ index = $index; state = 'SCORED'; arrowNo = $arrow; hit = $hit; points = $points } }
function Missed($index) { @{ index = $index; state = 'MISSED'; arrowNo = $null; hit = $null; points = 0 } }

# A valid 3-target round:
#   1 standard: kill, 1st arrow = 20
#   2 predator/prey: predator kill 1st (20) + prey wound 2nd (10) = 30
#   3 fan of 2: inner kill 1st (24) + miss = 24          total 74
function New-Round([datetime]$UpdatedUtc) {
    @{
        shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = 3
        archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'LB'; status = 'COMPLETED'; totalPoints = 74
        clientCreatedUtc = '2026-10-31T09:00:00Z'; clientUpdatedUtc = $UpdatedUtc.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
        targets = @(
            @{ targetNo = 1; type = 'STANDARD'; overridePoints = $null; points = 20; animals = @(Scored 0 1 'KILL' 20) }
            @{ targetNo = 2; type = 'PREDATOR_PREY'; overridePoints = $null; points = 30
               animals = @((Scored 0 1 'KILL' 20), (Scored 1 2 'WOUND' 10)) }
            @{ targetNo = 3; type = 'FAN'; overridePoints = $null; points = 24
               animals = @((Scored 0 1 'INNER_KILL' 24), (Missed 1)) }
        )
    }
}

Write-Host "Testing $BaseUrl`n"
$tokenA = New-TestUser
$tokenB = New-TestUser
$id = [guid]::NewGuid()
$t0 = [datetime]::UtcNow.AddMinutes(-10)

$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status)). Is the FieldArcheryApi app pool started? (server\api\FieldArcheryApi\Update-Api.ps1 starts it)" }
Expect 'API is version 1.2.09 or later' ([version]($r.Body.apiVersion -replace '\.0(\d)$', '.$1') -ge [version]'1.2.9') "(got $($r.Body.apiVersion) – publish the new build?)"

$r = Call PUT "/api/rounds/$id" (New-Round $t0) -Token $tokenA
Expect 'upload a finished round' ($r.Status -eq 200 -and $r.Body.result -eq 'saved') "($($r.Status) $($r.Body.message))"

$r = Call GET "/api/rounds/$id" -Token $tokenA
Expect 'read it back: total 74, 3 targets' ($r.Status -eq 200 -and $r.Body.totalPoints -eq 74 -and $r.Body.targets.Count -eq 3) "($($r.Status))"
Expect 'predator/prey stored with both animals' ($r.Body.targets[1].animals.Count -eq 2 -and $r.Body.targets[1].animals[1].hit -eq 'WOUND')

$r = Call PUT "/api/rounds/$id" (New-Round $t0) -Token $tokenA
Expect 'sending the same copy again changes nothing' ($r.Status -eq 200 -and $r.Body.result -eq 'unchanged') "($($r.Status))"

$r = Call PUT "/api/rounds/$id" (New-Round $t0.AddMinutes(-5)) -Token $tokenA
Expect 'an older copy is ignored' ($r.Status -eq 200 -and $r.Body.result -eq 'unchanged') "($($r.Status))"

$edit = New-Round $t0.AddMinutes(5)
$edit.targets[0].animals[0] = Scored 0 1 'WOUND' 16; $edit.targets[0].points = 16; $edit.totalPoints = 70
$r = Call PUT "/api/rounds/$id" $edit -Token $tokenA
Expect 'a newer edit is saved' ($r.Status -eq 200 -and $r.Body.result -eq 'saved') "($($r.Status) $($r.Body.message))"
$r = Call GET "/api/rounds/$id" -Token $tokenA
Expect 'the edit shows when read back (total 70)' ($r.Body.totalPoints -eq 70)

# Download list (step 6).
$r = Call GET '/api/rounds' -Token $tokenA
$listed = @($r.Body.rounds | Where-Object roundId -eq $id)
Expect 'round list includes the round, with targets' ($r.Status -eq 200 -and $listed.Count -eq 1 -and $listed[0].targets.Count -eq 3) "($($r.Status))"
# PowerShell turns ISO dates into DateTime objects; send it back in exact ISO form.
$stamp = $listed[0].serverUpdatedUtc
if ($stamp -is [datetime]) { $stamp = $stamp.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ') }
$r = Call GET "/api/rounds?since=$([uri]::EscapeDataString($stamp))" -Token $tokenA
Expect 'nothing new since its last change' ($r.Status -eq 200 -and @($r.Body.rounds).Count -eq 0) "($($r.Status), $(@($r.Body.rounds).Count) rounds)"
$r = Call GET '/api/rounds' -Token $tokenB
Expect "another archer's list doesn't include it" ($r.Status -eq 200 -and @($r.Body.rounds | Where-Object roundId -eq $id).Count -eq 0) "($($r.Status))"

# Rule checks: each of these must be refused.
$bad = New-Round $t0.AddMinutes(6); $bad.targets[0].animals[0] = Scored 0 2 'INNER_KILL' 24; $bad.targets[0].points = 24; $bad.totalPoints = 78
$r = Call PUT "/api/rounds/$id" $bad -Token $tokenA
Expect 'inner kill on the 2nd arrow refused' ($r.Status -eq 400 -and $r.Body.error -eq 'invalid_round') "($($r.Status))"

$bad = New-Round $t0.AddMinutes(6); $bad.targets[0].animals[0] = Scored 0 1 'KILL' 24; $bad.totalPoints = 74
$r = Call PUT "/api/rounds/$id" $bad -Token $tokenA
Expect 'wrong points for the hit refused' ($r.Status -eq 400) "($($r.Status))"

$bad = New-Round $t0.AddMinutes(6); $bad.totalPoints = 99
$r = Call PUT "/api/rounds/$id" $bad -Token $tokenA
Expect 'total that does not add up refused' ($r.Status -eq 400) "($($r.Status))"

$bad = New-Round $t0.AddMinutes(6)
$bad.targets[1].animals = @((Scored 0 2 'KILL' 14), (Scored 1 2 'WOUND' 10)); $bad.targets[1].points = 24; $bad.totalPoints = 68
$r = Call PUT "/api/rounds/$id" $bad -Token $tokenA
Expect 'prey scoring on an arrow it did not have refused' ($r.Status -eq 400) "($($r.Status) – predator used 2 arrows, prey only had 1)"

$bad = New-Round $t0.AddMinutes(6); $bad.targetCount = 4
$r = Call PUT "/api/rounds/$id" $bad -Token $tokenA
Expect 'missing target refused' ($r.Status -eq 400) "($($r.Status))"

$r = Call GET "/api/rounds/$id" -Token $tokenA
Expect 'refused uploads did not change the stored round' ($r.Body.totalPoints -eq 70)

# Other archers and anonymous callers.
$r = Call PUT "/api/rounds/$id" (New-Round $t0.AddMinutes(10)) -Token $tokenB
Expect "another archer can't overwrite it" ($r.Status -eq 404) "($($r.Status))"
$r = Call GET "/api/rounds/$id" -Token $tokenB
Expect "another archer can't read it" ($r.Status -eq 404) "($($r.Status))"
$r = Call GET "/api/rounds/$id"
Expect 'no token refused' ($r.Status -eq 401) "($($r.Status))"

# Delete.
$r = Call DELETE "/api/rounds/$id" -Token $tokenA
Expect 'delete' ($r.Status -eq 204) "($($r.Status))"
$r = Call GET "/api/rounds/$id" -Token $tokenA
Expect 'deleted round is kept, flagged as deleted' ($r.Status -eq 200 -and $r.Body.isDeleted) "($($r.Status))"
$r = Call GET "/api/rounds?since=$([uri]::EscapeDataString($stamp))" -Token $tokenA
$listed = @($r.Body.rounds | Where-Object roundId -eq $id)
Expect 'the deletion shows up in the changes list' ($listed.Count -eq 1 -and $listed[0].isDeleted) "($($r.Status))"

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All round checks passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) check(s) failed." -ForegroundColor Red }
