# Agent Handoff

## Task
Follow-up #2 from handoff 89: run the backend test suite in parallel to further cut CI wall time,
now that each repository integration test has an isolated `wrk_it` clone (handoff 89 / PR #260).

## Branch
`perf/parallel-backend-tests` (stacked on `perf/faster-it-db-reset-clean` / PR #260).

## Base Commit
`dc90b96` (tip of `perf/faster-it-db-reset-clean`).

## Agent / Model Used
Claude Opus 4.8

## Scope

### In Scope
- Surefire fork-level parallelism (`forkCount=2`, `reuseForks=true`).
- jacoco per-fork exec files + merge so the coverage ratchet still works under forking.
- One **production** change required to make forking safe (see below), stated plainly per CLAUDE.md.

### Out of Scope
- No business logic, schema, migrations, or authorization touched.
- Thread-level (in-JVM) parallelism — rejected: it would need per-thread DB isolation and a full
  thread-safety audit of shared static state. Fork-level gives process isolation for free.

## Approach
Fork-level parallelism: each surefire fork is a separate JVM, so on the Testcontainers path it gets
its **own** throwaway Postgres (its own golden + `wrk_it` clone) and its own Spring context cache —
tests stay fully isolated with zero test-code changes. `forkCount` is the `${test.fork.count}`
property (default 2) so external `TEST_DB_URL` runs can force `-Dtest.fork.count=1` (forks would
otherwise share and clobber that single external DB).

jacoco under forking: `prepare-agent` writes one exec file per fork
(`target/jacoco-exec/jacoco-${surefire.forkNumber}.exec`; the token survives POM interpolation and
surefire substitutes it per fork), a new `merge` execution (phase `prepare-package`) combines them
into `target/jacoco.exec`, and report/check read that as before. Coverage gate verified: "All
coverage checks have been met."

## Production change (stated explicitly — not a side effect smuggled under a test task)
`LibreOfficePdfConverter` hard-coded a single shared LibreOffice profile
(`-env:UserInstallation=file:///tmp/lo-profile`). LibreOffice takes an exclusive lock on its user
profile, so two `soffice` processes sharing it collide — under 2 forks the PDF renderer tests failed
with `NoSuchFileException` (no output PDF). Fix: scope the profile dir **per JVM process**
(`…/glr-lo-profile-<pid>`).
- Single JVM (the app) still reuses one warm profile across all conversions → **no behaviour or
  latency change for production**.
- Separate processes — parallel test forks, or two app instances on one host — no longer clobber
  each other. The old shared `/tmp/lo-profile` was in fact a latent cross-instance bug; this is a
  strict improvement.
- Does NOT attempt to fix concurrent conversions *within one JVM* (pre-existing, out of scope).

## Files Changed
- `backend/pom.xml`: added `test.fork.count` property (default 2); maven-surefire-plugin config
  (`forkCount`, `reuseForks=true`, no `argLine` override so jacoco's agent argLine still flows);
  jacoco `prepare-agent` per-fork `destFile` + new `jacoco-merge` execution.
- `backend/src/main/java/th/co/glr/hr/common/LibreOfficePdfConverter.java`: per-process
  `UserInstallation` profile dir (production robustness change described above).

## Commands Run
```bash
./mvnw -B clean verify                                  # forkCount=2 (default)
./mvnw -B -o test -Dtest='DepositNoticeRendererTest,QuotationRendererTest,RemainingInvoiceRendererTest,PayslipRendererTest' -Dtest.fork.count=2   # repro of the LibreOffice collision + fix
```

## Test / Build Results
- Backend full `clean verify` (forkCount=2): **1001 tests, 0 failures, All coverage checks met,
  BUILD SUCCESS**. Integration tests ran via Testcontainers.
- Renderer tests across 2 forks after the fix: 10/10 green (was 1 error before the fix).
- Local wall time 9:17 (PR #260, sequential) → 8:47 (this branch, 2 forks) — small locally because
  this machine is core-limited under 2 forks; the 4-vCPU CI runner is where fork parallelism pays
  off. Real delta will show in this branch's CI run.

## Authz Evidence
No authorization change in this task.

## Known Risks
- **External `TEST_DB_URL` + `forkCount>1` is unsafe** (forks share one DB). Mitigated by the
  `${test.fork.count}` property + doc; CI uses Testcontainers so is unaffected. Consider defaulting
  to 1 when `TEST_DB_URL` is set if this ever bites.
- `forkCount=2` runs 2 Testcontainers Postgres + 2 Spring context caches concurrently — more runner
  memory. Fine on ubuntu-latest (16 GB); revisit before raising the count.
- Fork balance is surefire's default (round-robin by class); a very uneven split limits the speedup.

## Recommended Next Agent
Claude Opus review; merge after PR #260, then let CI report the runner delta. Tune `test.fork.count`
up (e.g. 3–4) only if the runner has headroom.

## Exact Next Prompt
```
Review branch perf/parallel-backend-tests (stacked on PR #260): confirm fork parallelism keeps the
suite green and the jacoco coverage gate intact, that the LibreOfficePdfConverter per-process profile
change is correct and prod-safe, and read this branch's backend CI wall time vs PR #260's. Advise
whether to raise test.fork.count.
```
