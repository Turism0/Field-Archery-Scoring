# NFAS Big Game Scorer

Android app for scoring NFAS Big Game rounds. Works fully offline; central sync to a self-hosted server comes in iteration 2.

**Stack:** Kotlin, Jetpack Compose (Material 3), Room (SQLite), Navigation Compose, WorkManager, osmdroid (OpenStreetMap). minSdk 26, targetSdk 35, compileSdk 36. AGP 9.5 (built-in Kotlin 2.4), Gradle 9.6, KSP 2.3, Room 2.8.

## Version 2.0.03

- **2.0.03:** location pages and friends' rounds in the app. The bow-style table's **Avg** column is now **Avg 36** / **Avg 40** (or 18 / 20 for a half course): the per-animal average over a whole round of the course's current size, so predator/prey and fans no longer push it up and rounds of different lengths compare fairly. The course size comes from the latest round shot there (courses change between 36 and 40 a couple of times a year). Friends at a location show their course average too. Friends' round lists and location pages now say e.g. "36 targets · 37 animals (1 predator/prey)". Needs API 2.0.02 (older servers: just the number of targets, and Avg shows –).
- **2.0.02:** server: location pages give the course size (`courseTargets`: the number of targets on the latest finished round there that's 18, 20, 36 or 40, else the latest round's count rounded to the nearest of those) and a `courseAverage` (average per animal × course size) for each friend and bow style; round summaries on location pages and in friends' round lists include `animalCount`, `predatorPrey` and `fans`. Test script `server/tests/Test-CourseSize.ps1`. No database changes.
- **2.0.01:** rounds with predator/prey or fan targets say so: history, unfinished rounds and the scorecard show e.g. "36 targets · 37 animals (1 predator/prey)", so a higher total than a plain 36-target round makes sense. Averages were already per animal (predator/prey = 2, fan = its animals) on scorecards, friends' summaries and location stats. No API or database changes.
- **2.0:** **group scoring on one phone** (roadmap step 4, first app part). On the new round screen choose **Group** (or Just me): you plus friends (picked from your friends list, which the app now remembers so it works with no signal) and guests (name only), each with their own class, age group and bow style, in scoring order (move up / down), up to 8 archers. Scoring goes **archer by archer** on each target: the title says who's being scored ("Target 3 · Dave"), **Next: Dave** moves to the next archer and after the last one to the next target; a strip shows everyone's running total with a tick once they're scored on this target (tap someone to score them). Choosing a target type (predator/prey, fan) copies it to everyone not yet scored on that target. Round details change the date and number of targets for the whole group, and class / age group / bow for that archer; the location is shared. Finishing finishes everyone's round. Your round is in your history ("group of n") with a **group scorecard** card showing everyone's totals (open anyone's scorecard). Friends' rounds are sent to them as **requests** when finished (status shown on the group card); guests' scores stay on this phone and are deleted after 30 days. On the receiving side, the **Friends** tile shows a badge for rounds scored for you; Friends lists them under **Rounds scored for you** with Scorecard, Accept and Decline. Accepted rounds download into your history. Phone database v4 (group columns; existing rounds unchanged). Needs API 1.9.06.
- **1.9.09:** two `Test-Groups.ps1` fixes, both in the script rather than the API: the `since=` check sent the time in PowerShell's local text form (no milliseconds, read as month/day), so every entry came back; the self-scoring check read the first letter of the scorer's id when there was only one scorer. No app or API changes.
- **1.9.08:** `Test-Groups.ps1` fix: one call still used the old helper name `Join`, so the script stopped at "a wrong code is not found". No app or API changes.
- **1.9.07:** the server's address is now **https://fieldarchery.fnurble.co.uk** (was nfas.fnurble.co.uk). The app, the test scripts and the docs all use the new address. No API or database changes. Install this app build: older builds still look for the old address.
- **1.9.06:** server side of **group scoring** (roadmap step 4). `server/sql/008_group_scoring.sql` (schema v6). A group shoots the same round together; the host's phone makes the group and a 6-character join code (no 0/O/1/I/L). Members: the host, archers who **join** with the code on their own phone, **friends** added by name and **guests** (name only). Four scoring methods: **single scorer** (others watch), **double scoring** (two scorers enter every score; saved only when they match), **three or more scorers** (nobody scores their own card, two scorers per card, cards shared as evenly as possible, re-shared if someone joins or leaves) and **self scoring**. Entries are matched by the server (waiting / matched / mismatch) and everyone sees live totals. Rounds scored for a friend arrive as a **request** they accept (or decline) before it's in their history; `/api/me` gives the count for a badge. Groups (with guests' names and scores) are deleted after 30 days; codes stop working when the group closes or after 24 hours. Test scripts `server/tests/Test-Groups.ps1` and `Test-Offers.ps1`. No app changes yet (app 2.0 next).
- **1.9.05:** **Woodsman** rounds in the app (roadmap step 3). Choose Woodsman when starting a round. Each target: record each arrow in order (Kill, Wound or Miss, showing what it scores on that arrow) until a kill or three arrows; undo the last arrow; override and clear in the target's ⋮ menu. Scorecards show the arrows (e.g. "Wound, Kill (2nd)"); stats per target out of 30 (kills, wounds and misses count arrows; which arrow killed; first-arrow kill rate). Rounds are labelled "Woodsman ·" in lists, headers and friends' rounds; friends' summaries show Woodsman rounds and best separately; location pages split all-archer stats and friends by round type. Phone database v3 (round type, Woodsman arrows; existing rounds are Big Game). Needs API 1.9.01.
- **1.9.04:** `Test-Woodsman.ps1` fix: a loop variable `$b` overwrote the test account `$B` (PowerShell names ignore case), so the friend and location checks ran signed out. No app or API changes.
- **1.9.03:** `Test-Woodsman.ps1` shows the HTTP status and server message when the friends and location checks fail. No app or API changes.
- **1.9.02:** test script fix: `Test-Woodsman.ps1` built the round total in a way PowerShell left empty, so every Woodsman upload was refused before the rules were checked. No app or API changes.
- **1.9.01:** server side of round types, starting with **Woodsman** (roadmap step 3). `server/sql/007_round_types.sql` (schema v5): every round has a type (Big Game for all existing ones); Woodsman targets store their arrows in order (W wound 5, K kill 30 / 20 / 10 on arrow 1 / 2 / 3, M miss; nothing after a kill, up to 3). The API checks Woodsman rules (and keeps Big Game and Woodsman targets apart), round downloads and friends' rounds say which type they are, friends' summaries keep Big Game and Woodsman separate, and location stats have a row per round type (Woodsman average is per target, out of 30). Test script `server/tests/Test-Woodsman.ps1`. No app changes yet; older apps keep working (no type = Big Game).
- **1.8.05:** edit a round's details after it's started or finished: **Round details** in the scoring screen's ⋮ menu and **Edit round details** in the scorecard's ⋮ menu. Change the shoot date, number of targets, class, age group and bow style. Fewer targets removes the ones above (asks first if any are scored); more targets adds unscored ones and reopens a finished round. Scores aren't changed; a finished round uploads again with the new details.
- **1.8.04:** **New location** button on the Shoot locations map: tap it, then tap the map where the location is, name it (Private switch too) and it's added to the map. A name already used is refused with a message.
- **1.8.03:** more ways to set a round's location. The location sheet now also has a **Known locations** drop-down (every public location and your private ones, by name), shown even when the round has no GPS position. On **Choose on map**, tap the map itself where the shoot is to name a **new location** there (with the Private switch); a name already used offers the existing location instead.
- **1.8.02:** change a round's location by picking it on the map. The location sheet (tap the location line on the scoring screen or scorecard) has **Choose on map**: the map opens in pick mode, tap a pin then its pop-up to use that location. Works for rounds with no GPS position too. A finished round uploads again with its new location.
- **1.8.01:** map fix: tapping the pop-up over a pin now opens the location page too (as well as tapping the pin again).
- **1.8:** shoot location **map** and **location page**. New Home tile **Shoot locations** opens an OpenStreetMap map (osmdroid, no API key) of every public location plus your private ones: pins you've shot at are in the app's colour, others grey, with a small legend. Tap a pin for its name and round counts, tap again for the location page: your scorecards there (tap to open), friends who've shot there with rounds / best / average per animal (tap a friend for their rounds, tap a round for their scorecard), and all archers' stats per bow style (archers, rounds, best, average, per animal; no names). Private locations show only your own rounds. Needs API 1.7.03. New dependency `org.osmdroid:osmdroid-android` (Gradle sync needed).
- **1.7.03:** server side of the shoot location map and location page. `GET /api/locations/map`: every public location (plus your private ones) with how many finished rounds you and everyone have shot there. `GET /api/locations/{id}/page`: your rounds there, friends' rounds there with a summary each, and all-archer stats per bow style (archers, rounds, best, average, average per animal; no names). Private locations: only their creator and Admins can open the page, with no friends or all-archer sections. Test script `server/tests/Test-LocationPage.ps1`. No database or app changes.
- **1.7.02:** duplicate alerts and private locations in the app. Admins see a red count on the **Admin** tile when there are possible duplicate locations; Admin → **Possible duplicates** lists each pair (nearby / same name / similar name, distance, rounds, who named them) with **Keep 1st**, **Keep 2nd** (merge, with a confirm) or **Different places – stop flagging**. Naming a location has a **Private** switch (only you and admins see it; others shooting there aren't matched to it). Admin → Shoot locations shows a PRIVATE badge and can switch a location between private and public. Needs API 1.7.01.
- **1.7.01:** server side of possible-duplicate alerts and private locations. `server/sql/006_location_review_private.sql` (schema v4): locations can be **private** (only the archer who named them and Admins see the name; never auto-matched for anyone else; friends see "Private location"); location names only need to be unique among public locations. Admins get a list of **possible duplicates** (within the catchment, or the same / a similar name ignoring words like Archers, Bowmen, Club, Field) and can **approve** a pair as different places so it stops being flagged. `/api/me` tells Admins how many are waiting. Test script `server/tests/Test-LocationReview.ps1`. No app changes yet.
- **1.6.04:** Admin location tools (roadmap step 2c, completes shoot locations). Admin → **Shoot locations** lists every location with its rounds and who named it. Tap one to fix its name, or merge a duplicate into the location to keep (choices listed nearest first, with a confirm); its rounds move across. Your phone's rounds pick up the new names straight away; other phones on their next sync. Admins and the Owner only; uses the 1.5.01 API.
- **1.6.03:** the app tells "device offline" apart from "server unavailable". Home shows a small **Device offline** notice when the phone has no working internet, or **Server unavailable** when it's online but the server doesn't answer (or Nginx Proxy Manager says it's down: 502/503/504). Home checks the server when it opens, when the phone comes back online, and every 30 seconds while it's down; the notice goes as soon as it answers. Error messages say which it is, and say "the server" (no more "NFAS server").
- **1.6.02:** `server/api/NfasApi/Update-Api.ps1`: one command on Odin to stop the app pool, publish, start it again (always, even if the publish fails) and check `/health` shows the new version and the database. Test scripts now say plainly when the API isn't answering. No app or API changes (API stays 1.6.01).
- **1.6.01:** shoot location catchment cut from half a mile to a quarter mile (402 m) in the API (`Locations:CatchmentMetres` in `appsettings.json`). No app changes.
- **1.6:** shoot locations in the app (roadmap step 2b). Starting a round asks for location permission (once) and takes one GPS position in the background. Signed in with signal, the app finds the shoot location nearby (within a quarter mile from 1.6.01); if none is known it asks you to name it (first to shoot there names it). No signal: the position waits on the phone and is looked up on the next sync. A location line on the scoring screen and scorecard shows the name; tap it for **Not here?** (pick another location within 10 km, or name a new one), or to get a position now for a round that has none. Locations show on round lists, scorecards and friends' rounds; renamed or merged locations update on sync. Your own position never leaves the phone except to ask what's nearby. Phone database v2 (adds location columns; existing rounds keep working with no location).
- **1.5.01:** shoot locations in the database and API (roadmap step 2a). `server/sql/005_shoot_locations.sql` adds the `ShootLocation` table and a location on each round. New calls: locations near a position (half-mile catchment, or a wider radius for "Not here?"), name a new location (same name as an existing one is refused), resolve ids a phone holds (follows merges); Admins list, rename and merge locations. Rounds upload and download with an optional location; friends' rounds show the location name. Test script `server/tests/Test-Locations.ps1`; `900_delete_test_accounts.sql` also removes test locations. No app changes yet.
- **1.5:** roles in the app (roadmap step 1 done). The app learns the account's role when signing in and on every sync. Admins and the Owner get an **Admin** tile on Home ("Signed in as … · Owner/Admin" too); the Admin screen lists every account with its role, joined and last sign-in dates. The Owner switches Admin on or off per account; Admins see the list only. Everyone else sees nothing new. The server checks the role on every admin call.
- **1.4.03:** roles in the database and API (roadmap step 1, first half). `server/sql/004_roles.sql` gives every account a role (User, Admin or Owner; only one Owner) and makes your account the Owner. `/api/me` now returns the role; new admin calls list accounts (Admins and Owner) and set Admin on or off (Owner only). Test script `server/tests/Test-Roles.ps1`. No app changes yet.
- **1.4.02:** the app's version number now matches (1.4.02, shown in Android Settings → Apps → NFAS Big Game). New `server/tests/Test-FriendView.ps1`: a throwaway test friend sends you a request so you can check the Friends screens on the phone. No API changes.
- **1.4.01:** agreed plan for the next features recorded in `docs/DESIGN.md` (roles and Admin menu, shoot locations, round types with Woodsman). No app or API changes.
- **1.4:** Friends in the app: add friends by username, accept / decline / cancel requests, friends list with rounds, best and average per animal; tap a friend for their finished rounds and open any scorecard read-only. Remove a friend from their page. Completes the online features.
- **1.3.01:** friends in the API: request by username, accept / decline / cancel / unfriend, friends list with a summary per friend (rounds, best, average per animal), and read-only access to friends' finished rounds. Test script `server/tests/Test-Friends.ps1`. No app changes yet.
- **1.3:** new home screen with tiles for **Score a round**, **Score history** and **Friends** (coming next), plus sign-in / last-synced status and pull-to-sync. Score a round lists unfinished rounds to continue, or starts a new round via a **round type** choice (NFAS Big Game for now; more types can be added in `domain/RoundType.kt`). Score history shows finished rounds only.
- **1.2.11:** pull down on the home screen to sync now (upload and download), with a message saying how it went and a "Last synced" line. Sync problems are now reported instead of ignored.
- **1.2.10:** `server/tests/Test-Download.ps1`: acts as a second phone on your account to check the app downloads new, edited and deleted rounds. No app or API changes.
- **1.2.09:** download rounds: after uploading, the app fetches this archer's rounds changed on the server (new API `GET /api/rounds?since=`), so a new or reset phone gets all its rounds back after signing in, and two phones on one account stay in step, deletions included. Newest edit wins.
- **1.2.08:** the app uploads finished rounds (and deletions) in the background when signed in, using WorkManager: waits for signal, retries automatically, catches up on app start and after signing in. Round cards show Backed up / Waiting to upload / Couldn't upload.
- **1.2.07:** test script fix: `Test-Accounts.ps1` version check failed on API 1.2.05 and later. No app or API changes.
- **1.2.06:** API fix: dates are sent to SQL Server as `datetime2`, so re-sending the same round is correctly reported as unchanged (Dapper's default `datetime` rounded times by a few milliseconds).
- **1.2.05:** round upload in the API (`PUT/GET/DELETE /api/rounds/{id}`) with full Big Game rule checks, safe re-sends (newest edit wins) and soft delete; test script `server/tests/Test-Rounds.ps1`. No app changes yet.
- **1.2.04:** sign in / create account in the app (optional). Profile has an Account card; the Account screen checks the username, then asks for the password (existing user) or a new password typed twice (new user). Token stored encrypted with the Android Keystore and left out of backups.
- **1.2.03:** accounts in the API: check username, register, sign in / out, `/api/me`; password hashing, per-phone tokens, lockout and rate limiting. PowerShell test script `server/tests/Test-Accounts.ps1`. No app changes.
- **1.2.02:** API skeleton (`server/api/NfasApi`, .NET 10) with a `/health` check, and deployment steps for Odin (`server/README.md`). No app changes.
- **1.2.01:** server database scripts for the online features (`server/sql/`). No app changes.
- **1.2:** single-user offline scoring, Material 3 UI, running stats, end-of-round scorecard with edit.

Versioning: minor changes go 1.9.01, 1.9.02 … 1.9.99; major changes move to the next tenth (2.0). Numbers only ever go up: when a feature is split into server then app releases, the app release takes the next number after the server ones.

## Status: iteration 2 (online) done: accounts, two-way sync, friends, roles and Admin menu, shoot locations. Next: group scoring; more history and stats and a map of shoot locations, then round types (Woodsman), round types (Woodsman); see `docs/DESIGN.md`

### Screens

- **Home:** "Device offline" / "Server unavailable" notice when needed, sign-in and "last synced" status (tap for Profile), and three tiles (plus **Admin** for Admins and the Owner):
  - **Score a round** (shows how many are in progress) → continue an unfinished round, or **Start a new round** → choose the **round type** → New round.
  - **Score history** (finished rounds and best score) → finished rounds only, newest first, with Backed up / Waiting / Couldn't upload labels; tap for the scorecard.
  - **Friends** → add by username, requests, friends with a summary each → a friend's finished rounds → their scorecard (read-only).
  Pull down on Home or Score history to sync now.
- **Friends** (signed in, needs signal): add a friend by username (they accept before either sees the other's rounds); incoming requests with Accept / Decline, outgoing with Cancel; each friend shows rounds, best total, average per animal and last shoot. Tap a friend for their finished rounds (⋮ → Remove friend), and a round for its scorecard, same layout as yours but with no editing. Pull down to refresh. Nothing about friends is stored on the phone.
- **Shoot locations** (signed in, needs signal): map of locations (shot-at pins highlighted); **New location** button, then tap the map to add one. Tap a pin, then its name, for the **location page**: your scorecards there, friends' rounds and stats there, all-archer stats by bow style.
- **Admin** (Admins and the Owner only, needs signal): every account with its role (Owner, Admin, User), joined and last sign-in. The Owner has an Admin switch on each account; Admins see the list only. **Possible duplicates** (count shown on the Admin tile) → merge, or mark as different places. **Shoot locations** → rename, make private / public, or merge a duplicate into another (nearest first, confirm first). Pull down to refresh.
- **Round type:** NFAS Big Game or **Woodsman**. Add new types in `domain/RoundType.kt`.
- **Woodsman scoring:** the three arrows so far, then Kill / Wound / Miss buttons for the next arrow (with points for that arrow) until a kill or three arrows; Undo last arrow; override / clear in ⋮.
- **Profile:** Account card (sign in / create account, or "Signed in as …" with Sign out); name and date of birth (phone only); class (Ladies/Gents), age group (automatic from date of birth, or manual), main bow style.
- **Account** (optional, needs signal): enter a username → if it exists, "That username already exists" and enter the password; if not, choose a password (typed twice) to create the account. No email; no password reset yet. Scoring works without an account.
- **New round:** date, number of targets (quick picks 18 / 20 / 36 / 40), class and age group filled in from the profile, bow style defaulting to the main style. Changes only affect that round.
- **Shoot location** (signed in): a line under the scoring header and on the scorecard shows where the round is. New location → asked to name it once. Tap for **Not here?**: nearby locations (10 km) or name a new one (optionally **Private**: only you and admins see it); a name already used is offered instead of duplicated. No position → "Get position now". **Known locations** drop-down, or **Choose on map**: pick a pin, or tap the map to name a new location there.
- **Round details** (⋮ on the scoring screen or scorecard): change date, targets, class, age group, bow style.
- **Scoring:**
  - Big Game card layout: 1st / 2nd / 3rd arrow × inner kill / kill / wound, plus miss. Tap a selected score again to clear it.
  - Target type (Standard, Predator / Prey, Fan) is a small chip on the target; the fan animal count only appears for fans. Override score and clear target are in the target's ⋮ menu.
  - No skipping ahead: Next is disabled until the current target is scored. Earlier targets can always be revisited and corrected.
  - Running stats panel and a progress bar across the top.
  - Scorecard sheet (list icon) for a quick look mid-round; targets not yet reachable are locked.
- **Scorecard:** opens when a round is finished and when opening a past round. Shows the total, full stats and every target (predator/prey and fans expand per animal). **Edit scores**, or tapping a target, opens the scoring screen to make changes; on a finished round the last target's button says **Done** and returns to the scorecard. Reopen and delete are in the ⋮ menu.

### Stats

| Stat | How it's worked out |
|---|---|
| Total | All points, including overrides and partly scored targets |
| Avg per animal | Points ÷ animals on fully scored targets. Predator/prey = 2 animals, fan = its animal count, standard = 1. Out of 24 |
| Scored | Fully scored targets ÷ targets in the round |
| Inner kill / kill / wound / miss | Count of animals with that result (overridden targets left out, as no arrows were recorded) |
| 1st / 2nd / 3rd arrow | Count of animals scored on that arrow |
| Of maximum | Points on fully scored targets ÷ (24 × animals) |
| 1st arrow / Kills / Misses (%) | Share of animals on fully scored, non-overridden targets scored on the 1st arrow / inner kill or kill / missed |

A prey left with no arrows (the predator took all three) counts as an animal scoring 0.

### Look and feel

Material 3 with Material You dynamic colour on Android 12+ (NFAS green palette otherwise; switch with `USE_DYNAMIC_COLOUR` in `ui/theme/Theme.kt`), light and dark themes.

## Scoring rules implemented

| Arrow at that animal | Inner kill | Kill | Wound |
|---|---|---|---|
| 1st | 24 | 20 | 16 |
| 2nd | – | 14 | 10 |
| 3rd | – | 8 | 4 |

No scoring arrow = 0.

- **Predator/Prey:** 3 arrows shared. Shoot the predator until it scores, then the prey with the remaining arrows. Each animal is scored by arrows shot at *that* animal (max 48). Predator not scored → prey gets nothing.
- **Fan:** 2–6 animals on one peg, each up to 3 arrows, normal scoring (max 24 × animals).
- **Override:** any target can have a manual score, which replaces the calculated score and is flagged on the card.

**Age groups** (age on the day of the shoot): Adult 16+, Junior 12–15, Cub under 12.

**Bow styles:** AFB, BB, BH, CL, XB, TXB, FS, HT, LB, PV, TD, TB, UL.

## Server

Rounds sync to SQL Server 2025 on Mimir through an ASP.NET Core API on Odin; the app never talks to SQL Server directly. Accounts are a username and password (no email, no real names on the server). Friends (request + accept) see each other's finished rounds read-only.

Database setup, run in SSMS in order (details in `docs/DESIGN.md`):

1. `server/sql/001_create_database_and_login.sql` as sysadmin. Change the `nfas_api` password first.
2. `server/sql/002_create_tables.sql` as sysadmin.
3. `server/sql/003_test_as_api_login.sql` connected as `nfas_api`; every message should start with OK.

API: ASP.NET Core on .NET 10 under IIS on Odin, at https://fieldarchery.fnurble.co.uk. Build, update and test steps and the list of endpoints are in `server/README.md`.

## Building

Open the folder in Android Studio and run the `app` configuration. Unit tests for the scoring engine: `./gradlew test`.

## Project layout

```
app/src/main/java/uk/co/fnurble/nfas/
  domain/        Pure Kotlin scoring engine, age groups, bow styles (unit tested)
  data/local/    Room entities, DAOs, database
  data/          Repositories (domain <-> database mapping)
  ui/            ViewModels, navigation, theme
  ui/screens/    Home, Score a round, Round type, New round, Scoring, Scorecard (+ sheet), Score history,
                 Friends, Friend's rounds, Friend's scorecard, Profile, Account
  ui/components/ Shared form fields, section cards, running stats panel
server/sql/      Database and login scripts for SQL Server (run in order)
server/api/      ASP.NET Core API (.NET 10) for IIS on Odin; deploy steps in server/README.md
server/tests/    PowerShell scripts that test the live API
docs/DESIGN.md   Architecture, data model, privacy and sync design
```
