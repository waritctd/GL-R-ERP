# Agent Handoff

## Task
Backend CI (`backend-ci.yml` → `./mvnw -B clean verify`) consistently takes ~20–21 min. Investigate
why, then implement optimization #1: stop replaying all 66 Flyway migrations before every
integration-test method.

## Branch
`perf/faster-integration-test-db-reset`

## Base Commit
`d17bcc04f105c2d461a56e7a88cac69871d82aca`

## Current Commit
<uncommitted — do not commit/push without explicit ask>

## Agent / Model Used
Claude Opus 4.8

## Scope

### In Scope
- Test infrastructure only: how `AbstractPostgresIntegrationTest` resets the database between tests.

### Out of Scope
- No production code, business logic, schema, or migration changes.
- No test assertions changed. No authorization logic touched.
- Test parallelism (#2) and dropping `clean` from CI (#3) — deliberately left for a follow-up.

## Root Cause
The whole ~21 min is one CI step: `./mvnw -B clean verify` (~20m54s; checkout/JDK/LibreOffice are
~25s combined). Within it, `AbstractPostgresIntegrationTest.resetSchema()` was annotated
`@BeforeEach` and ran a full Flyway `clean()` + `migrate()` — **all 66 migrations, replayed before
every one of ~121 integration-test methods**, fully sequentially (no surefire fork parallelism, no
`junit-platform.properties`).

## Fix
Migrate the schema **once per JVM** into a frozen `golden_it` template database, then give each test
a byte-identical copy via `CREATE DATABASE wrk_it TEMPLATE golden_it` — a fast server-side file copy
— instead of replaying migrations. The clone reproduces the exact post-migration state (schema,
migration-seeded reference rows, sequences, identity columns) with zero DDL replay.

Database separation (single Testcontainers Postgres, three DBs):
- `test` (container default) — used by the 5 standalone `@SpringBootTest`s (via
  `@DynamicPropertySource → jdbcUrl`) and `FlywayMigrationTest`, exactly as before (old `jdbcUrl()`
  already returned this DB; everything used to share it). App-boot Flyway migrates it. Never dropped.
- `golden_it` — migrated once, only ever used as a `CREATE ... TEMPLATE` source (no connections).
- `wrk_it` — dropped + recloned from `golden_it` before each `AbstractPostgresIntegrationTest` test.

`AbstractPostgresIntegrationTest` now connects via a new `workingJdbcUrl()` (→ `wrk_it`); the
`jdbcUrl()` accessor keeps its old meaning for the boot-time consumers. `DROP DATABASE ... WITH
(FORCE)` evicts any leaked connection so the drop can't hang.

The **external `TEST_DB_URL` path is unchanged**: still `clean()` + `migrate()` per test, so we never
`DROP DATABASE` on an externally-provided (possibly shared) database. CI uses Testcontainers (no
`TEST_DB_URL`), so CI gets the speedup.

## Files Changed
- `backend/src/test/java/th/co/glr/hr/support/PostgresTestSupport.java`: added golden-template setup
  (`ensureGolden`), per-test clone (`resetToGolden`), admin-SQL helper (`execAdmin`), `usesContainer()`,
  `workingJdbcUrl()`, `workingDatabaseName()`, `externalFlyway()`, and a `goldenMigrateCount()`
  regression counter; `jdbcUrl()` still returns the container default DB.
- `backend/src/test/java/th/co/glr/hr/support/AbstractPostgresIntegrationTest.java`: `resetSchema()`
  now clones the golden template on the container path (clean+migrate retained for `TEST_DB_URL`);
  datasource built from `workingJdbcUrl()`.
- `backend/src/test/java/th/co/glr/hr/support/IntegrationResetInvariantTest.java` (new):
  **enforcement guard** — asserts (1) the full migration set is replayed exactly once per JVM
  (`goldenMigrateCount() == 1`) and (2) repository tests run on the clone DB (`wrk_it`), not the
  shared boot DB. Reverting to per-test clean+migrate fails both. Scoped to the container path.

## Commands Run
```bash
# baseline (old clean+migrate), 13 DB-reset integration classes
./mvnw -B -o test -Dtest="<all AbstractPostgresIntegrationTest subclasses>" -Djacoco.skip=true
# same set, with the change
./mvnw -B -o test -Dtest="<same>" -Djacoco.skip=true
# full suite
./mvnw -B clean verify
```

## Test / Build Results
- Frontend: not run (no frontend change).
- Backend, DB-reset integration classes (121 tests): baseline **3:57 min** → change **2:00 min**,
  all green, identical test count. (Docker/Testcontainers path; integration tests ran.)
- Backend full `clean verify` (with change): **808 tests, 0 failures, BUILD SUCCESS, 2:44 min**
  local wall time (integration tests ran via Testcontainers).
- Guard `IntegrationResetInvariantTest`: 2 tests green; verified it passes on the real code.
- Old-code full-suite baseline was NOT cleanly measurable on this base commit: it aborted on a
  pre-existing flake (a `factory_quote_email_dispatch` scheduled worker in a cached `@SpringBootTest`
  context hit `HikariDataSource has been closed` / bad SQL grammar because a base-class repo test
  `clean()`ed the shared `test` DB out from under it — the "scheduled workers racing shared-DB
  integration tests" issue). Moving repo tests off the shared DB removes that contention as a
  side benefit, but the dedicated origin/main fix for it is not on this base — do not claim this
  change as *the* fix. Clean apples-to-apples number remains the 121-test subset: 3:57 → 2:00.

## Authz Evidence
No authorization change in this task. `SecurityAuthorizationIntegrationTest` and the other
`@SpringBootTest`s still boot the real SecurityFilterChain against the real `test` database exactly as
before — their datasource wiring is unchanged. This change only alters how the repository integration
tests reset their own working database; it does not touch any role gate, scope/filter, or SQL.

## Decisions Made
- Template-clone over transactional rollback: several tests rely on autocommit semantics
  (`assertThatThrownBy(() -> jdbc.update(...))` mid-test, then continue), which a wrapping rollback tx
  would break (aborted-transaction on Postgres). Clone is faithful and keeps autocommit.
- Kept the `@SpringBootTest`/`FlywayMigrationTest` consumers on the container default `test` DB so
  their behavior is byte-for-byte unchanged; only the repo tests moved to `wrk_it`.

## Assumptions
- surefire runs single-fork (`reuseForks=true`, `forkCount=1`) → one JVM, singletons shared. Verified:
  no surefire parallelism config in `pom.xml`.
- No integration test issues DDL (verified: no CREATE/ALTER/DROP TABLE in the integration tests), so
  cloning the migrated golden is a complete reset.

## Known Risks
- If a future test class both extends `AbstractPostgresIntegrationTest` AND is `@SpringBootTest`, its
  app-boot datasource (`jdbcUrl` → `test`) and its per-test `jdbc` (`workingJdbcUrl` → `wrk_it`) would
  point at different DBs. None exist today; add a note if one is introduced.
- Per-test `DROP`/`CREATE DATABASE` (~121×) adds ~50–150ms each (file copy of a small DB) — far cheaper
  than 66-migration replay, but not free.

## Things Not Finished
- Follow-ups #2 (test parallelism via `junit-platform.properties`/surefire forks — only safe now that
  each repo test has an isolated `wrk_it`) and #3 (drop `clean` from the CI command) are not done.

## Recommended Next Agent
Claude Opus review, then merge on explicit say-so.

## Exact Next Prompt
```
Review branch perf/faster-integration-test-db-reset: verify the Testcontainers golden-template clone
in PostgresTestSupport/AbstractPostgresIntegrationTest is faithful to the old clean+migrate reset,
that the @SpringBootTest classes and FlywayMigrationTest are unaffected, and that the full backend
suite is green. If good, advise on follow-ups #2 (surefire/junit parallelism) and #3 (drop `clean`).
```
