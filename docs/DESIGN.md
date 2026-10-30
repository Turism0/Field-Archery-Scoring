# Design

## Goals

- Score NFAS Big Game rounds on the phone with no signal; sync to a central database when there is signal.
- Keep personal data on the phone. The server holds scores, a username and the categories needed for results (class, age group, bow style) — no real names, no dates of birth.
- Build iteratively.

## Iterations

1. **Single-user scoring, offline** ← current
2. API + central database + background sync, anonymous registration and recovery code
3. History and stats
4. Group scoring (one phone scoring several archers)

## Architecture

```
Phone (Kotlin/Compose)                         Home server
┌───────────────────────────┐   HTTPS (NPM,   ┌──────────────────────┐    ┌──────────────┐
│ UI → ViewModels           │   wildcard TLS) │ ASP.NET Core Web API │ →  │ SQL Server   │
│ Repositories              │ ──────────────▶ │ on Odin (IIS)        │    │ on Mimir     │
│ Room (SQLite) = truth     │                 └──────────────────────┘    └──────────────┘
│ WorkManager sync (iter 2) │
└───────────────────────────┘
```

- The phone's Room database is the source of truth while scoring. Every write is local first.
- The app never talks to SQL Server directly — only to the API.
- Sync (iteration 2) runs through WorkManager with a network constraint, so it waits for signal and retries automatically.

## Local data model (Room) — implemented

| Table | Purpose | Synced? |
|---|---|---|
| `profile` | Real name, DoB, username, class, manual age group, main bow style (single row) | **Never** (username only, at registration) |
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

## Server data model (SQL Server) — draft for iteration 2

```sql
CREATE TABLE Archer (
    ArcherId      UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,   -- random, made by server
    Username      NVARCHAR(40)     NOT NULL UNIQUE,
    SecretHash    VARBINARY(64)    NOT NULL,               -- hash of device secret
    RecoveryHash  VARBINARY(64)    NOT NULL,               -- hash of recovery code
    CreatedUtc    DATETIME2        NOT NULL
);

CREATE TABLE Round (
    RoundId       UNIQUEIDENTIFIER NOT NULL PRIMARY KEY,   -- from phone
    ArcherId      UNIQUEIDENTIFIER NOT NULL REFERENCES Archer(ArcherId),
    ShootDate     DATE             NOT NULL,
    TargetCount   SMALLINT         NOT NULL,
    ArcherClass   CHAR(1)          NOT NULL,               -- L / G
    AgeGroup      VARCHAR(10)      NOT NULL,               -- ADULT / JUNIOR / CUB
    BowStyle      VARCHAR(4)       NOT NULL,               -- AFB, BB, ...
    Status        VARCHAR(12)      NOT NULL,
    TotalPoints   INT              NOT NULL,
    IsDeleted     BIT              NOT NULL DEFAULT 0,
    UpdatedUtc    DATETIME2        NOT NULL,
    RowVer        ROWVERSION
);

CREATE TABLE TargetScore (
    RoundId        UNIQUEIDENTIFIER NOT NULL REFERENCES Round(RoundId) ON DELETE CASCADE,
    TargetNo       SMALLINT         NOT NULL,
    TargetType     VARCHAR(16)      NOT NULL,              -- STANDARD / PREDATOR_PREY / FAN
    OverridePoints SMALLINT         NULL,
    Points         SMALLINT         NOT NULL,
    UpdatedUtc     DATETIME2        NOT NULL,
    PRIMARY KEY (RoundId, TargetNo)
);

CREATE TABLE AnimalScore (
    RoundId     UNIQUEIDENTIFIER NOT NULL,
    TargetNo    SMALLINT         NOT NULL,
    AnimalIndex TINYINT          NOT NULL,
    State       VARCHAR(8)       NOT NULL,                 -- PENDING / MISSED / SCORED
    ArrowNo     TINYINT          NULL,
    Hit         VARCHAR(10)      NULL,                     -- INNER_KILL / KILL / WOUND
    Points      SMALLINT         NOT NULL,
    PRIMARY KEY (RoundId, TargetNo, AnimalIndex),
    FOREIGN KEY (RoundId, TargetNo) REFERENCES TargetScore(RoundId, TargetNo) ON DELETE CASCADE
);
```

Conflict handling: last write wins on `UpdatedUtc` per round (only one device edits a round until group scoring arrives; revisit then).

## Privacy (UK GDPR) — not legal advice

- Real name and date of birth stay in the phone's `profile` table and are never sent.
- Pseudonymous data (username + scores) can still be personal data if the username identifies someone; the app tells users not to use their real name.
- Class, age group and bow style are categories, not identifiers.
- The API should not log client IPs (or should truncate them) and keep log retention short.

## Security — iteration 2 plan

- No passwords or emails. On first sync the app registers a username; the server returns a random archer ID and secret. Only a hash of the secret is stored on the server; the phone keeps the secret in the Android Keystore (excluded from backup).
- A one-time recovery code (also stored hashed) lets the user restore their account on a new phone.
- HTTPS only via Nginx Proxy Manager; rate limiting and request size limits on the API.
- Server-side validation of every score against the scoring rules (allowed values, arrow limits, predator/prey rules, flagged overrides).
- SQL Server on Mimir is only reached by the API, never by the app.
- Note: the home network's UK-only inbound geoblock means syncing from abroad will fail; scores stay on the phone and sync on return.
