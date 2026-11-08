#Requires -Version 7
<#
    API – handicap (server 2.2.01). Run in PowerShell 7 (pwsh):

        .\Test-Handicap.ps1 -Username Turismo
        .\Test-Handicap.ps1 -Username Turismo -BaseUrl http://localhost:8085

    -Username is the Owner account; it asks for its password. Makes one throwaway account (zz_test_…).
    Checks the public handicap download (after the job has fitted a model) and that only Admins / the Owner can use
    the Admin handicap calls. Doesn't change any target count or model. Every line should start with OK.
    Tidy up afterwards with server/sql/900_delete_test_accounts.sql.
#>
param(
    [Parameter(Mandatory)][string]$Username,
    [string]$BaseUrl = 'https://fieldarchery.fnurble.co.uk'
)

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

function Why($r) { "($($r.Status) $($r.Body.error) $($r.Body.message)$($r.Body.title))" }

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status)). Is the FieldArcheryApi app pool started?" }
Expect 'API is version 2.2.01 or later' ([version]($r.Body.apiVersion -replace '\.0(\d)$', '.$1') -ge [version]'2.2.1') "(got $($r.Body.apiVersion) – publish the new build?)"
Expect 'database is schema version 7 or later (011 run)' ($r.Body.schemaVersion -ge 7) "(got $($r.Body.schemaVersion))"

# --- The download the app uses (no sign-in needed) ------------------------------------------------
$r = Call GET '/api/handicap'
if ($r.Status -eq 404 -and $r.Body.error -eq 'no_handicap') {
    Write-Host 'PROBLEM: no handicap model yet – run  C:\FieldArcheryJobs\FieldArcheryJobs.exe fetch  on Odin first (10–15 minutes), then this again.' -ForegroundColor Red
    $script:problems++
} else {
    Expect 'handicap download works without signing in' ($r.Status -eq 200) (Why $r)
    $f = @($r.Body.factors)
    $bows = @($f | Where-Object kind -eq 'BOW')
    Expect "it has factors for 10+ bow styles ($($bows.Count))" ($bows.Count -ge 10)
    Expect 'Unlimited is the reference (no factor of its own)' (-not ($bows | Where-Object code -eq 'UL'))
    $lb = $bows | Where-Object code -eq 'LB'
    Expect 'Longbow scores below Unlimited (negative factor)' ($lb -and $lb.value -lt 0) "($($lb.value))"
    Expect 'Ladies class factor present' (@($f | Where-Object { $_.kind -eq 'CLASS' -and $_.code -eq 'F-ADULT' }).Count -eq 1)
    Expect 'factors have 90% ranges' (@($f | Where-Object { $null -ne $_.low -and $null -ne $_.high }).Count -ge $bows.Count)
    Expect "data runs to $($r.Body.dataThroughYear)" ($r.Body.dataThroughYear -ge 2025)
}

# --- Admin calls ---------------------------------------------------------------------------------
$name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
$u = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'handicap tests' }
if ($u.Status -ne 201) { throw "Couldn't create test user ($($u.Status)): $($u.Body.message)" }
$user = $u.Body.token

$r = Call GET '/api/admin/handicap' -Token $user
Expect 'a User can''t see Admin → Handicap' ($r.Status -eq 403) (Why $r)
$r = Call PUT '/api/admin/handicap/events/NATIONALS/2019' @{ targetsPerDay = 36 } -Token $user
Expect 'a User can''t set a target count' ($r.Status -eq 403) (Why $r)
$r = Call POST '/api/admin/handicap/fetch' -Token $user
Expect 'a User can''t ask for a fetch' ($r.Status -eq 403) (Why $r)
$r = Call GET '/api/admin/handicap'
Expect 'not signed in: 401' ($r.Status -eq 401) (Why $r)

$password = Read-Host "Password for $Username (the Owner)" -MaskInput
$r = Call POST '/api/auth/login' @{ username = $Username; password = $password; deviceName = 'Handicap test script' }
if ($r.Status -ne 200) { throw "Owner sign-in failed ($($r.Status)): $($r.Body.message)" }
$owner = $r.Body.token
try {
    $r = Call GET '/api/admin/handicap' -Token $owner
    Expect 'Owner sees Admin → Handicap' ($r.Status -eq 200) (Why $r)
    $events = @($r.Body.events)
    Expect "championships stored ($($events.Count))" ($events.Count -ge 40) '(has the job read nfas.net yet?)'
    Expect 'every championship with results has a target count' (@($events | Where-Object { $_.status -eq 'RESULTS' -and -not $_.targetsPerDay }).Count -eq 0)
    $n19 = $events | Where-Object { $_.champs -eq 'NATIONALS' -and $_.year -eq 2019 }
    Expect 'Nationals 2019 is 40 targets (before Covid)' ($n19 -and $n19.targetsPerDay -eq 40) "($($n19.targetsPerDay) $($n19.targetsSource))"
    Expect 'Nationals 2020 shows no results' (($events | Where-Object { $_.champs -eq 'NATIONALS' -and $_.year -eq 2020 }).status -eq 'NONE')
    Expect 'models listed' (@($r.Body.models).Count -ge 1) '(has the job fitted one yet?)'
    Expect 'exactly one active model' (@($r.Body.models | Where-Object status -eq 'ACTIVE').Count -eq 1)
    Expect 'job runs listed' (@($r.Body.runs).Count -ge 1)

    $r = Call PUT '/api/admin/handicap/events/NATIONALS/2019' @{ targetsPerDay = 38 } -Token $owner
    Expect 'target count must be 36 or 40' ($r.Status -eq 400 -and $r.Body.error -eq 'invalid_targets') (Why $r)
    $r = Call PUT '/api/admin/handicap/events/NATIONALS/1999' @{ targetsPerDay = 40 } -Token $owner
    Expect 'unknown championship: 404' ($r.Status -eq 404 -and $r.Body.error -eq 'no_such_event') (Why $r)
    $r = Call POST '/api/admin/handicap/models/999999/activate' -Token $owner
    Expect 'unknown model: 404' ($r.Status -eq 404) (Why $r)
}
finally {
    $null = Call POST '/api/auth/logout' -Token $owner
}

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All handicap tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Tidy up: run server/sql/900_delete_test_accounts.sql in SSMS.'
