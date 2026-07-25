# 115 — Sync main into uat (UI-repair phases 0–3 + phase 4A + audit gate)

Branch: `uat` (pushed directly, no PR — owner-authorised)
Sync branch used: `sync/main-into-uat-c345e1c` (created from `origin/uat`)
Date: 2026-07-25
Main tip merged: `c345e1c`

## What this sync brings to uat

Everything on `main` that uat did not yet have — 50 commits. The substantive ones:

| Change | Origin |
| --- | --- |
| UI-repair phases 0–3 foundation: `docs/ui-repair/00-governance` … `03-design-foundation`, `PRODUCT.md`, `DESIGN.md`, additive `index.css` semantic tokens + `designTokens.test.js` | PR #318 |
| UI-repair phase 4A: ticket worklist rebuild, tablet shell repair, shared `DataTable`/`Button`/focus repair, mobile filter sheet modal contract | PR #319 |
| Frontend CI audit gate: `scripts/audit-gate.mjs` + `audit-allowlist.json` replacing bare `npm audit` | PR #320 |
| Docs consolidation: handoff index + pruning of stale docs | PR #316 |
| `prod` profile no longer applies `db/migration-demo` | earlier main work |

## Why a merge, not a rebase or reset

uat carries 111 uat-only commits — the `application-uat` Spring profile, the
`db/migration-uat` V900–V910 synthetic seed, the `gl-r-erp-uat` Render service, the
uat `vercel.json`/`.env.production`, and uat-specific test fixes. A rebase or a
force-push of main over uat would destroy them. This is the same
`merge main into uat` shape as syncs #89 and #103.

## Safety checks performed before pushing

- **Dry run first.** `git merge-tree --write-tree origin/uat origin/main` — clean, 0 conflicts.
  The real merge then also reported 0 conflicts.
- **Zero new migrations reach uat.**
  `git diff --diff-filter=A origin/uat HEAD -- backend/src/main/resources/db/` is empty: uat
  already had every migration through `V90`. The schema uat deploys is therefore **unchanged by
  this sync**, which means the V900+ seed-ordering trap (see #47, and the `uat-seed-v900-ordering`
  note) is not triggered here.
- **No Flyway version collisions.** No duplicate version across
  `db/migration` (V1–V90, continuous), `db/migration-demo` (V21–V46), `db/migration-uat`
  (V900–V910).
- **`V89`/`V90` are byte-identical between `main` and `uat`** — the earlier cherry-picks of the
  warehouse-device and leave-subday migrations were faithful, so the merge did not have to
  reconcile two versions of the same schema change.
- **uat assets intact after merge:** `application-uat.yml` **untouched** (still
  `locations: classpath:db/migration,classpath:db/migration-uat`), all 11 `V900–V910` seeds,
  `frontend/.env.production`, `render.yaml`'s `gl-r-erp-uat` service (`branch: uat`,
  `SPRING_PROFILES_ACTIVE=uat`), and `vercel.json` still rewriting `/api/*` to
  `gl-r-erp-uat.onrender.com` (not the demo backend — preserving the #? fix that repointed it).
- **Deletions audited:** 127 files deleted, **all** under `docs/` or `ERP Documentation/`, all from
  main's #316 consolidation. No backend, frontend, or uat-config file deleted. Two historical uat
  records went with it — `75_live-fire-api-test-uat-main.md` and
  `77_functional-db-live-test-uat.md` — which is main's deliberate pruning, inherited rather than
  fought.
- **Backend delta is 2 files only:** `application-prod.yml` (drops `db/migration-demo` from the
  `prod` profile) and a Javadoc-only change in `FlywayMigrationTest`. Both concern the
  `prod`/`demo` profiles; **neither affects the `uat` profile or the uat deploy.**

## Files changed

Only two categories, since uat already had the backend:

1. `frontend/**` — the phases 0–3 token work, phase 4A worklist/shared-table repair, the new
   `e2e/` Playwright suite, `scripts/audit-gate.mjs`, `audit-allowlist.json`, `playwright.config.js`.
2. `docs/**`, `PRODUCT.md`, `DESIGN.md` — the UI-repair documentation set, the handoff index, and
   the consolidation's deletions.

Plus the 2 backend files above.

## Commands run

```
git merge-tree --write-tree origin/uat origin/main      # dry run — clean
git switch -c sync/main-into-uat-c345e1c origin/uat
git merge origin/main --no-edit                          # 0 conflicts
cd frontend && npm ci
npm run audit && npm run lint && npm test && npm run build && npm run test:e2e
cd ../backend && ./mvnw -B compile
./mvnw -B test -Dtest='LeaveDayMathTest,SpecialMoneyPolicyEvaluatorTest,AttendanceDailyCalculatorTest'
```

## Tests / build results

| Gate | Result |
| --- | --- |
| `npm run audit` (new gate) | **PASS** — no unreviewed advisories, no expired exceptions |
| `npm run lint` | **PASS** — 0 errors, 1 pre-existing unrelated warning (`PayrollPage.jsx:312`) |
| `npm test` | **PASS** — 67 files / 635 tests |
| `npm run build` | **PASS** |
| `npm run test:e2e` | **PASS** — 64 Playwright specs (mock frontend) |
| `git diff --check` | clean |
| Backend `compile` | **PASS** |
| Backend targeted unit tests | **PASS** — 57 tests (leave day-math, special-money policy, attendance daily calculator) |
| Backend **full** suite | **NOT COMPLETED** — see below |
| Backend **integration** tests | **NOT RUN** — see below |

### Backend suite: not run, and why

`./mvnw -B test` was started and **hung for ~88 minutes** with no surefire output after the first
4 classes (60 tests, 0 failures). This host has **no Docker daemon, no local Postgres on 5432, and
no `TEST_DB_URL`**, so the first DB-dependent test blocks instead of skipping. The run was killed.
A targeted DB-free subset then passed in ~2s, confirming the harness itself is healthy and the
hang was environmental.

Consequence for this sync, stated plainly:

- `FlywayMigrationTest` — the combined-location test that would prove `db/migration-uat`'s seeds
  still apply cleanly over main's schema — **did not run**.
- This is mitigated, not ignored: the sync introduces **zero new migrations** to uat, so the
  migration set uat deploys is byte-identical to what it already deployed successfully. There is no
  new schema for the seeds to trip over.
- The two changed backend files were already validated by backend CI on `main` as part of their
  original PRs.

**Do not read this handoff as backend-verified on uat.** If a backend regression appears on the
uat deploy, run `./mvnw -B clean verify` with Docker running or `TEST_DB_URL` set before looking
anywhere else.

## Authz evidence

**No authz change.** This sync alters no role gate, scope filter, or read/write ownership rule.
`canViewDealPipeline` and every route guard arrive from `main` byte-identical to what CI validated
there. All frontend verification ran under `VITE_USE_MOCKS=true`, so per CLAUDE.md **no permission
behaviour is verified here** and none is claimed.

## Known risks

1. **Backend full suite + Flyway combined-location test unrun** on this host (above). Low residual
   risk because no migration is new to uat, but it is unverified rather than verified.
2. **Render auto-deploys `uat` on push.** The deploy will re-run Flyway against the hosted uat DB.
   With no new migrations this should be a schema no-op, but watch the deploy log — a checksum
   mismatch on an already-applied file would surface here (see #47 and the Flyway-checksum-repair
   note for the recovery path).
3. **uat frontend is now the phase 4A worklist.** Testers will see the rebuilt `/tickets`
   hierarchy, the new tablet icon rail, and the mobile filter bottom sheet. Brief them, or
   stale test scripts will read as regressions.
4. **Audit allowlist expires 2026-10-23.** `GHSA-qwww-vcr4-c8h2` (react-router, prod scope) and
   `GHSA-mh99-v99m-4gvg` (brace-expansion, dev scope) are reviewed exceptions with that deadline —
   CI goes red on 2026-10-24 unless react-router is upgraded first. Applies to `uat` as well as
   `main`.
5. **One unpushed local uat commit was deliberately left out:** `d354de9`
   (`docs(handoffs): record the main-into-uat sync (V55-V79)`), which had been sitting unpushed on
   the local `uat` branch since 2026-07-22. It is preserved as tag
   `backup/uat-local-unpushed-d354de9`. Decide whether to land it; it is docs-only.

## Exact next prompt for the next agent

> `uat` has been synced with `main` at `c345e1c` (see
> `docs/agent-handoffs/115_sync-main-into-uat-ui-repair.md`). Render auto-deploys `uat` on push.
> Verify the uat deploy came up: check the Render `gl-r-erp-uat` service log for a clean Flyway run
> (expect **no new migrations applied** — the sync added none; a checksum error is the failure mode
> to look for), then smoke the uat frontend as `sales` and `ceo` on `/tickets` at 1366x768 and
> 390x844 to confirm the phase 4A worklist renders against the real backend rather than mocks.
> Backend integration tests were NOT run during the sync (no Docker/Postgres on that host) — if you
> have Docker or `TEST_DB_URL`, run `cd backend && ./mvnw -B clean verify` and record the result,
> paying attention to `FlywayMigrationTest`. Do not force-push `uat` or rebase it onto `main`; it
> carries 111 uat-only commits including `application-uat.yml` and the `V900–V910` seed.
