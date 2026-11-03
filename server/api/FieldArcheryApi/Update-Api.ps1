#Requires -RunAsAdministrator
<#
    Rebuilds and restarts the API on Odin in one go:
      1. stops the FieldArcheryApi app pool (so its files can be replaced)
      2. dotnet publish into C:\inetpub\fieldarchery-api (appsettings.Production.json there is left alone)
      3. starts the app pool again, even if the publish failed (so the old version keeps running)
      4. checks /health answers with the version in FieldArcheryApi.csproj and the database is reachable

    Run in Windows PowerShell 5.1 as Administrator, after copying the new files into C:\zScripts\FieldArcheryApi:

        C:\zScripts\FieldArcheryApi\Update-Api.ps1

    Any problem is shown in red and the script ends with an error.
#>
param(
    [string]$Project   = $PSScriptRoot,
    [string]$Pool      = 'FieldArcheryApi',
    [string]$Output    = 'C:\inetpub\fieldarchery-api',
    [string]$HealthUrl = 'http://localhost:8085/health'
)

$ErrorActionPreference = 'Stop'

function Ok([string]$Text)      { Write-Host "OK: $Text" -ForegroundColor Green }
function Problem([string]$Text) { Write-Host "PROBLEM: $Text" -ForegroundColor Red }

# Calls /health. Windows PowerShell 5.1 treats any HTTP error as no answer, so keep the status and reply:
# a 503 means the API is running but can't reach the database.
function Get-Health([string]$Url) {
    try {
        $reply = Invoke-WebRequest $Url -UseBasicParsing -TimeoutSec 15
        return [pscustomobject]@{ Status = [int]$reply.StatusCode; Body = ($reply.Content | ConvertFrom-Json) }
    } catch {
        $resp = $_.Exception.Response
        if (-not $resp) { return $null }   # nothing answered at all
        $body = $null
        try { $body = $_.ErrorDetails.Message | ConvertFrom-Json } catch { }
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $body }
    }
}

if ($PSVersionTable.PSEdition -eq 'Core') {
    Problem 'Run this in Windows PowerShell 5.1 (not PowerShell 7): the IIS commands only work there.'
    exit 1
}

$csproj = Join-Path $Project 'FieldArcheryApi.csproj'
# The old project file next to the new one would make dotnet publish refuse (two projects) or build the old code too.
if (Test-Path (Join-Path $Project 'NfasApi.csproj')) {
    Problem "NfasApi.csproj is still in $Project. Start from an empty folder: copy server\api\FieldArcheryApi into it (see server\README.md)."
    exit 1
}
if (-not (Test-Path $csproj)) {
    Problem "No FieldArcheryApi.csproj in $Project. Put this script next to it (C:\zScripts\FieldArcheryApi)."
    exit 1
}
$expected = ([xml](Get-Content $csproj -Raw)).Project.PropertyGroup.InformationalVersion | Where-Object { $_ } | Select-Object -First 1
Write-Host "Updating the API to $expected" -ForegroundColor Cyan

Import-Module WebAdministration

if (-not (Test-Path "IIS:\AppPools\$Pool")) {
    Problem "There's no app pool called $Pool. If IIS still has the old NfasApi names, run Rename-Iis.ps1 first (once)."
    exit 1
}

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
    $health = Get-Health $HealthUrl
    if (-not $health) { Start-Sleep -Seconds 2 }
}
if (-not $health) {
    Problem "$HealthUrl isn't answering. Check Event Viewer > Windows Logs > Application (IIS AspNetCore Module / .NET Runtime)."
    exit 1
}
if ($health.Body.apiVersion -eq $expected) { Ok "API is running version $($health.Body.apiVersion)" }
else { Problem "API reports version '$($health.Body.apiVersion)', expected '$expected'. Were the new files copied in?"; exit 1 }
if ($health.Status -eq 200 -and $health.Body.database -eq 'ok') { Ok "database reachable (schema version $($health.Body.schemaVersion))" }
else {
    Problem "the API can't reach the database (HTTP $($health.Status)). Check the login and password in appsettings.Production.json, and that Mimir is up. Event Viewer > Application has SQL Server's message."
    exit 1
}

Write-Host ''
Write-Host "Done: Field Archery Scoring API $expected is live." -ForegroundColor Green
