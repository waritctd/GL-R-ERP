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
