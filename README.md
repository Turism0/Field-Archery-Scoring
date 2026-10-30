# NFAS Big Game Scorer

Android app for scoring NFAS Big Game rounds. Works fully offline; central sync to a self-hosted server comes in iteration 2.

**Stack:** Kotlin, Jetpack Compose (Material 3), Room (SQLite), Navigation Compose. minSdk 26, target/compile 35.

## Status: iteration 1 – single-user scoring (offline)

- Profile (phone only): name, date of birth, username, class (Ladies/Gents), age group (auto from DoB or manual), main bow style
- New round: date, number of targets, class and age group pre-filled from profile (editable), bow style list defaulting to main style
- Scoring: Standard, Predator/Prey and Fan targets, type changeable on any target mid-round; manual score override (flagged)
- Scorecard: running total, combined score per target with expandable predator/prey/fan breakdown, basic stats
- Finish / reopen / delete rounds

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
  ui/            ViewModels, navigation, Compose screens
docs/DESIGN.md   Architecture, data model, privacy and sync design
```
