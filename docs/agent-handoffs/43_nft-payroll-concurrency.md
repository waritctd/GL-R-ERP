# Agent Handoff

## Task
Round B of a 3-round pre-UAT NFT hardening pass (see plan `highest-value-currently-missing-cuddly-knuth`, opus-plans/sonnet-executes loop; Round A: [42_nft-2-live-load-smoke.md](42_nft-2-live-load-smoke.md)). Add the first real multi-threaded concurrency test to the backend suite, targeting `POST /api/payroll/process`, and — since the race turned out to be real — apply the minimal fix: a Postgres advisory lock so two concurrent same-month payroll-process calls serialize instead of racing into a raw constraint-violation 500. No business-logic (payroll/tax math) change.

## Branch
`test/nft-payroll-concurrency`

## Base Commit
`46311eccc1e3353b19e59f444176a367f11b9a39` (main tip; same base as Round A, cut separately)

## Current Commit
Not committed yet — awaiting user confirmation (repo rule: no push/merge/PR without explicit confirmation; this session also treats local commits as needing a check-in, per Round A's practice).

## Agent / Model Used
Claude Opus (micro-plan) → Claude Sonnet 5 (execution, this handoff)

## Scope

### In Scope
- One new integration test: `PayrollProcessConcurrencyIntegrationTest`, firing two concurrent `PayrollService.process()` calls (real threads, real Testcontainers Postgres) for the same payroll month.
- Confirming the pre-fix race actually reproduces (temporarily reverted the fix, ran the test, restored it — see Decisions Made).
- The minimal fix: a `pg_advisory_xact_lock` in `PayrollRepository.saveProcessedPeriod`.
- Full backend suite run (`./mvnw -B clean verify`) to confirm no regressions.

### Out of Scope
- Any payroll/tax/commission calculation logic — untouched.
- Rolling out `@Version`/optimistic locking elsewhere in the codebase (still zero instances outside this round; not this round's job).
- New error-handling/UI for a "conflict" response — the fix makes the second concurrent call succeed (serialize-then-succeed), not fail with a new structured error, so no frontend scope was needed.
- The frozen sales/CRM stack.
- Round C (migration replay) — next up.
- Push / PR / merge.

## Files Changed
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollRepository.java` — added a `PAYROLL_PROCESS_LOCK_NAMESPACE` constant, a private `acquirePayrollMonthLock(LocalDate)` method (`pg_advisory_xact_lock(namespace, hashtext(month))`), and one call to it at the top of `saveProcessedPeriod(...)`. No change to the DELETE/UPDATE/INSERT logic itself, no signature change, no new dependency, no migration.
- `backend/src/test/java/th/co/glr/hr/payroll/PayrollProcessConcurrencyIntegrationTest.java` (new) — two-thread `@SpringBootTest` race test against `PayrollService.process()`.

## Commands Run
```bash
git checkout main && git checkout -b test/nft-payroll-concurrency

# Implemented the lock in PayrollRepository.saveProcessedPeriod, wrote the new test.

cd backend
./mvnw -B -Dtest=PayrollProcessConcurrencyIntegrationTest test   # FAIL first: employee_code
                                                                   # too long (VARCHAR(20)) —
                                                                   # fixed to base-36 nanoTime suffix
./mvnw -B -Dtest=PayrollProcessConcurrencyIntegrationTest test   # PASS (with fix in place)

# Reproduction check: temporarily commented out acquirePayrollMonthLock(...) call
./mvnw -B -Dtest=PayrollProcessConcurrencyIntegrationTest test   # FAIL as predicted —
  # org.springframework.dao.DuplicateKeyException: duplicate key value violates unique
  # constraint "payroll_period_period_start_period_end_key" — confirms the race is real
  # and the test actually catches it (not a vacuous green).
# Restored the acquirePayrollMonthLock(...) call.
./mvnw -B -Dtest=PayrollProcessConcurrencyIntegrationTest test   # PASS again

./mvnw -B clean verify   # first run: 1 unrelated pre-existing failure in the full suite
                          # (lineCount 2 vs 4 — see Decisions Made, test-pollution fix applied)
./mvnw -B clean verify   # second run after fixing scoped assertions: 1 unrelated transient
                          # failure (BadSqlGrammar on hr.title / hr.employee in unrelated
                          # test classes — self-resolved, not reproducible)
./mvnw -B clean verify   # third run: BUILD SUCCESS, 323/323, 0 failures, 0 errors
```

## Test / Build Results
- `PayrollProcessConcurrencyIntegrationTest` (targeted): **PASS**, both concurrent `process()` calls succeed, exactly 2 lines / 2 distinct employees for the period (scoped to the test's own seeded employees — see below).
- Full backend suite (`./mvnw -B clean verify`): **PASS**, 323 tests, 0 failures, 0 errors, coverage checks met, `BUILD SUCCESS`.
- Frontend: not run — no frontend files touched this round.

## Decisions Made
- **Direct two-thread `PayrollService.process()` call, not MockMvc or real HTTP** — MockMvc runs synchronously on the calling thread (no real overlap of the DELETE→INSERT window); real HTTP would add unrelated auth/session/rate-limit scaffolding. Calling the Spring-proxied service bean from two threads via `ExecutorService` + `CountDownLatch` exercises the real `@Transactional` boundary and the real DB race with no extra noise.
- **Advisory lock, not `@Version`** — `@Version` optimistic locking would require schema changes across every entity and doesn't fit a single-endpoint fix; a transaction-scoped `pg_advisory_xact_lock` keyed on `(namespace, hashtext(payrollMonth))` is a ~10-line addition, auto-releases on commit/rollback, and only serializes same-month calls (different months don't block each other).
- **Verified the fix actually does something** — before trusting the green test, I temporarily reverted the lock and re-ran: it failed with `DuplicateKeyException` on `payroll_period_period_start_period_end_key` (a slightly different unique constraint than the Opus plan's prediction of `ux_payroll_period_month`, but the same root cause — the concurrent get-or-create-period race). This confirms the DB's existing unique constraints already prevented silent data corruption; what was missing was graceful serialization instead of a raw 500 on the losing call.
- **Block-then-succeed over reject-with-409** — reprocessing the same month is already a supported, idempotent operation (proven by the existing `reprocessingTheSameMonthReplacesLinesInsteadOfDuplicating` test), so letting the second concurrent call block and then succeed with identical output is the least-surprising behavior and needs zero new error-handling/UI scope.
- **Scoped post-race assertions to the test's own seeded employee ids**, not the whole period. First full-suite run revealed `lineCount == 4` instead of 2: `PayrollRepositoryIntegrationTest` seeds its own active/salaried employees (`EMP-001`/`EMP-002`) into the same shared Testcontainers Postgres container (per-JVM singleton, not reset between test classes), and `findActiveEmployees()` has no test-scoping — so those leaked into this test's payroll preview for the same month. Fixed by filtering the post-race count queries to `employee_id IN (:emp1, :emp2)`, which isolates the race under test from cross-suite pollution without changing any other test.

## Assumptions
- Docker/Testcontainers available locally (confirmed — same container reused within a run per `PostgresTestSupport`).
- `@SpringBootTest`-based tests share one Postgres container per JVM/Maven run with no cleanup between classes (confirmed via the pollution finding above) — this is pre-existing test architecture, not something this round changes.
- The advisory lock is per-Postgres-instance (correct for the current single-DB deployment; would not serialize across two independent Postgres primaries, which isn't the current topology).

## Known Risks
- The advisory-lock fix is narrowly scoped to `POST /api/payroll/process`. The broader finding — **zero `@Version`/optimistic-locking annotations anywhere in the backend** — remains true for every other write path (employee edits, attendance, leave, OT, commission). This round deliberately did not expand scope to cover those; flagging for a possible future round if the user wants broader concurrency hardening.
- One `./mvnw -B clean verify` run hit a transient `BadSqlGrammar` failure in unrelated test classes (`EmployeeRepositoryIntegrationTest`, `OvertimeRepositoryIntegrationTest` — `hr.title` table apparently missing mid-run) that did not reproduce on the next two runs and left no stray Docker containers behind. Likely a one-off Testcontainers/parallel-fork startup race, not caused by this round's changes (it also didn't reproduce when re-running with this round's changes in place). Noting it in case it recurs for a future agent — not something fixed or root-caused this round.

## Things Not Finished
- Round C (migration replay on a prod-shaped dataset) — next up.
- Broader `@Version`/optimistic-locking rollout beyond payroll processing — explicitly out of scope, flagged as a possible future round.
- Push / PR / merge — deliberately not done, needs explicit user confirmation.

## Recommended Next Agent
Claude (Sonnet), continuing the opus-plans/sonnet-executes loop for Round C.

## Exact Next Prompt
```
Round B (payroll concurrency test + advisory-lock fix) is complete and recorded in
docs/agent-handoffs/43_nft-payroll-concurrency.md — PayrollProcessConcurrencyIntegrationTest
passes, the pre-fix race was confirmed to reproduce (DuplicateKeyException) before the fix
and pass after, and the full backend suite is green (323/323).

Start Round C: on a fresh branch `test/nft-migration-replay` off main, get an Opus-tier
Plan agent to produce a micro-plan for a migration replay against a prod-shaped dataset.
The `uat` branch (local + origin, unmerged) has backend/src/main/resources/db/migration-uat/
V900-V905 (32 employees + dual-track sales fixtures) — extract those seed files via
`git show uat:<path>` into a disposable location WITHOUT merging the uat branch's other
changes into main. Spin up a fresh disposable Postgres (a differently-named docker-compose
service/volume so it doesn't collide with anything), run the full db/migration chain, layer
the extracted V900-V905 seed on top, and confirm the chain applies cleanly with no
Flyway checksum/ordering errors, capturing total migration duration. Per the plan at
~/.claude/plans/highest-value-currently-missing-cuddly-knuth.md, this is a one-off
verification pass — only add production migration fixes if replay surfaces an actual
ordering/perf problem, don't preemptively refactor migrations. Note: this session found the
repo directory lives inside iCloud Drive's synced Desktop folder and iCloud can spontaneously
create " 2"-suffixed conflict-copy duplicate files that break Docker builds (see Round A's
handoff, Known Risks) — check for and remove any such stray files before building if the
Docker build fails with duplicate-class errors. Run ./mvnw -B clean verify, update/create the
Round C handoff file, and checkpoint with the user before pushing/opening a PR.
```
