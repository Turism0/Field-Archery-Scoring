#Requires -RunAsAdministrator
<#
    Builds the server jobs (FieldArcheryJobs: NFAS results and the handicap) on Odin and schedules them:
      1. dotnet publish into C:\FieldArcheryJobs (appsettings.Production.json there is left alone)
      2. settings: the database connection is read from the API's appsettings.Production.json (one copy of the password);
         C:\FieldArcheryJobs\appsettings.Production.json holds only the NameKeySecret (made the first time). Both files
         are locked to Administrators, SYSTEM and the API's app pool.
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
# The database connection comes from the API's own settings file (one copy of the password); the job's file only
# holds the NameKeySecret. Both are locked so only Administrators, SYSTEM (the task) and the API's app pool can read them.
$apiSettings = 'C:\inetpub\fieldarchery-api\appsettings.Production.json'
if (-not (Test-Path $apiSettings)) { Problem "Can't find the API's settings at $apiSettings (the job takes the database connection from there)."; exit 1 }
$api = Get-Content $apiSettings -Raw | ConvertFrom-Json
if (-not ($api.ConnectionStrings.FieldArchery -or $api.ConnectionStrings.Nfas)) { Problem "$apiSettings has no FieldArchery connection string."; exit 1 }
Ok "database connection taken from the API's settings ($apiSettings)"

$settings = Join-Path $Output 'appsettings.Production.json'
$job = if (Test-Path $settings) { Get-Content $settings -Raw | ConvertFrom-Json } else { $null }
$secret = "$($job.Handicap.NameKeySecret)"
if (-not $secret -or $secret -match '^PUT_A_') {
    # A new secret, made once. Never change it afterwards: archers are matched across years by it.
    $bytes = New-Object byte[] 48
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $secret = [Convert]::ToBase64String($bytes)
    Ok "made a new NameKeySecret in $settings"
    Write-Host "  Back up $settings somewhere safe: if the secret is lost, every archer looks new and the results must be read again." -ForegroundColor Yellow
} elseif ($job.ConnectionStrings) {
    Ok "removed the copy of the database password from $settings (kept the NameKeySecret)"
} else {
    Ok "NameKeySecret kept ($settings)"
}
[ordered]@{ Handicap = [ordered]@{ NameKeySecret = $secret } } | ConvertTo-Json -Depth 5 | Set-Content $settings -Encoding UTF8

# Only Administrators (full), SYSTEM and, for the API's file, its app pool may read them. SIDs, so any Windows language works.
function Lock-File([string]$Path, [string[]]$Readers) {
    $grants = @('*S-1-5-32-544:(F)', '*S-1-5-18:(R)')
    foreach ($r in $Readers) { $grants += "${r}:(R)" }
    & icacls $Path /inheritance:r /grant:r @grants | Out-Null
    if ($LASTEXITCODE -ne 0) { Problem "Couldn't set the permissions on $Path (icacls)."; exit 1 }
}
Lock-File $settings @()
Lock-File $apiSettings @('IIS AppPool\FieldArcheryApi')
Ok 'settings files readable only by Administrators, SYSTEM and the API (IIS AppPool\FieldArcheryApi)'

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
