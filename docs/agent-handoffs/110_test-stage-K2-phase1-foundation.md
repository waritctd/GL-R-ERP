# Agent Handoff

## Task
Implement PHASE 1 (foundation only) of Stage K2 — Playwright browser e2e
against the MOCK frontend (`VITE_USE_MOCKS=true`). Phase 1 = harness +
login testids + auth helper + `auth.spec.js` + `rbac.spec.js` + a
non-gating CI workflow. Phase 2 (flow specs: deal-creation, PCR chain,
deposit/fulfilment close, commission, HR) is explicitly deferred to a
later run — see `docs/agent-handoffs/` spec at
`/private/tmp/.../scratchpad/stage-K2-e2e-spec.md` (full context; that spec
covers both phases, this branch only implements Phase 1 of it).

## Branch
`test/stage-K2-phase1-foundation`

## Base Commit
`678ce1943cb8f8fed593db811b1e0208abb4e333` (origin/main, PR #293 merged)

## Current Commit
`54755cf` (4 commits on top of base — see `git log` for the incremental
sequence: testids → harness+auth.spec → rbac.spec → CI workflow)

## Agent / Model Used
Claude Sonnet (implementation), per the repo's Sonnet-implements/Opus-reviews loop.

## Scope

### In Scope
- Playwright harness (`@playwright/test` devDep, `playwright.config.js`, `test:e2e` script).
- `data-testid` attributes on `LoginPage.jsx` only (attribute-only, no logic change).
- `e2e/helpers/auth.js`: `loginAs`/`loginWithCredentials`/`logout`/`switchRole`/`spaGoto`.
- `e2e/auth.spec.js`: all 7 seeded roles quick-login + land, logout, credential login (pass + fail).
- `e2e/rbac.spec.js`: role × guarded-route matrix, oracle = the app's own `canAccessPath`.
- `.github/workflows/e2e-ci.yml`: non-gating CI signal.

### Out of Scope (Phase 2, deferred)
- `deal-creation.spec.js`, `pcr-chain.spec.js`, `deposit-fulfilment-close.spec.js`,
  `commission.spec.js`, `hr.spec.js` — none of these were written.
- testids beyond `LoginPage.jsx` (TicketCreateModal, Deal*Panel, PricingRequestDetailPage,
  CommissionPage, TicketDetailPage) — none added; needed before Phase 2 specs can drive them.
- Any backend/`hrApi.js` change — none made.

## Files Changed
- `frontend/src/features/auth/LoginPage.jsx` — added `data-testid="login-email"`,
  `login-password`, `login-submit`, and `login-role-<role>` per quick-login button. Attribute-only.
- `frontend/package.json` / `package-lock.json` — added `@playwright/test` devDep,
  `"test:e2e": "playwright test"` script.
- `frontend/playwright.config.js` — new. `testDir: ./e2e`, `webServer` runs
  `npm run dev -- --port 5250 --strictPort` with `VITE_USE_MOCKS=true`,
  `reuseExistingServer: false`, `baseURL http://127.0.0.1:5250`. Dev-server only — `vite build`/
  `preview` throw with mocks (`src/api/index.js:7-11`, `import.meta.env.PROD && useMocks`).
- `frontend/e2e/helpers/auth.js` — new. `loginAs`, `loginWithCredentials`, `logout`, `switchRole`,
  `spaGoto` (pushState+popstate SPA navigation — a hard `page.goto` after the first load wipes
  `mockApi.js`'s in-memory `db`/`sessionUser`). Also `seededUser(role)` — builds the *real*
  per-persona `user` shape (role + actual employeeId presence from `demoData.js`, not a synthetic
  stand-in) for use as `canAccessPath`'s input.
- `frontend/e2e/auth.spec.js` — new. 10 tests: 7×role quick-login lands, logout returns to login,
  credential login success + wrong-password rejection.
- `frontend/e2e/rbac.spec.js` — new. 8 tests: 7×role render-vs-redirect matrix (20 guarded routes
  each) + 1 informational manifest-drift report.
- `.github/workflows/e2e-ci.yml` — new. Mirrors `frontend-ci.yml`'s Node setup, caches the
  Playwright chromium download, runs `npm run test:e2e`, uploads the HTML report. `frontend/**`
  path filter, PR + push. **Not** added to any required-checks list.
- `.gitignore` — added `frontend/playwright-report/`, `frontend/test-results/`,
  `frontend/blob-report/`, `frontend/playwright/.cache/`.

## Commands Run
```bash
git fetch origin
git checkout test/stage-K2-phase1-foundation   # already existed at origin/main tip, clean
cd frontend
npm ci
npm install --save-dev @playwright/test
npx playwright install --with-deps chromium
npm test                 # Vitest, after testid changes
npm run test:e2e         # Playwright, after each spec file added
npm run lint
unset VITE_USE_MOCKS && npm run build
```

## Test / Build Results
- **Vitest (`npm test`)**: 545/545 passing (63 files) — unchanged count before/after the testid
  addition; `src/app/permissions.test.js` (36 tests) already covers `canAccessPath` at the unit
  level, unaffected.
- **Playwright (`npm run test:e2e`)**: **18/18 passing** locally against the mock dev server on
  port 5250 — 10 in `auth.spec.js`, 8 in `rbac.spec.js` (7 role tests + 1 drift-report test).
  Runtime ~15-20s total. The `[WebServer] ... ECONNREFUSED /api/auth/login` lines in the log are
  expected noise — `mockApi.js`'s login also fires a fire-and-forget real-backend login attempt
  (for document downloads) that has nowhere to land in this suite; it's caught/ignored by the app
  and does not affect any assertion.
- **Lint (`npm run lint`)**: 0 errors, 1 pre-existing warning in `PayrollPage.jsx`
  (`react-hooks/exhaustive-deps`) unrelated to this branch, untouched file.
- **Build (`npm run build`, `VITE_USE_MOCKS` unset)**: succeeds — confirms the testid additions
  don't affect the non-mock production build path.
- Backend: not touched, not run (no backend changes in this branch).

### rbac.spec.js — oracle sanity check (pre-write verification)
Before writing the spec, ran the live `canAccessPath` against all 16 sanity-list routes × 7 roles
via `node -e` (see commit `bc29cb7`'s message for the exact command) and it reproduced the
owner-validated matrix from the task prompt **exactly** — zero mismatches, so no STOP condition
was hit.

### rbac.spec.js — drift vs `docs/ux-ui-audit/data/shoot-manifest.json`
16 `(role, path)` pairs drift between the manifest (a stale UX-audit-era baseline) and the live
`canAccessPath` oracle. Logged via `console.log`, not asserted (test still passes). Full list is in
the `npm run test:e2e` output; summary:
- **`/ceo-settings`**: manifest says allowed for hr/employee/sales/sales_manager/import/account;
  live oracle says `ceo`-only. Consistent with the task's owner-validated sanity matrix
  (`/ceo-settings`→ceo) — the manifest is simply stale here, not a live bug.
- **`/my-requests`**: manifest says denied for hr/sales_manager/ceo; live oracle says allowed
  (it's the `/profile` alias, gated on `!!employeeId`, which those 3 personas have). Manifest drift,
  not a bug.
- **`/ticket-overview`, `/tickets`**: manifest says allowed for import/account; live oracle says
  denied — this is the intentional `canViewDealPipeline` split (role-scoped-views program,
  memory: "role-shaped-views-program.md") that predates the manifest capture. Expected drift.
- **`/commissions`**: manifest says denied for account; live oracle says allowed — matches the
  later "keep /commissions nav parity for account" fix noted in memory. Expected drift.

None of this drift touches the owner-validated sanity matrix given in the task prompt, and none of
it was treated as a failure — it is exactly the kind of staleness the task asked to surface.

### Additional finding: `/catalog`'s guard is dead code
`app/permissions.js`'s `PATH_GUARDS` defines a guard for `/catalog` (`canViewCatalog`), but
`App.jsx` registers `<Route path="/catalog">` **outside** the `<RequireAccess>` wrapper (alongside
`/attendance`, both unguarded on purpose per that route's own comment for `/attendance` — but
`/catalog` looks like an oversight, not a documented intent, since `permissions.js` explicitly
defines a guard for it that never runs). Left `/catalog` out of `GUARDED_ROUTES` in `rbac.spec.js`
(nothing to assert render/redirect against) and documented the finding in the spec file's own
comments and in the commit message. **Not fixed** — out of this attribute-only branch's scope;
flagging for a follow-up decision (either wrap `/catalog` in `RequireAccess`, or delete the dead
guard from `PATH_GUARDS` if the omission is intentional).

## Authz Evidence
No authorization change in this task. `rbac.spec.js` is explicitly labeled
**FRONTEND-GATING ONLY** in its own header comment — it proves the client-side route guard
(`RequireAccess` → `canAccessPath`) behaves as `app/permissions.js` says it should, against a real
browser and the mock frontend. It does **not** verify backend authorization; that is Stage L
(already shipped, PR #292, `AttendanceScopeIntegrationTest` and friends via real Postgres). Do not
cite this spec as evidence any route or action is permission-safe against the real Spring services.

## Decisions Made
- **`seededUser(role)` uses each persona's real `employeeId` presence, not a synthetic nonzero.**
  The task prompt suggested `employeeId: <nonzero>` uniformly, but `demoData.js` seeds sales,
  import, and account with `employeeId: null`. Three routes (`/profile`, `/employee-requests`,
  `/leave`) are gated on `!!user.employeeId`; using a synthetic nonzero for those 3 roles would
  have made the oracle claim "allowed" while the real app (correctly) denies them, producing a
  false mismatch that isn't a real bug. Used the real per-persona shape instead — this is what
  "use the app's own guard as the oracle" means taken literally: same guard function, same real
  input the app actually holds. Confirmed this makes zero difference for every route in the
  owner-validated sanity matrix (none of those 16 routes reference `employeeId` at all).
- **SPA-only navigation (`pushState`+`popstate`), never `page.goto` after the first load.**
  `mockApi.js`'s `db` and `sessionUser` are plain in-memory JS module state with no
  localStorage/sessionStorage backing — a real browser navigation resets them and logs the session
  out. This is the same technique noted in prior UX-audit tooling (memory:
  `preview-launch-json-primary-worktree.md`).
- **`/overtime` and `/my-requests` excluded from `rbac.spec.js`'s primary `GUARDED_ROUTES` list**
  (still covered by the separate manifest-drift check). Both are `RequireAccess`-guarded `Navigate`
  aliases with guards identical to their canonical target (`/employee-requests`, `/profile`
  respectively) — testing them adds redirect-chain assertion complexity with no unique signal.
- **Port 5250** confirmed free and working; `npm run dev -- --port 5250 --strictPort` correctly
  overrides the script's baked-in `--port 5174` (Vite/cac takes the last occurrence of a repeated
  flag).

## Assumptions
- `VITE_ENABLE_SALES` is not set to `'false'` anywhere in this environment, so `SALES_ENABLED` is
  `true` under the Playwright dev server — all sales-gated routes in `App.jsx`'s route table are
  registered and testable.
- Dynamic-segment routes (`/employees/1`, `/tickets/1`, `/pricing-requests/1`,
  `/factory-purchase-orders/1`) don't need the underlying record to exist for a gating check:
  `RequireAccess` redirects on the pathname alone, before the guarded page component ever mounts
  or fetches data, so a missing record can't produce a false "denied" or "allowed" result.

## Known Risks
- **Playwright chromium binary is a local/session-scoped download** (`~/.cache/ms-playwright` on
  this machine, or the GitHub Actions runner in CI) — not committed, not guaranteed present on a
  fresh checkout without running `npx playwright install --with-deps chromium` first.
- **`e2e-ci.yml` has not yet run in real GitHub Actions** (only run locally in this session) — the
  Playwright-version cache-key step and the conditional install-deps-only branch are untested
  against the actual runner; worth a first real PR run to confirm before promoting this workflow
  to a required check.
- **`/catalog`'s dead guard** (see Files Changed above) is a pre-existing gap this branch surfaced
  but did not fix — someone should decide whether `/catalog` is meant to be gated and act on it.
- Phase 2 (flow specs) will need new testids on `TicketCreateModal`, the `Deal*Panel` components,
  `PricingRequestDetailPage`, `CommissionPage`, and `TicketDetailPage` before it can be written —
  none of those exist yet.

## Things Not Finished
- Phase 2 flow specs (`deal-creation.spec.js`, `pcr-chain.spec.js`,
  `deposit-fulfilment-close.spec.js`, `commission.spec.js`, `hr.spec.js`) — explicitly deferred to
  a later run per the task's Phase 1/Phase 2 split.
- No PR merge (explicitly out of scope — "do NOT merge").

## Recommended Next Agent
Opus review of this PR, then (once approved) a fresh Sonnet implementation session for Phase 2,
briefed with this handoff + the full spec at
`/private/tmp/claude-501/.../scratchpad/stage-K2-e2e-spec.md` §3-4 (testid instrumentation +
flow specs).

## Exact Next Prompt
```
Implement PHASE 2 of Stage K2 (Playwright browser e2e against the MOCK frontend) for GL-R-ERP —
the flow specs, on top of the foundation merged in
docs/agent-handoffs/110_test-stage-K2-phase1-foundation.md (branch test/stage-K2-phase1-foundation,
now merged to main — confirm with `git log --oneline main | grep stage-K2`).

Create a new branch `test/stage-K2-phase2-flows` off `origin/main`. Work in `frontend/`, commit
incrementally.

1. Add testids (attribute-only, no logic change) to: TicketCreateModal.jsx (reuse existing ids —
   #customer-select, #project-field, #item-0-brand/model/..., radiogroup aria-label="ช่องทางดีล";
   add only `ticket-create-submit` + `ticket-create-modal`), Deal{Stage,Quotation,Deposit,
   Fulfilment}Panel.jsx (a panel-root testid + each action button, e.g. `deal-deposit-confirm`,
   `deal-fulfilment-record-delivery`, `deal-fulfilment-complete`), PricingRequestDetailPage.jsx
   (pickup / propose-price / decision action testids), CommissionPage.jsx (manual-create form
   fields + `commission-approve`/`commission-reject`), TicketDetailPage.jsx (per-action testids:
   submit/confirm-deposit/confirm-final/close). Run `npm test` after — confirm no component test
   broke.

2. Write frontend/e2e/deal-creation.spec.js — sales: 6-section TicketCreateModal → DRAFT deal
   appears.

3. Write frontend/e2e/pcr-chain.spec.js — one session, role-switched (use e2e/helpers/auth.js's
   `switchRole`, never a hard reload): sales create deal + pricing request → import
   pickup/factory-quote/costing → ceo decision approve → sales quotation issue+accept.

4. Write frontend/e2e/deposit-fulfilment-close.spec.js — deposit-paid → fulfilment (record/complete
   delivery) → three-party close → CLOSED_PAID. Start from a seeded deal id if one exists to
   shorten the flow.

5. Write frontend/e2e/commission.spec.js — manual commission create (sales_manager) → approve
   (ceo two-step) → view.

6. Write frontend/e2e/hr.spec.js — OT create→approve (manager→ceo); leave create→approve/reject
   (leave.approve/leave.reject ARE implemented in mockApi.js at ~3608/3623, despite an older
   runbook claiming otherwise); attendance/dashboard views render.

7. Documented mock gaps — assert the gap, don't fight it: payroll processing/exports
   (payroll.preview/process/exportFile/... all throw "ไม่รองรับในโหมดทดลองใช้งาน (mock mode)");
   forced-password-change is unreachable (no seeded persona has mustChangePassword:true);
   attachment/file-upload specs must not assert real downloaded bytes (mock fires fire-and-forget
   to a real backend that isn't there in CI).

Run `cd frontend && npm run test:e2e` (all specs, Phase 1 + Phase 2) and `npm run lint && npm test
&& npm run build`. Update docs/agent-handoffs/ with a new handoff file listing pass counts per
spec, any environment-flaky specs, and the exact next prompt (promoting e2e-ci.yml to a required
check, once stable). Push, open a PR to main, do NOT merge.
```
