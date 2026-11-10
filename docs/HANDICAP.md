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

## In the app (2.9: multipliers and personal handicap)

Pete (Nov 2026): for handicap competitions. The out-of-1000 handicap points and rating (2.2.06–2.8) were removed.

- **Multipliers** (class / bow style competitions: score × multiplier, highest wins). Every bow style and class is
  measured against the maximum for the round: multiplier = maximum ÷ that bow and class's typical score, so a typical
  archer of any class scores about the maximum × 1. Typical = an average archer of that bow and class at a recent
  championship: typical share = 1 / (1 + e^−(BASE + off[bow, class, year])), with `off` as above (year = the model's
  last year) and **BASE:ALL** (API 2.9, worked out by `GET /api/handicap` from the stored EVENT factors) = the average
  of the latest five Nationals' and latest five 3Ds' championship levels. Shown and used to 2 decimals. Exact at each
  bow and class's typical score, close either side (the model isn't a flat ratio). Big Game only. Shown on Handicaps →
  Multipliers (bow × class table), on scorecards (score × multiplier), in the CSV export, and on location pages
  (Avg 40 × multiplier).
- **Personal handicap** (like golf; Pete: "par as a maximum possible score off a 40 course"). Per bow style, Big Game,
  from finished rounds with every target scored. Each round scaled to 40 targets (score × 40 ÷ targets); usual score =
  average of the best 40% (at least 3; 8 of 20) of the latest 20 with that bow; none until 3 rounds. Handicap =
  960 − usual score (rounded). Net score = score + handicap × targets ÷ 40 (rounded). Scorecards and Score history use
  the handicap from rounds before that one (same day: started earlier). Friends' handicaps come from
  `GET /api/friends` → `recent` (API 2.9: latest 20 finished Big Game rounds per bow, with `targetsScored`; only rounds
  with every target scored count). Rules in `domain/Handicap.kt` (`PersonalHandicap`) and web `domain/handicap.ts`,
  same tests.
- **Calculator** (unchanged): a score with any bow and class as the same performance for every other, plus × its
  multiplier.
