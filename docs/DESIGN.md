# Design

## Goals

- Score NFAS Big Game rounds on the phone with no signal; sync to a central database when there is signal.
- Keep personal data on the phone. The server holds scores, a username and the categories needed for results (class, age group, bow style) — no real names, no dates of birth.
- Build iteratively.

## Iterations

1. **Single-user scoring, offline** ← done (1.2: Material 3 UI, no skipping ahead, running stats, end-of-round scorecard with edit; 1.3: new home screen, round type choice, score history)
2. **Online** ← done (1.4). Steps, each tested before the next:
   1. Database and API login on Mimir (`server/sql/`) ← 1.2.01, tested
   2. API skeleton on Odin (IIS) behind Nginx Proxy Manager, health check that reaches the database ← 1.2.02, tested
   3. Accounts in the API: check username, register, sign in ← 1.2.03, tested
   4. Accounts in the app (optional sign-in, Account card on Profile) ← 1.2.04, tested
   5. Upload rounds: **finished rounds only** (the phone saves every change as you score, so in-progress rounds aren't at risk). Edits to finished rounds and deletions sync too.
      - 5a. API: `PUT/GET/DELETE /api/rounds/{id}` with server-side rule checks ← 1.2.05 (1.2.06: exact datetime2 parameters)
      - 5b. App: background upload with WorkManager when signed in and online; synced / waiting indicator ← 1.2.08
   6. Download rounds: `GET /api/rounds?since=` plus a download pass after every upload; a new phone gets everything after signing in, two phones stay in step ← 1.2.09
   7. Friends: request / accept, view friends' finished rounds read-only
      - 7a. API: `/api/friends` (list with summaries, requests, accept, remove, friends' finished rounds) ← 1.3.01
      - 7b. App: Friends tile → friends screen, friend's rounds, read-only scorecard ← 1.4
3. **Next (agreed order, each step tested before the next):**
   0. Housekeeping: app version number to match (1.4.x); test Friends.
   1. **Roles and Admin menu:** account role Owner (Pete) / Admin / User; API checks every admin action; only the Owner can grant or remove Admin; Admin menu in the app visible to Admins and the Owner only. *Done: DB + API 1.4.03, app 1.5.* The role is read from the database on every request, so changes apply at once.
   2. **Shoot locations:**
      - 2a. Database + API: `ShootLocation` (name, latitude, longitude); nearest-location lookup within a quarter mile (402 m, `Locations:CatchmentMetres`; was half a mile until 1.6.01); add a location; Admin rename and merge. *Done in 1.5.01.* Same name as a live location (any case) is refused and the existing one offered. Rounds keep only the location id; a merged id is followed to the location kept.
      - 2b. App: GPS fix when a new round starts. With signal: look it up; known club → name filled in; new → ask for a name once (whoever shoots there first names it). No signal: keep the fix with the round, look it up when there's signal, ask for a name only if it's new. **"Not here?"** lets the archer pick another location within 10 km or name a new one (agreed 1 Nov 2026). Location shown on rounds, scorecards, history and friends' scorecards. *Done in 1.6:* Room v2 adds `location_id`, `location_name`, `location_state` (PENDING_LOOKUP / NEEDS_NAME / SET), `fix_lat`, `fix_lon` (phone only) to `round`; `data/LocationRepository.kt` takes the fix (`LocationManagerCompat`, fused/GPS/network, 30 s timeout), looks up pending rounds after the fix and on every sync, and refreshes names via `/api/locations/resolve`. Setting a location counts as an edit, so finished rounds re-upload with it.
      - 2c. Admin: rename locations, merge duplicates (two offline phones can create the same new club). *Done in 1.6.04* (Admin → Shoot locations).
      - Privacy: the phone's raw position stays on the phone until matched; the server stores only which location a round was at (club coordinates are fine to store).
   2d. **Location extras (agreed 2 Nov 2026, in this order):** (1) possible-duplicate alerts for Admins: a badge on the Admin tile and a list (nearby, same or similar name) with Merge or **Approve** (approved pairs stop showing); (2) **private** locations: only the creator and Admins see the name, never matched for anyone else, friends see "Private location", left out of the map and all-archer stats; (3) a **location page**: your scorecards there, friends' scorecards and stats there, and all-archer stats per bow style; (4) a **map** of shoot locations (OpenStreetMap via osmdroid, no API key), ones you've shot at highlighted, tap for the location page. *Server for (1) and (2) done in 1.7.01; app in 1.7.02. Server for (3) and (4) in 1.7.03; app in 1.8.*
   3. **Round types:**
      - 3a. Groundwork: `RoundType` table and a type on every round (server and phone), per-type rules in the API, Big Game-only database checks relaxed; uploads without a type count as Big Game.
      - 3b. **Woodsman:** single animal per target; shoot until you kill, up to 3 arrows; wound 5 on any arrow; kill 30 / 20 / 10 on the 1st / 2nd / 3rd arrow; miss 0 (e.g. wound + kill 25; miss, wound, kill 15; three wounds 15; max 30). Needs each arrow recorded, so a per-arrow field. Own scoring screen, scorecard and stats; type shown in history and to friends; friends' summaries per type.
4. Later: group scoring (one phone scoring several archers); more history and stats

## Architecture

```
Phone (Kotlin/Compose)                         Home server
┌───────────────────────────┐   HTTPS (NPM,   ┌──────────────────────┐    ┌──────────────┐
│ UI → ViewModels           │   wildcard TLS) │ ASP.NET Core Web API │ →  │ SQL Server   │
│ Repositories              │ ──────────────▶ │ on Odin (IIS)        │    │ on Mimir     │
│ Room (SQLite) = truth     │  nfas.fnurble    └──────────────────────┘    └──────────────┘
│ WorkManager sync (iter 2) │
└───────────────────────────┘
```

- The phone's Room database is the source of truth while scoring. Every write is local first.
- The app never talks to SQL Server directly — only to the API at `https://nfas.fnurble.co.uk`.
- API: ASP.NET Core minimal API on **.NET 10 LTS** (supported to Nov 2028), IIS on Odin (app pool "No Managed Code"), Microsoft.Data.SqlClient to `sql.fnurble.co.uk` with encryption. The connection string sits in `appsettings.Production.json` on Odin only.
- Sync runs through WorkManager (`data/sync/`) with a network constraint, so it waits for signal and retries with exponential back-off.
  - Triggers: any change to a round (`RoundRepository.onChanged`), signing in, app start, and pull-to-refresh on the home screen (runs straight away in the foreground and shows the result). Unique work, appended, so a change made mid-upload is sent afterwards.
  - Sends rounds with `sync_state = PENDING` that are finished or deleted; in-progress rounds stay on the phone.
  - Marks a round SYNCED only if it wasn't edited while uploading (`markSync` checks `updated_at`), otherwise it goes again.
  - No signal / 5xx / 429 → retry later. 401 → sign out on the phone (Profile asks to sign in). 400/404 → FAILED, retried after the next edit.
  - Rounds scored before signing in upload once you sign in.
  - Download (after upload, same worker): fetches own rounds with `ServerUpdatedUtc` after the saved cursor (minus 5 minutes' overlap), keyset-paged by (`ServerUpdatedUtc`, `RoundId`). A downloaded round replaces the local copy only if its `clientUpdatedUtc` is newer than the phone's `updated_at`; deletions hide the local round. Cursor kept with the account and cleared on sign-out (next sign-in downloads everything again; identical rounds are skipped).
  - Signing in to a different account on the same phone mixes both accounts' rounds in the list; not handled yet.

## Local data model (Room) — implemented

| Table | Purpose | Synced? |
|---|---|---|
| `profile` | Real name, DoB, account username (set on sign-in), class, manual age group, main bow style (single row) | **Never** |
| `round` | `id` (UUID made on phone), shoot date, target count, class, age group, bow style, status, total, `created_at`, `updated_at`, `sync_state`, `deleted` | Yes |
| `target` | (`round_id`, `target_no`), type, override points, points, `updated_at`, `sync_state` | Yes |
| `animal` | (`round_id`, `target_no`, `animal_index`), state (Pending/Missed/Scored), arrow no, hit, points | Yes |

Design points:

- **Phone-generated UUIDs** for rounds so a retried upload never creates duplicates (idempotent upsert on the server).
- **`sync_state` + `updated_at`** on every synced row: sync sends `PENDING` rows, marks them `SYNCED` when the server confirms.
- **Soft delete** (`deleted`) so deletions also reach the server.
- **Class, age group and bow style are copied onto each round**, so later profile changes (or a junior moving up a group) don't rewrite history.
- **Points are stored** as well as the raw result (arrow + hit), so overrides and any future rule change don't alter old cards, and the server can report without re-implementing the rules.
- Room schema JSON is exported to `app/schemas/` for writing migrations.
- **Stats are derived, not stored:** total, average per animal (predator/prey = 2, fan = n), hit counts and rates are worked out from the round each time (`RoundStats` in `domain/Round.kt`), so changing a definition never needs a migration.

## Screens and navigation

```
home ─┬─ scoreRound ─┬─ (continue) ─────────────▶ round/{id}?target=n  (scoring) ──finish──▶ card/{id}
      │              └─ roundType ─▶ newRound/{type} ─start─▶ round/{id}                       │
      ├─ history ──(finished round)──▶ card/{id} ──edit / tap target──▶ round/{id}?target=n ◀──┘
      ├─ friends ─▶ friend/{archerId}/{username} ─▶ friendCard/{archerId}/{username}/{roundId}  (read-only)
      ├─ map ─▶ location/{locationId} ─┬▶ card/{roundId}  (yours)
      │                                 └▶ friendCard/…    (a friend's, read-only)
      ├─ admin ─┬▶ adminDuplicates  (possible duplicate locations: merge / approve)
      │         └▶ adminLocations   (rename, private / public, merge)   — Admins and Owner only
      └─ profile ─▶ account
```

- Home has tiles for Score a round, Score history and Friends; Score history shows finished rounds only.
- The Admin tile shows only when the stored role (`AccountStore.role`, refreshed from `/api/me` on sign-in and every sync) is Admin or Owner. The Admin screen (`data/AdminRepository.kt`) is fetched from the API each time; if the server says the account is no longer an admin, the stored role drops and the screen closes.
- Friends data (`data/FriendsRepository.kt`) is fetched from the API each time a friends screen opens or is pulled down; nothing about friends is stored on the phone. A friend's scorecard reuses the scorecard layout with `readOnly` target rows (predator/prey and fans still expand) and no edit button or menu.
- Round types come from `domain/RoundType.kt` (one entry, NFAS Big Game). A second type will need a `round_type` column on the phone and server (Room migration + schema version 3).
- Starting a round clears the menu screens from the back stack; finishing a round replaces the scoring screen with the scorecard; back from either goes home.
- The scoring screen enforces no skipping ahead (`Round.canGoTo`): a target can be opened if it's scored or it's the next unscored one.

## Server data model (SQL Server 2025 on Mimir) — schema version 4

Scripts in `server/sql/`, run in order in SSMS:

| Script | Run as | Does |
|---|---|---|
| `001_create_database_and_login.sql` | sysadmin | Creates database `NfasScoring` and login/user `nfas_api` (db_datareader + db_datawriter only, no schema changes). Set a strong password first; it never goes in Git. |
| `002_create_tables.sql` | sysadmin | Creates the tables below and records schema version 1 |
| `004_roles.sql` | admin | Schema v2: `Archer.Role` (USER / ADMIN / OWNER, one Owner), set your account as Owner |
| `005_shoot_locations.sql` | admin | Schema v3: `ShootLocation` table and `Round.LocationId` |
| `006_location_review_private.sql` | admin | Schema v4: `ShootLocation.IsPrivate` (name unique among public live locations only), `LocationPairApproval` (pairs an Admin has said are different places) |
| `003_test_as_api_login.sql` | `nfas_api` | Inserts test data inside a transaction, checks constraints, cascades and the friend view, then rolls back; also checks the login can't change the schema |

| Table | Key | Holds |
|---|---|---|
| `Archer` | `ArcherId` (server GUID) | `Username` (unique, case-insensitive, 3–30 of A–Z 0–9 _ . -), `PasswordHash`, created / last login, failed-login count and lockout, disabled flag, `Role` (USER / ADMIN / OWNER, only one OWNER; schema v2, `004_roles.sql`) with who changed it and when |
| `AuthToken` | `TokenId` | One per signed-in phone: SHA-256 `TokenHash`, device name, created / last used / expires / revoked |
| `Round` | `RoundId` (phone GUID) | `ArcherId`, shoot date, target count, class (L/G), age group, bow style, status, total, deleted flag, phone created/updated times, server updated time, rowversion, `LocationId` (v3, optional) |
| `ShootLocation` | `LocationId` (server GUID) | Name (2–60, unique among live locations, case-insensitive), latitude/longitude where first named (5 dp), who named / renamed / merged it and when, `MergedIntoId` (a merged duplicate points at the location kept) |
| `TargetScore` | `RoundId` + `TargetNo` | Target type, override, points |
| `AnimalScore` | `RoundId` + `TargetNo` + `AnimalIndex` | State, arrow, hit, points (checks enforce valid combinations, inner kill on 1st arrow only) |
| `Friendship` | `ArcherLowId` + `ArcherHighId` | One row per pair: who asked, PENDING / ACCEPTED, dates. Declining or removing deletes the row |
| `vFriend` (view) | | Accepted friends in both directions |
| `SchemaVersion` | `Version` | Applied schema scripts |

Decisions:

- **Shared tables keyed by `ArcherId`, not one table per user.** Friends' scorecards are a simple join, schema changes happen once, and the API restricts every query to the signed-in archer and their accepted friends.
- Deleting a round or archer cascades to scores and tokens. Friendships don't cascade (SQL Server allows only one cascade path to `Archer`), so the API removes them first.
- Conflict handling: last write wins on `ClientUpdatedUtc` per round (only one phone edits a round until group scoring arrives).
- Friends see **finished rounds only** (`Status = 'COMPLETED'`, not deleted), read-only. Every friends query checks `vFriend` (accepted, either direction); anything else is "not found".
- Sending a request to someone who has already asked you accepts it. Declining, cancelling and unfriending all delete the row.
- Friend summaries (rounds, best, latest, average per animal) are worked out by the API from finished rounds; the average divides total points by all animals on those rounds, so it can differ slightly from the app's (which skips unscored targets).

## Privacy (UK GDPR) — not legal advice

- Real name and date of birth stay in the phone's `profile` table and are never sent.
- Pseudonymous data (username + scores) can still be personal data if the username identifies someone; the app tells users not to use their real name.
- Class, age group and bow style are categories, not identifiers.
- The API should not log client IPs (or should truncate them) and keep log retention short.

## Security — iteration 2

- **Accounts:** username + password, no email. The app asks for a username; the API says whether it exists. Existing → "user already exists, enter password". New → create the account with a password.
- Passwords are hashed by the API with ASP.NET Core's `PasswordHasher` (salted PBKDF2); only the hash is stored.
- After signing in, the API issues a random token for that phone; the phone stores the token, never the password. App side: `data/AccountStore.kt` encrypts it with an AES-GCM key held in the Android Keystore, in `shared_prefs/nfas_account.xml`, which the backup rules leave out (a restored phone signs in again).
- App networking: `data/remote/NfasApi.kt` uses the platform's HttpURLConnection and org.json (no extra libraries), HTTPS only, 15-second timeouts; errors carry a message to show the archer, and "no signal" is reported as such.
- Signing in is optional; signing out tells the server if there's signal and always clears the phone. Only a SHA-256 hash of the token is stored on the server, so it can be revoked per phone.
- Brute-force protection: sign-in calls limited per IP (30/minute, in memory only) and a 15-minute lockout after 5 wrong passwords.
- Tokens are 32 random bytes (64 hex characters), valid for a year and extended while in use; signing out revokes only that phone's token.
- Endpoints and error codes: see `server/README.md`.
- Checking whether a username exists does reveal which usernames are taken; accepted, as usernames are public to friends anyway. Rate-limited.
- **No password reset for now** (to revisit): a forgotten password means a new account.
- HTTPS only via Nginx Proxy Manager; request size limits on the API.
- Server-side validation of every score against the scoring rules (allowed values, arrow limits, predator/prey rules, flagged overrides).
- SQL Server on Mimir is only reached by the API (login `nfas_api`), never by the app. Port 1433 is open to the internet, so `nfas_api` needs a long random password.
- Note: the home network's UK-only inbound geoblock means syncing from abroad will fail; scores stay on the phone and sync on return.
