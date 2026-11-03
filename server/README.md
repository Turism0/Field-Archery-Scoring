# NFAS server

- `sql/`: database scripts for SQL Server 2025 on Mimir (step 1, done). See `../docs/DESIGN.md`.
- `api/NfasApi/`: ASP.NET Core (.NET 10) API on Odin under IIS, published at **https://fieldarchery.fnurble.co.uk** through Nginx Proxy Manager.

The app only ever talks to the API; the API is the only thing that talks to SQL Server.

## Updating the API on Odin

If the release adds a database script (e.g. `sql/007_round_types.sql`), run it in SSMS on Mimir **first**.

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
| GET | `/api/me` | `Authorization: Bearer <token>` | `{ archerId, username, createdUtc, role, duplicateLocations }`; role is `USER`, `ADMIN` or `OWNER`; `duplicateLocations` is the number of possible duplicate locations waiting (Admins only, 0 for Users); `roundOffers` is the number of rounds friends have scored for you, waiting to be accepted |
| PUT | `/api/rounds/{roundId}` | Bearer token; whole round (below) | `{ roundId, result: "saved" \| "unchanged", serverUpdatedUtc }`; 400 `invalid_round` with `problems` |
| GET | `/api/rounds/{roundId}` | Bearer token | The stored round (own rounds only), with `isDeleted` and `serverUpdatedUtc` |
| DELETE | `/api/rounds/{roundId}` | Bearer token | 204; marks it deleted (kept so other phones find out) |
| GET | `/api/friends` | Bearer token | `{ friends: [{ archerId, username, friendsSinceUtc, rounds, bestTotal, lastShootDate, averagePerAnimal, woodsmanRounds, woodsmanBest }], incoming: [...], outgoing: [...] }`; rounds / best / average are Big Game |
| POST | `/api/friends/requests` | Bearer token; `{ username }` | 201 `requested`; 200 `accepted` if they'd already asked you; 404 `no_such_user`; 409 `already_requested` / `already_friends`; 400 `cannot_friend_self` |
| POST | `/api/friends/{archerId}/accept` | Bearer token | 200; 404 `no_request` unless they asked you |
| DELETE | `/api/friends/{archerId}` | Bearer token | 204: decline, cancel or unfriend |
| GET | `/api/friends/{archerId}/rounds` | Bearer token | `{ rounds: [{ roundId, shootDate, targetCount, archerClass, ageGroup, bowStyle, totalPoints, locationName }] }`: their finished rounds, newest first; 404 `not_friends` |
| GET | `/api/friends/{archerId}/rounds/{roundId}` | Bearer token | One finished round with targets and animals (read-only) |
| GET | `/api/rounds?since=&afterId=&limit=` | Bearer token | `{ rounds: [...], more }`: own rounds changed after the cursor, oldest change first, deleted ones included; full rounds with targets and animals. No `since` = everything. Page with the last round's `serverUpdatedUtc` + `roundId`. |

| PUT | `/api/groups/{groupId}` | Host; `{ code, method, roundType, targetCount, shootDate, locationId, status, members, order?, scorers? }` | The group (as GET). The host's phone makes the id and the 6-character code (`ABCDEFGHJKMNPQRSTUVWXYZ23456789`). `method`: `SINGLE` (one scorer, others watching), `DOUBLE` (two scorers, saved when both match), `MULTI` (3+ scorers, nobody scores their own card, two per card, shared evenly), `SELF`. `status`: `OPEN` → `STARTED` → `CLOSED`. `members`: the host (`HOST`), friends added by name (`FRIEND`, must be friends) and guests (`GUEST`, `guestName`), each `{ memberId, kind, archerId?, guestName?, archerClass, ageGroup, bowStyle, roundId? }`; archers who joined stay. `scorers`: `[{ cardMemberId, scorerMemberId }]`; left out when starting = the usual ones. 409 `code_taken` / `group_started` / `group_closed`; 400 `invalid_group` / `invalid_scorers` with `problems` |
| POST | `/api/groups/join` | `{ code, groupId?, memberId, archerClass, ageGroup, bowStyle, roundId? }` | The group. Joining again updates your details; a friend the host added becomes a joined member. 404 `no_such_group` if no open group has the code (codes last 24 hours or until closed) |
| GET | `/api/groups` | Bearer token | `{ groups: [...] }`: groups you're in, last 30 days, newest first |
| GET | `/api/groups/{groupId}?since=` | Members only | `{ group fields, me, members: [{ memberId, kind, archerId, name, …, scorers }], entries, cells: [{ targetNo, cardMemberId, state, points }], totals: [{ memberId, points, targetsDone }], serverTimeUtc }`. `entries` changed after `since` (pass the last `serverTimeUtc`); `state` `WAITING` / `MATCHED` / `MISMATCH` |
| PUT | `/api/groups/{groupId}/entries` | Scorers; `{ entries: [{ targetNo, cardMemberId, scorerMemberId, target, enteredUtc }] }` | `{ results: [{ targetNo, cardMemberId, state, points }] }`. `target` is one target as in a round upload, checked against the rules. A newer entry from the same scorer replaces the older one. Your own entries (the host may pass on others'). |
| DELETE | `/api/groups/{groupId}/members/{memberId}` | Host, or the archer themselves | 204; 409 `is_host` |
| PUT | `/api/offers/{roundId}` | `{ forArcherId, groupId?, round }` | `{ roundId, result: "offered" }`: a finished round you scored for a friend, waiting for them. Send again to correct it. 404 `not_a_friend`; 409 `round_exists` once accepted |
| GET | `/api/offers` | Bearer token | `{ received: [...], sent: [...] }` summaries (who, date, type, class, bow, total, location) |
| GET | `/api/offers/{roundId}` | The friend or the scorer | `{ offer, round }` |
| POST | `/api/offers/{roundId}/accept` | The friend | `{ roundId, result: "accepted", serverUpdatedUtc }`: now the friend's round (downloads to their phones on the next sync) |
| POST | `/api/offers/{roundId}/decline` | The friend | 204, deleted |
| DELETE | `/api/offers/{roundId}` | The scorer | 204, withdrawn |
| GET | `/api/admin/archers` | Bearer token, Admin or Owner | `{ archers: [{ archerId, username, role, createdUtc, lastLoginUtc }] }`, Owner first, then Admins; 403 `not_admin` |
| PUT | `/api/admin/archers/{archerId}/role` | Bearer token, Owner only; `{ role: "ADMIN" \| "USER" }` | `{ archerId, username, role }`; 403 `not_owner` / `not_admin`; 400 `invalid_role`; 404 `no_such_archer`; 409 `cannot_change_owner` |
| GET | `/api/locations/nearby?lat=&lon=&radius=` | Bearer token | `{ locations: [{ locationId, name, distanceMetres }], catchmentMetres }`, nearest first. Radius in metres: default the catchment (402 m, a quarter mile, `Locations:CatchmentMetres`), max 10 km. The position isn't stored. |
| POST | `/api/locations` | Bearer token; `{ name, latitude, longitude, isPrivate? }` | 201 `{ locationId, name }`; 409 `name_taken` with `existing: { locationId, name }`; 400 `invalid_name` (2–60 characters) / `invalid_position` |
| POST | `/api/locations/resolve` | Bearer token; `{ ids: [...] }` | `{ locations: [{ requestedId, locationId, name }] }`: current id and name for each known id (merged ones point at the location kept) |
| GET | `/api/admin/locations` | Bearer token, Admin or Owner | `{ locations: [{ locationId, name, latitude, longitude, createdUtc, createdBy, rounds, isPrivate }] }`; 403 `not_admin` |
| GET | `/api/locations/map` | Bearer token | `{ locations: [{ locationId, name, latitude, longitude, isPrivate, myRounds, rounds }] }`: public live locations plus your own private ones (Admins: all); finished rounds by you / by everyone |
| GET | `/api/locations/{locationId}/page` | Bearer token | `{ location: { …, courseTargets }, myRounds, friends: [{ archerId, username, roundType, rounds, bestTotal, averagePerAnimal, courseAverage, roundsList }], bowStyles: [{ roundType, bowStyle, archers, rounds, bestTotal, averageTotal, averagePerAnimal, courseAverage }] }`. `courseTargets` (2.0.02): the course size now, from the latest finished round there that's 18, 20, 36 or 40 (else the latest round rounded to the nearest); `courseAverage` = average per animal × `courseTargets`. Round summaries here and in `/api/friends/{id}/rounds` include `animalCount`, `predatorPrey`, `fans`. Finished rounds only. Private: creator and Admins only (404 otherwise), no friends / bowStyles. Friends' rounds open with `/api/friends/{id}/rounds/{roundId}`. |
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

- `"roundType"`: `BIG_GAME` (default when missing) or `WOODSMAN`. A Woodsman round's targets are `"type": "WOODSMAN"` with `"animals": []` and `"arrows"`: the arrows in order, `W` wound (5), `K` kill (30 / 20 / 10 on arrow 1 / 2 / 3, shooting stops), `M` miss (0), e.g. `"WK"` = 25; an unfinished target has fewer than 3 and no K. Override 0–30. Big Game targets have no arrows.
- Every score is checked against the Big Game rules (allowed hit per arrow and its points, predator/prey shared arrows, fan size, overrides, target and round totals). Anything wrong is refused with a list of problems.
- Optional `"locationId"`: the shoot location. An id that was merged is stored as the location kept; an unknown id is stored as no location (the round is still saved). Downloads include `locationId` and `locationName`.
- Same round id again is safe: the copy with the later `clientUpdatedUtc` wins, an equal or older copy is ignored (`unchanged`).
- A round id belonging to another archer is reported as not found.
- Optional `"groupId"`: the scoring group it was shot in (kept if you're in that group). Group rounds can be uploaded while in progress so the group sees scores as they go.

**Groups are temporary.** A group, with its members (guests' names too), scorers and entries, is deleted 30 days after it was made (whenever a new group starts). Members' own rounds and accepted rounds are kept as normal; rounds waiting to be accepted are kept until answered.

`ForwardedHeaders:KnownProxies` in `appsettings.json` is the address Nginx Proxy Manager connects to Odin
from (set to Heimdall, `192.168.0.6`). If it's wrong the API still works, but every phone shares one
rate-limit allowance.

## Testing accounts (step 3)

In PowerShell 7 on any PC, from `server\tests`:

```powershell
.\Test-Accounts.ps1                                  # through NPM: https://fieldarchery.fnurble.co.uk
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

## Testing course size and round make-up (2.0.02)

```powershell
.\Test-CourseSize.ps1
```

Two test accounts (friends) and test locations: no rounds = no course size; a 40-target round makes it 40 and a later 36-target round 36; a later 10-target round (not a usual size) leaves it at 36; only a 37-target round = 36, only a 19-target round = 20 (half course); course averages are per animal × course size, counting predator/prey as 2 and a fan as its animals; rounds report animals, predator/prey and fans on location pages and in friends' round lists. Every line should start with OK; tidy up with `900_delete_test_accounts.sql`.

## Testing group scoring (1.9.06)

```powershell
.\Test-Groups.ps1
.\Test-Offers.ps1
```

`Test-Groups.ps1`: five test accounts. Join by code (wrong code, case, joining twice, code clash, closed group), who can see a group, the four methods – single scorer (everyone scored by the host, others can't), double scoring (waiting, matched, mismatch then re-entry, non-scorers refused), three or more scorers (needs three phones, nobody scores their own card, two per card, shared evenly, re-shared when someone joins or leaves) and self scoring – live totals, `since=`, leaving and removing, closing, and uploading a round with the group id.
`Test-Offers.ps1`: three test accounts. A round scored for a friend waits as a request (badge count, not in their rounds), can be corrected, accepted (then it's theirs and downloads), declined or withdrawn; not to non-friends, yourself or for unfinished rounds. Every line should start with OK; tidy up with `900_delete_test_accounts.sql`.

## Testing Woodsman rounds (1.9.01)

```powershell
.\Test-Woodsman.ps1
```

Two test accounts (friends) and a test location: Woodsman scoring (K 30, WK 25, MWK 15, WWW 15, MMM 0, WWK 20, MK 20), impossible arrows and wrong points refused, overrides, Big Game and Woodsman targets kept apart, no type = Big Game, friends' summaries and location stats per type. Every line should start with OK; tidy up with `900_delete_test_accounts.sql`.

## Testing the location map and page (1.7.03)

```powershell
.\Test-LocationPage.ps1
```

Three test accounts (A and B friends, C not) and test locations in the North Sea: map counts (yours / everyone's, finished only, private hidden from others), the page's own rounds, friends section (friends only, average per animal), all-archer stats per bow style (non-friends included, no names), opening a friend's round, and private pages. Every line should start with OK; tidy up with `900_delete_test_accounts.sql`.

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

- Domain: `fieldarchery.fnurble.co.uk`
- Scheme `http`, forward to Odin's IP, port `8085`
- SSL: the wildcard certificate, **Force SSL**, HTTP/2 on
- **No** "Local LAN Only" access list: phones need to reach it from outside

Then check:

- https://fieldarchery.fnurble.co.uk/health from a PC at home
- the same URL from your phone **on mobile data, with WiFi off**. This proves public DNS (Cloudflare needs a `fieldarchery` or wildcard record) and the 443 forward work. Remember the UK-only geoblock.
