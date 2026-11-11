#Requires -Version 7
<#
    Loads historical rounds from a scorecard CSV into one archer's account through the API (3.0.01, Pete).
    Run in PowerShell 7 (pwsh). Needs API 3.0 and schema 10 (014_location_no_pin.sql) for name-only locations.

        .\Import-HistoricalRounds.ps1 -CsvPath .\archery_scores_in_memory.csv -Username Turismo            # dry run
        .\Import-HistoricalRounds.ps1 -CsvPath .\archery_scores_in_memory.csv -Username Turismo -Apply     # for real

    Without -Apply nothing is written: it signs in, reads your rounds and locations, and lists what it would do.

    The CSV (one row per scorecard): File, Shoot_Date, Archer, Host_Club, Class, Bow_Style, Round_Type, Target_Count,
    Recorded_Total, ..., Target_1 ... Target_40 (each target's points).

    - Only rows for -Archer (default "Pete Bullock") are loaded; the files in $Skip are left out.
    - Each round's id is made from its file name, so running it again changes nothing (the API says "unchanged").
    - A round you already have with the same date and total (e.g. scored in the app) is left alone.
    - Big Game points become an arrow and hit, as on the desktop score sheet: 24 inner kill, 20 kill, 16 wound on
      arrow 1; 14 kill, 10 wound on arrow 2; 8 kill, 4 wound on arrow 3; 0 a miss.
    - A scorecard with any other target score (e.g. 30, 34, 40, 44: two animals, split unknown) is left out (Pete,
      3.0.02): it isn't an ordinary Big Game round we can record shot by shot.
    - Locations: the club name, or "<name> <year>" for the NFAS championships (a different ground every year).
      An existing location with that name (any case) is used; otherwise a public name-only location (no map pin) is
      made. Use $LocationNames to point a CSV club at a location you already have under another name.

    Tidy-up if needed: every round it loads is listed with its id; delete one in the app or with DELETE /api/rounds/{id}.
#>
param(
    [Parameter(Mandatory)][string]$CsvPath,
    [Parameter(Mandatory)][string]$Username,
    [string]$Archer = 'Pete Bullock',
    [string]$BaseUrl = 'https://fieldarchery.fnurble.co.uk',
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'

# Scorecards left out (Pete, 11 Nov 2026).
$Skip = @{
    'PXL_20250622_133250404.jpg' = 'a second photo of the 21 June 2025 Druids card (same scores)'
    '20170803_165238.jpg'        = "Elmtree 2017-08-03: 15/25/30/35 aren't Big Game scores"
    '20170729_173431.jpg'        = 'Woodsman 2017-07-29: 35/40/45 are more than the 30 a Woodsman target allows'
}

# A different location for a scorecard (Pete: June 2019 was Druids, not the 3D Championships).
$LocationForFile = @{
    '20190622_162109.jpg' = 'Druids Field Archers'
    '20190623_155930.jpg' = 'Druids Field Archers'
}

# CSV club name → the name of a location you already have, if they differ. Fill in after a dry run if needed.
$LocationNames = @{
    # 'Severn Valley Yeoman Foresters' = 'SVYF'
}

# Championships are named with their year: a different ground each year.
$PerYear = @('NFAS National Championships', 'NFAS 3D Championships')

$Arrows = @{ 24 = @(1, 'INNER_KILL'); 20 = @(1, 'KILL'); 16 = @(1, 'WOUND'); 14 = @(2, 'KILL'); 10 = @(2, 'WOUND'); 8 = @(3, 'KILL'); 4 = @(3, 'WOUND') }
$Bows = 'AFB', 'BB', 'BH', 'CL', 'XB', 'TXB', 'FS', 'HT', 'LB', 'PV', 'TD', 'TB', 'UL'

function Call([string]$Method, [string]$Path, $Body = $null) {
    $params = @{
        Method = $Method; Uri = "$BaseUrl$Path"; SkipHttpErrorCheck = $true
        StatusCodeVariable = 'status'; ContentType = 'application/json'
    }
    if ($null -ne $Body) { $params.Body = ($Body | ConvertTo-Json -Depth 10) }
    if ($script:token) { $params.Headers = @{ Authorization = "Bearer $script:token" } }
    $result = Invoke-RestMethod @params
    [pscustomobject]@{ Status = [int]$status; Body = $result }
}

# The same id every run for the same scorecard.
function RoundIdFor([string]$file) {
    $bytes = [System.Security.Cryptography.MD5]::HashData([Text.Encoding]::UTF8.GetBytes("fieldarchery-history:$file"))
    $bytes[6] = ($bytes[6] -band 0x0F) -bor 0x30   # version 3 (name-based)
    $bytes[8] = ($bytes[8] -band 0x3F) -bor 0x80
    [guid]::new($bytes)
}

function TargetFor([int]$no, [int]$points) {
    if ($points -eq 0) {
        return @{ targetNo = $no; type = 'STANDARD'; overridePoints = $null; points = 0
                  animals = @(@{ index = 0; state = 'MISSED'; arrowNo = $null; hit = $null; points = 0 }) }
    }
    if ($Arrows.ContainsKey($points)) {
        $a = $Arrows[$points]
        return @{ targetNo = $no; type = 'STANDARD'; overridePoints = $null; points = $points
                  animals = @(@{ index = 0; state = 'SCORED'; arrowNo = $a[0]; hit = $a[1]; points = $points }) }
    }
    throw "Target $no has $points points, which isn't a single Big Game shot."
}

# Target scores that aren't one Big Game shot (0, 4, 8, 10, 14, 16, 20, 24), as "target N: points".
function OddTargets($row) {
    $count = [int]$row.Target_Count
    @(foreach ($n in 1..$count) {
        $v = $row."Target_$n"
        if ($v -ne '' -and -not ([int][double]$v -eq 0 -or $Arrows.ContainsKey([int][double]$v))) { "target $n`: $([int][double]$v)" }
    })
}

# ---- read the CSV ----
$rows = Import-Csv -Path $CsvPath | Where-Object { $_.Archer -eq $Archer }
if (-not $rows) { throw "No rows for '$Archer' in $CsvPath." }
Write-Host "$($rows.Count) scorecards for $Archer in the CSV.`n"

# ---- sign in ----
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status))." }
if ($r.Body.schemaVersion -lt 10) { throw "The database is schema $($r.Body.schemaVersion): run 014_location_no_pin.sql and publish API 3.0 first." }
$password = Read-Host "Password for $Username" -AsSecureString
$r = Call POST '/api/auth/login' @{ username = $Username; password = (ConvertFrom-SecureString $password -AsPlainText); deviceName = 'Historical import' }
if ($r.Status -ne 200) { throw "Couldn't sign in ($($r.Status)): $($r.Body.message)" }
$script:token = $r.Body.token

# ---- what's there already ----
# Read with System.Text.Json so dates stay as the server wrote them (ConvertFrom-Json turns them into local times).
$mine = [System.Collections.Generic.List[object]]::new()
$since = $null; $afterId = $null
do {
    $q = if ($since) { "?since=$([uri]::EscapeDataString($since))&afterId=$afterId&limit=200" } else { '?limit=200' }
    $resp = Invoke-WebRequest -Uri "$BaseUrl/api/rounds$q" -Headers @{ Authorization = "Bearer $script:token" } -SkipHttpErrorCheck
    if ($resp.StatusCode -ne 200) { throw "Couldn't read your rounds ($($resp.StatusCode))." }
    $doc = [System.Text.Json.JsonDocument]::Parse($resp.Content)
    $last = $null
    foreach ($e in $doc.RootElement.GetProperty('rounds').EnumerateArray()) {
        $last = $e
        $mine.Add([pscustomobject]@{
            roundId     = $e.GetProperty('roundId').GetString()
            shootDate   = $e.GetProperty('shootDate').GetString().Substring(0, 10)
            totalPoints = $e.GetProperty('totalPoints').GetInt32()
            isDeleted   = $e.GetProperty('isDeleted').GetBoolean()
        })
    }
    $more = $doc.RootElement.GetProperty('more').GetBoolean()
    if ($last) { $since = $last.GetProperty('serverUpdatedUtc').GetString(); $afterId = $last.GetProperty('roundId').GetString() }
} while ($more)
$live = @($mine | Where-Object { -not $_.isDeleted })
Write-Host "You have $($live.Count) rounds on the server."

$known = @{}
foreach ($path in '/api/locations/map', '/api/locations/named') {
    $r = Call GET $path
    if ($r.Status -ne 200) { throw "Couldn't read $path ($($r.Status)): $($r.Body.message)" }
    foreach ($l in $r.Body.locations) { $known[$l.name.ToLowerInvariant()] = $l.locationId }
}
Write-Host "$($known.Count) shoot locations you can use.`n"

# ---- work out each round ----
$plan = foreach ($row in $rows | Sort-Object Shoot_Date, File) {
    if ($Skip.ContainsKey($row.File)) { [pscustomobject]@{ Row = $row; Action = 'skip'; Why = $Skip[$row.File] }; continue }
    $odd = OddTargets $row
    if ($row.Round_Type -eq 'Big Game' -and $odd.Count) {
        [pscustomobject]@{ Row = $row; Action = 'skip'; Why = "not one Big Game shot per target ($($odd -join ', '))" }; continue
    }
    $date = $row.Shoot_Date
    $total = [int][double]$row.Recorded_Total
    $existing = $live | Where-Object { $_.shootDate -eq $date -and $_.totalPoints -eq $total }
    $id = RoundIdFor $row.File
    if ($existing -and -not ($existing | Where-Object { $_.roundId -eq $id.ToString() })) {
        [pscustomobject]@{ Row = $row; Action = 'skip'; Why = 'you already have a round on that date with that total' }; continue
    }
    $club = if ($LocationForFile.ContainsKey($row.File)) { $LocationForFile[$row.File] } else { $row.Host_Club.Trim() }
    if ($LocationNames.ContainsKey($club)) { $club = $LocationNames[$club] }
    elseif ($PerYear -contains $club) { $club = "$club $($date.Substring(0, 4))" }
    [pscustomobject]@{ Row = $row; Action = 'load'; Id = $id; Date = $date; Total = $total; Location = $club }
}

$load = @($plan | Where-Object Action -eq 'load')
$newNames = @($load.Location | Sort-Object -Unique | Where-Object { -not $known.ContainsKey($_.ToLowerInvariant()) })

Write-Host '---- Skipped ----' -ForegroundColor Cyan
$plan | Where-Object Action -eq 'skip' | ForEach-Object { Write-Host "  $($_.Row.Shoot_Date) $($_.Row.Host_Club) ($($_.Row.File)): $($_.Why)" }
Write-Host "`n---- Locations ----" -ForegroundColor Cyan
$load.Location | Sort-Object -Unique | ForEach-Object {
    $n = @($load | Where-Object Location -eq $_).Count
    $how = if ($known.ContainsKey($_.ToLowerInvariant())) { 'existing' } else { 'NEW, name only (no map pin)' }
    Write-Host ("  {0,-45} {1,3} round(s)  {2}" -f $_, $n, $how)
}
Write-Host "`n$($load.Count) round(s) to load, $($newNames.Count) new location(s)."

if (-not $Apply) {
    Write-Host "`nDry run: nothing written. Check the locations above (a NEW one that should be an existing club goes in" -ForegroundColor Yellow
    Write-Host '$LocationNames at the top of the script), then run again with -Apply.' -ForegroundColor Yellow
    return
}

# ---- locations ----
foreach ($name in $newNames) {
    $r = Call POST '/api/locations' @{ name = $name; noPin = $true; isPrivate = $false }
    if ($r.Status -eq 201) { $known[$name.ToLowerInvariant()] = $r.Body.locationId; Write-Host "  Made '$name' (no map pin)" }
    elseif ($r.Status -eq 409 -and $r.Body.existing) { $known[$name.ToLowerInvariant()] = $r.Body.existing.locationId; Write-Host "  Using existing '$($r.Body.existing.name)'" }
    else { throw "Couldn't make location '$name' ($($r.Status)): $($r.Body.message)" }
}

# ---- rounds ----
$saved = 0; $unchanged = 0; $failed = 0
foreach ($p in $load) {
    $row = $p.Row
    $count = [int]$row.Target_Count
    $bow = $row.Bow_Style.Trim().ToUpperInvariant()
    if ($Bows -notcontains $bow) { Write-Host "PROBLEM: $($p.Date) $($row.File): unknown bow style '$bow'" -ForegroundColor Red; $failed++; continue }
    if ($row.Round_Type -ne 'Big Game') { Write-Host "PROBLEM: $($p.Date) $($row.File): only Big Game is loaded" -ForegroundColor Red; $failed++; continue }
    $targets = foreach ($n in 1..$count) { TargetFor $n ([int][double]$row."Target_$n") }
    $sum = ($targets | Measure-Object -Property points -Sum).Sum
    if ($sum -ne $p.Total) { Write-Host "PROBLEM: $($p.Date) $($row.File): targets add up to $sum, card says $($p.Total)" -ForegroundColor Red; $failed++; continue }
    $at = "$($p.Date)T12:00:00.000Z"
    $body = @{
        roundType = 'BIG_GAME'; shootDate = $p.Date; targetCount = $count
        archerClass = if ($row.Class -eq 'Ladies') { 'L' } else { 'G' }; ageGroup = 'ADULT'; bowStyle = $bow
        status = 'COMPLETED'; totalPoints = $p.Total; clientCreatedUtc = $at; clientUpdatedUtc = $at
        locationId = $known[$p.Location.ToLowerInvariant()]; targets = @($targets)
    }
    $r = Call PUT "/api/rounds/$($p.Id)" $body
    if ($r.Status -in 200, 201) {
        if ($r.Body.result -eq 'unchanged') { $unchanged++ } else { $saved++ }
        Write-Host "  OK $($p.Date) $($p.Location) $($p.Total) ($($r.Body.result), $($p.Id))"
    } else {
        $failed++
        Write-Host "PROBLEM: $($p.Date) $($row.File) ($($r.Status)): $($r.Body.message) $($r.Body.problems -join '; ')" -ForegroundColor Red
    }
}

Write-Host "`n$saved saved, $unchanged already there, $failed problem(s)." -ForegroundColor $(if ($failed) { 'Red' } else { 'Green' })
Write-Host 'Sync the app (or open the web app) to see them. Signing in made a "Historical import" device: sign it out from your account if you like.'
