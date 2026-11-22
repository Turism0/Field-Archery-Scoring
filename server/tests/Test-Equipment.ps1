#Requires -Version 7
<#
    API – equipment (API 3.2.24, schema 15). Run in PowerShell 7 (pwsh):

        .\Test-Equipment.ps1
        .\Test-Equipment.ps1 -BaseUrl http://localhost:8085

    Bows and arrows are kept with the account (the later copy wins, deleting keeps them flagged), only ever returned to
    their archer, and a round's bow and arrows are only returned to the archer who owns it (not to friends). A round sent
    without them (older apps) keeps what's stored.
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
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'equipment tests' }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    [pscustomobject]@{ Name = $r.Body.username; Id = $r.Body.archerId; Token = $r.Body.token }
}

function Stamp([datetime]$t) { $t.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ') }

function New-Bow([datetime]$Updated, [string]$Make = 'Border', [bool]$Deleted = $false) {
    @{ type = 'LONGBOW'; make = $Make; model = 'Hex 7'; poundageLb = 45.5; bowStyles = @('LB', 'HT')
       isDeleted = $Deleted; clientUpdatedUtc = (Stamp $Updated) }
}

function New-Arrows([string[]]$BowIds, [datetime]$Updated, [string]$Spine = '45-50') {
    @{ make = 'Port Orford'; model = 'Cedar'; lengthIn = 29.5; arrowGrains = 480; spine = $Spine; fletchCount = 3
       fletchMaterial = 'FEATHER'; fletchLengthIn = 5; fletchColours = 'barred grey, white cock'; pointGrains = 125
       bowIds = @($BowIds); isDeleted = $false; clientUpdatedUtc = (Stamp $Updated) }
}

# A finished 3-target Big Game round. $Bow/$Arrow = 'NOT SENT' leaves the field out.
function New-Round($Bow, $Arrow, [datetime]$Updated = [datetime]::UtcNow) {
    $targets = 1..3 | ForEach-Object {
        @{ targetNo = $_; type = 'STANDARD'; overridePoints = $null; points = 20
           animals = @(@{ index = 0; state = 'SCORED'; arrowNo = 1; hit = 'KILL'; points = 20 }) }
    }
    $round = @{ roundType = 'BIG_GAME'; shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = 3; archerClass = 'G'
                ageGroup = 'ADULT'; bowStyle = 'LB'; status = 'COMPLETED'; totalPoints = 60; clientCreatedUtc = '2026-11-22T09:00:00.000Z'
                clientUpdatedUtc = (Stamp $Updated); targets = @($targets) }
    if ($Bow -ne 'NOT SENT') { $round.bowId = $Bow }
    if ($Arrow -ne 'NOT SENT') { $round.arrowId = $Arrow }
    $round
}

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status))." }
Expect 'database is schema version 15 or later (019 run)' ($r.Body.schemaVersion -ge 15) "(got $($r.Body.schemaVersion))"

$A = New-TestUser; $B = New-TestUser
$t = [datetime]::UtcNow
$bow = [guid]::NewGuid().ToString(); $arrows = [guid]::NewGuid().ToString()

$r = Call GET '/api/equipment' -Token $A.Token
Expect 'a new account has no equipment' ($r.Status -eq 200 -and @($r.Body.bows).Count -eq 0 -and @($r.Body.arrows).Count -eq 0) "($($r.Status))"
$r = Call GET '/api/equipment'
Expect 'needs a sign-in' ($r.Status -eq 401) "($($r.Status))"

$r = Call PUT "/api/equipment/bows/$bow" (New-Bow $t) -Token $A.Token
Expect 'a bow is saved' ($r.Status -eq 200 -and $r.Body.result -eq 'saved') "($($r.Status) $($r.Body.message))"
$r = Call PUT "/api/equipment/arrows/$arrows" (New-Arrows @($bow) $t) -Token $A.Token
Expect 'arrows for it are saved' ($r.Status -eq 200 -and $r.Body.result -eq 'saved') "($($r.Status) $($r.Body.message))"

$r = Call GET '/api/equipment' -Token $A.Token
$b = @($r.Body.bows)[0]; $a = @($r.Body.arrows)[0]
Expect 'the bow comes back as sent' ($b.bowId -eq $bow -and $b.type -eq 'LONGBOW' -and $b.make -eq 'Border' -and $b.poundageLb -eq 45.5 -and (@($b.bowStyles) -join ',') -eq 'LB,HT') ($b | ConvertTo-Json -Compress)
Expect 'the arrows come back as sent' ($a.arrowId -eq $arrows -and $a.spine -eq '45-50' -and $a.fletchCount -eq 3 -and $a.fletchMaterial -eq 'FEATHER' -and $a.pointGrains -eq 125 -and (@($a.bowIds) -join ',') -eq $bow) ($a | ConvertTo-Json -Compress)

$r = Call PUT "/api/equipment/bows/$bow" (New-Bow $t.AddMinutes(-1) 'Older') -Token $A.Token
Expect 'an older copy is ignored' ($r.Body.result -eq 'unchanged') "($($r.Body.result))"
$r = Call PUT "/api/equipment/bows/$bow" (New-Bow $t.AddMinutes(1) 'Newer') -Token $A.Token
$r = Call GET '/api/equipment' -Token $A.Token
Expect 'a newer edit is saved' (@($r.Body.bows)[0].make -eq 'Newer') "(got $(@($r.Body.bows)[0].make))"

$r = Call GET '/api/equipment' -Token $B.Token
Expect "another archer doesn't get them" (@($r.Body.bows).Count -eq 0 -and @($r.Body.arrows).Count -eq 0)
$r = Call PUT "/api/equipment/bows/$bow" (New-Bow $t.AddMinutes(5) 'Stolen') -Token $B.Token
Expect "another archer can't change them" ($r.Status -eq 404) "($($r.Status))"

$bad = New-Bow $t; $bad.bowStyles = @()
$r = Call PUT "/api/equipment/bows/$([guid]::NewGuid())" $bad -Token $A.Token
Expect 'a bow with no bow style is refused' ($r.Status -eq 400) "($($r.Status))"
$bad = New-Bow $t; $bad.type = 'SLINGSHOT'
$r = Call PUT "/api/equipment/bows/$([guid]::NewGuid())" $bad -Token $A.Token
Expect 'an unknown bow type is refused' ($r.Status -eq 400) "($($r.Status))"
$bad = New-Bow $t; $bad.make = ''; $bad.model = ' '
$r = Call PUT "/api/equipment/bows/$([guid]::NewGuid())" $bad -Token $A.Token
Expect 'a bow with no make or model is refused' ($r.Status -eq 400) "($($r.Status))"
foreach ($s in '500', '2213', '45-50') {
    $r = Call PUT "/api/equipment/arrows/$([guid]::NewGuid())" (New-Arrows @($bow) $t $s) -Token $A.Token
    Expect "spine $s is fine" ($r.Status -eq 200) "($($r.Status) $($r.Body.message))"
}
$r = Call PUT "/api/equipment/arrows/$([guid]::NewGuid())" (New-Arrows @($bow) $t 'stiff') -Token $A.Token
Expect "spine 'stiff' is refused" ($r.Status -eq 400) "($($r.Status))"
$r = Call PUT "/api/equipment/arrows/$([guid]::NewGuid())" (New-Arrows @() $t) -Token $A.Token
Expect 'arrows with no bow are refused' ($r.Status -eq 400) "($($r.Status))"

# Rounds: the bow and arrows are the archer's own.
$id = [guid]::NewGuid()
$r = Call PUT "/api/rounds/$id" (New-Round $bow $arrows $t) -Token $A.Token
Expect 'a round with a bow and arrows is saved' ($r.Status -eq 200) "($($r.Status) $($r.Body.message))"
$r = Call GET "/api/rounds/$id" -Token $A.Token
Expect 'and comes back to its archer with them' ($r.Body.bowId -eq $bow -and $r.Body.arrowId -eq $arrows) "(got $($r.Body.bowId) / $($r.Body.arrowId))"
$r = Call PUT "/api/rounds/$id" (New-Round 'NOT SENT' 'NOT SENT' $t.AddMinutes(1)) -Token $A.Token
$r = Call GET "/api/rounds/$id" -Token $A.Token
Expect 'a change from an older app (none sent) keeps them' ($r.Body.bowId -eq $bow -and $r.Body.arrowId -eq $arrows) "(got $($r.Body.bowId) / $($r.Body.arrowId))"

$null = Call POST '/api/friends/requests' @{ username = $B.Name } -Token $A.Token
$null = Call POST "/api/friends/$($A.Id)/accept" -Token $B.Token
$r = Call GET "/api/friends/$($A.Id)/rounds/$id" -Token $B.Token
Expect 'a friend can see the round' ($r.Status -eq 200) "($($r.Status))"
Expect 'but not the bow or arrows' ($null -eq $r.Body.bowId -and $null -eq $r.Body.arrowId) "(got $($r.Body.bowId) / $($r.Body.arrowId))"

$none = [guid]::Empty.ToString()
$r = Call PUT "/api/rounds/$id" (New-Round $none $none $t.AddMinutes(2)) -Token $A.Token
$r = Call GET "/api/rounds/$id" -Token $A.Token
Expect 'an empty id clears them' ($null -eq $r.Body.bowId -and $null -eq $r.Body.arrowId) "(got $($r.Body.bowId) / $($r.Body.arrowId))"

# Deleting keeps them, flagged.
$r = Call PUT "/api/equipment/bows/$bow" (New-Bow $t.AddMinutes(3) 'Newer' $true) -Token $A.Token
$r = Call GET '/api/equipment' -Token $A.Token
$b = @($r.Body.bows) | Where-Object bowId -eq $bow
Expect 'a deleted bow is kept, flagged as deleted' ($b.isDeleted -eq $true -and $b.make -eq 'Newer') ($b | ConvertTo-Json -Compress)

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All equipment tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Tidy up: run server/sql/900_delete_test_accounts.sql.'
exit $script:problems
