# Agent Handoff

## Task
Sync `main` into `uat` — forward the accumulated main-line work (attendance day view,
attendance backfill, employee list/detail fixes, profile avatar menu, authz-evidence rule)
onto the `uat` branch without disturbing the UAT-only stack.

## Branch
`uat`

## Base Commit
`f6d5f87` — merge: sync main into uat — UX/UI audit remediation Phases A + B

## Current Commit
`b603ee7` — merge: sync main into uat — attendance day view + backfill, employee list/detail,
profile avatar menu. **Committed on local `uat`, NOT pushed** (`origin/uat` is still at `f6d5f87`).
Merged source: `origin/main` @ `8b513b8` (Merge pull request #244: bulk-load the attendance backfill).

## Agent / Model Used
Claude Opus 4.8

## Scope

### In Scope
- Merge `origin/main` into `uat`.
- Verify the merge preserves the UAT-only stack (mail seam, seeds, deploy config).
- Run the full backend and frontend suites on the merged tree.

### Out of Scope
- Any behaviour change. This is a pure merge — no hand-edited reconciliation was needed.
- Pushing `uat` (which auto-deploys the hosted `gl-r-erp-uat` Render service).
- The in-flight `feat/sales-factory-quote-costing` work in the primary worktree.

## Files Changed
No files were hand-edited. The merge fast-forwarded 25 commits from `main` with **zero conflicts**
(64 files, +5374 / −612). Notable arrivals:

- `backend/src/main/java/th/co/glr/hr/attendance/daily/**` (new): `AttendanceDailyCalculator`,
  `AttendanceDailyService`, `AttendanceDailyRepository`, `AttendanceDailyRecalcJob` — the
  `hr.attendance_daily` roll-up now has real production writers.
- `backend/src/main/java/th/co/glr/hr/attendance/schedule/**` (new): company-wide work-schedule
  resolver (`08:30`–`17:30`, 5-minute grace, Mon–Fri).
- `backend/src/main/java/th/co/glr/hr/attendance/Attendance{Scope,Sql,*Response}.java` (new):
  day-view endpoints and the scope resolver.
- `backend/src/main/resources/application.yml`: adds `app.attendance.schedule.*` and
  `app.attendance.daily.*` (`recalc-enabled: true`, `recalc-lookback-days: 7`,
  `backfill-on-startup: false`).
- `frontend/src/components/layout/UserMenu.jsx` (new), `frontend/src/features/profile/MyRequestsPage.jsx`
  (deleted) — profile menus collapsed into the avatar dropdown.
- `frontend/src/utils/format.js` + `format.test.js`: shared formatting helpers.
- `CLAUDE.md`: the "Permission changes must ship evidence" rule (PR #239).
- `docs/agent-handoffs/86_*`, `87_*`: employee list/detail handoffs.

**No migration files changed.** Both branches still cap at `V54`; the attendance branch deliberately
held its migration (`ba187a5 chore(attendance): hold the migration; ship only the backfill action`)
after discovering `V56`–`V59` were claimed by the sales branches.

## Commands Run
```bash
git fetch --all --prune
git worktree add .claude/worktrees/uat-sync uat
git merge origin/main --no-edit          # 25 commits, 0 conflicts

cd backend  && ./mvnw -B clean verify
cd backend  && ./mvnw -B test -Dtest='FlywayMigrationTest,AttendanceScopeIntegrationTest,AttendanceDailyRepositoryIntegrationTest'
cd frontend && npm ci && npm run lint && npm test && npm run build
```

## Test / Build Results
- **Backend:** PASS — `Tests run: 586, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
  Integration tests **ran** (Docker available → Testcontainers; `TEST_DB_URL` unset). `Skipped: 0`
  across the whole run, so nothing fell through the `PostgresTestSupport#isAvailable` assumption.
- **Backend, targeted re-run against real Postgres** (to prove the uat gate was not silently skipped):
  - `FlywayMigrationTest` — 4/4 pass, including
    `uatProfileCombinedLocationsApplyToACleanDatabase`, which applies the full
    `V1..V54 + V900..V909` chain to a fresh container and asserts the deal-pipeline seed end state.
  - `AttendanceScopeIntegrationTest` — 10/10 pass.
  - `AttendanceDailyRepositoryIntegrationTest` — 13/13 pass (20.3 s — a real container, not a mock).
- **Frontend:** PASS — `Test Files 37 passed (37)`, `Tests 218 passed (218)`, `built in 191ms`.
- **Lint:** PASS — 0 errors, 3 warnings (pre-existing, inherited from `main`).

## Authz Evidence
**No authorization change was authored in this task** — it is a pure merge.

The merge does *carry in* `main`'s authorization work, which arrives with its own real-service
evidence: `AttendanceScopeIntegrationTest` (10 tests, real Postgres, real service + repository,
written wrong-way-round) was authored on `main` under PR #238/#239 and is **green on the merged
tree**. No permission behaviour was inferred from `mockApi.js`.

## Decisions Made
- **Merged in an isolated worktree** (`.claude/worktrees/uat-sync`) rather than in the primary
  checkout, which is mid-flight on `feat/sales-factory-quote-costing` with ~35 modified files and
  6 uncommitted migrations. The primary worktree was never touched.
- **Took the merge as-is.** The previous uat sync needed hand reconciliation of the mail seam; this
  one did not — `main` changed nothing under `mail/`, and `application.yml` merged additively.
- **Committed locally, did not push.** The merge commit is a normal by-product of `git merge`;
  pushing is not. Pushing `uat` auto-deploys the hosted Render service, which is an outward-facing
  action and is left for explicit owner approval.

## Verification of the UAT-only stack (post-merge)
| Concern | Result |
|---|---|
| Flyway collision | Clear — no new migrations on either side; `V900`–`V909` seeds all present |
| Mail seam | Intact — `mail/{Mailer,ResendMailer,SmtpMailer,LogMailer,MailSendException}` present; `JavaMailSender` appears only in `SmtpMailer` (legitimate) |
| Deploy config | Intact — `gl-r-erp-uat` in `render.yaml`; both `vercel.json` rewrites point at the uat backend, not the demo |
| `application-uat.yml` | Present and unmodified |
| `tools/uat-tests/` | Present and unmodified |

## Known Risks / Open Items
1. **The merge is committed locally but NOT pushed.** Local `uat` is at `b603ee7`; `origin/uat` is
   still at `f6d5f87`, so the hosted `gl-r-erp-uat` Render service has **not** been redeployed and
   still runs the pre-merge build. The commit lives in the `.claude/worktrees/uat-sync` worktree —
   push it (deploys) or reset it, then remove the worktree. Leaving a stale worktree around invites
   the concurrent-worktree collisions this repo has hit before.
2. **`AttendanceDailyRecalcJob` now runs on the uat deploy.** A nightly `02:15 Asia/Bangkok` sweep
   recalculates the last 7 days of `hr.attendance_daily`. The `V901` uat seed uses **fixed** dates
   (`2026-06-29`…`2026-07-04`, no `CURRENT_DATE`), which today (2026-07-20) sit well outside the
   lookback — so seeded attendance will not be rewritten. This is a *date-dependent* safety margin,
   not a structural one: if the seed is ever re-dated to be relative to "now", the job will start
   overwriting it. `backfill-on-startup` is `false` by default, so deploying does not trigger a
   full-history rewrite.
3. **Hosted-UAT drift is untestable locally.** `FlywayMigrationTest` proves a *fresh* database
   migrates cleanly; it cannot see tester-made rows on the hosted UAT DB. This merge adds no
   migrations, so the usual seed-collision risk does not apply here — but the limitation stands for
   the next sync that does.
4. **Stale project memory corrected.** A prior memory recorded `AttendanceDailyCalculator` as
   never having existed and `hr.attendance_daily` as dead with zero production writers. PR #238
   changed that; both are now real. The memory has been updated.

## Next Prompt for the Next Agent
> The `uat` branch has `origin/main` @ `8b513b8` merged into it as commit `b603ee7`, in the worktree
> `.claude/worktrees/uat-sync` (25 commits, zero conflicts, committed but unpushed —
> `origin/uat` is still at `f6d5f87`). Backend 586 tests and
> frontend 218 tests are green on the merged tree, and `FlywayMigrationTest`'s
> `uatProfileCombinedLocationsApplyToACleanDatabase` passes, so `V1..V54 + V900..V909` applies
> cleanly to a fresh database. No migrations were added, so no Flyway checksum repair is needed on
> the hosted DB.
>
> Commit the merge on `uat` and push it. Pushing auto-deploys the hosted `gl-r-erp-uat` Render
> service — confirm with the owner before pushing. After the deploy, verify the new attendance day
> view renders against the seeded UAT data and that the `02:15` recalc job has not disturbed the
> `V901` seed rows (dates `2026-06-29`…`2026-07-04`). Then remove the `uat-sync` worktree.
