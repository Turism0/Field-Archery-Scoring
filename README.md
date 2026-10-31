# NFAS Big Game Scorer

Android app for scoring NFAS Big Game rounds. Works fully offline; central sync to a self-hosted server comes in iteration 2.

**Stack:** Kotlin, Jetpack Compose (Material 3), Room (SQLite), Navigation Compose, WorkManager. minSdk 26, targetSdk 35, compileSdk 36. AGP 9.5 (built-in Kotlin 2.4), Gradle 9.6, KSP 2.3, Room 2.8.

## Version 1.2.11

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

Versioning: minor changes go 1.2.01, 1.2.02 … 1.2.99; major changes move to the next tenth (1.3).

## Status: iteration 1 done (single-user, offline); iteration 2 (online) started

### Screens

- **Home:** list of rounds, newest first. Rounds in progress are highlighted and open on the scoring screen; finished rounds open on the scorecard. When signed in, finished rounds show whether they're backed up to the server; pull down to sync now ("Last synced" shows at the top).
- **Profile:** Account card (sign in / create account, or "Signed in as …" with Sign out); name and date of birth (phone only); class (Ladies/Gents), age group (automatic from date of birth, or manual), main bow style.
- **Account** (optional, needs signal): enter a username → if it exists, "That username already exists" and enter the password; if not, choose a password (typed twice) to create the account. No email; no password reset yet. Scoring works without an account.
- **New round:** date, number of targets (quick picks 18 / 20 / 36 / 40), class and age group filled in from the profile, bow style defaulting to the main style. Changes only affect that round.
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

## Server (iteration 2, in progress)

Rounds will sync to SQL Server 2025 on Mimir through an ASP.NET Core API on Odin; the app never talks to SQL Server directly. Accounts are a username and password (no email, no real names on the server). Friends (request + accept) will see each other's finished rounds read-only.

Database setup, run in SSMS in order (details in `docs/DESIGN.md`):

1. `server/sql/001_create_database_and_login.sql` as sysadmin. Change the `nfas_api` password first.
2. `server/sql/002_create_tables.sql` as sysadmin.
3. `server/sql/003_test_as_api_login.sql` connected as `nfas_api`; every message should start with OK.

API: ASP.NET Core on .NET 10 under IIS on Odin, at https://nfas.fnurble.co.uk. Build, update and test steps and the list of endpoints are in `server/README.md`.

## Building

Open the folder in Android Studio and run the `app` configuration. Unit tests for the scoring engine: `./gradlew test`.

## Project layout

```
app/src/main/java/uk/co/fnurble/nfas/
  domain/        Pure Kotlin scoring engine, age groups, bow styles (unit tested)
  data/local/    Room entities, DAOs, database
  data/          Repositories (domain <-> database mapping)
  ui/            ViewModels, navigation, theme
  ui/screens/    Home, Profile, New round, Scoring, Scorecard (+ scorecard sheet)
  ui/components/ Shared form fields, section cards, running stats panel
server/sql/      Database and login scripts for SQL Server (run in order)
server/api/      ASP.NET Core API (.NET 10) for IIS on Odin; deploy steps in server/README.md
server/tests/    PowerShell scripts that test the live API
docs/DESIGN.md   Architecture, data model, privacy and sync design
```
