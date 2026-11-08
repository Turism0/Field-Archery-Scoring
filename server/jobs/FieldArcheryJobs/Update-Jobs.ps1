#Requires -RunAsAdministrator
<#
    Builds the server jobs (FieldArcheryJobs: NFAS results and the handicap) on Odin and schedules them:
      1. dotnet publish into C:\FieldArcheryJobs (appsettings.Production.json there is left alone)
      2. checks appsettings.Production.json is there and filled in (first time: copies the example for you to fill in)
      3. runs "verify": the C# handicap fit must match the Python reference
      4. creates or updates the scheduled task "FieldArchery jobs" (weekly, Monday 03:23, as SYSTEM, one copy at a time)

    Run in Windows PowerShell 5.1 as Administrator, after copying server\jobs\FieldArcheryJobs into C:\zScripts\FieldArcheryJobs:

        C:\zScripts\FieldArcheryJobs\Update-Jobs.ps1

    Then, once, to read nfas.net and fit the first model straight away (about 10–15 minutes):

        C:\FieldArcheryJobs\FieldArcheryJobs.exe fetch
#>
param(
    [string]$Project = $PSScriptRoot,
    [string]$Output  = 'C:\FieldArcheryJobs',
    [string]$Task    = 'FieldArchery jobs'
)

$ErrorActionPreference = 'Stop'
function Ok([string]$Text)      { Write-Host "OK: $Text" -ForegroundColor Green }
function Problem([string]$Text) { Write-Host "PROBLEM: $Text" -ForegroundColor Red }

$csproj = Join-Path $Project 'FieldArcheryJobs.csproj'
if (-not (Test-Path $csproj)) { Problem "No FieldArcheryJobs.csproj in $Project. Put this script next to it (C:\zScripts\FieldArcheryJobs)."; exit 1 }
$version = ([xml](Get-Content $csproj -Raw)).Project.PropertyGroup.InformationalVersion | Where-Object { $_ } | Select-Object -First 1
Write-Host "Updating the server jobs to $version" -ForegroundColor Cyan

# --- 1. Publish (the scheduled task can't be mid-run while its files are replaced) -------------------
$existing = Get-ScheduledTask -TaskName $Task -ErrorAction SilentlyContinue
if ($existing -and $existing.State -eq 'Running') {
    Problem "The '$Task' task is running right now (probably reading nfas.net). Try again in a few minutes."
    exit 1
}
Write-Host 'Publishing (this takes a minute)...'
& dotnet publish $Project -c Release -o $Output --nologo
if ($LASTEXITCODE -ne 0) { Problem 'dotnet publish failed (see the errors above). Nothing else was changed.'; exit 1 }
Ok "published to $Output"

# --- 2. Settings (server only, never in Git) ---------------------------------------------------------
$settings = Join-Path $Output 'appsettings.Production.json'
if (-not (Test-Path $settings)) {
    Copy-Item (Join-Path $Project 'appsettings.Production.json.example') $settings
    Problem "Created $settings from the example. Fill in the password (the same as the API's) and a long random NameKeySecret, then run this script again."
    Write-Host '  A random secret:  [Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Maximum 256 }))'
    exit 1
}
$json = Get-Content $settings -Raw | ConvertFrom-Json
if ("$($json.ConnectionStrings.FieldArchery)" -match 'PUT_THE_' -or "$($json.Handicap.NameKeySecret)" -match '^PUT_A_' -or -not $json.Handicap.NameKeySecret) {
    Problem "$settings still has a placeholder: fill in the password and NameKeySecret."
    exit 1
}
Ok 'appsettings.Production.json is filled in'

# --- 3. Verify the fit against the Python reference ---------------------------------------------------
Write-Host 'Checking the handicap fit against the reference (about a minute)...'
& (Join-Path $Output 'FieldArcheryJobs.exe') verify
if ($LASTEXITCODE -ne 0) { Problem 'The handicap fit does not match the reference (see above). The scheduled task was not changed.'; exit 1 }

# --- 4. Scheduled task -----------------------------------------------------------------------------
$action   = New-ScheduledTaskAction -Execute (Join-Path $Output 'FieldArcheryJobs.exe') -Argument 'fetch' -WorkingDirectory $Output
$trigger  = New-ScheduledTaskTrigger -Weekly -DaysOfWeek Monday -At '03:23'
$settingsTask = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 2) -StartWhenAvailable
$principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
Register-ScheduledTask -TaskName $Task -Action $action -Trigger $trigger -Settings $settingsTask -Principal $principal `
    -Description 'Field Archery Scoring: weekly NFAS results check and handicap fit (Admin "Fetch now" and target changes are picked up here).' -Force | Out-Null
Ok "scheduled task '$Task' runs every Monday at 03:23 (missed runs start when Odin is next on)"

Write-Host ''
Write-Host "Done: server jobs $version installed." -ForegroundColor Green
if (-not $existing) { Write-Host "First time: run  $Output\FieldArcheryJobs.exe fetch  to read nfas.net and fit the first model now." -ForegroundColor Cyan }
