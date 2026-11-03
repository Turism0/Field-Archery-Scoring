#Requires -RunAsAdministrator
<#
    One-off: moves the API in IIS on Odin from the old NFAS names to the Field Archery Scoring names.

      app pool  NfasApi              ->  FieldArcheryApi   (new pool, same settings)
      site      NfasApi              ->  FieldArcheryApi   (renamed, same port 8085, so Nginx Proxy Manager is unchanged)
      folder    C:\inetpub\nfas-api  ->  C:\inetpub\fieldarchery-api   (copied, with appsettings.Production.json)

    The old app pool and folder are left in place (stopped / unused) so you can go back if anything is wrong.
    Delete them once the new ones have run for a while:
        Remove-WebAppPool NfasApi
        Remove-Item C:\inetpub\nfas-api -Recurse

    Run in Windows PowerShell 5.1 as Administrator:
        C:\zScripts\FieldArcheryApi\Rename-Iis.ps1
    Safe to run again. Any problem is shown in red and the script stops.
#>
param(
    [string]$OldPool   = 'NfasApi',
    [string]$NewPool   = 'FieldArcheryApi',
    [string]$OldSite   = 'NfasApi',
    [string]$NewSite   = 'FieldArcheryApi',
    [string]$OldFolder = 'C:\inetpub\nfas-api',
    [string]$NewFolder = 'C:\inetpub\fieldarchery-api',
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
Import-Module WebAdministration

# --- 1. New app pool with the old one's settings -----------------------------------------------------
if (-not (Test-Path "IIS:\AppPools\$NewPool")) {
    if (-not (Test-Path "IIS:\AppPools\$OldPool")) { Problem "Neither app pool $OldPool nor $NewPool exists."; exit 1 }
    $old = Get-Item "IIS:\AppPools\$OldPool"
    New-WebAppPool -Name $NewPool | Out-Null
    $p = "IIS:\AppPools\$NewPool"
    Set-ItemProperty $p -Name managedRuntimeVersion -Value ''            # No Managed Code (.NET 10 runs itself)
    Set-ItemProperty $p -Name managedPipelineMode -Value $old.managedPipelineMode
    Set-ItemProperty $p -Name autoStart -Value $old.autoStart
    Set-ItemProperty $p -Name startMode -Value $old.startMode
    Set-ItemProperty $p -Name processModel.identityType -Value $old.processModel.identityType
    Set-ItemProperty $p -Name processModel.idleTimeout -Value $old.processModel.idleTimeout
    Set-ItemProperty $p -Name recycling.periodicRestart.time -Value $old.recycling.periodicRestart.time
    Ok "app pool $NewPool made (settings copied from $OldPool)"
} else {
    Ok "app pool $NewPool already exists"
}

# --- 2. Stop the site, copy the folder ---------------------------------------------------------------
$site = if (Test-Path "IIS:\Sites\$NewSite") { $NewSite } elseif (Test-Path "IIS:\Sites\$OldSite") { $OldSite } else { $null }
if (-not $site) { Problem "Neither site $OldSite nor $NewSite exists."; exit 1 }
if ((Get-WebsiteState $site).Value -ne 'Stopped') { Stop-Website $site; Ok "site $site stopped" }
if ((Test-Path "IIS:\AppPools\$OldPool") -and (Get-WebAppPoolState $OldPool).Value -ne 'Stopped') {
    Stop-WebAppPool $OldPool
    for ($i = 0; $i -lt 30 -and (Get-WebAppPoolState $OldPool).Value -ne 'Stopped'; $i++) { Start-Sleep -Seconds 1 }
    Ok "app pool $OldPool stopped"
}

if (-not (Test-Path $NewFolder)) {
    if (-not (Test-Path $OldFolder)) { Problem "Neither $OldFolder nor $NewFolder exists."; exit 1 }
    Copy-Item $OldFolder $NewFolder -Recurse
    Ok "copied $OldFolder to $NewFolder"
} else {
    Ok "$NewFolder already exists (not copied again)"
}
if (-not (Test-Path (Join-Path $NewFolder 'appsettings.Production.json'))) {
    Problem "No appsettings.Production.json in $NewFolder. Copy it from $OldFolder, then run this again."
    exit 1
}
# The new pool's own account needs to read the folder.
& icacls $NewFolder /grant "IIS AppPool\$($NewPool):(OI)(CI)RX" /T /Q | Out-Null
if ($LASTEXITCODE -eq 0) { Ok "IIS AppPool\$NewPool can read $NewFolder" }
else { Problem "couldn't give IIS AppPool\$NewPool read access to $NewFolder"; exit 1 }

# --- 3. Point the site at the new pool and folder, rename it, start --------------------------------
Set-ItemProperty "IIS:\Sites\$site" -Name applicationPool -Value $NewPool
Set-ItemProperty "IIS:\Sites\$site" -Name physicalPath -Value $NewFolder
if ($site -ne $NewSite) {
    Rename-Item "IIS:\Sites\$site" $NewSite
    $site = $NewSite
    Ok "site renamed to $NewSite"
}
Ok "site $site uses app pool $NewPool and $NewFolder"

Start-WebAppPool $NewPool -ErrorAction SilentlyContinue
Start-Website $site
Ok "site $site started"

# --- 4. Health check ------------------------------------------------------------------------------------
$health = $null
for ($i = 0; $i -lt 10 -and -not $health; $i++) {
    $health = Get-Health $HealthUrl
    if (-not $health) { Start-Sleep -Seconds 2 }
}
$back = "To go back: Set-ItemProperty IIS:\Sites\$site -Name applicationPool -Value $OldPool; Set-ItemProperty IIS:\Sites\$site -Name physicalPath -Value $OldFolder; Start-WebAppPool $OldPool"
if (-not $health) {
    Problem "$HealthUrl isn't answering. Check Event Viewer > Windows Logs > Application."
    Write-Host $back
    exit 1
}
if ($health.Status -ne 200 -or $health.Body.database -ne 'ok') {
    Problem "the API is running in the new pool but can't reach the database (HTTP $($health.Status)). Check the login and password in $NewFolder\appsettings.Production.json; Event Viewer > Application has SQL Server's message."
    Write-Host $back
    exit 1
}
Ok "API $($health.Body.apiVersion) answering, database ok"

Write-Host ''
Write-Host "Done. Update-Api.ps1 now uses $NewPool and $NewFolder. Old pool $OldPool and folder $OldFolder are left for now." -ForegroundColor Green
