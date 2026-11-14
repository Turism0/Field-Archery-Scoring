#Requires -Version 7
<#
    API – the list of an account's round ids (API 3.2.09). Run in PowerShell 7 (pwsh):

        .\Test-RoundIds.ps1
        .\Test-RoundIds.ps1 -BaseUrl http://localhost:8085

    The apps check their finished rounds against GET /api/rounds/ids once a day and send any the server is missing (it
    lost data, or was restored from an older backup). Also checks the 3.2.06 security headers and the 3.2.05 version
    check (with MinVersions left at 0 / "", nothing is refused). Two throwaway accounts (zz_test_…). Every line should
    start with OK. Tidy up afterwards with server/sql/900_delete_test_accounts.sql.
#>
param([string]$BaseUrl = 'https://fieldarchery.fnurble.co.uk')

$ErrorActionPreference = 'Stop'
$script:problems = 0

function Call([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null, [hashtable]$Headers = @{}) {
    $h = @{} + $Headers
    if ($Token) { $h.Authorization = "Bearer $Token" }
    $params = @{
        Method = $Method; Uri = "$BaseUrl$Path"; SkipHttpErrorCheck = $true
        StatusCodeVariable = 'status'; ResponseHeadersVariable = 'heads'; ContentType = 'application/json'; Headers = $h
    }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Depth 10) }
    $result = Invoke-RestMethod @params
    [pscustomobject]@{ Status = [int]$status; Body = $result; Headers = $heads }
}

function Expect([string]$What, [bool]$Ok, $Detail = '') {
    if ($Ok) { Write-Host "OK: $What" -ForegroundColor Green }
    else     { Write-Host "PROBLEM: $What $Detail" -ForegroundColor Red; $script:problems++ }
}

function New-Round {
    $now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    @{ roundType = 'BIG_GAME'; shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = 1; archerClass = 'G'; ageGroup = 'ADULT'
       bowStyle = 'LB'; status = 'COMPLETED'; totalPoints = 20; clientCreatedUtc = $now; clientUpdatedUtc = $now
       targets = @(@{ targetNo = 1; type = 'STANDARD'; overridePoints = $null; points = 20
                      animals = @(@{ index = 0; state = 'SCORED'; arrowNo = 1; hit = 'KILL'; points = 20 }) }) }
}

function New-Account([string]$what) {
    $name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = $what }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    $r.Body.token
}

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status))." }

$me = New-Account 'round id tests'
$other = New-Account 'round id tests (other)'

$r = Call GET '/api/rounds/ids' -Token $me
Expect 'a new account has no round ids' ($r.Status -eq 200 -and @($r.Body.ids).Count -eq 0) "($($r.Status) $($r.Body | ConvertTo-Json -Compress))"

$a = [guid]::NewGuid(); $b = [guid]::NewGuid(); $c = [guid]::NewGuid()
foreach ($id in $a, $b) { $null = Call PUT "/api/rounds/$id" (New-Round) -Token $me }
$null = Call PUT "/api/rounds/$c" (New-Round) -Token $other
$null = Call DELETE "/api/rounds/$b" -Token $me
$r = Call GET '/api/rounds/ids' -Token $me
$ids = @($r.Body.ids) | ForEach-Object { "$_" }
Expect 'lists your rounds, deleted ones too' ($ids.Count -eq 2 -and "$a" -in $ids -and "$b" -in $ids) "($($ids -join ', '))"
Expect "never another account's" ("$c" -notin $ids)

$r = Call GET '/api/rounds/ids'
Expect 'needs a sign-in' ($r.Status -eq 401) "($($r.Status))"

# 3.2.06 headers and the 3.2.05 version check.
$r = Call GET '/health'
Expect 'security headers are sent' ($r.Headers['X-Content-Type-Options'] -eq 'nosniff' -and $r.Headers['X-Frame-Options'] -eq 'DENY' -and
    "$($r.Headers['Content-Security-Policy'])" -like "*frame-ancestors 'none'*") "($($r.Headers | ConvertTo-Json -Compress))"
$r = Call GET '/api/rounds/ids' -Token $me -Headers @{ 'X-App-Version' = 'android/1' }
Expect 'an app version header is accepted (MinVersions not raised)' ($r.Status -eq 200) "($($r.Status))"

foreach ($t in $me, $other) { $null = Call POST '/api/auth/logout' -Token $t }
Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All round id checks passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red; exit 1 }
