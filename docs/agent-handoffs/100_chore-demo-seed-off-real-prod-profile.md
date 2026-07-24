# 100 — chore/demo-seed-off-real-prod-profile

## Goal
Stop the `prod` Spring profile from seeding demo data into the **real** production DB. The demo
seed (`db/migration-demo`) must run only for the public Render showcase, never for real prod.

## Background / root cause
`application-prod.yml` set `spring.flyway.locations: classpath:db/migration,classpath:db/migration-demo`.
The `prod` profile is activated by **both** the real GL&R production deploy **and** the
`gl-r-erp.onrender.com` showcase, so a bare `prod` deploy applied the demo seed (`V21` demo logins +
sample tickets/leave/OT/commission) into real production. This is exactly how the real prod DB
(`tdyzcqzxmhtxpbouewud`) ended up with 6 `DEMO-*` employees + 8 test tickets etc., cleaned out
2026-07-24 (snapshot: `docs/agent-handoffs/backups/prod-demo-data-snapshot-2026-07-24.json`).

## Fix
The demo location already lives in the `demo` profile (`application-demo.yml`), and `render.yaml`'s
showcase opts in via `SPRING_PROFILES_ACTIVE=prod,demo` **plus** an explicit `SPRING_FLYWAY_LOCATIONS`
env var (both override the yaml). So the fix is simply to drop `db/migration-demo` from the `prod`
profile:
- **Real prod** (`prod` only) → `locations: classpath:db/migration` → **no demo seed**.
- **Showcase** (`prod,demo` + env var, higher precedence) → still seeds → **unaffected**.
- **UAT** (`uat` profile) → unaffected (uses `db/migration-uat`).
- **Local/CI/test** (no profile) → base `application.yml` has no `locations`, so Flyway default
  `classpath:db/migration` → no demo seed (already the case).

No migration files were edited (forward-only rule not engaged). No schema/API/authz change.

## 1. Files changed
- `backend/src/main/resources/application-prod.yml` — `spring.flyway.locations` now
  `classpath:db/migration` only (dropped `,classpath:db/migration-demo`); comment rewritten to
  explain the prod-vs-showcase profile boundary and the 2026-07-24 incident.
- `backend/src/test/java/th/co/glr/hr/FlywayMigrationTest.java` — Javadoc on
  `demoProfileCombinedLocationsApplyToACleanDatabase()` updated to point at `application-demo.yml`
  (the demo profile) instead of `application-prod.yml`. Test body unchanged (it hardcodes its own
  locations, so it still validates the base+demo combined-location version-collision guard).

## 2. Commands run
- `git checkout -b chore/demo-seed-off-real-prod-profile origin/main`
- Throwaway DB `createdb glr_gate_demo_$$`; ran targeted tests with `TEST_DB_URL` pointing at it;
  `dropdb` after.
- `./mvnw -B -Dtest='FlywayMigrationTest,ProductionConfigTest' -Dfork.count=1 test`

## 3. Tests / build results
- **PASS** — `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
  - `ProductionConfigTest` (reads `application-prod.yml`): pass.
  - `FlywayMigrationTest` (2 ran, incl. demo combined-location apply-to-clean-DB): pass — demo seed
    still applies cleanly under the demo-profile locations.
- Full `mvnw clean verify` NOT run (scoped, config-only change). Integration tests ran against a
  local throwaway Postgres (`TEST_DB_URL`), not Docker/Testcontainers.

## 4. Authz evidence
No authorization change (no role gate, scope/filter, or read/write-scoping touched). N/A.

## 5. Known risks
- The showcase's demo seed now depends on `render.yaml`'s `SPRING_PROFILES_ACTIVE=prod,demo` +
  `SPRING_FLYWAY_LOCATIONS` staying in place. Both are present and pinned in git; if someone
  deletes them, the showcase would stop seeding (harmless — no data loss, just an unseeded demo).
- This does not retroactively remove demo data from any DB — it prevents *future* seeding. The
  real prod DB was already cleaned manually on 2026-07-24.
- Defense-in-depth not added (e.g. a guard that refuses the demo seed against a real-prod DB). Out
  of scope for this change; note if a future incident warrants it.

## 6. Next prompt
> Review branch `chore/demo-seed-off-real-prod-profile` (diff: `application-prod.yml` flyway
> locations + one test Javadoc). Confirm the `prod`-only deploy no longer resolves
> `db/migration-demo` while the `prod,demo` showcase still does, then rebase onto latest
> `origin/main`, run `cd frontend && npm run lint && npm test && npm run build` is N/A (backend
> only) — run `cd backend && ./mvnw -B clean verify` if Docker/Postgres is available — and open the PR.
