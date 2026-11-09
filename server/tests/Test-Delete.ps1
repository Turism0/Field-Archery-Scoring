#Requires -Version 7
<#
    API – deleting an account, and the privacy policy page (API 2.4.02, for Google Play). PowerShell 7 (pwsh):

        .\Test-Delete.ps1
        .\Test-Delete.ps1 -BaseUrl http://localhost:8085

    Deleting in the app (password needed) and on the web page, what goes with the account (rounds, friends, groups,
    private locations), and that the privacy policy and delete pages are there. Makes throwaway zz_test_ accounts.
    Every line should start with OK. Tidy up afterwards with server/sql/900_delete_test_accounts.sql.
#>
param([string]$BaseUrl = 'https://fieldarchery.fnurble.co.uk')

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

function Why($r) { "($($r.Status) $($r.Body.error) $($r.Body.message)$($r.Body.title) $($r.Body.detail))" }

function New-TestUser {
    $name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'group tests' }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    [pscustomobject]@{ Name = $r.Body.username; Id = $r.Body.archerId; Token = $r.Body.token }
}

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status)). Is the FieldArcheryApi app pool started?" }
Expect 'API is version 2.4.02 or later' ([version]($r.Body.apiVersion -replace '\.0(\d)$', '.$1') -ge [version]'2.4.2') "(got $($r.Body.apiVersion) – publish the new build?)"

$A = New-TestUser; $B = New-TestUser
$null = Call POST '/api/friends/requests' @{ username = $B.Name } -Token $A.Token
$null = Call POST "/api/friends/$($A.Id)/accept" -Token $B.Token
$now = [datetime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
$round = @{ shootDate = (Get-Date).ToString('yyyy-MM-dd'); targetCount = 1; archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'LB'
            status = 'COMPLETED'; totalPoints = 20; clientCreatedUtc = $now; clientUpdatedUtc = $now
            targets = @(@{ targetNo = 1; type = 'STANDARD'; overridePoints = $null; points = 20
                           animals = @(@{ index = 0; state = 'SCORED'; arrowNo = 1; hit = 'KILL'; points = 20 }) }) }
$roundId = [guid]::NewGuid()
$null = Call PUT "/api/rounds/$roundId" $round -Token $A.Token
# A in B's live group.
$alphabet = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'
$code = -join (1..6 | ForEach-Object { $alphabet[(Get-Random -Maximum $alphabet.Length)] })
$g = [guid]::NewGuid()
$hostB = @{ memberId = [guid]::NewGuid().ToString(); kind = 'HOST'; archerId = $B.Id; guestName = $null; archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'BB' }
$null = Call PUT "/api/groups/$g" @{ code = $code; method = 'SINGLE'; roundType = 'BIG_GAME'; targetCount = 2; shootDate = (Get-Date).ToString('yyyy-MM-dd'); status = 'OPEN'; members = @($hostB) } -Token $B.Token
$r = Call POST '/api/groups/join' @{ code = $code; memberId = [guid]::NewGuid().ToString(); archerClass = 'G'; ageGroup = 'ADULT'; bowStyle = 'LB' } -Token $A.Token
Expect 'A is in B''s group' ($r.Status -eq 200) (Why $r)

# --- In the app -------------------------------------------------------------------------------------
$r = Call POST '/api/me/delete' @{ password = 'wrong-password' } -Token $A.Token
Expect 'deleting needs the password' ($r.Status -eq 401) (Why $r)
$r = Call POST '/api/me/delete' @{ password = 'Test-password-123' } -Token $A.Token
Expect 'A deletes their account' ($r.Status -eq 204) (Why $r)
$r = Call POST '/api/auth/login' @{ username = $A.Name; password = 'Test-password-123'; deviceName = 'delete tests' }
Expect 'A can no longer sign in' ($r.Status -eq 401) (Why $r)
$r = Call GET '/api/me' -Token $A.Token
Expect 'A''s phone is signed out' ($r.Status -eq 401) (Why $r)
$r = Call POST '/api/auth/check' @{ username = $A.Name }
Expect 'the username is free again' ($r.Status -eq 200 -and -not $r.Body.exists) (Why $r)
$r = Call GET '/api/friends' -Token $B.Token
Expect 'A is gone from B''s friends' (@($r.Body.friends | Where-Object archerId -eq $A.Id).Count -eq 0) (Why $r)
$r = Call GET "/api/groups/$g" -Token $B.Token
Expect 'A is gone from B''s group' ($r.Status -eq 200 -and @($r.Body.members | Where-Object archerId -eq $A.Id).Count -eq 0) (Why $r)

# --- On the web page ------------------------------------------------------------------------------
$C = New-TestUser
$page = Invoke-WebRequest -Uri "$BaseUrl/delete-account" -SkipHttpErrorCheck
Expect 'the delete-account page is there' ($page.StatusCode -eq 200 -and $page.Content -match 'Delete your account') "($($page.StatusCode))"
$bad = Invoke-WebRequest -Uri "$BaseUrl/delete-account" -Method Post -Body @{ username = $C.Name; password = 'nope-nope' } -SkipHttpErrorCheck
Expect 'the web page refuses a wrong password' ($bad.Content -match 'Wrong username or password')
$ok = Invoke-WebRequest -Uri "$BaseUrl/delete-account" -Method Post -Body @{ username = $C.Name; password = 'Test-password-123' } -SkipHttpErrorCheck
Expect 'the web page deletes the account' ($ok.StatusCode -eq 200 -and $ok.Content -match 'Account deleted') "($($ok.StatusCode))"
$r = Call POST '/api/auth/check' @{ username = $C.Name }
Expect '... and it''s gone' (-not $r.Body.exists) (Why $r)

$r = Call POST '/api/locations/nearby' @{ latitude = 52.0; longitude = -1.5 } -Token $B.Token
Expect 'nearby locations with the position in the body (kept out of the logs)' ($r.Status -eq 200 -and $null -ne $r.Body.locations) (Why $r)

$p = Invoke-WebRequest -Uri "$BaseUrl/privacy" -SkipHttpErrorCheck
Expect 'the privacy policy page is there, with the contact email' ($p.StatusCode -eq 200 -and $p.Content -match 'fieldarcheryscoring@gmail.com') "($($p.StatusCode))"

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All delete tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Tidy up: run server/sql/900_delete_test_accounts.sql in SSMS.'
