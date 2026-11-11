# Field Archery Scoring server

- `sql/`: database scripts for SQL Server 2025 on Mimir (step 1, done). See `../docs/DESIGN.md`.
- `api/FieldArcheryApi/`: ASP.NET Core (.NET 10) API on Odin under IIS, published at **https://fieldarchery.fnurble.co.uk** through Nginx Proxy Manager.
- `jobs/FieldArcheryJobs/`: console program on Odin, run by Task Scheduler once a week (2.2.01; weekly from 2.2.02): reads the NFAS Nationals and 3D Championships results from nfas.net weekly (or on Admin → Handicap → Fetch now) and fits the handicap. See `../docs/HANDICAP.md`.

The app only ever talks to the API; the API is the only thing that talks to SQL Server.

## Updating the API on Odin

If the release adds a database script (e.g. `sql/007_round_types.sql`), run it in SSMS on Mimir **first**.

The web app (2.5) is part of the API: copy `wwwroot\` with the rest (it's already built, from `web/`), and it's published at https://fieldarcheryscoring.fnurble.co.uk/ (2.6.03: the public website address; the API and the Android app stay at fieldarchery.fnurble.co.uk, and website pages opened there are sent to the new address – see `Web` in appsettings.json).

After a new version, copy the changed files into `C:\zScripts\FieldArcheryApi` (keeping subfolders), then in **Windows PowerShell 5.1 as Administrator**:

```powershell
C:\zScripts\FieldArcheryApi\Update-Api.ps1
```

It stops the `FieldArcheryApi` app pool, publishes into `C:\inetpub\fieldarchery-api`, starts the pool again (even if the publish failed, so the old version keeps running), then checks `/health` shows the version in `FieldArcheryApi.csproj` and that the database is reachable. Every line should start with OK; anything else is in red. `appsettings.Production.json` in `C:\inetpub\fieldarchery-api` is left alone. If Windows says the script is blocked, run `Unblock-File C:\zScripts\FieldArcheryApi\Update-Api.ps1` once.

The same by hand:

```powershell
Import-Module WebAdministration
Stop-WebAppPool FieldArcheryApi                   # releases the files so they can be replaced
cd C:\zScripts\FieldArcheryApi
dotnet publish -c Release -o C:\inetpub\fieldarchery-api
Start-WebAppPool FieldArcheryApi                 # press Enter: a pasted last line doesn't run by itself
```

## Server jobs: NFAS results and the handicap (2.2.01)

First time, in this order:

1. **SSMS on Mimir (as an administrator):** run `sql/011_handicap.sql` (schema version 7).
2. **The API:** copy `api/FieldArcheryApi` into `C:\zScripts\FieldArcheryApi` and run `Update-Api.ps1` as usual (2.2.01).
3. **The jobs:** copy `jobs/FieldArcheryJobs` (with its `Verify` folder) into `C:\zScripts\FieldArcheryJobs`, then in **Windows PowerShell 5.1 as Administrator**:

   ```powershell
   C:\zScripts\FieldArcheryJobs\Update-Jobs.ps1
   ```

   The job takes the database connection from the API's own `C:\inetpub\fieldarchery-api\appsettings.Production.json` (one copy of the password, 2.2.03). The first time it also makes `C:\FieldArcheryJobs\appsettings.Production.json` holding only a random `NameKeySecret`: **back that file up and never change the secret**, as archers are matched across years by it. Both files are then locked so only Administrators, SYSTEM (the task) and the API's app pool (`IIS AppPool\FieldArcheryApi`) can read them; run the script again after any change to the API's settings file. The script publishes into `C:\FieldArcheryJobs`, checks the C# handicap fit matches the Python reference (`verify`), and creates the scheduled task **FieldArchery jobs** (every Monday at 03:23 as `FieldArcheryJobs.exe fetch`, as SYSTEM, one copy at a time; a missed run starts when Odin is next on).
4. **Read nfas.net and fit the first model now** (about 10–15 minutes; every bow style page for every year, half a second apart):

   ```powershell
   C:\FieldArcheryJobs\FieldArcheryJobs.exe fetch
   ```

5. **Test:** `tests\Test-Handicap.ps1 -Username Turismo` (also in `Run-AllTests.ps1`).

Later updates to the jobs: copy the changed files into `C:\zScripts\FieldArcheryJobs` and run `Update-Jobs.ps1` again.

What the weekly run does: reads nfas.net, then refits if anything changed – new or corrected results, a target count an Admin changed, or no model yet. **Fetch now** in the Admin menu and target changes wait for the next weekly run; to do it straight away, run `C:\FieldArcheryJobs\FieldArcheryJobs.exe fetch` on Odin. Reading nfas.net covers new years and the last two every time, years with no results monthly, everything else yearly; results are only replaced when they've changed. Target counts are then worked out again (ones set by hand are kept), and if the data changed the handicap is refitted (about 5 minutes). The new model goes live by itself if its tests are no worse than the current one's; otherwise it waits in Admin → Handicap for the Owner. Every run's outcome is shown there too.

By hand: `FieldArcheryJobs.exe fetch` (read now), `FieldArcheryJobs.exe fit` (refit now), `FieldArcheryJobs.exe verify` (check the fit; no database needed).

### Moving to the FieldArcheryApi project (2.0.14, once)

The API project is now `server/api/FieldArcheryApi` (`FieldArcheryApi.csproj`, namespaces `FieldArcheryApi.*`). The old files must go, or `dotnet publish` finds two projects:

```powershell
Rename-Item C:\zScripts\FieldArcheryApi C:\zScripts\FieldArcheryApi.old
New-Item C:\zScripts\FieldArcheryApi -ItemType Directory | Out-Null
# copy everything in server\api\FieldArcheryApi into C:\zScripts\FieldArcheryApi (keep subfolders), and server\tests into C:\zScripts\FieldArcheryApi\tests
C:\zScripts\FieldArcheryApi\Update-Api.ps1
```

The connection string's key is now `FieldArchery` (the old `Nfas` key still works, so the live settings file needn't change straight away). To rename it: in `C:\inetpub\fieldarchery-api\appsettings.Production.json` change `"Nfas":` to `"FieldArchery":`, then `Restart-WebAppPool FieldArcheryApi`. The old `NfasApi.dll` left in `C:\inetpub\fieldarchery-api` isn't used (web.config now starts `FieldArcheryApi.dll`) and can be deleted. Delete `C:\zScripts\FieldArcheryApi.old` once the new one is live.

### Moving IIS to the new names (2.0.10, once)

```powershell
C:\zScripts\FieldArcheryApi\Rename-Iis.ps1
```

Windows PowerShell 5.1 as Administrator. Makes the `FieldArcheryApi` app pool (copying the old pool's settings), renames the site to `FieldArcheryApi` (still port 8085), copies `C:\inetpub\nfas-api` to `C:\inetpub\fieldarchery-api` with `appsettings.Production.json`, gives the new pool read access, starts it and checks `/health`. The old `FieldArcheryApi` pool and `C:\inetpub\nfas-api` stay until you delete them (`Remove-WebAppPool NfasApi`, `Remove-Item C:\inetpub\nfas-api -Recurse`). After this, `Update-Api.ps1` publishes to the new folder.

## API endpoints

| Method | Path | Body / auth | Returns |
|---|---|---|---|
| GET | `/health` | | API and database status, schema and API version |
| POST | `/api/auth/check` | `{ username }` | `{ username, exists }` |
| POST | `/api/auth/register` | `{ username, password, deviceName }` | 201 `{ archerId, username, token, expiresUtc }`; 409 `username_taken` |
| POST | `/api/auth/login` | `{ username, password, deviceName }` | 200 as above; 401 `invalid_credentials`; 423 `locked_out` |
| POST | `/api/auth/reset` | `{ username, code, newPassword, deviceName }` (API 2.4) | 200 as register, with a new `recoveryCode`. `code`: the recovery code or an Admin's reset code (case, spaces and dashes don't matter). Every other phone is signed out. 401 `invalid_code`; 423 `locked_out` (wrong codes count like wrong passwords) |
| POST | `/api/me/delete` | Bearer token; `{ password }` (API 2.4.02) | 204: the account and everything about it deleted (rounds, friends, requests, group places, private locations; public locations kept, unlinked). 401 `wrong_password`; 409 `is_owner` |
| GET / POST | `/delete-account`, `/privacy` | Web pages (API 2.4.02) | Delete an account with username and password; the privacy policy. Linked from the Play listing |
| POST | `/api/locations/nearby` | Bearer token; `{ latitude, longitude, radiusMetres? }` (API 2.4.02) | As the GET, with the position in the body so it isn't in web logs |
| POST | `/api/auth/recovery-code` | Bearer token; `{ password }` (API 2.4) | `{ recoveryCode }`, a new one (the old one stops working); 401 `wrong_password` |
| POST | `/api/auth/logout` | `Authorization: Bearer <token>` | 204, revokes this phone's token |
| GET | `/api/me` | `Authorization: Bearer <token>` | `{ archerId, username, createdUtc, role, hasRecoveryCode, duplicateLocations }`; role is `USER`, `ADMIN` or `OWNER`; `duplicateLocations` is the number of possible duplicate locations waiting (Admins only, 0 for Users); `roundOffers` is the number of rounds friends have scored for you, waiting to be accepted |
| PUT | `/api/rounds/{roundId}` | Bearer token; whole round (below) | `{ roundId, result: "saved" \| "unchanged", serverUpdatedUtc }`; 400 `invalid_round` with `problems` |
| GET | `/api/rounds/{roundId}` | Bearer token | The stored round (own rounds only), with `isDeleted` and `serverUpdatedUtc` |
| DELETE | `/api/rounds/{roundId}` | Bearer token | 204; marks it deleted (kept so other phones find out) |
| GET | `/api/friends` | Bearer token | `{ friends: [{ archerId, username, friendsSinceUtc, rounds, bestTotal, lastShootDate, averagePerAnimal, woodsmanRounds, woodsmanBest, recent }], incoming: [...], outgoing: [...] }`; rounds / best / average are Big Game; `recent` (API 2.9): their latest 20 finished Big Game rounds with each bow style `{ shootDate, targetCount, animalCount, archerClass, ageGroup, bowStyle, totalPoints, targetsScored }` for their personal handicaps (2.4–2.8: latest 10 in all) |
| POST | `/api/friends/requests` | Bearer token; `{ username }` | 201 `requested`; 200 `accepted` if they'd already asked you; 404 `no_such_user`; 409 `already_requested` / `already_friends`; 400 `cannot_friend_self` |
| POST | `/api/friends/{archerId}/accept` | Bearer token | 200; 404 `no_request` unless they asked you |
| DELETE | `/api/friends/{archerId}` | Bearer token | 204: decline, cancel or unfriend |
| GET | `/api/friends/{archerId}/rounds` | Bearer token | `{ rounds: [{ roundId, shootDate, targetCount, archerClass, ageGroup, bowStyle, totalPoints, locationName }] }`: their finished rounds, newest first; 404 `not_friends` |
| GET | `/api/friends/{archerId}/rounds/{roundId}` | Bearer token | One finished round with targets and animals (read-only) |
| GET | `/api/rounds?since=&afterId=&limit=` | Bearer token | `{ rounds: [...], more }`: own rounds changed after the cursor, oldest change first, deleted ones included; full rounds with targets and animals. No `since` = everything. Page with the last round's `serverUpdatedUtc` + `roundId`. |

| PUT | `/api/groups/{groupId}` | Host; `{ code, method, roundType, targetCount, shootDate, locationId, status, members, order?, scorers?, startTarget? }` | The group (as GET). The host's phone makes the id and the 6-character code (`ABCDEFGHJKMNPQRSTUVWXYZ23456789`). `method`: `SINGLE` (one scorer, others watching), `DOUBLE` (two scorers, saved when both match), `MULTI` (3+ scorers, nobody scores their own card, two per card, shared evenly), `SELF`. `status`: `OPEN` → `STARTED` → `CLOSED`. `members`: the host (`HOST`), friends added by name (`FRIEND`, must be friends) and guests (`GUEST`, `guestName`), each `{ memberId, kind, archerId?, guestName?, archerClass, ageGroup, bowStyle, roundId? }`; archers who joined stay. `scorers`: `[{ cardMemberId, scorerMemberId }]`; left out when starting = the usual ones. 409 `code_taken` / `group_started` / `group_closed`; 400 `invalid_group` / `invalid_scorers` with `problems` |
| POST | `/api/groups/join` | `{ code, groupId?, memberId, archerClass, ageGroup, bowStyle, roundId? }` | The group. Joining again updates your details; a friend the host added becomes a joined member. 404 `no_such_group` if no open group has the code (codes last 24 hours or until closed) |
| GET | `/api/groups` | Bearer token | `{ groups: [...] }`: groups you're in, last 30 days, newest first |
| GET | `/api/groups/{groupId}?since=` | Members only | `{ group fields, me, members: [{ memberId, kind, archerId, name, …, scorers }], entries, cells: [{ targetNo, cardMemberId, state, points }], totals: [{ memberId, points, targetsDone }], serverTimeUtc }`. `entries` changed after `since` (pass the last `serverTimeUtc`); `state` `WAITING` / `MATCHED` / `MISMATCH` |
| PUT | `/api/groups/{groupId}/entries` | Scorers; `{ entries: [{ targetNo, cardMemberId, scorerMemberId, target, enteredUtc }] }` | `{ results: [{ targetNo, cardMemberId, state, points }] }`. `target` is one target as in a round upload, checked against the rules. A newer entry from the same scorer replaces the older one. Your own entries (the host may pass on others'). |
| DELETE | `/api/groups/{groupId}/members/{memberId}` | Host, or the archer themselves | 204; 409 `is_host` |
| POST | `/api/groups/{groupId}/host` | A joined archer (API 2.3.01) | The group. Takes over as host when the host's phone has gone: the caller becomes `HOST`, the old host a `FRIEND` card (joining again on a phone makes them `JOINED`). Friends already in the group stay when the new host sends it, even if they aren't the new host's friends. 404 if not in the group; 403 `not_a_scorer` for friends / guests; 409 `group_closed` |
| PUT | `/api/offers/{roundId}` | `{ forArcherId, groupId?, round }` | `{ roundId, result: "offered" }`: a finished round you scored for a friend, waiting for them. Send again to correct it. 404 `not_a_friend`; 409 `round_exists` once accepted |
| GET | `/api/offers` | Bearer token | `{ received: [...], sent: [...] }` summaries (who, date, type, class, bow, total, location) |
| GET | `/api/offers/{roundId}` | The friend or the scorer | `{ offer, round }` |
| POST | `/api/offers/{roundId}/accept` | The friend | `{ roundId, result: "accepted", serverUpdatedUtc }`: now the friend's round (downloads to their phones on the next sync) |
| POST | `/api/offers/{roundId}/decline` | The friend | 204, deleted |
| DELETE | `/api/offers/{roundId}` | The scorer | 204, withdrawn |
| GET | `/api/admin/archers` | Bearer token, Admin or Owner | `{ archers: [{ archerId, username, role, createdUtc, lastLoginUtc }] }`, Owner first, then Admins; 403 `not_admin` |
| POST | `/api/admin/archers/{archerId}/reset-code` | Bearer token; Admins for users, the Owner for anyone but themselves (API 2.4) | `{ username, code, expiresUtc }`: a one-off password reset code, 24 hours (replaces any earlier one). 403 `not_admin` / `not_owner`; 400 `not_yourself`; 404 `no_such_archer` |
| PUT | `/api/admin/archers/{archerId}/role` | Bearer token, Owner only; `{ role: "ADMIN" \| "USER" }` | `{ archerId, username, role }`; 403 `not_owner` / `not_admin`; 400 `invalid_role`; 404 `no_such_archer`; 409 `cannot_change_owner` |
| GET | `/api/locations/nearby?lat=&lon=&radius=` | Bearer token | `{ locations: [{ locationId, name, distanceMetres }], catchmentMetres }`, nearest first. Radius in metres: default the catchment (402 m, a quarter mile, `Locations:CatchmentMetres`), max 10 km. The position isn't stored. |
| POST | `/api/locations` | Bearer token; `{ name, latitude, longitude, isPrivate? }` or (API 3.0) `{ name, noPin: true, isPrivate? }` for a name-only location | 201 `{ locationId, name }`; 409 `name_taken` with `existing: { locationId, name }`; 400 `invalid_name` (2–60 characters) / `invalid_position`; a name-only location has no map pin: not on the map, never matched nearby, still on scorecards and with a page |
| GET | `/api/locations/named` | Bearer token (API 3.0) | `{ locations: [{ locationId, name, isPrivate, myRounds, rounds }] }`: name-only locations you can see (public, and your own private ones), by name, for picking one for a round |
| PUT | `/api/locations/{locationId}/position` | Bearer token; `{ latitude, longitude }` (API 3.0) | `{ locationId, name, latitude, longitude }`: puts a name-only location on the map. Its creator or an Admin; 403 `not_creator`; 404 `no_such_location`; 409 `already_placed`; 400 `invalid_position` |
| POST | `/api/locations/resolve` | Bearer token; `{ ids: [...] }` | `{ locations: [{ requestedId, locationId, name }] }`: current id and name for each known id (merged ones point at the location kept) |
| GET | `/api/admin/locations` | Bearer token, Admin or Owner | `{ locations: [{ locationId, name, latitude, longitude, createdUtc, createdBy, rounds, isPrivate, hidden }] }`: public locations and your own private ones; the Owner also gets other people's private locations with `hidden: true`, name "Private location" and no position (2.0.17); 403 `not_admin` |
| GET | `/api/locations/map` | Bearer token | `{ locations: [{ locationId, name, latitude, longitude, isPrivate, myRounds, rounds }] }`: public live locations plus your own private ones, not name-only ones (3.0); finished rounds by you / by everyone |
| GET | `/api/locations/{locationId}/page` | Bearer token | `{ location, myRounds, friends: [{ archerId, username, roundType, rounds, bestTotal, averagePerAnimal, average36, average40, roundsList }], bowStyles: [{ roundType, bowStyle, archers, rounds, bestTotal, averageTotal, averagePerAnimal, average36, average40 }] }`. `average36` / `average40` (2.0.05) = average per animal × 36 / 40. Round summaries here and in `/api/friends/{id}/rounds` include `animalCount`, `predatorPrey`, `fans`. Finished rounds only. Private: creator and Admins only (404 otherwise), no friends / bowStyles. API 3.0: `location.latitude` / `longitude` null for a name-only location, `location.canPlace` true when you (its creator or an Admin) can put it on the map. Friends' rounds open with `/api/friends/{id}/rounds/{roundId}`. |
| GET | `/api/admin/locations/duplicates` | Admin or Owner | `{ pairs: [{ a, b, distanceMetres, reasons }] }`: live locations within the catchment (`nearby`) or with the same / a similar name (`same_name`, `similar_name`; ignores case, punctuation and words like Archers, Bowmen, Club, Field), not yet approved. Most likely first. `distanceMetres` null when either has no map pin (3.0; names only). |
| POST | `/api/admin/locations/duplicates/approve` | Admin or Owner; `{ aId, bId }` | 204: they're different places, stop flagging them |
| PUT | `/api/admin/locations/{locationId}` | Admin or Owner; `{ name, isPrivate? }` | `{ locationId, name }`; 409 `name_taken`; 404 `no_such_location` (also for someone else's private location); 403 `not_creator` if making someone else's location private |
| POST | `/api/admin/locations/{locationId}/merge` | Admin or Owner; `{ intoId }` | `{ locationId, name, roundsMoved }`: the duplicate's rounds move to `intoId`; 400 `same_location`; 404 if either is missing or already merged |
| GET | `/api/handicap` | (no sign-in) | 2.2.01: the active handicap model for the app: `{ modelId, fittedUtc, dataThroughYear, reference, scale: "logit", maxPerTarget: 24, yearZero: 2014, factors: [{ kind, code, value, low, high }] }` (kinds BOW, SLOPE, BOW3D, CLASS, BOWSEX); 404 `no_handicap` until the job has fitted one. Formula in `docs/HANDICAP.md` 2.9: also `BASE:ALL`, the typical championship level (average of the latest five Nationals' and five 3Ds' EVENT factors), for the class multipliers |
| GET | `/api/admin/handicap` | Admins and Owner | `{ events, models, runs }`: every championship (status, targets per day and where that came from), the last 20 models with their test results, the last 10 job runs |
| PUT | `/api/admin/handicap/events/{NATIONALS\|3DS}/{year}` | Admins and Owner; `{ targetsPerDay: 36 \| 40 \| null }` | Sets the target count by hand (`null` = back to the job's); the job refits on its next weekly run. 400 `invalid_targets`, 404 `no_such_event` |
| POST | `/api/admin/handicap/fetch` | Admins and Owner | 202: the job's next weekly run reads nfas.net; 409 `already_requested` |
| POST | `/api/admin/handicap/models/{id}/activate` | Owner only | Uses a model the job left waiting; 403 `not_owner`, 404 `no_such_model` |

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

- `"startTarget"` (API 2.7, optional): the target the archer started on, 1 to `targetCount` (`null` when not given). Only noted on the scorecard: targets keep their own numbers.
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

## Testing the start target (2.7)

Run `sql/013_start_target.sql` in SSMS first (schema 9), then publish API 2.7.

```powershell
.\Test-StartTarget.ps1
```

A round's `startTarget` is kept and comes back, can be left out, and must be 1 to the number of targets; a live group's is set by the host and can be cleared. Every line should start with OK; tidy up with `900_delete_test_accounts.sql`.

## Loading historical scorecards (3.0.01)

```powershell
.\tools\Import-HistoricalRounds.ps1 -CsvPath <csv> -Username <your username>          # dry run: lists what it would do
.\tools\Import-HistoricalRounds.ps1 -CsvPath <csv> -Username <your username> -Apply   # loads them
```

PowerShell 7; needs API 3.0 and schema 10. Loads one archer's rows from the scorecard CSV as finished Big Game rounds through `PUT /api/rounds/{id}` (ids from the photo file names, so it's safe to run again), using an existing location with the club's name or making a name-only one. See the comments at the top of the script.

## Testing name-only locations (3.0)

```powershell
.\Test-NameOnlyLocations.ps1
```

Needs `014_location_no_pin.sql` run. A location made with a name and no map pin: rounds can be saved at it, everyone sees it in `/api/locations/named`, it's not on the map or found nearby, its page has no position; only its creator (or an Admin) can put it on the map, once, after which it's an ordinary location. Every line should start with OK; tidy up with `900_delete_test_accounts.sql`.

## Testing password reset (2.4)

Run `sql/012_password_reset.sql` in SSMS first (schema 8), then publish API 2.4.

```powershell
.\Test-Reset.ps1 -Username Turismo
```

Recovery codes (with a new account, used once, renewed, a new one from Profile needs the password), wrong codes and the lock-out, every other phone signed out, the Owner's reset codes (once, not for yourself, not by a User), and friends' latest rounds per bow for their handicaps (2.9). `Test-LocationPage.ps1` also checks the per-class rows. Every line should start with OK; tidy up with `900_delete_test_accounts.sql`.

## Testing account deletion and the privacy page (2.4.02)

```powershell
.\Test-Delete.ps1
```

Deleting needs the password; after deleting, the account can't sign in, its phones are signed out, the username is free, and it's gone from friends and groups; the web page refuses a wrong password and deletes with the right one; the privacy page is there with the contact email; nearby search works with the position in the body.

## Running every test (2.0.16)

```powershell
.\Run-AllTests.ps1 -Username Turismo
```

Runs the automatic tests one after another (Accounts, Rounds, Friends, Woodsman, LocationPage, CourseAverages, Groups, Offers, Delete, and with `-Username` the Owner ones: Roles, Locations, LocationReview, Handicap, Reset, which each ask for the password) and prints a summary. It waits a minute before Test-Groups and each Owner test (2.4), because the API allows 30 sign-ins / new accounts a minute from one address and the earlier tests use most of that (otherwise 429 Too Many Requests); the whole run takes about 7 minutes. Test-Download and Test-FriendView aren't included because they need the phone. Tidy up with `900_delete_test_accounts.sql`.

## Testing the handicap (2.2.01)

After the job has read nfas.net and fitted a model (see *Server jobs* above):

```powershell
.\Test-Handicap.ps1 -Username Turismo
```

The download the app uses (no sign-in, factors for every bow style with 90% ranges), that only Admins and the Owner can use Admin → Handicap, that stored championships have target counts (Nationals 2019 = 40, 2020 none), one active model, and bad requests refused. It doesn't change anything. Tidy up with `900_delete_test_accounts.sql`.

## Testing course averages and round make-up (2.0.05)

```powershell
.\Test-CourseAverages.ps1
```

Two test accounts (friends) and a test location: 36- and 40-target averages are the per-animal average × 36 / 40, counting predator/prey as 2 and a fan as its animals; rounds report animals, predator/prey and fans on location pages and in friends' round lists. Every line should start with OK; tidy up with `900_delete_test_accounts.sql`.

## Testing group scoring (1.9.06)

```powershell
.\Test-Groups.ps1
.\Test-Offers.ps1
```

`Test-Groups.ps1`: five test accounts. Join by code (wrong code, case, joining twice, code clash, closed group), who can see a group, the four methods – single scorer (everyone scored by the host, others can't), double scoring (waiting, matched, mismatch then re-entry, non-scorers refused), three or more scorers (needs three phones, nobody scores their own card, two per card, shared evenly, re-shared when someone joins or leaves) and self scoring – live totals, `since=`, leaving and removing, taking over as host (2.3.01), closing, and uploading a round with the group id.
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

## Testing private locations and duplicate alerts (1.7.01, rules changed in 2.0.17)

```powershell
.\Test-LocationReview.ps1 -Username <your username>
```

Asks for your password. Two test accounts and test locations in the North Sea: a private location is only matched and named for its creator (friends and Admins see "Private location"; not on an Admin's map, no page for them; the Owner sees it exists in the admin list, hidden); two people's private locations near each other aren't flagged; a private location next to a public club is flagged to Admins with its name, can be merged into the club (its rounds move) but nothing can be merged into it, and can be approved as a different place; Admins can't rename it or make others' locations private; similar names far apart are flagged and can be approved; the /api/me count. Every line should start with OK; tidy up with `900_delete_test_accounts.sql`.
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
cd C:\src\NFAS-Scoring\server\api\FieldArcheryApi
dotnet publish -c Release -o C:\inetpub\fieldarchery-api
```

To update later: stop the site (or app pool), run the same publish command, start it again.

### 5. Add the connection string (server only, never in Git)

```powershell
Copy-Item .\appsettings.Production.json.example C:\inetpub\fieldarchery-api\appsettings.Production.json
notepad C:\inetpub\fieldarchery-api\appsettings.Production.json
```

Replace `PUT_THE_NFAS_API_PASSWORD_HERE` with the API login's password (`fieldarchery_api`, see `009_fieldarchery_api_permissions.sql`). Publishing again won't overwrite this file.

### 6. Create the IIS site

PowerShell as Administrator. Port 8085 is a suggestion; use any free port.

```powershell
Import-Module WebAdministration
New-WebAppPool -Name FieldArcheryApi
Set-ItemProperty IIS:\AppPools\FieldArcheryApi -Name managedRuntimeVersion -Value ''   # "No Managed Code"
New-Website -Name FieldArcheryApi -Port 8085 -PhysicalPath C:\inetpub\fieldarchery-api -ApplicationPool FieldArcheryApi
New-NetFirewallRule -DisplayName 'Field Archery Scoring API (8085)' -Direction Inbound -Protocol TCP -LocalPort 8085 -Action Allow
```

### 7. Test on Odin

Open http://localhost:8085/health. Expected:

```json
{"status":"ok","database":"ok","schemaVersion":1,"apiVersion":"1.2.02","serverTimeUtc":"..."}
```

- `"database":"unreachable"` (503): the details are in **Event Viewer → Windows Logs → Application** (source *FieldArcheryApi* / *.NET Runtime*). Usually the password, or step 2.
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
