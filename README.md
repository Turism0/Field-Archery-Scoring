# NFAS Big Game Scorer

Android app for scoring NFAS Big Game rounds. Works fully offline; central sync to a self-hosted server comes in iteration 2.

**Stack:** Kotlin, Jetpack Compose (Material 3), Room (SQLite), Navigation Compose. minSdk 26, targetSdk 35, compileSdk 36. AGP 9.5 (built-in Kotlin 2.4), Gradle 9.6, KSP 2.3, Room 2.8.

## Status: iteration 1.2 – single-user scoring (offline)

### Screens

- **Home:** list of rounds, newest first. Rounds in progress are highlighted and open on the scoring screen; finished rounds open on the scorecard.
- **Profile** (phone only): name, date of birth, username, class (Ladies/Gents), age group (automatic from date of birth, or manual), main bow style.
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
docs/DESIGN.md   Architecture, data model, privacy and sync design
```
