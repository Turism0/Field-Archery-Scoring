#Requires -Version 7
<#
    API – which app each live group member uses (API 3.2.23, schema 14). Run in PowerShell 7 (pwsh):

        .\Test-GroupApps.ps1
        .\Test-GroupApps.ps1 -BaseUrl http://localhost:8085

    The host's and joined archers' app ("android" / "web", from the X-App-Version header) is in the group, so phones can
    say who's on the web app (no Bluetooth). Guests have none; a call with no header leaves it unknown.
    Three throwaway accounts (zz_test_…). Every line should start with OK.
    Tidy up afterwards with server/sql/900_delete_test_accounts.sql.
#>
param([string]$BaseUrl = 'https://fieldarchery.fnurble.co.uk')

$ErrorActionPreference = 'Stop'
$script:problems = 0

function Call([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null, [string]$App = $null) {
    $headers = @{}
    if ($Token) { $headers.Authorization = "Bearer $Token" }
    if ($App) { $headers['X-App-Version'] = $App }
    $params = @{
        Method = $Method; Uri = "$BaseUrl$Path"; SkipHttpErrorCheck = $true
        StatusCodeVariable = 'status'; ContentType = 'application/json'; Headers = $headers
    }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Depth 10) }
    $result = Invoke-RestMethod @params
    [pscustomobject]@{ Status = [int]$status; Body = $result }
}

function Expect([string]$What, [bool]$Ok, $Detail = '') {
    if ($Ok) { Write-Host "OK: $What" -ForegroundColor Green }
    else     { Write-Host "PROBLEM: $What $Detail" -ForegroundColor Red; $script:problems++ }
}

function New-TestUser {
    $name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'group app tests' }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    [pscustomobject]@{ Name = $r.Body.username; Id = $r.Body.archerId; Token = $r.Body.token }
}

$alphabet = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'
$code = -join (1..6 | ForEach-Object { $alphabet[(Get-Random -Maximum $alphabet.Length)] })

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status))." }
Expect 'database is schema version 14 or later (018 run)' ($r.Body.schemaVersion -ge 14) "(got $($r.Body.schemaVersion))"

$H = New-TestUser; $W = New-TestUser; $N = New-TestUser
$groupId = [guid]::NewGuid().ToString()
$hostMember = @{ memberId = [guid]::NewGuid().ToString(); kind = 'HOST'; archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'LB' }
$guest = @{ memberId = [guid]::NewGuid().ToString(); kind = 'GUEST'; guestName = 'Sam'; archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'LB' }
$group = @{ code = $code; method = 'SINGLE'; roundType = 'BIG_GAME'; targetCount = 2; shootDate = (Get-Date).ToString('yyyy-MM-dd')
            status = 'OPEN'; members = @($hostMember, $guest) }

$r = Call PUT "/api/groups/$groupId" $group -Token $H.Token -App 'android/84'
Expect 'host makes the group from the Android app' ($r.Status -eq 200) "($($r.Status) $($r.Body.message))"
$r = Call POST '/api/groups/join' @{ code = $code; memberId = [guid]::NewGuid().ToString(); archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'BB' } -Token $W.Token -App 'web/3.2.23'
Expect 'one archer joins from the web app' ($r.Status -eq 200) "($($r.Status) $($r.Body.message))"
$r = Call POST '/api/groups/join' @{ code = $code; memberId = [guid]::NewGuid().ToString(); archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'BB' } -Token $N.Token
Expect 'another joins with no app header (e.g. an old app)' ($r.Status -eq 200) "($($r.Status) $($r.Body.message))"

$r = Call GET "/api/groups/$groupId" -Token $H.Token
$m = @{}; foreach ($x in $r.Body.members) { $m[[string]$x.kind + ':' + [string]($x.archerId ?? $x.name)] = $x }
Expect "the host is 'android'" ($m["HOST:$($H.Id)"].app -eq 'android') "(got '$($m["HOST:$($H.Id)"].app)')"
Expect "the web archer is 'web'" ($m["JOINED:$($W.Id)"].app -eq 'web') "(got '$($m["JOINED:$($W.Id)"].app)')"
Expect 'the archer with no header is unknown' ($null -eq $m["JOINED:$($N.Id)"].app) "(got '$($m["JOINED:$($N.Id)"].app)')"
Expect 'the guest has no app' ($null -eq $m['GUEST:Sam'].app) "(got '$($m['GUEST:Sam'].app)')"

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All group app tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Tidy up: run server/sql/900_delete_test_accounts.sql.'
