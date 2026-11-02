#Requires -Version 7
<#
    NFAS API – step 3 account tests. Run in PowerShell 7 (pwsh) from any PC:

        .\Test-Accounts.ps1                                   # against https://fieldarchery.fnurble.co.uk
        .\Test-Accounts.ps1 -BaseUrl http://localhost:8085    # on Odin, straight to IIS

    Creates a throwaway account named zz_test_xxxxxx. Remove test accounts afterwards with
    server/sql/900_delete_test_accounts.sql. Every line should start with OK.
#>
param([string]$BaseUrl = 'https://fieldarchery.fnurble.co.uk')

$ErrorActionPreference = 'Stop'
$script:problems = 0

function Call([string]$Method, [string]$Path, $Body = $null, [string]$Token = $null) {
    $params = @{
        Method = $Method; Uri = "$BaseUrl$Path"; SkipHttpErrorCheck = $true
        StatusCodeVariable = 'status'; ContentType = 'application/json'
    }
    if ($Body)  { $params.Body = ($Body | ConvertTo-Json) }
    if ($Token) { $params.Headers = @{ Authorization = "Bearer $Token" } }
    $result = Invoke-RestMethod @params
    [pscustomobject]@{ Status = [int]$status; Body = $result }
}

function Expect([string]$What, [bool]$Ok, $Detail = '') {
    if ($Ok) { Write-Host "OK: $What" -ForegroundColor Green }
    else     { Write-Host "PROBLEM: $What $Detail" -ForegroundColor Red; $script:problems++ }
}

$user = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
$password = 'Test-password-123'
Write-Host "Testing $BaseUrl with user $user`n"

$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status)). Is the NfasApi app pool started? (server\api\NfasApi\Update-Api.ps1 starts it)" }
Expect 'API healthy, database reachable' ($r.Status -eq 200 -and $r.Body.database -eq 'ok') "($($r.Status))"
Expect 'API is version 1.2.03 or later' ([version]($r.Body.apiVersion -replace '\.0(\d)$', '.$1') -ge [version]'1.2.3') "(got $($r.Body.apiVersion) – publish the new build?)"

$r = Call POST '/api/auth/check' @{ username = $user }
Expect 'new username does not exist yet' ($r.Status -eq 200 -and -not $r.Body.exists) "($($r.Status))"

$r = Call POST '/api/auth/check' @{ username = 'a!' }
Expect 'invalid username rejected' ($r.Status -eq 400 -and $r.Body.error -eq 'invalid_username') "($($r.Status))"

$r = Call POST '/api/auth/register' @{ username = $user; password = 'short'; deviceName = 'test' }
Expect 'short password rejected' ($r.Status -eq 400 -and $r.Body.error -eq 'invalid_password') "($($r.Status))"

$r = Call POST '/api/auth/register' @{ username = $user; password = $password; deviceName = 'test phone 1' }
Expect 'register creates the account and returns a token' ($r.Status -eq 201 -and $r.Body.token.Length -eq 64) "($($r.Status))"
$token1 = $r.Body.token

$r = Call POST '/api/auth/check' @{ username = $user.ToUpper() }
Expect 'username now exists (any capitals)' ($r.Status -eq 200 -and $r.Body.exists -and $r.Body.username -eq $user) "($($r.Status))"

$r = Call POST '/api/auth/register' @{ username = $user.ToUpper(); password = $password }
Expect 'registering the same username again is refused' ($r.Status -eq 409 -and $r.Body.error -eq 'username_taken') "($($r.Status))"

$r = Call POST '/api/auth/login' @{ username = $user; password = 'wrong-password' }
Expect 'wrong password refused' ($r.Status -eq 401 -and $r.Body.error -eq 'invalid_credentials') "($($r.Status))"

$r = Call POST '/api/auth/login' @{ username = $user; password = $password; deviceName = 'test phone 2' }
Expect 'correct password signs in with a new token' ($r.Status -eq 200 -and $r.Body.token -ne $token1) "($($r.Status))"
$token2 = $r.Body.token

$r = Call GET '/api/me' -Token $token2
Expect '/api/me with the token returns the account' ($r.Status -eq 200 -and $r.Body.username -eq $user) "($($r.Status))"

$r = Call GET '/api/me'
Expect '/api/me without a token is refused' ($r.Status -eq 401) "($($r.Status))"

$r = Call POST '/api/auth/logout' -Token $token2
Expect 'sign out' ($r.Status -eq 204) "($($r.Status))"

$r = Call GET '/api/me' -Token $token2
Expect 'signed-out token no longer works' ($r.Status -eq 401) "($($r.Status))"

$r = Call GET '/api/me' -Token $token1
Expect "the other phone's token still works" ($r.Status -eq 200) "($($r.Status))"

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All account checks passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) check(s) failed." -ForegroundColor Red }
