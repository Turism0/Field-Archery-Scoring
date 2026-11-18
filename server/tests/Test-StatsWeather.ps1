#Requires -Version 7
<#
    API – round weather and "compared with others" (3.2.17). Run in PowerShell 7 (pwsh):

        .\Test-StatsWeather.ps1
        .\Test-StatsWeather.ps1 -BaseUrl http://localhost:8085

    Names a location with a map pin, uploads a finished round there dated a fortnight ago, and waits (up to 2 minutes)
    for the background job to look up its weather from Open-Meteo; then checks GET /api/rounds/weather gives it to the
    owner only. Also checks POST /api/stats/compare answers (with too few rounds it says nothing: betterThan null).
    Needs sql/016_round_weather.sql and internet access from Odin to api.open-meteo.com. Throwaway accounts (zz_test_…)
    and a zz_test location; tidy up afterwards with server/sql/900_delete_test_accounts.sql.
#>
param([string]$BaseUrl = 'https://fieldarchery.fnurble.co.uk')

$ErrorActionPreference = 'Stop'
$script:problems = 0

function Call([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null) {
    $h = @{}
    if ($Token) { $h.Authorization = "Bearer $Token" }
    $params = @{ Method = $Method; Uri = "$BaseUrl$Path"; SkipHttpErrorCheck = $true; StatusCodeVariable = 'status'; ContentType = 'application/json'; Headers = $h }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Depth 10) }
    $result = Invoke-RestMethod @params
    [pscustomobject]@{ Status = [int]$status; Body = $result }
}

function Expect([string]$What, [bool]$Ok, $Detail = '') {
    if ($Ok) { Write-Host "OK: $What" -ForegroundColor Green }
    else     { Write-Host "PROBLEM: $What $Detail" -ForegroundColor Red; $script:problems++ }
}

function New-Account {
    $name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'weather tests' }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    $r.Body.token
}

Write-Host "Testing $BaseUrl`n"
$me = New-Account
$other = New-Account

$name = 'zz_test weather ' + (Get-Random -Maximum 99999)
$loc = Call POST '/api/locations' @{ name = $name; latitude = 53.2; longitude = -1.07 } -Token $me
Expect 'name a location with a map pin' ($loc.Status -eq 201) "($($loc.Status) $($loc.Body.message))"

$date = (Get-Date).AddDays(-14).ToString('yyyy-MM-dd')
$created = (Get-Date).AddDays(-14).Date.AddHours(10).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
$updated = (Get-Date).AddDays(-14).Date.AddHours(13).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
$id = [guid]::NewGuid()
$round = @{ roundType = 'BIG_GAME'; shootDate = $date; targetCount = 1; archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'LB'
    status = 'COMPLETED'; totalPoints = 20; clientCreatedUtc = $created; clientUpdatedUtc = $updated; locationId = $loc.Body.locationId
    targets = @(@{ targetNo = 1; type = 'STANDARD'; overridePoints = $null; points = 20; animals = @(@{ index = 0; state = 'SCORED'; arrowNo = 1; hit = 'KILL'; points = 20 }) }) }
$r = Call PUT "/api/rounds/$id" $round -Token $me
Expect 'upload a finished round there' ($r.Status -eq 200) "($($r.Status) $($r.Body.message))"

$found = $null
for ($i = 0; $i -lt 24 -and -not $found; $i++) {
    Start-Sleep -Seconds 5
    $r = Call GET '/api/rounds/weather' -Token $me
    $found = @($r.Body.weather | Where-Object { "$($_.roundId)" -eq "$id" -and $_.weather })[0]
}
Expect 'its weather is looked up (from Open-Meteo)' ($null -ne $found) '(nothing after 2 minutes: is Odin able to reach api.open-meteo.com? Check the Application event log)'
if ($found) {
    Write-Host "   $($found.weather | ConvertTo-Json -Compress)"
    Expect 'weather has temperature and wind' ($null -ne $found.weather.tempC -and $null -ne $found.weather.windKph)
}
$r = Call GET '/api/rounds/weather' -Token $other
Expect "another archer doesn't get it" ($r.Status -eq 200 -and @($r.Body.weather | Where-Object { "$($_.roundId)" -eq "$id" }).Count -eq 0) "($($r.Status))"
$r = Call GET '/api/rounds/weather'
Expect 'needs a sign-in' ($r.Status -eq 401) "($($r.Status))"

$r = Call POST '/api/stats/compare' @{ groups = @(@{ roundType = 'BIG_GAME'; bowStyle = 'LB'; archerClass = 'G'; ageGroup = 'ADULT'; average = 18.5 }) } -Token $other
Expect 'compare answers for each group' ($r.Status -eq 200 -and @($r.Body.groups).Count -eq 1) "($($r.Status))"
Expect 'compare: rounds is a number' ($null -ne $r.Body.groups[0].rounds)

foreach ($t in $me, $other) { $null = Call POST '/api/auth/logout' -Token $t }
Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All weather and compare checks passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red; exit 1 }
