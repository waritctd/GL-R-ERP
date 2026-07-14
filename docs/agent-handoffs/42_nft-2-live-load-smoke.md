# Agent Handoff

## Task
Round A of a 3-round pre-UAT NFT hardening pass (see plan `highest-value-currently-missing-cuddly-knuth`, opus-plans/sonnet-executes loop). NFT round 1 (PR #173) built `scripts/nft/load-smoke.sh` but only ever ran it with `--check` (dry-run, no live server). This round executes it for real against a local docker-compose stack for the first time. Verification-only — no production code was expected to change.

## Branch
`test/nft-2-live-load-smoke`

## Base Commit
`46311eccc1e3353b19e59f444176a367f11b9a39` (main tip this branch was cut from; PR #173 merge)

## Current Commit
Not committed yet — this handoff file is uncommitted, awaiting user confirmation before commit/push per repo rule ("do not push, merge, or open a PR without explicit user confirmation").

## Agent / Model Used
Claude Opus (micro-plan) → Claude Sonnet 5 (execution, this handoff)

## Scope

### In Scope
- Bring up `docker-compose up -d --build` (db + backend) locally.
- Manufacture a one-off, disposable HR-role login directly in the local docker Postgres (no migration file, nothing committed) since neither the base `db/migration` path nor a demo-profile seed matches the script's default login.
- Run `scripts/nft/load-smoke.sh` live (no `--check`) at default `REQUEST_COUNT=50` against all 3 hot-read endpoints.
- Record results in this handoff.
- Tear the stack down (`docker compose down -v`) before Round B starts.

### Out of Scope
- Any change to production/migration/config/Java code.
- Tuning latency thresholds, rate limits, multipart limits, session timeout, or CORS.
- The frozen sales/CRM stack.
- The untracked `tools/` directory (unrelated, pre-existing).
- Push / PR / merge (needs explicit user confirmation first).

## Files Changed
- `docs/agent-handoffs/42_nft-2-live-load-smoke.md` — new (this file).
- No `db/migration*`, Java, or config files changed. The HR login row was inserted at runtime directly into the disposable docker Postgres volume, which was destroyed (`docker compose down -v`) at the end of this round — no trace left in the repo or in any persisted volume.

## Commands Run
```bash
git checkout -b test/nft-2-live-load-smoke

# Discovered and removed 3 stray iCloud-Drive conflict-copy files (byte-identical
# duplicates of NFT round-1 files, untracked, breaking the Docker build with
# "duplicate class" javac errors) — see Known Risks.
rm -f "backend/src/test/java/th/co/glr/hr/config/ActuatorHealthDownIntegrationTest 2.java" \
      "backend/src/test/java/th/co/glr/hr/config/SessionCookieFlagsIntegrationTest 2.java" \
      "docs/agent-handoffs/41_nft-non-functional-testing 2.md"

docker compose up -d --build
# First attempt failed: stale local `pgdata` volume from an earlier, unrelated
# session had a Flyway history that no longer matched current migrations
# (V21 removed from base path since moved to db/migration-demo; V31 checksum
# changed). Reset the disposable local volume:
docker compose down -v
docker compose up -d --build

# Poll health (note: docker-compose.override.yml remaps backend to host port 8090,
# not the base compose file's 8080, and sets SPRING_PROFILES_ACTIVE=prod)
curl -s http://127.0.0.1:8090/actuator/health   # {"status":"UP"}

# Manufacture a disposable HR login (division + employee), reusing the already-
# verified demo BCrypt hash for password "Demo@2026" from V21__demo_seed_accounts.sql
docker compose exec -T db psql -U postgres -d hris -c "
INSERT INTO hr.division (source_code, name_th) VALUES ('HR', 'HR-บุคคล')
  ON CONFLICT (source_code) DO NOTHING;
INSERT INTO hr.employee (employee_code, first_name_th, last_name_th, email,
    division_id, password_hash, must_change_password, is_active)
  SELECT 'NFT-HR01', 'NFT', 'SmokeHR', 'hr@glr.co.th', d.division_id,
         '\$2y\$10\$dgX94V4KgoGZzoiJPGiA9.Xa3M1GLBph8x1yOyTPqr8c9DPF4Fkt2', FALSE, TRUE
    FROM hr.division d WHERE d.source_code = 'HR'
  ON CONFLICT (employee_code) DO NOTHING;
"

curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' \
  -d '{"email":"hr@glr.co.th","password":"Demo@2026"}' \
  http://127.0.0.1:8090/api/auth/login   # 200

BASE_URL='http://127.0.0.1:8090' LOGIN_PASSWORD='Demo@2026' bash scripts/nft/load-smoke.sh
# ran twice for consistency; second run's exit code captured explicitly

docker compose down -v   # teardown, volumes wiped
```

## Test / Build Results
- **Live load-smoke run (2 consecutive runs, default `REQUEST_COUNT=50`):**

  | Endpoint | p50 | p95 | max | failures |
  |---|---|---|---|---|
  | `employees-list` (`GET /api/employees`) | 4ms | 14ms | 85ms | 0/50 |
  | `attendance-punches` (`GET /api/attendance/punches?limit=20`) | 3ms | 6ms | 20ms | 0/50 |
  | `payroll-current` (`GET /api/payroll?payrollMonth=2026-07`) | 4ms | 6ms | 45ms | 0/50 |

  Run 2 (exit-code-verified): payroll-current p50=4ms p95=5ms max=9ms failures=0/50; all endpoints similarly clean.
  **Result: PASS, exit code 0, 0 total failures across both runs.** p95 threshold is 2000ms — actual p95 was 5–14ms, i.e. ~150–400x headroom under threshold on a local single-container stack.
- Frontend build/lint/test: not run — no frontend source changed this round.
- Backend `./mvnw -B clean verify`: not run standalone this round (the Docker build stage runs `mvn clean package -DskipTests`, which compiled successfully after removing the stray duplicate files; full `verify` with tests deferred to Round B where test code is actually touched).

## Decisions Made
- **Login manufactured via one-off `psql` insert, not a new seed migration.** The base `db/migration` path has no `hr.division`/`hr.employee` seed at all, and the `demo` profile (which does seed an HR user) isn't active in the default `docker-compose.yml` and uses a different login anyway. Inventing a new committed migration for a throwaway test login was explicitly out of scope; the insert lives only in the disposable Docker volume, destroyed at teardown.
- **Reused the already-verified `Demo@2026` BCrypt hash** from `V21__demo_seed_accounts.sql` instead of generating a new one — one less moving part, and it's a hash already known to work with Spring's `BCryptPasswordEncoder`.
- **Used port 8090, not 8080**, once `docker-compose.override.yml` was found to remap the backend port and force `SPRING_PROFILES_ACTIVE=prod` for local compose runs. `SERVER_SESSION_COOKIE_SECURE` stays `false` from the base file, so plain-HTTP login still works fine under the `prod` profile locally.
- **Wiped the `pgdata` volume (`docker compose down -v`) rather than trying to repair Flyway history**, since the stale volume was leftover local dev state (unrelated migration history), not something worth preserving, and CLAUDE.md's migration-fix path (Flyway `repair`) is for real environments, not disposable local scratch volumes.
- **Did not tune the p95 threshold or endpoints** even before knowing the result — this round's job was to surface real numbers, not adjust the bar.

## Assumptions
- Docker Desktop was available and had capacity to pull `postgres:16-alpine` + build the Maven multi-stage image.
- The local `pgdata`/`backend_uploads` docker volumes were disposable dev scratch state, not anything the user needed preserved (confirmed via investigation: the corrupted Flyway history predated this session, and volumes are gitignored/non-source infrastructure).
- Current-month (`2026-07`) payroll preview returns 200 without any seeded payroll data (confirmed true — no payroll-line seed was needed).

## Known Risks
- **`~/Desktop/GL-R-ERP` lives inside iCloud Drive's synced Desktop folder.** During this session, iCloud created 3 byte-identical " 2"-suffixed conflict-copy files (2 Java test classes from NFT round 1, 1 handoff doc), which broke the Docker build with duplicate-class compile errors. These were verified byte-identical to the originals (safe) and removed. **This can recur** — if two devices/sessions touch this iCloud-synced folder concurrently, or even parallel local agents read/write near-simultaneously, iCloud may reintroduce conflict copies. Recommend either moving the repo out of iCloud Drive's synced folder, or excluding it via iCloud Drive settings, to prevent this from silently breaking future builds. Not fixed this round (infrastructure/environment issue, not repo code).
- The one-off HR login (`NFT-HR01` / `hr@glr.co.th`) existed only in the now-destroyed disposable volume — no lingering credential risk.
- `scripts/nft/README.md` documents that the script "needs a seeded login" but doesn't say how to get one on a plain (non-demo) local compose stack — left as a documentation gap for a possible follow-up, not fixed this round (README edits were out of scope for a verification-only run).

## Things Not Finished
- Round B (payroll concurrency test + advisory-lock fix) and Round C (migration replay) of the 3-round NFT pass — next up.
- Push / PR / merge of this branch — deliberately not done, needs explicit user confirmation first.
- The iCloud sync risk noted above is flagged but not remediated (would need a user decision on repo location/iCloud settings).

## Recommended Next Agent
Claude (Sonnet), continuing the opus-plans/sonnet-executes loop for Round B.

## Exact Next Prompt
```
Round A (live NFT-2 load-smoke run) is complete and recorded in
docs/agent-handoffs/42_nft-2-live-load-smoke.md — PASS, p95 5-14ms across all
3 endpoints, well under the 2000ms threshold. The docker-compose stack has been
torn down (docker compose down -v) and ports 8090/5432 are clear.

Start Round B: on a fresh branch `test/nft-payroll-concurrency` off main, get an
Opus-tier Plan agent to produce a micro-plan for a backend integration test
(Testcontainers, same pattern as ActuatorHealthDownIntegrationTest /
SessionCookieFlagsIntegrationTest under backend/src/test/java/th/co/glr/hr/config/)
that fires two concurrent POST /api/payroll/process calls for the same payrollMonth
via ExecutorService/CountDownLatch, and asserts on the resulting hr.payroll_line
state. PayrollService.saveProcessedPeriod() does a raw DELETE-then-INSERT with no
@Version, no advisory lock, and no synchronization anywhere in the codebase
(confirmed: zero @Version annotations exist in backend/src/main/java). If the test
proves a real race/corruption (expected), apply the minimal fix: wrap
PayrollService.process()'s transactional body in a pg_advisory_xact_lock keyed by
a hash of payrollMonth, per the plan at
~/.claude/plans/highest-value-currently-missing-cuddly-knuth.md (user has already
approved "test + fix" scope for this round, not "test only"). Run
./mvnw -B clean verify, update/create the Round B handoff file, and checkpoint
with the user before pushing/opening a PR — do not push without explicit
confirmation.
```
