#Requires -Version 7
<#
    Runs every automatic API test in this folder, one after another, and ends with a summary. PowerShell 7 (pwsh):

        .\Run-AllTests.ps1 -Username Turismo
        .\Run-AllTests.ps1                      # skips the five that need the Owner account
        .\Run-AllTests.ps1 -BaseUrl http://localhost:8085

    -Username is the Owner (or an Admin) account: Test-Roles, Test-Locations, Test-LocationReview, Test-Handicap and Test-Reset use
    it for the admin checks and each asks for its password. Without it those five are skipped.
    Not included (they need you to look at the phone): Test-Download.ps1 and Test-FriendView.ps1.
    Every test makes throwaway zz_test_ accounts: run server/sql/900_delete_test_accounts.sql afterwards.
    The API allows 30 sign-ins a minute from one address, so it waits a minute before Test-Groups and each Owner test (about 6 minutes).
#>
param(
    [string]$BaseUrl = 'https://fieldarchery.fnurble.co.uk',
    [string]$Username
)

$plain = 'Test-Accounts', 'Test-Rounds', 'Test-Friends', 'Test-Woodsman', 'Test-LocationPage', 'Test-CourseAverages',
         'Test-Groups', 'Test-Offers', 'Test-Delete', 'Test-StartTarget'
$owner = 'Test-Roles', 'Test-Locations', 'Test-LocationReview', 'Test-Handicap', 'Test-Reset'

$results = @()
foreach ($name in $plain + $owner) {
    $path = Join-Path $PSScriptRoot "$name.ps1"
    if ($owner -contains $name -and -not $Username) {
        $results += [pscustomobject]@{ Test = $name; Ok = 0; Problems = 0; Result = 'skipped (no -Username)' }
        continue
    }
    # The API allows 30 sign-ins / new accounts a minute from one address (RateLimits:AuthPerMinute); the tests before
    # use most of that, so wait a minute before Test-Groups and each Owner test (2.4) rather than get 429 Too Many Requests.
    if ($owner -contains $name -or ($name -in @('Test-Groups', 'Test-Delete'))) {
        Write-Host "`nWaiting a minute before $name (sign-in limit)..." -ForegroundColor DarkGray
        Start-Sleep -Seconds 61
    }
    Write-Host "`n===== $name =====" -ForegroundColor Cyan
    $testParams = @{ BaseUrl = $BaseUrl }
    if ($owner -contains $name) { $testParams.Username = $Username }
    $out = @()
    $stopped = $null
    try {
        & $path @testParams *>&1 | Tee-Object -Variable out | Out-Host
    } catch {
        $stopped = $_.Exception.Message
        Write-Host "STOPPED: $stopped" -ForegroundColor Red
    }
    $lines = @($out | ForEach-Object { "$_" })
    $ok = @($lines | Where-Object { $_ -like 'OK:*' }).Count
    $bad = @($lines | Where-Object { $_ -like 'PROBLEM:*' }).Count
    $result = if ($stopped) { "stopped: $stopped" } elseif ($bad -gt 0) { 'problems' } else { 'passed' }
    $results += [pscustomobject]@{ Test = $name; Ok = $ok; Problems = $bad; Result = $result }
}

Write-Host "`n===== Summary =====" -ForegroundColor Cyan
$results | Format-Table -AutoSize | Out-Host
$failed = @($results | Where-Object { $_.Result -ne 'passed' -and $_.Result -notlike 'skipped*' })
if ($failed.Count -eq 0) { Write-Host 'Everything that ran passed.' -ForegroundColor Green }
else { Write-Host "$($failed.Count) test script(s) need a look: $(($failed | ForEach-Object Test) -join ', ')" -ForegroundColor Red }
Write-Host 'Tidy up: run server/sql/900_delete_test_accounts.sql in SSMS.'
