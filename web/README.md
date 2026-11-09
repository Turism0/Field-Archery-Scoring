# Field Archery Scoring – web app (2.7)

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
