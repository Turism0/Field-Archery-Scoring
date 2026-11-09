# Handicap

Lets any score be compared across bow styles and classes: every score can be turned into what the same performance
would be for **Unlimited Gents (adult)**. Worked out from the NFAS Nationals and 3D Championships results since 2002,
by the server job (`server/jobs/FieldArcheryJobs`), and kept up to date automatically. Agreed Nov 2026; server 2.2.01.

## Data

- **Source:** https://nfas.net/championships/results?y=YEAR&champs=Nationals|3Ds&style=CODE — one page per year,
  championship and bow style (the all-styles page is cut short). Title rows "2019 - Nationals - Unlimited - Gents",
  then `# | Archer | Club | Day 1 | Day 2 | Total | (24s)`.
- **Stored** (`ChampEvent`, `ChampResult`, schema 7): everything published except the name. Archers are matched across
  years by **NameKey** = HMAC-SHA256(secret, tidied name) — lower case, letters only, hyphens as spaces, "Mc Dermott" as
  "mcdermott". A name shared by two archers at one championship is told apart by club everywhere. Sex comes from the class.
- **Gaps:** no results for Nationals 2020, 2023, 2024, 2026 or 3Ds 2020, 2021; 3Ds 2004–05 have placings only; 24s are
  only recorded from 2010 (not 3Ds 2017).
- **Targets per day** (not published), in order:
  1. before 2020 (Covid): 40 (Pete);
  2. a day over 36 × 24 = 864: 40;
  3. otherwise from the 24s: points on the targets that weren't 24s follow a steady curve against the share of 24s
     (fitted on championships certain to be 40); whichever of 36 / 40 fits better, flagged DOUBTFUL unless clearly
     better (0.75 points a target) or 36 is impossible;
  4. no 24s: 40, DOUBTFUL.
  An Admin can set any of them by hand. The choice matters little: switching the doubtful years moves handicaps
  by about 1–2%.

## Model

Each day's score as a share of the maximum, p = score / (24 × targets) clipped to 0.01–0.99, on a logit scale:

    logit(p) = championship + bow + slope[bow] × (year − 2014) / 10 + bow3d[bow] × (3Ds)
               + class[sex-age] + bowsex[bow] × (ladies) + archer + noise[bow]

- **championship**: each Nationals / 3Ds (course, weather, 36 or 40 targets, the general rise in scores).
- **bow, slope**: bow style, changing in a straight line over the years (Unlimited has been pulling away).
- **bow3d, bowsex**: small extras for 3Ds and for ladies on each bow, partly pooled (penalty 5) so small groups
  lean on the averages.
- **class**: Gents / Ladies × Adult / Junior / Cub (U12). A junior's results are a separate "archer" from their adult ones.
- **archer**: each archer's own ability, shrunk towards average (ridge λ = 1). This is what separates "this bow
  scores more" from "the people who shoot it are better".
- **noise**: allowed to differ by bow; three rounds of re-weighting.
- Reference: Unlimited, Gents, Adult (all their factors are 0).

Chosen by testing (Nov 2026, Python in Claude's workspace; the C# job must match it, `FieldArcheryJobs.exe verify`):
the logit scale held best across ability levels (against raw share, log, cloglog); following archers cut the
systematic bias for archers who changed bow style about 3× (rms 0.031 against 0.094 SD) and predicted hidden results
better (66 against 87 points a day); era steps, a spline, ability changing in 6-year blocks, and dropping the 3D or
bow × ladies terms were no better.

## Tests on every refit (stored with the model)

- **Hidden results:** 15% of entries (chosen by FNV-1a hash of "EVENT YEAR|archer") left out; RMSE in points a day
  predicting the hidden days of archers seen elsewhere.
- **Bow switchers:** archers who shot 2+ bow styles, every 5th (hash) left out of the fit; their ability on each bow
  should agree. rms of the per-bow bias in SDs of ability, weighted by archers (bows with 10+).
- **Ranges:** 20 refits on archers drawn at random with replacement; 5th–95th percentile of each factor.
- A new model goes live by itself if it's sane (finite, |factor| < 4) and its hidden-results RMSE is no more than
  3% worse and its switcher bias no more than 1.5× (or 0.05) the active model's. Otherwise it waits for the Owner.

## Using it (the app)

`GET /api/handicap` returns the active factors. For a day score `s` on `t` targets with bow `b`, class `c`
(e.g. `F-ADULT`) in year `y` (no later than `dataThroughYear`) at Nationals or 3Ds:

    p   = clamp(s / (24 t), 0.01, 0.99)
    off = BOW[b] + CLASS[c] + (ladies ? BOWSEX[b] : 0) + SLOPE[b] × (y − 2014) / 10 + (3D ? BOW3D[b] : 0)
    equivalent = 24 t / (1 + exp(−(logit(p) − off)))

Missing factors are 0. A round's equivalent is the sum over its days (or the formula on the whole round with its
total targets). It isn't a flat percentage: e.g. in 2026 a Longbow day of 480 / 600 / 720 is about 811 / 864 / 904.

## In the app (2.2.05; handicap points from 2.2.06)

- **Handicap points (2.2.06):** what's shown is never "as Unlimited Gents" (Pete: traditional archers don't want to be
  measured against Unlimited). Handicap points = 1000 / (1 + e^−(logit(p) − off[bow, class] + neutral)), where
  neutral is the median offset over every bow style × class × age group in that year: a neutral yardstick in the
  middle of all of them. Out of 1000; the same for the same performance with any bow and class. The rating is the
  average handicap points of the last 10 Big Game rounds. (Unlimited Gents being zero in the model is only the fit's
  arithmetic.)

- The model is downloaded from `GET /api/handicap` (no sign-in) at most once a day and kept on the phone.
- **Paper faces and 3D targets are averaged** (Pete): half of each bow's BOW3D term is always included, so there's one
  handicap; it's at most about 5–8 points a day from either.
- **Equivalent of a round:** its points and maximum (24 × animals on fully scored targets) through the formula, out of
  the same maximum. Friends' rounds use their animal count (or targets) from the server.
- **Rating:** the average equivalent share of your last 10 finished Big Game rounds × 24 × 40 (a 40-target Unlimited
  Gents score); 3 rounds needed. Shown on Stats → Bests and the Stats tile; friends' ratings on their rounds page and (2.4) the friends list.
- **Calculator tile:** a score, targets, bow and class → the Unlimited Gents score and a table for every bow style
  (rows) and class (columns), using the latest year's factors.
- **Location pages:** each bow's 40-target average in handicap points (all classes: each taken as Gents; 2.4: filter by
  class to use that class) and an "All bows" average weighted by rounds.
- Woodsman rounds have no handicap (the championships are Big Game).

## First results (Nov 2026, 2026 factors, Nationals, Gents, 40 targets: a 600 day as Unlimited Gents)

Primitive 870, Longbow 864, American Flatbow 846, Hunting Tackle 840, Thumb Draw 831, Barebow 829, Traditional
Bowhunter 826, Bowhunter 802, Traditional Crossbow 790, Freestyle 786, Compound Limited 733, Unlimited 600,
Crossbow 541. 3Ds are 5–15 kinder to traditional bows. Classes (Unlimited 600): Junior Boys 628, U12 Boys 631,
Ladies 686, Junior / U12 Girls about 693.
