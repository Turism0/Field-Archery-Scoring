# Field Archery Scoring – web app (3.2.07)

The app for iPhones (and any phone or computer with a browser), at **https://fieldarcheryscoring.fnurble.co.uk/** (2.6.03; the apps and the API stay at fieldarchery.fnurble.co.uk).
It's a Progressive Web App: open it in Safari, tap Share → **Add to Home Screen**, and it runs like an app, works with
no signal, and keeps its rounds (an account backs them up to the server too).

**In 2.5 (core):** sign in / create an account / forgotten password / recovery code / delete account; Profile; score Big
Game and Woodsman rounds with no signal, on your own or a group on one phone; scorecards and history; two-way sync with
the server (the same as the Android app, so rounds appear on both); live groups through the server (make, join by code,
the four scoring methods, Start waits for everyone, take over as host). Not yet: stats, handicap, friends, shoot
locations and the map (use the Android app). Live groups need signal on the web: a browser can't use Bluetooth.

**In 2.6 (everything else):** Stats (bests, trends, seasons, handicap rating), history filters and PB marks, handicap
calculator and handicap points, Friends and rounds scored for you, shoot locations (GPS, "Not here?", the map from
OpenStreetMap via Leaflet, location pages), round details editing, arranging Home, offline / server notices and Admin.
The one thing the web app can't do is score phone to phone with no signal.

**In 2.6.01:** a desktop layout for wide windows (1000px+): sidebar (`ui/Shell.tsx`), dashboard Home (`ui/Dashboard.tsx`) and
two- or three-column pages (`.split`, `.grid2`, `.grid3` in `styles.css`; one column on phones).

**In 2.6.02:** desktop is for catching up, not live scoring: **Add a round** types in a paper scorecard as a score sheet
(`ui/PaperEntry.tsx`, rules in `domain/paper.ts` with `test/paper.test.ts`), and scorecards have **Edit scores** with the same
sheet. Live groups aren't offered on desktop.

**In 2.7:** an optional starting target on new rounds, Add a round and Round details, shown on the scorecard (`startTarget` in
the round JSON; a live group's comes from the host).

**In 2.9:** Handicaps (`ui/Handicap.tsx`: My handicap, Multipliers, Calculator) – personal handicap (par 960 off 40
targets, best 40% of the last 20 per bow) and class multipliers (maximum ÷ typical score) in `domain/handicap.ts`, tested in
`test/stats.test.ts`; net score and multiplier on scorecards, net on history cards, friends' handicaps; the out-of-1000
handicap points and rating removed.

**In 2.8:** live groups carry on with no signal (`data/live.ts` keeps the last copy of the group and this phone's timed
entries; `offlineState` works out matching with `domain/liveCheck.ts`, a port of the app's `LiveRules`), with checks by hand
every 5 targets read out or by QR (`ui/LiveCheck.tsx`; the QR format is shared with the app's `LiveCheckQr.kt`, tested in
`test/liveCheck.test.ts`). Fix: double scorers moving to the next archer no longer jump back to target 1.

**In 2.7.06:** Join a live group can scan the host's QR (`ui/QrScanner.tsx`: BarcodeDetector where available, else jsQR loaded on
demand); repeated figures removed (Woodsman "Target N" heading, the total in scorecards' stats box, the paper sheet's Score when it
only repeats the number typed).

**In 2.7.05:** Shoot locations shows "Sign in to see shoot locations" when signed out (no account reads as undefined, not null).

**In 2.7.04:** the scoring screen has no "20 / 24 · Target scored" line (nor Woodsman's "25 / 30"), as in the app.

**In 2.7.03:** desktop export (Score history's ⤓ Export for the filtered rounds, a scorecard's ⋮ Export… for one): CSV per round,
CSV per target or JSON. The rules are in `domain/export.ts` (tested in `test/export.test.ts`), the dialog in `ui/Export.tsx`; the
version shown and written into exports is in `src/version.ts`.

**In 2.7.02:** the build always writes `assets/index.js` and `assets/index.css` (no hash in the name, see `vite.config.ts`), so a
new build copied over the old one leaves no old files behind; the API sends them with `Cache-Control: no-cache`.

**In 2.7.01:** the phone layout follows the Android app screen by screen (Score a round → Round type → New round, the scoring
screen's points tiles, running stats, Prev / Next and menus, scorecards and history cards). Shared pieces are in `ui/Parts.tsx`.

## How it's built

TypeScript + [Preact](https://preactjs.com) with [Vite](https://vite.dev). Rounds are kept in the browser's IndexedDB.
`src/domain/` is a copy of the app's rules (`Scoring.kt`, `PersonalStats.kt`, `Handicap.kt`, `HomeLayout.kt`) – keep
them in step; `test/` has the same test cases as the app's `ScoringTest`, `PersonalStatsTest`, `HandicapTest` and
`HomeLayoutTest`. `src/data/` talks to the API with the same JSON as the Android app.

## Building (only needed when the web app changes)

Needs Node.js 20 or later. In this folder:

```
npm install
npm test          # the rules (scoring, stats, handicap, Home layout)
npm run build     # type-checks, then builds into ../server/api/FieldArcheryApi/wwwroot/app
```

The built files are committed, so publishing the API (`Update-Api.ps1`) publishes the web app too; Odin doesn't need Node.
`npm run dev` runs it locally (point `VITE_API_BASE` at a test API if needed).
