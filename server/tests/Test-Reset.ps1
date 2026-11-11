#Requires -Version 7
<#
    API – password reset with no personal details (API 2.4). Run in PowerShell 7 (pwsh):

        .\Test-Reset.ps1 -Username Turismo
        .\Test-Reset.ps1 -Username Turismo -BaseUrl http://localhost:8085

    -Username is the Owner account; it asks for its password. Makes five throwaway accounts (zz_test_…).
    Recovery codes (shown when the account is made, used once, renewed), reset codes from an Admin / the Owner, wrong
    codes counting towards the lock-out, every phone signed out after a reset. Also (2.4) friends' latest rounds for the
    handicap rating. Every line should start with OK. Tidy up afterwards with server/sql/900_delete_test_accounts.sql.
#>

param(
    [Parameter(Mandatory)][string]$Username,
    [string]$BaseUrl = 'https://fieldarchery.fnurble.co.uk',
    # 3.1.01: Run-AllTests asks once and passes it in; on its own the script asks.
    [securestring]$OwnerPassword
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
Expect 'API is version 2.4 or later' ([version]($r.Body.apiVersion -replace '\.0(\d)$', '.$1') -ge [version]'2.4') "(got $($r.Body.apiVersion) – publish the new build?)"
Expect 'database is schema version 8 or later (012 run)' ($r.Body.schemaVersion -ge 8) "(got $($r.Body.schemaVersion))"

function New-TestUser {
    $name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'reset tests' }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    [pscustomobject]@{ Name = $r.Body.username; Id = $r.Body.archerId; Token = $r.Body.token; Recovery = $r.Body.recoveryCode }
}

# --- Recovery code -------------------------------------------------------------------------------------
Write-Host "`nRecovery code"
$A = New-TestUser
Expect 'a new account comes with a recovery code (16 characters)' ($A.Recovery -cmatch '^[A-Z2-9]{4}(-[A-Z2-9]{4}){3}$') "($($A.Recovery))"
$r = Call GET '/api/me' -Token $A.Token
Expect '/api/me says there is one' ($r.Body.hasRecoveryCode -eq $true) (Why $r)
$r = Call POST '/api/auth/reset' @{ username = $A.Name; code = 'AAAA-BBBB-CCCC-DDDD'; newPassword = 'New-password-456'; deviceName = 'reset tests' }
Expect 'a wrong code is refused' ($r.Status -eq 401 -and $r.Body.error -eq 'invalid_code') (Why $r)
$typed = $A.Recovery.ToLower().Replace('-', ' ')
$r = Call POST '/api/auth/reset' @{ username = $A.Name; code = $typed; newPassword = 'short'; deviceName = 'reset tests' }
Expect 'the new password must be 8+ characters' ($r.Status -eq 400) (Why $r)
$r = Call POST '/api/auth/reset' @{ username = $A.Name; code = $typed; newPassword = 'New-password-456'; deviceName = 'reset tests' }
Expect 'the recovery code resets the password (any case, spaces fine)' ($r.Status -eq 200 -and $r.Body.token) (Why $r)
$newRecovery = $r.Body.recoveryCode; $newToken = $r.Body.token
Expect '... and gives a new recovery code' ($newRecovery -and $newRecovery -ne $A.Recovery)
$r = Call GET '/api/me' -Token $A.Token
Expect 'the old phone is signed out' ($r.Status -eq 401) (Why $r)
$r = Call POST '/api/auth/login' @{ username = $A.Name; password = 'New-password-456'; deviceName = 'reset tests' }
Expect 'the new password works' ($r.Status -eq 200) (Why $r)
$r = Call POST '/api/auth/reset' @{ username = $A.Name; code = $A.Recovery; newPassword = 'Other-password-789'; deviceName = 'reset tests' }
Expect 'the old recovery code is used up' ($r.Status -eq 401) (Why $r)
$r = Call POST '/api/auth/recovery-code' @{ password = 'wrong-password' } -Token $newToken
Expect 'a new recovery code needs the password' ($r.Status -eq 401 -and $r.Body.error -eq 'wrong_password') (Why $r)
$r = Call POST '/api/auth/recovery-code' @{ password = 'New-password-456' } -Token $newToken
Expect 'a new recovery code from Profile' ($r.Status -eq 200 -and $r.Body.recoveryCode -and $r.Body.recoveryCode -ne $newRecovery) (Why $r)
$r = Call POST '/api/auth/reset' @{ username = $A.Name; code = $newRecovery; newPassword = 'Other-password-789'; deviceName = 'reset tests' }
Expect '... and the one before stops working' ($r.Status -eq 401) (Why $r)

# Lock-out: wrong codes count like wrong passwords.
$B = New-TestUser
$last = $null
foreach ($i in 1..5) { $last = Call POST '/api/auth/reset' @{ username = $B.Name; code = 'ZZZZ-ZZZZ-ZZZZ-ZZZZ'; newPassword = 'New-password-456' } }
Expect 'five wrong codes lock the account for a while' ($last.Status -eq 423) (Why $last)
$r = Call POST '/api/auth/reset' @{ username = $B.Name; code = $B.Recovery; newPassword = 'New-password-456' }
Expect '... even the right code then waits' ($r.Status -eq 423) (Why $r)

# --- Reset codes from the Owner / an Admin ---------------------------------------------------------
Write-Host "`nReset codes"
$C = New-TestUser
$r = Call POST "/api/admin/archers/$($A.Id)/reset-code" -Token $C.Token
Expect 'a User can''t make reset codes' ($r.Status -eq 403) (Why $r)

$password = if ($OwnerPassword) { ConvertFrom-SecureString $OwnerPassword -AsPlainText } else { Read-Host "Password for $Username (the Owner)" -MaskInput }
$r = Call POST '/api/auth/login' @{ username = $Username; password = $password; deviceName = 'Reset test script' }
if ($r.Status -ne 200) { throw "Owner sign-in failed ($($r.Status)): $($r.Body.message)" }
$owner = $r.Body.token; $ownerId = $r.Body.archerId
try {
    $r = Call POST "/api/admin/archers/$ownerId/reset-code" -Token $owner
    Expect 'not for yourself' ($r.Status -eq 400) (Why $r)
    $r = Call POST "/api/admin/archers/$($C.Id)/reset-code" -Token $owner
    Expect 'the Owner makes a reset code (8 characters, 24 hours)' ($r.Status -eq 200 -and $r.Body.code -cmatch '^[A-Z2-9]{4}-[A-Z2-9]{4}$') (Why $r)
    $code = $r.Body.code
    $r = Call POST '/api/auth/reset' @{ username = $C.Name; code = $code; newPassword = 'New-password-456'; deviceName = 'reset tests' }
    Expect 'the reset code resets the password and gives a recovery code' ($r.Status -eq 200 -and $r.Body.recoveryCode) (Why $r)
    $r = Call POST '/api/auth/reset' @{ username = $C.Name; code = $code; newPassword = 'Other-password-789' }
    Expect 'a reset code works once' ($r.Status -eq 401) (Why $r)
}
finally {
    $null = Call POST '/api/auth/logout' -Token $owner
}

# --- 2.9: friends' latest rounds for their personal handicaps (2.4: rating) --------------------------
Write-Host "`nFriends' latest rounds"
$D = New-TestUser; $E = New-TestUser
$null = Call POST '/api/friends/requests' @{ username = $E.Name } -Token $D.Token
$null = Call POST "/api/friends/$($D.Id)/accept" -Token $E.Token
$now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
foreach ($i in 1..12) {
    $round = @{ shootDate = (Get-Date).AddDays(-$i).ToString('yyyy-MM-dd'); targetCount = 1; archerClass = 'L'; ageGroup = 'ADULT'; bowStyle = 'LB'
                status = 'COMPLETED'; totalPoints = 20; clientCreatedUtc = $now; clientUpdatedUtc = $now
                targets = @(@{ targetNo = 1; type = 'STANDARD'; overridePoints = $null; points = 20
                               animals = @(@{ index = 0; state = 'SCORED'; arrowNo = 1; hit = 'KILL'; points = 20 }) }) }
    $null = Call PUT "/api/rounds/$([guid]::NewGuid())" $round -Token $E.Token
}
# 2.9: one Barebow round, and one Longbow round with its only target not scored.
$round.bowStyle = 'BB'; $round.shootDate = (Get-Date).AddDays(-20).ToString('yyyy-MM-dd')
$null = Call PUT "/api/rounds/$([guid]::NewGuid())" $round -Token $E.Token
$round.bowStyle = 'LB'; $round.shootDate = (Get-Date).AddDays(-21).ToString('yyyy-MM-dd'); $round.totalPoints = 0
$round.targets = @(@{ targetNo = 1; type = 'STANDARD'; overridePoints = $null; points = 0; animals = @(@{ index = 0; state = 'PENDING'; arrowNo = $null; hit = $null; points = 0 }) })
$null = Call PUT "/api/rounds/$([guid]::NewGuid())" $round -Token $E.Token
$r = Call GET '/api/friends' -Token $D.Token
$f = @($r.Body.friends | Where-Object archerId -eq $E.Id)[0]
Expect 'a friend comes with up to 20 finished Big Game rounds per bow (13 LB + 1 BB)' (@($f.recent).Count -eq 14) "($(@($f.recent).Count))"
$x = @($f.recent | Where-Object bowStyle -eq 'LB')[0]
Expect '... with what the handicap needs' ($x.targetCount -eq 1 -and $x.targetsScored -eq 1 -and $x.archerClass -eq 'L' -and $x.totalPoints -eq 20 -and $x.ageGroup -eq 'ADULT') "($($x | ConvertTo-Json -Compress))"
$p = @($f.recent | Where-Object { $_.bowStyle -eq 'LB' -and $_.totalPoints -eq 0 })[0]
Expect '... and says when a target has no score' ($p -and $p.targetsScored -eq 0) "($($p | ConvertTo-Json -Compress))"

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All reset tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Tidy up: run server/sql/900_delete_test_accounts.sql in SSMS.'
