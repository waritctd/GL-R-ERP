# Agent Handoff

## Task
Stop the core Flyway migrations from seeding invented "sample/demo" data into real production. A
manual cleanup of prod on 2026-07-28 removed the rows, but a rebuilt production database would
migrate straight back into them, because the INSERTs live in `db/migration` — the folder every
environment runs — not in `db/migration-demo`.

## Branch
`chore/remove-demo-seed-from-migrations` (worktree: `.claude/worktrees/demo-seed-cleanup`)

## Base Commit
`c345e1c` — `refactor(ui): repair ticket worklist and shared table interactions (#319)`, i.e. current
`origin/main`.

## Current Commit
Not committed. Working tree only, awaiting review.

## Agent / Model Used
Claude Opus 5.

## Scope

### In Scope
- A forward-only `V91` that deletes the sample fixtures seeded by V16 / V23 / V24 / V25.
- A demo-only `V91.1` that restores them for the Render showcase.
- Assertions in `FlywayMigrationTest` proving both halves, on real Postgres.

### Out of Scope
- Editing V16/V23/V24/V25 in place. Their checksums are already recorded on every deployed database;
  changing them breaks Flyway validation at startup. This repo has been bitten by version/checksum
  collisions twice before (V31, V32).
- The runtime test data also cleaned out of prod by hand on 2026-07-28 (one `reason='TEST'` overtime
  request, six `DEMO-TKT-01` notifications, four duplicate `profile_change_request` rows). None of it
  came from a migration, so no migration should recreate or delete it.
- `sales.fx_rates` — seeded by V26 with stale values, but it is live pricing configuration, not junk.
  Update the rates; do not delete the rows.
- `db/migration-uat`. That folder lives on the `uat` branch and is not present here. See Known Risks.

## Files Changed
- `backend/src/main/resources/db/migration/V91__remove_sample_seed_from_core_migrations.sql` (new):
  deletes 4 customers, 5 contacts, 5 projects, 14 `sales.catalog` rows and 4 `sales.factory_config`
  rows. Matches on the seed's natural keys (tax_id / email / name / brand+collection+color+size),
  never on surrogate ids, so a real row that merely occupies id 1 is untouched. The customer,
  contact and project deletes carry `NOT EXISTS` guards against `sales.ticket` and
  `sales.pricing_request`.
- `backend/src/main/resources/db/migration-demo/V91.1__demo_restore_sample_fixtures.sql` (new):
  re-inserts the same fixtures, anti-joined on their natural keys so it is idempotent and never
  duplicates a row V91's guards spared. Children re-attach to their parent by `tax_id`, because a
  deleted-then-restored customer comes back with a fresh identity value.
- `backend/src/test/java/th/co/glr/hr/FlywayMigrationTest.java`: both existing tests now assert row
  counts after migrating. Core-only run → all five tables empty. Demo combined-locations run →
  4/5/5/14/4. Adds a private `countRows` helper over `PostgresTestSupport`'s JDBC coordinates.

## Commands Run
```bash
createdb glr_v91_test
TEST_DB_URL="jdbc:postgresql://localhost:5432/glr_v91_test" TEST_DB_USERNAME="$USER" TEST_DB_PASSWORD="" \
  ./mvnw -B test -Dtest=FlywayMigrationTest -Dtest.fork.count=1
TEST_DB_URL="jdbc:postgresql://localhost:5432/glr_v91_test" TEST_DB_USERNAME="$USER" TEST_DB_PASSWORD="" \
  ./mvnw -B clean verify -Dtest.fork.count=1
```

> **The property is `test.fork.count`, not `fork.count`.** `pom.xml:24` spells this out, and Maven
> ignores an unknown `-D` silently. Getting it wrong against an external `TEST_DB_URL` leaves
> `forkCount=2`, and the two JVMs run `flyway.clean()` on the same database concurrently: the first
> full `verify` attempt here came back `Tests run: 1226, Failures: 3, Errors: 201` with errors like
> `Unable to drop "hr"."division" ... table "division" does not exist` and `Found non-empty schema(s)
> ... but no schema history table`. Those are two forks clobbering each other, not a code defect —
> but the only honest way to know that is to re-run correctly, which is what the recorded result
> below is.

## Test / Build Results
- Backend `FlywayMigrationTest`: **pass, 2/2**. Integration tests **ran** — real local Postgres via
  `TEST_DB_URL` (Docker was unavailable, so this was the external-DB path, `fork.count=1`).
  `allMigrationsApplyToACleanDatabase` reaches v91 across 89 migrations; the demo run reaches v91.1
  across 93.
- Backend `./mvnw -B clean verify -Dtest.fork.count=1`: **pass — `Tests run: 1226, Failures: 0,
  Errors: 0, Skipped: 2`**, jacoco coverage checks met, BUILD SUCCESS in 7:44. Integration tests
  **ran** (external local Postgres, not skipped).
- Frontend: **not run** — no frontend file is touched by this branch.
- Lint: **not run** (backend has no separate lint step; `verify` covers compile + checks).

### Mutation check (required by CLAUDE.md — a green test that cannot fail is not evidence)
1. Replaced V91's body with `SELECT 1;` → `allMigrationsApplyToACleanDatabase` **failed**
   (`expected: 0`), demo test stayed green. 1 failure, 0 errors — the failure is targeted, not
   collateral.
2. Removed only the `NOT EXISTS (... sales.ticket ... customer_id ...)` guard →
   `demoProfileCombinedLocationsApplyToACleanDatabase` **errored** with
   `violates foreign key constraint "ticket_customer_id_fkey" on table "ticket"`, which is exactly
   the breakage the guard exists to prevent.
3. Both mutations reverted; `diff` against the pre-mutation copy confirms V91 is byte-identical, and
   `git status` shows only the three intended files.

## Authz Evidence
**No authorization change in this task.** V91/V91.1 touch only seed data rows in
`customers.customer`, `customers.contact`, `customers.project`, `sales.catalog` and
`sales.factory_config`. No role gate, no scope/filter, no change to who may read or write whose rows,
and no Java service or repository was modified.

## Decisions Made
- **Forward-only delete rather than editing the seeding migrations.** Non-negotiable per CLAUDE.md;
  also the only option that leaves already-deployed databases valid.
- **Guarded deletes rather than unconditional ones.** On the demo showcase, V21 creates `DEMO-TKT-*`
  tickets against these customers and V32 links them by FK — both run before V91, so an unguarded
  delete would abort the deploy with a foreign-key error. Mutation 2 above demonstrates this
  concretely.
- **Restore the fixtures for the demo profile (V91.1) rather than let the showcase lose them.** The
  showcase is the environment these rows were written for: it browses the catalog and generates
  quotations that auto-fill a customer's tax ID and address. Numbered `V91.1` so Flyway orders it
  immediately after `V91` — version ordering is global across configured locations, not per folder —
  following the existing `V11.1` / `V11.2` convention. If you would rather the showcase simply lose
  the fixtures, delete this one file; V91 stands on its own.
- **Natural-key matching.** Deleting by id would be shorter and wrong.

## Assumptions
- The Render showcase still wants sample customers and a sample catalog. If it does not, drop V91.1.
- Nobody has legitimately created a customer carrying one of the four invented tax IDs
  (`0105565012345`, `0105556789012`, `0105578901234`, `0105591234567`).

## Known Risks
- **UAT.** `db/migration-uat` is not on `main`, so this branch could not check it. On a fresh UAT
  database V91 runs long before the `V900+` seed, so it deletes the sample rows and UAT's own seed
  then inserts its data — expected to be fine, but confirm when this reaches the `uat` branch that no
  `V90x` file hard-codes `customer_id = 1`.
- **Existing databases keep their history.** V91 changes nothing that is already deleted; on real
  prod it is a no-op by design (the rows went on 2026-07-28). Its value is entirely in what a
  *rebuilt* database does.
- **The root cause is a habit, not a file.** Four separate migrations shipped invented data into the
  production folder over time. Nothing in CI stops a fifth. The new assertion in
  `FlywayMigrationTest` now does, but only for these five tables.

## Things Not Finished
- Not committed, not pushed, no PR opened — waiting on explicit say-so.
- No CI run yet (branch is local only).

## Recommended Next Agent
A reviewer (Opus) to re-verify the guards and the mutation evidence, then — on Ploy's say-so — commit
and open the PR.

## Exact Next Prompt
> Review branch `chore/remove-demo-seed-from-migrations` (worktree
> `.claude/worktrees/demo-seed-cleanup`, based on `origin/main` @ c345e1c). Read
> `docs/agent-handoffs/116_chore-remove-demo-seed-from-migrations.md` first. Re-verify independently:
> (1) that V91's natural-key matching cannot delete a legitimately-created row; (2) that the
> `NOT EXISTS` guards cover every inbound FK to `customers.customer`, `customers.contact` and
> `customers.project` that exists at V91 time — re-derive the FK list from the schema, do not trust
> the handoff; (3) that V91.1 is genuinely idempotent and restores exactly what V91 removed; and
> (4) re-run the mutation checks yourself. Then run
> `TEST_DB_URL=... ./mvnw -B clean verify -Dfork.count=1` against a throwaway Postgres. Report
> findings; do not commit.
