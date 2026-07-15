# Agent Handoff

## Task
Give UAT a quick login: one-click sign-in as any of the 9 seeded UAT personas from the login screen,
so testers can switch roles without retyping credentials.

## Branch
`feat/uat-quick-login` (off `origin/uat`)

## Base Commit
`9a6ffda` (origin/uat tip — "chore: trigger uat redeploy after schema history rebuild")

> Note: the local `uat` branch was 2 commits **behind** `origin/uat` when this branch was cut, so the
> base is `origin/uat`, which includes the V903/V905 fresh-DB fix (#184) and the redeploy trigger.

## Current Commit
_Not committed — working tree only (no commit/push was requested)._

Work was done in a worktree at `.claude/worktrees/uat-quick-login` to avoid disturbing the
in-progress catalog-importer changes on `chore/catalog-size-unit-fix` in the primary checkout.

## Agent / Model Used
Claude Opus 4.8

## Decisions Made (confirmed with the user before implementing)
1. **One-click, not prefill.** Clicking a persona signs in immediately rather than only filling the
   form fields.
2. **Drop the forced password change** for the 9 UAT personas (new migration V907), accepting the
   loss of the UAT-AUTH-01/02 forced-change coverage that V900 was set up to exercise.
3. **Land on a branch off `uat`**, because the flag is switched on by `frontend/.env.production`,
   which is a uat-only overlay file that must never merge to `main`.

## Scope

### In Scope
- A flag-gated persona quick-login block on the login screen (uat builds only).
- A UAT-only migration that keeps the shared `Uat@2026` password valid.

### Out of Scope
- The existing mock quick-login block (`VITE_USE_MOCKS`) — untouched; it posts a password-less
  `{ role }` that `AuthService` rejects with a 403, so it was never usable on UAT.
- Any change to auth code, `DivisionAccessPolicy` role derivation, or `db/migration` (real
  migrations). `admin@uat.glr` still resolves to role `employee` — the documented V900 caveat.

## Files Changed
- `backend/src/main/resources/db/migration-uat/V907__uat_clear_forced_password_change.sql` **(new)**:
  resets `password_hash` to V900's shared `Uat@2026` BCrypt hash and sets
  `must_change_password = FALSE` for the 9 `@uat.glr` personas, scoped by an explicit email IN-list.
  **Deliberately destructive**: overwrites any password a tester already chose for those 9 seed
  accounts — that is what makes the shared password reliable again.
- `frontend/src/features/auth/uatQuickLogin.js` **(new)**: the 9 personas (email + label + Thai
  helper + icon) and the shared `UAT_PASSWORD`.
- `frontend/src/app/features.js`: added `UAT_QUICK_LOGIN_ENABLED` (`VITE_UAT_QUICK_LOGIN === 'true'`).
- `frontend/src/features/auth/LoginPage.jsx`: added the flag-gated "UAT — เข้าสู่ระบบด่วน" block;
  each button calls `onLogin({ email, password: UAT_PASSWORD })` — real credentials, real backend.
- `frontend/src/features/auth/LoginPage.test.jsx` **(new)**: 3 tests — hidden when the flag is off,
  posts real credentials (not a role), and offers all 9 personas in seed order.
- `backend/src/test/java/th/co/glr/hr/FlywayMigrationTest.java`: added
  `uatPersonasCanSignInWithTheSharedQuickLoginPassword`, which applies the uat-profile migrations to
  a clean DB and asserts each persona is seeded, accepts `Uat@2026` via `BCryptPasswordEncoder`, is
  active, and is not forced through a password change.
- `frontend/.env.production`: `VITE_UAT_QUICK_LOGIN=true` (uat overlay — this is what turns it on).
- `frontend/.env.example`: documents `VITE_UAT_QUICK_LOGIN=false` as the default everywhere else.

## Commands Run
```bash
git worktree add .claude/worktrees/uat-quick-login -b feat/uat-quick-login origin/uat
cd frontend && npm run lint && npm test && npm run build
cd frontend && VITE_UAT_QUICK_LOGIN=false npx vite build --mode production   # tree-shake check
cd backend && ./mvnw -B clean verify
cd backend && ./mvnw -B clean test -Dtest=FlywayMigrationTest                # with V907 removed, to
                                                                            # prove the test is real
# End-to-end: throwaway Postgres + the uat-profile jar behind the vite preview proxy
docker run -d --name uat-ql-pg -e POSTGRES_DB=hris -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres -p 55432:5432 postgres:16-alpine
SPRING_PROFILES_ACTIVE=uat SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/hris \
  APP_CORS_ALLOWED_ORIGINS=http://localhost:4174 java -jar target/glr-hr-backend-0.1.0.jar
```

## Tests / Build Results
- **Frontend lint**: pass — 0 errors (10 pre-existing `react-hooks/exhaustive-deps` warnings, none
  from this change).
- **Frontend tests**: pass — 91/91 across 19 files (3 new).
- **Frontend build**: pass — 150ms.
- **Backend `clean verify`**: pass — **381 tests, 0 failures, 0 errors, 0 skipped** (1 new). Docker
  was available, so the Testcontainers Flyway tests really ran and V907 was applied to a clean DB.
- **Negative control**: with `V907` deleted (and a `clean` build to avoid the stale
  `target/classes` copy that masked it on the first attempt), the new test fails with
  `persona ceo@uat.glr is not forced through a password change` — the test is not vacuous.
- **Tree-shaking check**: with `VITE_UAT_QUICK_LOGIN=false`, `Uat@2026` and every `@uat.glr` string
  are **absent** from `dist/assets/*.js`; with the flag on they are present. The password only ships
  in a UAT build.
- **End-to-end (real backend, uat profile, real Postgres)**: clicking **CEO** logged in as
  `ceo@uat.glr` (สมชาย บริหารกิจ, GLR-0001) and landed on the CEO dashboard with **no** forced
  password-change modal. All 9 personas then verified via `POST /api/auth/login`: HTTP 200 each,
  `mustChangePassword=false`, roles `ceo / hr / sales_manager / sales / import / employee /
  employee / employee / employee` — matching V900's documented expectations.

## Known Risks
1. **V907 overwrites tester-chosen passwords** for the 9 seed personas on the next uat deploy. This
   is intended and was confirmed, but it is worth announcing to testers: anyone who set a personal
   password on a `@uat.glr` account will find it reset to `Uat@2026`.
2. **Forced-change coverage is gone.** UAT-AUTH-01/02 must now be tested deliberately — flip one
   persona back by hand: `UPDATE hr.employee SET must_change_password = TRUE WHERE email =
   'employee@uat.glr';`. `ERP Documentation/UAT Deliverables/UAT_Accounts.md` still says every
   persona is forced to change on first login, and is now **stale** — see the next prompt.
3. **The shared password is in the UAT JS bundle.** Verified it cannot leak into a demo/prod build
   (tree-shaken when the flag is off), and it is already published in V900 / UAT_Accounts.md, so this
   is not a new disclosure — but the UAT site is public, so anyone can sign in as any persona. That
   is the accepted trade-off for a UAT convenience feature.
4. **`.env.production` must never merge to `main`**, and it now carries a second uat-only flag. The
   existing never-merge warning at the top of the file still covers this.
5. **`out-of-order: true`** is already set on the uat profile, so V907 applies cleanly to the hosted
   UAT DB even though it lands above V906. Verified on a fresh DB; the hosted DB was rebuilt on
   2026-07-15, so its history is clean and V907 will simply append.

## Next Prompt for the Next Agent
> On branch `feat/uat-quick-login` (worktree `.claude/worktrees/uat-quick-login`, off `origin/uat`),
> the UAT persona quick-login is implemented and fully verified but **not committed**. Do two things:
> (1) Update `ERP Documentation/UAT Deliverables/UAT_Accounts.md` — it still states that all 9
> personas are forced to change their password on first login, which `db/migration-uat/V907` now
> undoes; document the new quick-login buttons, keep `Uat@2026` as the shared password, and add the
> one-line UPDATE that re-arms a persona for the UAT-AUTH-01/02 forced-change test.
> (2) Commit as `feat: add UAT persona quick login` and open a PR **into `uat`** (never into `main` —
> `frontend/.env.production` is a uat-only overlay). Do not re-run the full verify unless you change
> code; results are recorded above.
