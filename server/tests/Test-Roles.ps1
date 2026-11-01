#Requires -Version 7
<#
    NFAS API – roles and admin tests (roadmap step 1). Run in PowerShell 7 (pwsh):

        .\Test-Roles.ps1 -Username pete                                  # against https://nfas.fnurble.co.uk
        .\Test-Roles.ps1 -Username pete -BaseUrl http://localhost:8085   # on Odin, straight to IIS

    -Username is the Owner account (the one set in 004_roles.sql); it asks for its password.
    Creates two throwaway accounts (zz_test_…): A is made Admin and back again, B stays a User.
    Your own account isn't changed. Every line should start with OK.
    Remove the test accounts afterwards with server/sql/900_delete_test_accounts.sql.
#>
param(
    [Parameter(Mandatory)][string]$Username,
    [string]$BaseUrl = 'https://nfas.fnurble.co.uk'
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

function New-TestUser {
    $name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'role tests' }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    [pscustomobject]@{ Name = $r.Body.username; Id = $r.Body.archerId; Token = $r.Body.token }
}

function Set-Role($Target, [string]$Role, [string]$Token) {
    Call PUT "/api/admin/archers/$Target/role" @{ role = $Role } -Token $Token
}

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
Expect 'API is version 1.4.03 or later' ([version]($r.Body.apiVersion -replace '\.0(\d)$', '.$1') -ge [version]'1.4.3') "(got $($r.Body.apiVersion) – publish the new build?)"
Expect 'database is schema version 2 or later (004_roles.sql run)' ($r.Body.schemaVersion -ge 2) "(got $($r.Body.schemaVersion))"

$password = Read-Host "Password for $Username (the Owner)" -MaskInput
$r = Call POST '/api/auth/login' @{ username = $Username; password = $password; deviceName = 'Role test script' }
if ($r.Status -ne 200) { throw "Owner sign-in failed ($($r.Status)): $($r.Body.message)" }
$owner = [pscustomobject]@{ Name = $r.Body.username; Id = $r.Body.archerId; Token = $r.Body.token }

try {
    $A = New-TestUser; $B = New-TestUser

    # --- Roles reported by /api/me ------------------------------------------------------------------
    $r = Call GET '/api/me' -Token $owner.Token
    Expect "$($owner.Name) is the Owner" ($r.Body.role -eq 'OWNER') "(got '$($r.Body.role)' – check @OwnerUsername in 004_roles.sql)"
    $r = Call GET '/api/me' -Token $A.Token
    Expect 'a new account is a User' ($r.Body.role -eq 'USER') "(got '$($r.Body.role)')"

    # --- Users can't use admin calls ----------------------------------------------------------------
    $r = Call GET '/api/admin/archers' -Token $A.Token
    Expect 'a User cannot list accounts' ($r.Status -eq 403 -and $r.Body.error -eq 'not_admin') "($($r.Status) $($r.Body.error))"
    $r = Set-Role $B.Id 'ADMIN' $A.Token
    Expect 'a User cannot make an Admin' ($r.Status -eq 403 -and $r.Body.error -eq 'not_admin') "($($r.Status) $($r.Body.error))"
    $r = Set-Role $A.Id 'ADMIN' $A.Token
    Expect 'a User cannot make themselves Admin' ($r.Status -eq 403) "($($r.Status))"
    $r = Call GET '/api/admin/archers'
    Expect 'no token: refused' ($r.Status -eq 401) "($($r.Status))"

    # --- The Owner ----------------------------------------------------------------------------------
    $r = Call GET '/api/admin/archers' -Token $owner.Token
    $list = @($r.Body.archers)
    Expect 'the Owner can list accounts' ($r.Status -eq 200 -and $list.Count -ge 3) "($($r.Status), $($list.Count))"
    Expect 'the Owner is listed first' ($list.Count -gt 0 -and $list[0].username -eq $owner.Name -and $list[0].role -eq 'OWNER')
    Expect 'test accounts are listed as Users' (@($list | Where-Object { $_.archerId -in @($A.Id, $B.Id) -and $_.role -eq 'USER' }).Count -eq 2)
    Expect 'only one Owner listed' (@($list | Where-Object role -eq 'OWNER').Count -eq 1)

    $r = Set-Role $A.Id 'admin' $owner.Token
    Expect 'the Owner makes A an Admin' ($r.Status -eq 200 -and $r.Body.role -eq 'ADMIN') "($($r.Status) $($r.Body.message))"
    $r = Call GET '/api/me' -Token $A.Token
    Expect 'A now sees role Admin (same token, no sign-in needed)' ($r.Body.role -eq 'ADMIN') "(got '$($r.Body.role)')"
    $r = Set-Role $A.Id 'ADMIN' $owner.Token
    Expect 'making A Admin again is harmless' ($r.Status -eq 200 -and $r.Body.role -eq 'ADMIN') "($($r.Status))"

    $r = Set-Role $B.Id 'OWNER' $owner.Token
    Expect 'nobody can be made a second Owner' ($r.Status -eq 400 -and $r.Body.error -eq 'invalid_role') "($($r.Status) $($r.Body.error))"
    $r = Set-Role $B.Id 'BOSS' $owner.Token
    Expect 'unknown role refused' ($r.Status -eq 400) "($($r.Status))"
    $r = Set-Role $owner.Id 'USER' $owner.Token
    Expect "the Owner's own role can't be changed" ($r.Status -eq 409 -and $r.Body.error -eq 'cannot_change_owner') "($($r.Status) $($r.Body.error))"
    $r = Set-Role ([guid]::NewGuid()) 'ADMIN' $owner.Token
    Expect 'unknown account: not found' ($r.Status -eq 404 -and $r.Body.error -eq 'no_such_archer') "($($r.Status) $($r.Body.error))"

    # --- Admins -------------------------------------------------------------------------------------
    $r = Call GET '/api/admin/archers' -Token $A.Token
    Expect 'an Admin can list accounts' ($r.Status -eq 200 -and @($r.Body.archers).Count -ge 3) "($($r.Status))"
    $r = Set-Role $B.Id 'ADMIN' $A.Token
    Expect 'an Admin cannot make another Admin (Owner only)' ($r.Status -eq 403 -and $r.Body.error -eq 'not_owner') "($($r.Status) $($r.Body.error))"
    $r = Set-Role $owner.Id 'USER' $A.Token
    Expect 'an Admin cannot change the Owner' ($r.Status -eq 403) "($($r.Status))"
    $r = Call GET '/api/me' -Token $B.Token
    Expect 'B is still a User' ($r.Body.role -eq 'USER') "(got '$($r.Body.role)')"

    # --- Removing Admin -----------------------------------------------------------------------------
    $r = Set-Role $A.Id 'USER' $owner.Token
    Expect 'the Owner removes Admin from A' ($r.Status -eq 200 -and $r.Body.role -eq 'USER') "($($r.Status))"
    $r = Call GET '/api/admin/archers' -Token $A.Token
    Expect 'A can no longer list accounts, straight away' ($r.Status -eq 403) "($($r.Status))"
    $r = Call GET '/api/me' -Token $owner.Token
    Expect "$($owner.Name) is still the Owner" ($r.Body.role -eq 'OWNER')
}
finally {
    $null = Call POST '/api/auth/logout' -Token $owner.Token
}

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All role tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Remove the zz_test_ accounts with server/sql/900_delete_test_accounts.sql.'
