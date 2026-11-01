# NFAS server

- `sql/`: database scripts for SQL Server 2025 on Mimir (step 1, done). See `../docs/DESIGN.md`.
- `api/NfasApi/`: ASP.NET Core (.NET 10) API on Odin under IIS, published at **https://nfas.fnurble.co.uk** through Nginx Proxy Manager.

The app only ever talks to the API; the API is the only thing that talks to SQL Server.

## Updating the API on Odin

If the release adds a database script (e.g. `sql/006_location_review_private.sql`), run it in SSMS on Mimir **first**.

After a new version, copy the changed files into `C:\zScripts\NfasApi` (keeping subfolders), then in **Windows PowerShell 5.1 as Administrator**:

```powershell
C:\zScripts\NfasApi\Update-Api.ps1
```

It stops the `NfasApi` app pool, publishes into `C:\inetpub\nfas-api`, starts the pool again (even if the publish failed, so the old version keeps running), then checks `/health` shows the version in `NfasApi.csproj` and that the database is reachable. Every line should start with OK; anything else is in red. `appsettings.Production.json` in `C:\inetpub\nfas-api` is left alone. If Windows says the script is blocked, run `Unblock-File C:\zScripts\NfasApi\Update-Api.ps1` once.

The same by hand:

```powershell
Import-Module WebAdministration
Stop-WebAppPool NfasApi                     # releases the files so they can be replaced
cd C:\zScripts\NfasApi
dotnet publish -c Release -o C:\inetpub\nfas-api
Start-WebAppPool NfasApi                    # press Enter: a pasted last line doesn't run by itself
```

## API endpoints

| Method | Path | Body / auth | Returns |
|---|---|---|---|
| GET | `/health` | | API and database status, schema and API version |
| POST | `/api/auth/check` | `{ username }` | `{ username, exists }` |
| POST | `/api/auth/register` | `{ username, password, deviceName }` | 201 `{ archerId, username, token, expiresUtc }`; 409 `username_taken` |
| POST | `/api/auth/login` | `{ username, password, deviceName }` | 200 as above; 401 `invalid_credentials`; 423 `locked_out` |
| POST | `/api/auth/logout` | `Authorization: Bearer <token>` | 204, revokes this phone's token |
| GET | `/api/me` | `Authorization: Bearer <token>` | `{ archerId, username, createdUtc, role, duplicateLocations }`; role is `USER`, `ADMIN` or `OWNER`; `duplicateLocations` is the number of possible duplicate locations waiting (Admins only, 0 for Users) |
| PUT | `/api/rounds/{roundId}` | Bearer token; whole round (below) | `{ roundId, result: "saved" \| "unchanged", serverUpdatedUtc }`; 400 `invalid_round` with `problems` |
| GET | `/api/rounds/{roundId}` | Bearer token | The stored round (own rounds only), with `isDeleted` and `serverUpdatedUtc` |
| DELETE | `/api/rounds/{roundId}` | Bearer token | 204; marks it deleted (kept so other phones find out) |
| GET | `/api/friends` | Bearer token | `{ friends: [{ archerId, username, friendsSinceUtc, rounds, bestTotal, lastShootDate, averagePerAnimal }], incoming: [...], outgoing: [...] }` |
| POST | `/api/friends/requests` | Bearer token; `{ username }` | 201 `requested`; 200 `accepted` if they'd already asked you; 404 `no_such_user`; 409 `already_requested` / `already_friends`; 400 `cannot_friend_self` |
| POST | `/api/friends/{archerId}/accept` | Bearer token | 200; 404 `no_request` unless they asked you |
| DELETE | `/api/friends/{archerId}` | Bearer token | 204: decline, cancel or unfriend |
| GET | `/api/friends/{archerId}/rounds` | Bearer token | `{ rounds: [{ roundId, shootDate, targetCount, archerClass, ageGroup, bowStyle, totalPoints, locationName }] }`: their finished rounds, newest first; 404 `not_friends` |
| GET | `/api/friends/{archerId}/rounds/{roundId}` | Bearer token | One finished round with targets and animals (read-only) |
| GET | `/api/rounds?since=&afterId=&limit=` | Bearer token | `{ rounds: [...], more }`: own rounds changed after the cursor, oldest change first, deleted ones included; full rounds with targets and animals. No `since` = everything. Page with the last round's `serverUpdatedUtc` + `roundId`. |

| GET | `/api/admin/archers` | Bearer token, Admin or Owner | `{ archers: [{ archerId, username, role, createdUtc, lastLoginUtc }] }`, Owner first, then Admins; 403 `not_admin` |
| PUT | `/api/admin/archers/{archerId}/role` | Bearer token, Owner only; `{ role: "ADMIN" \| "USER" }` | `{ archerId, username, role }`; 403 `not_owner` / `not_admin`; 400 `invalid_role`; 404 `no_such_archer`; 409 `cannot_change_owner` |
| GET | `/api/locations/nearby?lat=&lon=&radius=` | Bearer token | `{ locations: [{ locationId, name, distanceMetres }], catchmentMetres }`, nearest first. Radius in metres: default the catchment (402 m, a quarter mile, `Locations:CatchmentMetres`), max 10 km. The position isn't stored. |
| POST | `/api/locations` | Bearer token; `{ name, latitude, longitude, isPrivate? }` | 201 `{ locationId, name }`; 409 `name_taken` with `existing: { locationId, name }`; 400 `invalid_name` (2–60 characters) / `invalid_position` |
| POST | `/api/locations/resolve` | Bearer token; `{ ids: [...] }` | `{ locations: [{ requestedId, locationId, name }] }`: current id and name for each known id (merged ones point at the location kept) |
| GET | `/api/admin/locations` | Bearer token, Admin or Owner | `{ locations: [{ locationId, name, latitude, longitude, createdUtc, createdBy, rounds, isPrivate }] }`; 403 `not_admin` |
| GET | `/api/admin/locations/duplicates` | Admin or Owner | `{ pairs: [{ a, b, distanceMetres, reasons }] }`: live locations within the catchment (`nearby`) or with the same / a similar name (`same_name`, `similar_name`; ignores case, punctuation and words like Archers, Bowmen, Club, Field), not yet approved. Most likely first. |
| POST | `/api/admin/locations/duplicates/approve` | Admin or Owner; `{ aId, bId }` | 204: they're different places, stop flagging them |
| PUT | `/api/admin/locations/{locationId}` | Admin or Owner; `{ name, isPrivate? }` | `{ locationId, name }`; 409 `name_taken`; 404 `no_such_location` |
| POST | `/api/admin/locations/{locationId}/merge` | Admin or Owner; `{ intoId }` | `{ locationId, name, roundsMoved }`: the duplicate's rounds move to `intoId`; 400 `same_location`; 404 if either is missing or already merged |

Errors are JSON `{ error, message }`; `message` is written to show to the archer.
Rules: usernames 3–30 of letters, numbers, `.` `-` `_` (not case-sensitive); passwords 8–128 characters;
5 wrong passwords lock the account for 15 minutes; sign-in calls are limited to 30 per minute per IP
(`RateLimits:AuthPerMinute` in `appsettings.json`). Tokens last a year, extended while in use.

**Round upload.** The app sends the whole round each time (finished rounds only):

```json
{ "shootDate": "2026-10-31", "targetCount": 36, "archerClass": "G", "ageGroup": "ADULT", "bowStyle": "LB",
  "status": "COMPLETED", "totalPoints": 612,
  "clientCreatedUtc": "2026-10-31T09:00:00Z", "clientUpdatedUtc": "2026-10-31T13:05:12.345Z",
  "targets": [ { "targetNo": 1, "type": "STANDARD", "overridePoints": null, "points": 20,
                 "animals": [ { "index": 0, "state": "SCORED", "arrowNo": 1, "hit": "KILL", "points": 20 } ] } ] }
```

- Every score is checked against the Big Game rules (allowed hit per arrow and its points, predator/prey shared arrows, fan size, overrides, target and round totals). Anything wrong is refused with a list of problems.
- Optional `"locationId"`: the shoot location. An id that was merged is stored as the location kept; an unknown id is stored as no location (the round is still saved). Downloads include `locationId` and `locationName`.
- Same round id again is safe: the copy with the later `clientUpdatedUtc` wins, an equal or older copy is ignored (`unchanged`).
- A round id belonging to another archer is reported as not found.

`ForwardedHeaders:KnownProxies` in `appsettings.json` is the address Nginx Proxy Manager connects to Odin
from (set to Heimdall, `192.168.0.6`). If it's wrong the API still works, but every phone shares one
rate-limit allowance.

## Testing accounts (step 3)

In PowerShell 7 on any PC, from `server\tests`:

```powershell
.\Test-Accounts.ps1                                  # through NPM: https://nfas.fnurble.co.uk
.\Test-Accounts.ps1 -BaseUrl http://localhost:8085   # on Odin, straight to IIS
```

Every line should start with OK. It creates a `zz_test_…` account each run; remove them with
`server/sql/900_delete_test_accounts.sql`.

## Testing round upload (step 5a)

```powershell
.\Test-Rounds.ps1                                    # through NPM
.\Test-Rounds.ps1 -BaseUrl http://localhost:8085     # on Odin
```

Uploads, re-sends, edits, the download list, rule violations, another archer's access and delete. Every line should start with OK; tidy up with `900_delete_test_accounts.sql`.

## Testing friends (step 7a)

```powershell
.\Test-Friends.ps1                                   # through NPM
.\Test-Friends.ps1 -BaseUrl http://localhost:8085    # on Odin
```

Three test accounts: requests (unknown user, yourself, twice), who can accept, both-way friendship, summaries, seeing only finished rounds, read-only, asking back auto-accepts, unfriending. Every line should start with OK; tidy up with `900_delete_test_accounts.sql`.

## Testing private locations and duplicate alerts (1.7.01)

```powershell
.\Test-LocationReview.ps1 -Username <your username>
```

Asks for your password. Two test accounts and test locations in the North Sea: a private location is only matched and named for its creator (friends see "Private location"), the same name can be reused for another private location, look-alike pairs are flagged (nearby, same name, "Bowmen of X" v "X Archers"), approving a pair stops it being flagged, `/api/me` counts them for Admins, and an Admin can make a location public. Every line should start with OK; tidy up with `900_delete_test_accounts.sql`.

## Testing shoot locations (step 2a)

```powershell
.\Test-Locations.ps1 -Username <your username>                                  # through NPM
.\Test-Locations.ps1 -Username <your username> -BaseUrl http://localhost:8085   # on Odin
```

Asks for your password (for the admin tests). Uses a test account and test locations in the North Sea: nearby lookup and catchment, naming (tidying, duplicates, bad input), rounds carrying a location, Users refused admin calls, admin list / rename / merge, resolving merged ids. Every line should start with OK; tidy up with `900_delete_test_accounts.sql` (removes test locations too).

## Testing roles (step 1)

```powershell
.\Test-Roles.ps1 -Username <your username>                                  # through NPM
.\Test-Roles.ps1 -Username <your username> -BaseUrl http://localhost:8085   # on Odin
```

Asks for your (Owner) password. Two test accounts: Users are refused admin calls, the Owner lists accounts and makes one an Admin and back, nobody can become a second Owner or change the Owner, Admins can list but not make Admins, and a role change applies straight away. Your account isn't changed. Every line should start with OK; tidy up with `900_delete_test_accounts.sql`.

## Testing Friends on a phone

```powershell
.\Test-FriendView.ps1 -Username <your username>
```

Doesn't need your password. Creates a throwaway friend (`zz_test_…`) with two finished rounds (50 and 54) and one unfinished, and sends you a friend request. It pauses while you accept on the phone, check the friend's summary, rounds and read-only scorecards (the unfinished round must not show), then remove the friend; the script checks the server agrees after each step. Tidy up with `900_delete_test_accounts.sql`.

## Testing download on a phone (step 6)

```powershell
.\Test-Download.ps1 -Username <your username>
```

Acts as a second phone on your real account: lists the rounds the server holds for you, then adds, edits and deletes a TEST round, pausing after each step so you can check the phone picks it up (pull down on the phone's home screen to make it sync). Signs itself out at the end. Leaves nothing behind except the deleted test round (hidden, kept as a deletion record).

## Deploying the API to Odin (step 2, first time)

### 1. Install .NET 10 on Odin

From https://dotnet.microsoft.com/download/dotnet/10.0 install both:

- **ASP.NET Core Runtime – Hosting Bundle** (lets IIS run the API)
- **.NET SDK 10** (to build it on Odin)

Then restart IIS so it picks up the Hosting Bundle (PowerShell as Administrator):

```powershell
iisreset
dotnet --list-runtimes   # should now include Microsoft.AspNetCore.App 10.x
dotnet --list-sdks       # should show 10.x
```

### 2. Check Odin can reach SQL Server by name

```powershell
Test-NetConnection sql.fnurble.co.uk -Port 1433
```

`TcpTestSucceeded : True` is good. If it's False, `sql.fnurble.co.uk` is probably resolving to Nginx Proxy Manager (the `*.fnurble.co.uk` local record) rather than Mimir. Add a local DNS record on **pihole1** for `sql.fnurble.co.uk` → Mimir's IP, then restart nebula-sync so pihole2 gets it, and test again. The name has to be the FQDN because of the wildcard certificate.

### 3. Get the code onto Odin

Either push from Android Studio and clone it on Odin:

```powershell
git clone https://github.com/Turism0/NFAS-Scoring.git C:\src\NFAS-Scoring
```

or copy the `server\api` folder from your project to `C:\src\NFAS-Scoring\server\api` on Odin.

### 4. Build and publish into the IIS folder

```powershell
cd C:\src\NFAS-Scoring\server\api\NfasApi
dotnet publish -c Release -o C:\inetpub\nfas-api
```

To update later: stop the site (or app pool), run the same publish command, start it again.

### 5. Add the connection string (server only, never in Git)

```powershell
Copy-Item .\appsettings.Production.json.example C:\inetpub\nfas-api\appsettings.Production.json
notepad C:\inetpub\nfas-api\appsettings.Production.json
```

Replace `PUT_THE_NFAS_API_PASSWORD_HERE` with the `nfas_api` password from step 1. Publishing again won't overwrite this file.

### 6. Create the IIS site

PowerShell as Administrator. Port 8085 is a suggestion; use any free port.

```powershell
Import-Module WebAdministration
New-WebAppPool -Name NfasApi
Set-ItemProperty IIS:\AppPools\NfasApi -Name managedRuntimeVersion -Value ''   # "No Managed Code"
New-Website -Name NfasApi -Port 8085 -PhysicalPath C:\inetpub\nfas-api -ApplicationPool NfasApi
New-NetFirewallRule -DisplayName 'NFAS API (8085)' -Direction Inbound -Protocol TCP -LocalPort 8085 -Action Allow
```

### 7. Test on Odin

Open http://localhost:8085/health. Expected:

```json
{"status":"ok","database":"ok","schemaVersion":1,"apiVersion":"1.2.02","serverTimeUtc":"..."}
```

- `"database":"unreachable"` (503): the details are in **Event Viewer → Windows Logs → Application** (source *NfasApi* / *.NET Runtime*). Usually the password, or step 2.
- **HTTP 500.30 / 500.19**: the Hosting Bundle isn't installed or IIS wasn't restarted (step 1), or `appsettings.Production.json` is missing (step 5).

### 8. Nginx Proxy Manager

New proxy host:

- Domain: `nfas.fnurble.co.uk`
- Scheme `http`, forward to Odin's IP, port `8085`
- SSL: the wildcard certificate, **Force SSL**, HTTP/2 on
- **No** "Local LAN Only" access list: phones need to reach it from outside

Then check:

- https://nfas.fnurble.co.uk/health from a PC at home
- the same URL from your phone **on mobile data, with WiFi off**. This proves public DNS (Cloudflare needs an `nfas` or wildcard record) and the 443 forward work. Remember the UK-only geoblock.
