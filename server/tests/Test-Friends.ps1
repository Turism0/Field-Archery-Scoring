#Requires -Version 7
<#
    NFAS API – step 7a friends tests. Run in PowerShell 7 (pwsh):

        .\Test-Friends.ps1                                   # against https://nfas.fnurble.co.uk
        .\Test-Friends.ps1 -BaseUrl http://localhost:8085    # on Odin, straight to IIS

    Creates three throwaway accounts (zz_test_…): A shoots rounds, B becomes A's friend, C doesn't.
    Remove them afterwards with server/sql/900_delete_test_accounts.sql. Every line should start with OK.
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
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'friend tests' }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    [pscustomobject]@{ Name = $r.Body.username; Id = $r.Body.archerId; Token = $r.Body.token }
}

function Scored($index, $arrow, $hit, $points) { @{ index = $index; state = 'SCORED'; arrowNo = $arrow; hit = $hit; points = $points } }

# A 2-target round: standard kill 1st arrow (20) + predator/prey 20 + prey wound 2nd (10) = 50 over 3 animals.
function New-Round([string]$Status, [int]$FirstPoints = 20) {
    $first = if ($FirstPoints -eq 24) { Scored 0 1 'INNER_KILL' 24 } else { Scored 0 1 'KILL' 20 }
    $now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    @{
        shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = 2
        archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'LB'; status = $Status; totalPoints = $FirstPoints + 30
        clientCreatedUtc = $now; clientUpdatedUtc = $now
        targets = @(
            @{ targetNo = 1; type = 'STANDARD'; overridePoints = $null; points = $FirstPoints; animals = @($first) }
            @{ targetNo = 2; type = 'PREDATOR_PREY'; overridePoints = $null; points = 30
               animals = @((Scored 0 1 'KILL' 20), (Scored 1 2 'WOUND' 10)) }
        )
    }
}

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status)). Is the NfasApi app pool started? (server\api\NfasApi\Update-Api.ps1 starts it)" }
Expect 'API is version 1.3.01 or later' ([version]($r.Body.apiVersion -replace '\.0(\d)$', '.$1') -ge [version]'1.3.1') "(got $($r.Body.apiVersion) – publish the new build?)"

$A = New-TestUser; $B = New-TestUser; $C = New-TestUser

# A's rounds: two finished (50 and 54), one in progress, one finished then deleted.
$done1 = [guid]::NewGuid(); $done2 = [guid]::NewGuid(); $open = [guid]::NewGuid(); $gone = [guid]::NewGuid()
$null = Call PUT "/api/rounds/$done1" (New-Round 'COMPLETED' 20) -Token $A.Token
$null = Call PUT "/api/rounds/$done2" (New-Round 'COMPLETED' 24) -Token $A.Token
$null = Call PUT "/api/rounds/$open"  (New-Round 'IN_PROGRESS' 20) -Token $A.Token
$null = Call PUT "/api/rounds/$gone"  (New-Round 'COMPLETED' 20) -Token $A.Token
$null = Call DELETE "/api/rounds/$gone" -Token $A.Token

# Requests that must be refused.
$r = Call POST '/api/friends/requests' @{ username = 'zz_nobody_here' } -Token $A.Token
Expect 'request to an unknown username refused' ($r.Status -eq 404 -and $r.Body.error -eq 'no_such_user') "($($r.Status))"
$r = Call POST '/api/friends/requests' @{ username = $A.Name } -Token $A.Token
Expect "can't friend yourself" ($r.Status -eq 400 -and $r.Body.error -eq 'cannot_friend_self') "($($r.Status))"
$r = Call GET "/api/friends/$($A.Id)/rounds" -Token $B.Token
Expect "B can't see A's rounds before being friends" ($r.Status -eq 404) "($($r.Status))"

# A asks B.
$r = Call POST '/api/friends/requests' @{ username = $B.Name.ToUpper() } -Token $A.Token
Expect 'A sends B a request (any capitals)' ($r.Status -eq 201 -and $r.Body.result -eq 'requested') "($($r.Status) $($r.Body.message))"
$r = Call POST '/api/friends/requests' @{ username = $B.Name } -Token $A.Token
Expect 'asking twice is refused' ($r.Status -eq 409 -and $r.Body.error -eq 'already_requested') "($($r.Status))"
$r = Call GET '/api/friends' -Token $A.Token
Expect "A sees an outgoing request to B" (@($r.Body.outgoing | Where-Object username -eq $B.Name).Count -eq 1)
$r = Call GET '/api/friends' -Token $B.Token
Expect "B sees an incoming request from A" (@($r.Body.incoming | Where-Object username -eq $A.Name).Count -eq 1)
$r = Call GET "/api/friends/$($A.Id)/rounds" -Token $B.Token
Expect "B still can't see A's rounds while pending" ($r.Status -eq 404) "($($r.Status))"

# Only B can accept.
$r = Call POST "/api/friends/$($B.Id)/accept" -Token $A.Token
Expect "A can't accept their own request" ($r.Status -eq 404) "($($r.Status))"
$r = Call POST "/api/friends/$($A.Id)/accept" -Token $C.Token
Expect "C can't accept a request that wasn't sent to them" ($r.Status -eq 404) "($($r.Status))"
$r = Call POST "/api/friends/$($A.Id)/accept" -Token $B.Token
Expect 'B accepts' ($r.Status -eq 200) "($($r.Status))"

$r = Call GET '/api/friends' -Token $B.Token
$friendA = @($r.Body.friends | Where-Object username -eq $A.Name)
Expect 'B now has A as a friend' ($friendA.Count -eq 1 -and @($r.Body.incoming).Count -eq 0)
Expect "A's summary: 2 finished rounds, best 54" ($friendA[0].rounds -eq 2 -and $friendA[0].bestTotal -eq 54) "(rounds $($friendA[0].rounds), best $($friendA[0].bestTotal))"
Expect "A's average per animal is 17.3 (104 / 6)" ([math]::Abs($friendA[0].averagePerAnimal - 17.3) -lt 0.05) "(got $($friendA[0].averagePerAnimal))"
$r = Call GET '/api/friends' -Token $A.Token
Expect 'and A has B (friendship works both ways)' (@($r.Body.friends | Where-Object username -eq $B.Name).Count -eq 1)

# What B can see of A.
$r = Call GET "/api/friends/$($A.Id)/rounds" -Token $B.Token
$ids = @($r.Body.rounds | ForEach-Object roundId)
Expect "B sees A's 2 finished rounds" ($r.Status -eq 200 -and $ids.Count -eq 2) "($($r.Status), $($ids.Count) rounds)"
Expect "but not A's in-progress or deleted rounds" (($ids -notcontains $open.ToString()) -and ($ids -notcontains $gone.ToString()))
$r = Call GET "/api/friends/$($A.Id)/rounds/$done1" -Token $B.Token
Expect "B can open one of A's scorecards" ($r.Status -eq 200 -and $r.Body.totalPoints -eq 50 -and $r.Body.targets.Count -eq 2) "($($r.Status))"
$r = Call GET "/api/friends/$($A.Id)/rounds/$open" -Token $B.Token
Expect "B can't open A's in-progress round" ($r.Status -eq 404) "($($r.Status))"
$r = Call PUT "/api/rounds/$done1" (New-Round 'COMPLETED' 24) -Token $B.Token
Expect "B can't change A's round (read-only)" ($r.Status -eq 404) "($($r.Status))"
$r = Call GET "/api/friends/$($A.Id)/rounds" -Token $C.Token
Expect "C (not a friend) can't see A's rounds" ($r.Status -eq 404) "($($r.Status))"

# Asking back accepts straight away.
$r = Call POST '/api/friends/requests' @{ username = $A.Name } -Token $C.Token
$r = Call POST '/api/friends/requests' @{ username = $C.Name } -Token $A.Token
Expect 'if they already asked you, asking back makes you friends' ($r.Status -eq 200 -and $r.Body.result -eq 'accepted') "($($r.Status))"
$r = Call POST '/api/friends/requests' @{ username = $C.Name } -Token $A.Token
Expect 'asking an existing friend is refused' ($r.Status -eq 409 -and $r.Body.error -eq 'already_friends') "($($r.Status))"

# Unfriend.
$r = Call DELETE "/api/friends/$($A.Id)" -Token $B.Token
Expect 'B removes A' ($r.Status -eq 204) "($($r.Status))"
$r = Call GET "/api/friends/$($A.Id)/rounds" -Token $B.Token
Expect "B can no longer see A's rounds" ($r.Status -eq 404) "($($r.Status))"
$r = Call GET '/api/friends' -Token $A.Token
Expect 'A no longer has B as a friend' (@($r.Body.friends | Where-Object username -eq $B.Name).Count -eq 0)

$r = Call GET '/api/friends'
Expect 'no token refused' ($r.Status -eq 401) "($($r.Status))"

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All friend checks passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) check(s) failed." -ForegroundColor Red }
