#Requires -RunAsAdministrator
<#
    Rebuilds and restarts the NFAS API on Odin in one go:
      1. stops the NfasApi app pool (so its files can be replaced)
      2. dotnet publish into C:\inetpub\nfas-api (appsettings.Production.json there is left alone)
      3. starts the app pool again, even if the publish failed (so the old version keeps running)
      4. checks /health answers with the version in NfasApi.csproj and the database is reachable

    Run in Windows PowerShell 5.1 as Administrator, after copying the new files into C:\zScripts\NfasApi:

        C:\zScripts\NfasApi\Update-Api.ps1

    Any problem is shown in red and the script ends with an error.
#>
param(
    [string]$Project   = $PSScriptRoot,
    [string]$Pool      = 'NfasApi',
    [string]$Output    = 'C:\inetpub\nfas-api',
    [string]$HealthUrl = 'http://localhost:8085/health'
)

$ErrorActionPreference = 'Stop'

function Ok([string]$Text)      { Write-Host "OK: $Text" -ForegroundColor Green }
function Problem([string]$Text) { Write-Host "PROBLEM: $Text" -ForegroundColor Red }

if ($PSVersionTable.PSEdition -eq 'Core') {
    Problem 'Run this in Windows PowerShell 5.1 (not PowerShell 7): the IIS commands only work there.'
    exit 1
}

$csproj = Join-Path $Project 'NfasApi.csproj'
if (-not (Test-Path $csproj)) {
    Problem "No NfasApi.csproj in $Project. Put this script next to it (C:\zScripts\NfasApi)."
    exit 1
}
$expected = ([xml](Get-Content $csproj -Raw)).Project.PropertyGroup.InformationalVersion | Where-Object { $_ } | Select-Object -First 1
Write-Host "Updating the NFAS API to $expected" -ForegroundColor Cyan

Import-Module WebAdministration

function Wait-Pool([string]$State) {
    for ($i = 0; $i -lt 30; $i++) {
        if ((Get-WebAppPoolState $Pool).Value -eq $State) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

# --- 1. Stop -----------------------------------------------------------------------------------------
if ((Get-WebAppPoolState $Pool).Value -ne 'Stopped') {
    Stop-WebAppPool $Pool
    if (Wait-Pool 'Stopped') { Ok "app pool $Pool stopped" }
    else { Problem "app pool $Pool didn't stop within 30 seconds"; exit 1 }
} else {
    Ok "app pool $Pool was already stopped"
}

# --- 2. Publish --------------------------------------------------------------------------------------
Write-Host 'Publishing (this takes a minute)...'
& dotnet publish $Project -c Release -o $Output --nologo
$published = ($LASTEXITCODE -eq 0)
if ($published) { Ok "published to $Output" }
else { Problem "dotnet publish failed (see the errors above). Starting the previous version again." }

# --- 3. Start (always) -------------------------------------------------------------------------------
Start-WebAppPool $Pool
if (Wait-Pool 'Started') { Ok "app pool $Pool started" }
else { Problem "app pool $Pool didn't start. Check Event Viewer > Windows Logs > Application."; exit 1 }

if (-not $published) { exit 1 }

# --- 4. Health check (the first request also wakes the API up) ---------------------------------------
$health = $null
for ($i = 0; $i -lt 10 -and -not $health; $i++) {
    try { $health = Invoke-RestMethod $HealthUrl -TimeoutSec 15 } catch { Start-Sleep -Seconds 2 }
}
if (-not $health) {
    Problem "$HealthUrl isn't answering. Check Event Viewer > Windows Logs > Application (IIS AspNetCore Module / .NET Runtime)."
    exit 1
}
if ($health.apiVersion -eq $expected) { Ok "API is running version $($health.apiVersion)" }
else { Problem "API reports version '$($health.apiVersion)', expected '$expected'. Were the new files copied in?"; exit 1 }
if ($health.database -eq 'ok') { Ok "database reachable (schema version $($health.schemaVersion))" }
else { Problem "database is '$($health.database)'. Check appsettings.Production.json and that Mimir is up."; exit 1 }

Write-Host ''
Write-Host "Done: NFAS API $expected is live." -ForegroundColor Green
