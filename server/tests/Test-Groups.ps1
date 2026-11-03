#Requires -Version 7
<#
    API – group scoring (API 1.9.06). Run in PowerShell 7 (pwsh):

        .\Test-Groups.ps1
        .\Test-Groups.ps1 -BaseUrl http://localhost:8085

    Groups: join by code, the four scoring methods (single scorer, double scoring, three or more scorers,
    self scoring), matching entries, leaving and closing. Five throwaway accounts (zz_test_…).
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
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'group tests' }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    [pscustomobject]@{ Name = $r.Body.username; Id = $r.Body.archerId; Token = $r.Body.token }
}

$alphabet = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'
function New-Code { -join (1..6 | ForEach-Object { $alphabet[(Get-Random -Maximum $alphabet.Length)] }) }

function New-Member([string]$Kind, $ArcherId = $null, $GuestName = $null) {
    @{ memberId = [guid]::NewGuid().ToString(); kind = $Kind; archerId = $ArcherId; guestName = $GuestName
       archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'LB' }
}

function New-Group($Code, [string]$Method, $Members, [string]$Status = 'OPEN', $Scorers = $null) {
    $g = @{ code = $Code; method = $Method; roundType = 'BIG_GAME'; targetCount = 2; shootDate = (Get-Date).ToString('yyyy-MM-dd')
            status = $Status; members = @($Members) }
    if ($null -ne $Scorers) { $g.scorers = @($Scorers) }
    $g
}

function Join-Group($Code, $User) {
    Call POST '/api/groups/join' @{ code = $Code; memberId = [guid]::NewGuid().ToString(); archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'BB' } -Token $User.Token
}

function Target([int]$No, [string]$Hit = 'KILL', [int]$Points = 20) {
    @{ targetNo = $No; type = 'STANDARD'; overridePoints = $null; points = $Points
       animals = @(@{ index = 0; state = 'SCORED'; arrowNo = 1; hit = $Hit; points = $Points }) }
}

function Entry([int]$No, $Card, $Scorer, [string]$Hit = 'KILL', [int]$Points = 20) {
    @{ targetNo = $No; cardMemberId = $Card; scorerMemberId = $Scorer; target = (Target $No $Hit $Points)
       enteredUtc = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ') }
}

function Send($GroupId, $User, [object[]]$Entries) { Call PUT "/api/groups/$GroupId/entries" @{ entries = @($Entries) } -Token $User.Token }

function MemberOf($Group, $User) { @($Group.members | Where-Object { $_.archerId -eq $User.Id })[0] }
function ScorersOf($Group, $MemberId) { @(@($Group.members | Where-Object { $_.memberId -eq $MemberId })[0].scorers) }
function CellOf($Result, $No, $Card) { @($Result.Body.results | Where-Object { $_.targetNo -eq $No -and $_.cardMemberId -eq $Card })[0] }

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status)). Is the NfasApi app pool started?" }
Expect 'API is version 1.9.06 or later' ([version]($r.Body.apiVersion -replace '\.0(\d)$', '.$1') -ge [version]'1.9.6') "(got $($r.Body.apiVersion) – publish the new build?)"
Expect 'database is schema version 6 or later (008 run)' ($r.Body.schemaVersion -ge 6) "(got $($r.Body.schemaVersion))"

$A = New-TestUser; $B = New-TestUser; $C = New-TestUser; $D = New-TestUser; $E = New-TestUser
$r = Call POST '/api/friends/requests' @{ username = $B.Name } -Token $A.Token
$r = Call POST "/api/friends/$($A.Id)/accept" -Token $B.Token
Expect 'A and B are friends' ($r.Status -eq 200) (Why $r)

# --- 1. Single scorer: host, a guest and a friend added by name; C joins on their phone -----------------
Write-Host "`nSingle scorer"
$g1 = [guid]::NewGuid(); $code1 = New-Code
$host1 = New-Member 'HOST'; $guest1 = New-Member 'GUEST' -GuestName 'Robin'; $friend1 = New-Member 'FRIEND' $B.Id
$r = Call PUT "/api/groups/$g1" (New-Group $code1 'SINGLE' @($host1, $guest1, $friend1)) -Token $A.Token
Expect 'A starts a group with a guest and friend B' ($r.Status -eq 200 -and @($r.Body.members).Count -eq 3) (Why $r)
Expect 'guest shows by name, friend by username' ((@($r.Body.members | ForEach-Object name) -join ',') -eq "$($A.Name),Robin,$($B.Name)") "($(@($r.Body.members | ForEach-Object name) -join ','))"

$r = Call PUT "/api/groups/$([guid]::NewGuid())" (New-Group $code1 'SINGLE' @((New-Member 'HOST'))) -Token $C.Token
Expect 'another group can''t use the same code' ($r.Status -eq 409 -and $r.Body.error -eq 'code_taken') (Why $r)
$r = Call PUT "/api/groups/$([guid]::NewGuid())" (New-Group (New-Code) 'SINGLE' @((New-Member 'HOST'), (New-Member 'FRIEND' $D.Id))) -Token $A.Token
Expect 'someone who isn''t a friend can''t be added by name' ($r.Status -eq 400) (Why $r)
$r = Call PUT "/api/groups/$([guid]::NewGuid())" (New-Group 'ABC10O' 'SINGLE' @((New-Member 'HOST'))) -Token $A.Token
Expect 'codes with 0, O, 1, I or L are refused' ($r.Status -eq 400) (Why $r)
$r = Call PUT "/api/groups/$([guid]::NewGuid())" (New-Group (New-Code) 'SINGLE' @((New-Member 'HOST'), (New-Member 'JOINED' $C.Id))) -Token $A.Token
Expect 'the host can''t add joined archers (they join themselves)' ($r.Status -eq 400) (Why $r)

$r = Join-Group $code1 $C
Expect 'C joins with the code' ($r.Status -eq 200 -and (MemberOf $r.Body $C).kind -eq 'JOINED' -and @($r.Body.members).Count -eq 4) (Why $r)
$r = Join-Group $code1.ToLower() $C
Expect 'joining again (lower case) doesn''t add C twice' ($r.Status -eq 200 -and @($r.Body.members).Count -eq 4) (Why $r)
$r = Join-Group (New-Code) $D
Expect 'a wrong code is not found' ($r.Status -eq 404) (Why $r)
$r = Call GET "/api/groups/$g1" -Token $D.Token
Expect 'D (not in the group) can''t see it' ($r.Status -eq 404) (Why $r)
$r = Call GET "/api/groups/$g1" -Token $C.Token
Expect 'C can see it' ($r.Status -eq 200 -and $r.Body.me.kind -eq 'JOINED') (Why $r)
$cMember1 = (MemberOf $r.Body $C).memberId

$r = Call PUT "/api/groups/$g1" (New-Group $code1 'SINGLE' @($host1, $guest1, $friend1) 'STARTED') -Token $A.Token
$allByA = @($r.Body.members | Where-Object { (@($_.scorers) -join ',') -ne $host1.memberId }).Count -eq 0
Expect 'started: A scores every card' ($r.Status -eq 200 -and $allByA) (Why $r)
$r = Call PUT "/api/groups/$g1" (New-Group $code1 'DOUBLE' @($host1, $guest1, $friend1) 'STARTED') -Token $A.Token
Expect 'the method can''t change once started' ($r.Status -eq 409) (Why $r)

$r = Send $g1 $A @((Entry 1 $host1.memberId $host1.memberId), (Entry 1 $guest1.memberId $host1.memberId 'WOUND' 16),
                   (Entry 1 $friend1.memberId $host1.memberId), (Entry 1 $cMember1 $host1.memberId 'INNER_KILL' 24))
Expect 'A scores target 1 for everyone: all saved' ($r.Status -eq 200 -and @($r.Body.results | Where-Object state -eq 'MATCHED').Count -eq 4) (Why $r)
$r = Send $g1 $C @((Entry 2 $cMember1 $cMember1))
Expect 'C can''t score in a single-scorer group' ($r.Status -eq 400) (Why $r)
$r = Send $g1 $A @((Entry 3 $host1.memberId $host1.memberId))
Expect 'target 3 of 2 is refused' ($r.Status -eq 400) (Why $r)
$r = Send $g1 $A @((Entry 2 $host1.memberId $host1.memberId 'KILL' 24))
Expect 'wrong points are refused' ($r.Status -eq 400) (Why $r)
$r = Call GET "/api/groups/$g1" -Token $C.Token
$cTotal = @($r.Body.totals | Where-Object memberId -eq $cMember1)[0]
Expect 'C sees the live totals (C: 24 after 1 target)' ($cTotal.points -eq 24 -and $cTotal.targetsDone -eq 1) "($($cTotal.points) $($cTotal.targetsDone))"
# As ISO UTC: PowerShell turns the time into a local date, and its default text form loses the milliseconds and
# can be read the wrong way round (day/month vs month/day) by the server.
$since = ([datetime]$r.Body.serverTimeUtc).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
Start-Sleep -Milliseconds 50
$r = Send $g1 $A @((Entry 2 $guest1.memberId $host1.memberId))
$r = Call GET "/api/groups/$g1`?since=$([uri]::EscapeDataString($since))" -Token $C.Token
Expect 'since= gives only the new entry' ($r.Status -eq 200 -and @($r.Body.entries).Count -eq 1 -and @($r.Body.entries)[0].targetNo -eq 2) "($(@($r.Body.entries).Count))"
$r = Call GET '/api/groups' -Token $C.Token
Expect 'C''s group list has the group' (@($r.Body.groups | Where-Object groupId -eq $g1.ToString()).Count -eq 1) (Why $r)

# --- 2. Double scoring --------------------------------------------------------------------------------
Write-Host "`nDouble scoring"
$g2 = [guid]::NewGuid(); $code2 = New-Code; $host2 = New-Member 'HOST'
$r = Call PUT "/api/groups/$g2" (New-Group $code2 'DOUBLE' @($host2)) -Token $A.Token
Expect 'A starts a double-scoring group' ($r.Status -eq 200) (Why $r)
$r = Call PUT "/api/groups/$g2" (New-Group $code2 'DOUBLE' @($host2) 'STARTED') -Token $A.Token
Expect 'can''t start double scoring with one phone' ($r.Status -eq 400 -and $r.Body.error -eq 'invalid_scorers') (Why $r)
$r = Join-Group $code2 $C; $cM = (MemberOf $r.Body $C).memberId
$r = Join-Group $code2 $D; $dM = (MemberOf $r.Body $D).memberId
$r = Call PUT "/api/groups/$g2" (New-Group $code2 'DOUBLE' @($host2) 'STARTED') -Token $A.Token
$pair = (@($host2.memberId, $cM) | Sort-Object) -join ','
$samePair = @($r.Body.members | Where-Object { ((@($_.scorers) | Sort-Object) -join ',') -ne $pair }).Count -eq 0
Expect 'started: A and C (first to join) score every card' ($r.Status -eq 200 -and $samePair) (Why $r)

$r = Send $g2 $A @((Entry 1 $dM $host2.memberId))
Expect 'one entry for D: waiting for the second scorer' ((CellOf $r 1 $dM).state -eq 'WAITING') (Why $r)
$r = Send $g2 $C @((Entry 1 $dM $cM))
Expect 'C enters the same: saved' ((CellOf $r 1 $dM).state -eq 'MATCHED' -and (CellOf $r 1 $dM).points -eq 20) (Why $r)
$r = Send $g2 $A @((Entry 1 $cM $host2.memberId))
$r = Send $g2 $C @((Entry 1 $cM $cM 'WOUND' 16))
Expect 'different entries for C''s card: mismatch' ((CellOf $r 1 $cM).state -eq 'MISMATCH') (Why $r)
Start-Sleep -Milliseconds 20
$r = Send $g2 $C @((Entry 1 $cM $cM))
Expect 'C re-enters to match: saved' ((CellOf $r 1 $cM).state -eq 'MATCHED') (Why $r)
$r = Send $g2 $D @((Entry 1 $host2.memberId $dM))
Expect 'D isn''t a scorer here' ($r.Status -eq 400) (Why $r)
$r = Send $g2 $C @((Entry 1 $host2.memberId $host2.memberId))
Expect 'C can''t send entries as A' ($r.Status -eq 400) (Why $r)

# --- 3. Three or more scorers -------------------------------------------------------------------------
Write-Host "`nThree or more scorers"
$g3 = [guid]::NewGuid(); $code3 = New-Code; $host3 = New-Member 'HOST'; $guest3 = New-Member 'GUEST' -GuestName 'Sam'
$r = Call PUT "/api/groups/$g3" (New-Group $code3 'MULTI' @($host3, $guest3)) -Token $A.Token
$r = Join-Group $code3 $C; $cM3 = (MemberOf $r.Body $C).memberId
$r = Call PUT "/api/groups/$g3" (New-Group $code3 'MULTI' @($host3, $guest3) 'STARTED') -Token $A.Token
Expect 'can''t start with two phones' ($r.Status -eq 400) (Why $r)
$r = Join-Group $code3 $D; $dM3 = (MemberOf $r.Body $D).memberId
$bad = @(@{ cardMemberId = $host3.memberId; scorerMemberId = $host3.memberId }, @{ cardMemberId = $host3.memberId; scorerMemberId = $cM3 })
$r = Call PUT "/api/groups/$g3" (New-Group $code3 'MULTI' @($host3, $guest3) 'STARTED' $bad) -Token $A.Token
Expect 'scorers that score their own card are refused' ($r.Status -eq 400 -and $r.Body.error -eq 'invalid_scorers') (Why $r)
$r = Call PUT "/api/groups/$g3" (New-Group $code3 'MULTI' @($host3, $guest3) 'STARTED') -Token $A.Token
Expect 'started with three phones' ($r.Status -eq 200) (Why $r)

function Check-Multi($Group, [string]$When) {
    $own = 0; $notTwo = 0; $load = @{}
    foreach ($m in @($Group.members)) {
        $s = @($m.scorers)
        if ($s.Count -ne 2 -or ($s | Select-Object -Unique).Count -ne 2) { $notTwo++ }
        if ($s -contains $m.memberId) { $own++ }
        foreach ($x in $s) { $load[$x] = 1 + [int]$load[$x] }
    }
    $loads = @($load.Values)
    Expect "$($When): every card has two scorers" ($notTwo -eq 0) "($notTwo cards not)"
    Expect "$($When): nobody scores their own card" ($own -eq 0) "($own cards)"
    Expect "$($When): cards shared evenly ($(($loads | Sort-Object) -join ', '))" ((($loads | Measure-Object -Maximum).Maximum - ($loads | Measure-Object -Minimum).Minimum) -le 1)
}
Check-Multi $r.Body '3 phones + guest'

$r = Join-Group $code3 $E; $eM3 = (MemberOf $r.Body $E).memberId
$r = Call GET "/api/groups/$g3" -Token $A.Token
Check-Multi $r.Body 'E joins after the start'

$cScorers = ScorersOf $r.Body $cM3
$first = @($r.Body.members | Where-Object { $_.memberId -eq $cScorers[0] })[0]
$second = @($r.Body.members | Where-Object { $_.memberId -eq $cScorers[1] })[0]
$users = @{ $host3.memberId = $A; $cM3 = $C; $dM3 = $D; $eM3 = $E }
$r = Send $g3 $users[$first.memberId] @((Entry 1 $cM3 $first.memberId))
$r = Send $g3 $users[$second.memberId] @((Entry 1 $cM3 $second.memberId))
Expect 'C''s two scorers agree: saved' ((CellOf $r 1 $cM3).state -eq 'MATCHED') (Why $r)
$r = Send $g3 $C @((Entry 1 $cM3 $cM3))
Expect 'C can''t score their own card' ($r.Status -eq 400) (Why $r)

$r = Call DELETE "/api/groups/$g3/members/$eM3" -Token $E.Token
Expect 'E leaves' ($r.Status -eq 204) (Why $r)
$r = Call GET "/api/groups/$g3" -Token $A.Token
Expect 'E is gone' (@($r.Body.members).Count -eq 4 -and -not (MemberOf $r.Body $E)) "($(@($r.Body.members).Count))"
Check-Multi $r.Body 'after E leaves'
$r = Call DELETE "/api/groups/$g3/members/$cM3" -Token $D.Token
Expect 'D can''t remove C' ($r.Status -eq 404) (Why $r)
$r = Call DELETE "/api/groups/$g3/members/$($host3.memberId)" -Token $A.Token
Expect 'the host can''t leave' ($r.Status -eq 409) (Why $r)

# --- 4. Self scoring ----------------------------------------------------------------------------------
Write-Host "`nSelf scoring"
$g4 = [guid]::NewGuid(); $code4 = New-Code; $host4 = New-Member 'HOST'; $guest4 = New-Member 'GUEST' -GuestName 'Alex'
$r = Call PUT "/api/groups/$g4" (New-Group $code4 'SELF' @($host4, $guest4)) -Token $A.Token
$r = Join-Group $code4 $C; $cM4 = (MemberOf $r.Body $C).memberId
$r = Call PUT "/api/groups/$g4" (New-Group $code4 'SELF' @($host4, $guest4) 'STARTED') -Token $A.Token
# @(...) keeps a single scorer as a list: PowerShell unwraps one-item lists, and [0] on a string is its first letter.
$cBy = @(ScorersOf $r.Body $cM4)[0]; $guestBy = @(ScorersOf $r.Body $guest4.memberId)[0]
Expect 'started: C scores C, A scores the guest' ($r.Status -eq 200 -and $cBy -eq $cM4 -and $guestBy -eq $host4.memberId) "($($r.Status) C by $cBy, guest by $guestBy)"
$r = Send $g4 $C @((Entry 1 $cM4 $cM4))
Expect 'C''s own score is saved straight away' ((CellOf $r 1 $cM4).state -eq 'MATCHED') (Why $r)

# --- 5. A friend added by name joins on their own phone; closing --------------------------------------
Write-Host "`nJoining as a friend, closing"
$r = Join-Group $code1 $B
Expect 'friend B joins group 1: now a joined member, same member id' ($r.Status -eq 200 -and (MemberOf $r.Body $B).kind -eq 'JOINED' -and (MemberOf $r.Body $B).memberId -eq $friend1.memberId) (Why $r)
$r = Call PUT "/api/groups/$g1" (New-Group $code1 'SINGLE' @($host1, $guest1) 'STARTED') -Token $A.Token
Expect 'the host''s update keeps B (joined) and the guest' ($r.Status -eq 200 -and @($r.Body.members).Count -eq 4) (Why $r)
$r = Call PUT "/api/groups/$g1" (New-Group $code1 'SINGLE' @($host1) 'STARTED') -Token $A.Token
Expect 'the host removes the guest' ($r.Status -eq 200 -and @($r.Body.members | Where-Object kind -eq 'GUEST').Count -eq 0) (Why $r)
$r = Call PUT "/api/groups/$g1" (New-Group $code1 'SINGLE' @($host1) 'CLOSED') -Token $A.Token
Expect 'A closes group 1' ($r.Status -eq 200 -and $r.Body.status -eq 'CLOSED') (Why $r)
$r = Join-Group $code1 $D
Expect 'the code no longer works' ($r.Status -eq 404) (Why $r)
$r = Call PUT "/api/groups/$g1" (New-Group $code1 'SINGLE' @($host1) 'STARTED') -Token $A.Token
Expect 'a closed group stays closed' ($r.Status -eq 409) (Why $r)
$r = Call PUT "/api/groups/$g1" (New-Group $code1 'SINGLE' @((New-Member 'HOST')) 'OPEN') -Token $C.Token
Expect 'only the host can change a group' ($r.Status -eq 404) (Why $r)

# --- 6. A member's own round with the group id -------------------------------------------------------
$now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
$round = @{ shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = 1; archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'BB'
            status = 'IN_PROGRESS'; totalPoints = 24; clientCreatedUtc = $now; clientUpdatedUtc = $now; groupId = $g2.ToString()
            targets = @((Target 1 'INNER_KILL' 24)) }
$r = Call PUT "/api/rounds/$([guid]::NewGuid())" $round -Token $C.Token
Expect 'C uploads their group round as they go (in progress, with the group id)' ($r.Status -eq 200) (Why $r)

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All group tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Tidy up: run server/sql/900_delete_test_accounts.sql in SSMS.'
