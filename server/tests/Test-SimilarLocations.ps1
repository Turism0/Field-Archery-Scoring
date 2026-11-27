#Requires -Version 7
<#
    API – suggesting an existing location instead of a new one (API 3.2.25). Run in PowerShell 7 (pwsh):

        .\Test-SimilarLocations.ps1
        .\Test-SimilarLocations.ps1 -BaseUrl http://localhost:8085

    Naming a new location with checkSimilar: true is answered 409 similar_exists when a location you can use (public, or
    your own private) has the same name (anywhere), a similar name within 500 m (or either has no pin; 3.2.28), or is
    within 300 m. Without checkSimilar (older apps, or "it's a new
    place"), it's made as before. 3.2.32: an archer's private location can be merged into a similar public one. 3.2.33: they're told about it
    (/api/me similarLocations, page similarPublic) and can say it's a different place (not-same). Two throwaway accounts (zz_test_…) and test locations out in the North Sea, named
    "zzsim …" (not zz_test: that holds the word "test", which a real "Test Ground" counts as similar to).
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

function New-TestUser {
    $name = 'zz_test_' + -join ((97..122) | Get-Random -Count 6 | ForEach-Object { [char]$_ })
    $r = Call POST '/api/auth/register' @{ username = $name; password = 'Test-password-123'; deviceName = 'similar location tests' }
    if ($r.Status -ne 201) { throw "Couldn't create test user ($($r.Status)): $($r.Body.message)" }
    [pscustomobject]@{ Name = $r.Body.username; Id = $r.Body.archerId; Token = $r.Body.token }
}

function Word { -join ((97..122) | Get-Random -Count 7 | ForEach-Object { [char]$_ }) }
function Ids($r) { @($r.Body.matches | ForEach-Object { $_.locationId }) }

Write-Host "Testing $BaseUrl`n"
$r = Call GET '/health'
if (-not $r.Body.apiVersion) { throw "The API isn't answering at $BaseUrl/health (HTTP $($r.Status))." }

# (Users in $Archer1 / $Archer2: PowerShell names ignore case, so no single letters that other variables might reuse.)
$Archer1 = New-TestUser; $Archer2 = New-TestUser
$lat = 55.5 + (Get-Random -Maximum 1000) / 1000.0; $lon = 2.0 + (Get-Random -Maximum 1000) / 1000.0
$word = Word

$r = Call POST '/api/locations' @{ name = "zzsim $word Bowmen"; latitude = $lat; longitude = $lon; checkSimilar = $true } -Token $Archer1.Token
Expect 'a new location with nothing like it is made' ($r.Status -eq 201) "($($r.Status) $($r.Body.message))"
$club = $r.Body.locationId

$r = Call POST '/api/locations' @{ name = "zzsim $word Archers"; latitude = $lat + 0.1; longitude = $lon; checkSimilar = $true } -Token $Archer2.Token
Expect 'the same name with a different club word, 11 km away: suggested (same_name)' ($r.Status -eq 409 -and $r.Body.error -eq 'similar_exists' -and (Ids $r) -contains $club -and (@($r.Body.matches | Where-Object locationId -eq $club)[0].reasons -contains 'same_name')) "($($r.Status) $($r.Body | ConvertTo-Json -Compress -Depth 5))"

$typo = $word.Substring(0, 6) + $(if ($word[6] -eq 'x') { 'y' } else { 'x' })   # one letter different
# 3.2.28 (Pete): a similar name only counts within 500 m (or with no pin); the same name counts anywhere.
$r = Call POST '/api/locations' @{ name = "zzsim $typo Bowmen"; latitude = $lat + 0.0036; longitude = $lon; checkSimilar = $true } -Token $Archer2.Token
Expect 'a one-letter typo 400 m away: suggested (similar_name)' ($r.Status -eq 409 -and (Ids $r) -contains $club -and (@($r.Body.matches | Where-Object locationId -eq $club)[0].reasons -contains 'similar_name')) "($($r.Status) $($r.Body | ConvertTo-Json -Compress -Depth 5))"
$r = Call POST '/api/locations' @{ name = "zzsim $typo Bowmen"; latitude = $lat + 0.1; longitude = $lon; checkSimilar = $true } -Token $Archer2.Token
Expect 'a one-letter typo 11 km away: not suggested (made)' ($r.Status -eq 201) "($($r.Status) $($r.Body | ConvertTo-Json -Compress -Depth 5))"
$r = Call POST '/api/locations' @{ name = "zzsim $typo Society"; noPin = $true; checkSimilar = $true } -Token $Archer2.Token
Expect 'a typo with no pin (distance unknown): suggested' ($r.Status -eq 409 -and (Ids $r) -contains $club) "($($r.Status))"

$r = Call POST '/api/locations' @{ name = "zzsim $(Word) Field"; latitude = $lat + 0.002; longitude = $lon; checkSimilar = $true } -Token $Archer2.Token
Expect 'a different name 220 m away: suggested (nearby)' ($r.Status -eq 409 -and (Ids $r) -contains $club -and (@($r.Body.matches | Where-Object locationId -eq $club)[0].distanceMetres -lt 300)) "($($r.Status) $($r.Body | ConvertTo-Json -Compress -Depth 5))"

$r = Call POST '/api/locations' @{ name = "zzsim $(Word) Field"; latitude = $lat + 0.004; longitude = $lon; checkSimilar = $true } -Token $Archer2.Token
Expect 'a different name 450 m away: made (not near enough)' ($r.Status -eq 201) "($($r.Status) $($r.Body | ConvertTo-Json -Compress -Depth 5))"

$r = Call POST '/api/locations' @{ name = "zzsim $word Club"; latitude = $lat + 0.2; longitude = $lon } -Token $Archer2.Token
Expect "without checkSimilar (older apps, or 'it's a new place'): made" ($r.Status -eq 201) "($($r.Status) $($r.Body.message))"

$r = Call POST '/api/locations' @{ name = "zzsim $word Bowmen"; latitude = $lat + 0.3; longitude = $lon } -Token $Archer2.Token
Expect 'the exact same public name is still refused (name_taken)' ($r.Status -eq 409 -and $r.Body.error -eq 'name_taken') "($($r.Status))"

$r = Call POST '/api/locations' @{ name = "zzsim $word Society"; noPin = $true; checkSimilar = $true } -Token $Archer2.Token
Expect 'a name-only location is checked by name' ($r.Status -eq 409 -and (Ids $r) -contains $club) "($($r.Status))"

# Private locations: only their owner is told about them.
$secret = Word
$r = Call POST '/api/locations' @{ name = "zzsim $secret Garden"; latitude = $lat - 0.2; longitude = $lon; isPrivate = $true } -Token $Archer1.Token
$garden = $r.Body.locationId
$r = Call POST '/api/locations' @{ name = "zzsim $secret Garden"; latitude = $lat - 0.2; longitude = $lon; checkSimilar = $true } -Token $Archer2.Token
Expect "someone else's private location isn't suggested" ($r.Status -eq 201 -or -not ((Ids $r) -contains $garden)) "($($r.Status))"
$publicGarden = if ($r.Status -eq 201) { $r.Body.locationId } else { $null }
$r = Call POST '/api/locations' @{ name = "zzsim $secret Gardens"; latitude = $lat - 0.2; longitude = $lon + 0.0001; checkSimilar = $true; isPrivate = $true } -Token $Archer1.Token
Expect 'your own private location is' ($r.Status -eq 409 -and (Ids $r) -contains $garden) "($($r.Status))"

$r = Call POST '/api/locations' @{ name = "zzsim $(Word) Range"; latitude = $lat; longitude = $lon; checkSimilar = $true }
Expect 'needs a sign-in' ($r.Status -eq 401) "($($r.Status))"

# 3.2.33 (Pete): the archer is told when a similar public location exists for their private one, and can say it's a
# different place.
$r = Call GET '/api/me' -Token $Archer1.Token
Expect '/api/me counts your private location with a similar public one (similarLocations)' ($r.Body.similarLocations -eq 1) "(got $($r.Body.similarLocations))"
$r = Call GET '/api/me' -Token $Archer2.Token
Expect '...and 0 for an archer with no private locations' ($r.Body.similarLocations -eq 0) "(got $($r.Body.similarLocations))"
$r = Call GET "/api/locations/$garden/page" -Token $Archer1.Token
Expect "the private location's page suggests the public Garden (similarPublic)" ($r.Status -eq 200 -and (@($r.Body.location.similarPublic | ForEach-Object { $_.locationId }) -contains $publicGarden)) "($($r.Status) $($r.Body.location | ConvertTo-Json -Compress -Depth 5))"
$r = Call POST "/api/locations/$garden/not-same" @{ publicIds = @($publicGarden) } -Token $Archer2.Token
Expect "someone else can't answer for your private location" ($r.Status -eq 404) "($($r.Status))"
$r = Call POST "/api/locations/$garden/not-same" @{ publicIds = @() } -Token $Archer1.Token
Expect 'not-same with no ids is refused' ($r.Status -eq 400) "($($r.Status))"
$r = Call POST "/api/locations/$garden/not-same" @{ publicIds = @($publicGarden) } -Token $Archer1.Token
Expect "you can say it's a different place" ($r.Status -eq 204) "($($r.Status) $($r.Body.message))"
$r = Call GET '/api/me' -Token $Archer1.Token
Expect '...then it no longer counts' ($r.Body.similarLocations -eq 0) "(got $($r.Body.similarLocations))"
$r = Call GET "/api/locations/$garden/page" -Token $Archer1.Token
Expect "...or shows on the page" (-not @($r.Body.location.similarPublic).Count) "($($r.Body.location | ConvertTo-Json -Compress -Depth 5))"
$r = Call GET "/api/locations/$garden/merge-targets" -Token $Archer1.Token
Expect '...but you can still merge into it' (@($r.Body.locations | ForEach-Object { $_.locationId }) -contains $publicGarden) "($($r.Body | ConvertTo-Json -Compress -Depth 5))"
$r = Call POST '/api/locations' @{ name = "zzsim $secret Garden Club"; latitude = $lat - 0.2; longitude = $lon + 0.0002 } -Token $Archer2.Token
$r = Call GET '/api/me' -Token $Archer1.Token
Expect 'a new similar public location is counted again' ($r.Body.similarLocations -eq 1) "(got $($r.Body.similarLocations))"

# 3.2.32 (Pete): the archer who named a private location can merge it into a similar public one.
$r = Call GET "/api/locations/$garden/merge-targets" -Token $Archer2.Token
Expect "someone else can't list merge targets for your private location" ($r.Status -eq 404) "($($r.Status))"
$r = Call GET "/api/locations/$garden/merge-targets" -Token $Archer1.Token
Expect 'the public Garden is offered as a merge target' ($r.Status -eq 200 -and $null -ne $publicGarden -and (@($r.Body.locations | ForEach-Object { $_.locationId }) -contains $publicGarden)) "($($r.Status) $($r.Body | ConvertTo-Json -Compress -Depth 5))"
Expect "...and private locations aren't" (-not (@($r.Body.locations | Where-Object { $_.isPrivate }).Count)) "($($r.Body | ConvertTo-Json -Compress -Depth 5))"
$r = Call POST "/api/locations/$garden/merge-into" @{ intoId = $club } -Token $Archer1.Token
Expect "a public location that isn't similar is refused (not_similar)" ($r.Status -eq 409 -and $r.Body.error -eq 'not_similar') "($($r.Status) $($r.Body.error))"
$r = Call POST "/api/locations/$garden/merge-into" @{ intoId = $publicGarden } -Token $Archer2.Token
Expect "someone else can't merge your private location" ($r.Status -eq 404) "($($r.Status))"
$r = Call POST "/api/locations/$garden/merge-into" @{ intoId = $publicGarden } -Token $Archer1.Token
Expect 'you can merge it into the similar public one' ($r.Status -eq 200 -and $r.Body.locationId -eq $publicGarden) "($($r.Status) $($r.Body | ConvertTo-Json -Compress))"
$r = Call POST '/api/locations/resolve' @{ ids = @($garden) } -Token $Archer1.Token
Expect 'the old id now leads to the public location' (@($r.Body.locations)[0].locationId -eq $publicGarden) "($($r.Body | ConvertTo-Json -Compress -Depth 5))"
$r = Call GET "/api/locations/$garden/merge-targets" -Token $Archer1.Token
Expect "once merged it can't be merged again" ($r.Status -eq 404) "($($r.Status))"
$r = Call GET '/api/me' -Token $Archer1.Token
Expect "once merged it's not counted (3.2.33)" ($r.Body.similarLocations -eq 0) "(got $($r.Body.similarLocations))"

Write-Host ''
if ($script:problems -eq 0) { Write-Host 'All similar location tests passed.' -ForegroundColor Green }
else { Write-Host "$($script:problems) problem(s)." -ForegroundColor Red }
Write-Host 'Tidy up: run server/sql/900_delete_test_accounts.sql.'
exit $script:problems
