# Agent Handoff

## Task
Remove the UAT persona quick-login feature (one-click sign-in buttons on the login screen for the
9 seeded `@uat.glr` personas) added in `48_feat-uat-quick-login.md`. Requested by Ploy after today's
UAT rebuild ([uat-rebuild-mirror-prod-2026-07-24] in agent memory): the DB was dropped and rebuilt to
mirror real prod data (106/213 real employees, unique `Uat-<id>-<hex>` passwords per person) instead
of the old V900 synthetic 9-persona seed, so the quick-login buttons now point at accounts/passwords
that no longer match the live UAT DB.

## Branch
`chore/uat-remove-quick-login` (based on `origin/uat`, worktree at
`.claude/worktrees/uat-remove-quick-login`)

## Base Commit
767e4a36464d91b9e217c5cbcf05ff1fdbe35172 (origin/uat, "Merge pull request #310 from
waritctd/sync/warehouse-pyzk-to-uat")

## Current Commit
Not yet committed.

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- Remove the frontend quick-login UI, its feature flag, and its persona data.

### Out of Scope
- `backend/src/main/resources/db/migration-uat/V907__uat_clear_forced_password_change.sql` — already
  applied, forward-only per CLAUDE.md; not touched. It still resets the 9 `@uat.glr` synthetic
  personas' password to the shared `Uat@2026` on a fresh seed run, but that seed is not what today's
  rebuilt UAT DB is running on top of.
- `backend/src/test/java/th/co/glr/hr/FlywayMigrationTest.java`
  (`uatPersonasCanSignInWithTheSharedQuickLoginPassword`) — still validates V907's seed-migration
  correctness against a fresh `db/migration-uat` run; that's a migration-history assertion, not the
  removed UI feature, so left as-is.
- `tools/uat-tests/conftest.py` — separate pytest acceptance-test tooling that logs in with the
  shared `Uat@2026` password directly (not via the removed UI buttons). Left untouched; flagged below
  as a known risk since it targets the old synthetic personas, which may no longer exist post-rebuild.
- `docs/agent-handoffs/48_feat-uat-quick-login.md` — left as the historical record of the original
  feature; not rewritten.

## Files Changed
- `frontend/src/features/auth/LoginPage.jsx`: removed the `UAT_QUICK_LOGIN_ENABLED` block (the
  "UAT — เข้าสู่ระบบด่วน" persona buttons) and its imports.
- `frontend/src/features/auth/uatQuickLogin.js`: deleted (the 9-persona list + shared `UAT_PASSWORD`).
- `frontend/src/features/auth/LoginPage.test.jsx`: deleted (entirely dedicated to the removed
  feature — three tests, all about `UAT_QUICK_LOGIN_ENABLED`).
- `frontend/src/app/features.js`: removed the `UAT_QUICK_LOGIN_ENABLED` export.
- `frontend/.env.production`: removed `VITE_UAT_QUICK_LOGIN=true` and its comment (this is the
  uat-branch-only overlay file that turned the feature on).
- `frontend/.env.example`: removed `VITE_UAT_QUICK_LOGIN=false` and its comment.

## Commands Run
```bash
cd frontend && npm install   # fresh worktree, no node_modules
npm run lint
npm test -- --run
npm run build
git checkout -- frontend/package-lock.json   # revert incidental lockfile churn from npm install
```

## Test / Build Results
- Lint: pass (1 pre-existing unrelated warning in `PayrollPage.jsx`, not touched by this change)
- Tests: pass — 63 test files, 553 tests (LoginPage.test.jsx's 3 tests correctly gone, nothing else
  broke — `LoginPage.test.jsx` was the only file referencing the removed exports)
- Build: pass (`vite build`, production mode, no errors)
- Backend: not run — no backend files changed

## Authz Evidence
No authorization change. This removes a UI convenience (real credentials, real `/api/auth/login`,
no auth-decision logic in the frontend or backend) — it does not touch any role gate, scope, or
backend permission check.

## Decisions Made
- Deleted `LoginPage.test.jsx` outright rather than trimming it, since 100% of its content was about
  the removed feature.
- Left the already-applied `V907` migration and its `FlywayMigrationTest` case alone per the
  forward-only migration rule — they validate seed-migration state, not the UI.

## Assumptions
- "Remove quick login from uat" refers to the login-screen one-click persona buttons
  (`VITE_UAT_QUICK_LOGIN` feature), not the separate mock-only DEMO quick-login on `main`
  (`VITE_USE_MOCKS`-gated, used by the K2 Playwright e2e suite) — that one is untouched and out of
  scope.

## Known Risks
- The shared `Uat@2026` password still technically works against the 9 old synthetic `@uat.glr`
  accounts if they still exist in the live UAT DB (via V907, already applied) — there's just no
  button for it anymore. If today's UAT rebuild dropped those rows entirely, this is moot; if any
  survived the rebuild, the credential is public (`UAT_Accounts.md`, `V900`) but no longer
  discoverable via the UI.
- `tools/uat-tests/conftest.py` still assumes the 9 synthetic personas exist with the shared
  password — it was not part of this task's scope, but it may already be broken by today's DB
  rebuild independent of this change.

## Things Not Finished
- Not committed or pushed. No PR opened.

## Recommended Next Agent
Same implementation agent, or a quick reviewer pass, then commit + PR against `uat`.

## Exact Next Prompt
```
Review the diff on chore/uat-remove-quick-login (worktree .claude/worktrees/uat-remove-quick-login,
based on origin/uat). Confirm the UAT quick-login removal is complete and no dead references remain,
then commit and open a PR into `uat` (not `main` — this feature only ever existed on the uat overlay).
```
