# NFT scripts

Local-only Non-Functional Testing helper scripts. These never touch the Render demo, Vercel,
Supabase, or any hosted/production system — they are for local, opt-in use only.

## load-smoke.sh

A lightweight load/perf smoke test for hot read paths, using only `bash` + `curl` (no new
runtime dependency, no JMeter/Gatling/k6/Locust).

It logs in once via `POST /api/auth/login` (using a temp cookie jar), then loops N requests
(default 50, configurable) over:

- `GET /api/employees`
- `GET /api/attendance/punches`
- `GET /api/payroll?payrollMonth=<current month>`

For each endpoint it prints p50/p95/max latency and a failure count, and exits non-zero if any
request errors (non-2xx/3xx) or if p95 exceeds a configurable threshold (default 2000ms).

### Prerequisites

1. The local `docker-compose` stack must be up (Postgres + backend), e.g.:
   ```bash
   docker compose up -d
   ```
2. The database must be seeded with a login you can authenticate as (an HR-role account is
   sufficient — `GET /api/payroll` requires the `HR` or `CEO` role). Set `LOGIN_EMAIL` /
   `LOGIN_PASSWORD` to match a real seeded account if the defaults don't apply to your local
   stack.

### Usage

```bash
# Dry run: validates argument parsing and the localhost-only guard, no server needed.
scripts/nft/load-smoke.sh --check

# Live run against a local backend on the default port.
BASE_URL=http://127.0.0.1:8080 \
LOGIN_EMAIL=hr@glr.co.th \
LOGIN_PASSWORD=yourpassword \
scripts/nft/load-smoke.sh
```

### Environment variables

| Variable            | Default                   | Notes                                              |
|---------------------|----------------------------|-----------------------------------------------------|
| `BASE_URL`           | `http://127.0.0.1:8080`    | Must be `localhost`/`127.0.0.1` — the script hard-refuses anything else. |
| `LOGIN_EMAIL`         | `hr@glr.co.th`              | Must match a seeded local account.                  |
| `LOGIN_PASSWORD`       | `password`                  | Must match a seeded local account.                  |
| `REQUEST_COUNT`        | `50`                        | Requests per endpoint.                              |
| `P95_THRESHOLD_MS`   | `2000`                      | p95 latency (ms) above which the script fails.      |

### Safety guard

`BASE_URL` is validated against a strict `localhost`/`127.0.0.1` regex before any request is
made. Any other host (including the Render demo `gl-r-erp.onrender.com`, Vercel, or a hosted DB)
causes the script to exit immediately with an error — it can never accidentally load-test a
shared or production environment.

### Note

Actually running this script live requires the local docker-compose stack up and a seeded login.
This is a manual follow-up step — it is not run automatically as part of `mvnw verify` or any CI
workflow.

## migration-replay.sh

Replays the full `db/migration` Flyway chain against a throwaway Postgres, then loads a directory
of ordered `*.sql` seed files on top via `psql` — surfacing schema/constraint assumptions that
only break against realistic, populated data (a fresh/empty-DB migration run can never catch
these).

It uses a standalone, uniquely-named Docker container/volume/ports (`glr-replay-*`), never the
dev `docker-compose` stack's `pgdata`/`backend_uploads` — so it cannot corrupt, or be corrupted
by, your normal local dev environment. Everything it creates is torn down automatically on exit.

### Prerequisites

1. Docker available locally (it builds the backend image itself — no docker-compose stack needs
   to be running first).
2. A directory of ordered `*.sql` seed files to replay against (`SEED_DIR`). This script is
   intentionally decoupled from any specific data source — point it at any prod-shaped seed you
   have. As of writing, the most realistic seed available is the `uat` branch's
   `db/migration-uat/V900`–`V905` fixtures (32 employees + dual-track sales data), which are
   **not** merged into `main`. Extract them read-only (no branch checkout/merge) with:
   ```bash
   mkdir -p /tmp/uat-seed
   for f in V900__uat_reference_and_employees V901__uat_attendance \
            V902__uat_leave_and_overtime V903__uat_sales \
            V904__uat_resync_employee_code_seq V905__uat_dual_track_sales; do
     git show "uat:backend/src/main/resources/db/migration-uat/${f}.sql" > "/tmp/uat-seed/${f}.sql"
   done
   ```

### Usage

```bash
# Dry run: validates argument parsing only, no Docker required.
scripts/nft/migration-replay.sh --check

# Live run against a seed directory.
SEED_DIR=/tmp/uat-seed scripts/nft/migration-replay.sh
```

### Environment variables

| Variable       | Default      | Notes                                                       |
|-----------------|---------------|---------------------------------------------------------------|
| `SEED_DIR`       | *(required)*   | Directory of ordered `*.sql` files, applied via `psql -f`.    |
| `REPLAY_DB`       | `replaydb`     | Database name inside the throwaway Postgres.                  |
| `REPLAY_PGPORT`     | `55432`        | Host port for the throwaway Postgres.                         |
| `REPLAY_APPPORT`     | `8091`         | Host port for the throwaway backend (used only during migrate).|

### What it does and doesn't prove

Proves: the full Flyway chain applies cleanly to an empty schema, and the given seed loads (or
doesn't) against the *final* schema — the seed load is the part that actually exercises
populated-table assumptions, since every existing migration in the chain still only ever runs
against an empty database in this replay. If a seed file fails to load, that's a real
schema/data-model finding — flag it, don't "fix" it by editing a committed `db/migration` file on
whatever branch you're on.

Does not prove: true future-migration-ordering safety (i.e. how a *new* migration would behave
against already-populated tables) — that requires an actual new migration to test, which this
script doesn't fabricate.

### Note

Not run automatically as part of `mvnw verify` or any CI workflow — manual, opt-in only.
