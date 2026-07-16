# Agent Handoff

## Task
Implement issue #205 — lock down price import to roles `ceo`+`import` across three layers (nav,
URL route, backend) that today disagree with each other, plus restrict catalog product writes
(add/update/delete) to `ceo`/`import` while widening catalog *browsing* to any logged-in user.
Followed the approved plan at `~/.claude/plans/take-a-look-at-dreamy-honey.md` exactly; no
deviations were needed — every reused piece (`SessionContext.requireAnyRole`, `hasPermission` +
`PATH_GUARDS` + `RequireAccess`, the mock's `hasRole(...)`) matched the plan's description on
inspection.

## Branch
`claude/item-205-price-import-lockdown` (worktree `.claude/worktrees/item-201-885dff`)

## Base Commit
`217d19f82ebd0870793b4a943cd3390ce86540bf` (main, includes #201/#207 mockApi-fidelity fix)

## Current Commit
Not committed — changes left in the working tree for review, as instructed.

## Agent / Model Used
Claude Sonnet 5 (implementation).

## Scope

### In Scope
- Backend: gate every `PriceImportController` endpoint (10 endpoints, reads included) and the
  three `CatalogController` mutations to `ceo`/`import`.
- New standalone MockMvc tests for both controllers.
- Frontend: narrow `canManagePriceImport`, add `canManageCatalogProducts`, widen catalog nav to
  `SALES_ENABLED` only, add a `/price-import` `PATH_GUARDS` entry, move the `/price-import` route
  inside `RequireAccess`, gate the catalog edit button + `ProductFormModal` on the new permission.
- `permissions.test.js` coverage for the new fixtures/paths/keys.
- Mock (`mockApi.js`): mirror the same `ceo`/`import` gate on `priceImport.*` and the three catalog
  mutations, per the #201 contract ("mock and backend move together").

### Out of Scope (deliberately untouched)
- `PriceImportPage.jsx` — no component-level button gating added. The route is now locked to
  ceo/import via `RequireAccess`, and every action already hits a backend-gated endpoint; adding
  button-hiding would mean threading `user` into a page that doesn't currently receive it, for
  pure defense-in-depth with no reachable exploit path. Noted per the plan's explicit call-out.
- The other ~18 mock authz divergences tracked by #206 — not this branch.
- Any business logic (pricing math, commit's incremental-merge semantics) — untouched.
- `admin` role removal is incidental: `canManagePriceImport`/`canManageCatalogProducts` simply
  don't list it, matching `ApplicationRoles.java` (`ceo, sales_manager, hr, sales, import, employee`
  — `admin` was never a real backend role).

## Files Changed
- `backend/src/main/java/th/co/glr/hr/catalog/importer/PriceImportController.java`
  - Added private `requireImporter(HttpSession)` (`sessions.requireUser` + `sessions.requireAnyRole(user, "ceo", "import")`).
  - Replaced all 10 `sessions.requireUser(session)` call sites (factories, createFactory,
    uploadAndCommit, upload, profile GET, updateProfile, versions, validate, stagingReport, commit)
    with `requireImporter(session)`.
- `backend/src/main/java/th/co/glr/hr/catalog/CatalogController.java`
  - Added private `requireCatalogEditor(HttpSession)` (same pattern).
  - Gated only `addProduct`, `updateProduct`, `deleteProduct`. `search`/`searchPrices` untouched
    (still `sessions.requireUser(session)` only — browsable by any authenticated user).
- `backend/src/test/java/th/co/glr/hr/catalog/importer/PriceImportControllerTest.java` (new)
  - Standalone MockMvc (`standaloneSetup` + `ApiExceptionHandler`), mirroring
    `DepositNoticeControllerTest.java`'s pattern. 9 tests: employee/sales 403 on `factories` (GET)
    and `commit` (POST); ceo/import 2xx on both; unauthenticated 401.
- `backend/src/test/java/th/co/glr/hr/catalog/CatalogControllerTest.java` (new)
  - Same pattern. 12 tests: `search`/`searchPrices` succeed for employee **and** sales (proving
    reads stay open); employee/sales 403 on add/update/delete; ceo/import 2xx on add/update/delete.
- `frontend/src/api/routes.js`
  - `canManagePriceImport`: `['ceo','import','admin','sales','sales_manager']` → `['ceo','import']`.
  - Added `canManageCatalogProducts: ['ceo','import']`.
- `frontend/src/app/permissions.js`
  - Added `PATH_GUARDS` entry: `{ test: (p) => p === '/price-import', can: (u) => hasPermission(u.role, 'canManagePriceImport') }`.
- `frontend/src/app/permissions.test.js`
  - Added `ceo`/`import` fixtures to the `canAccessPath` describe block.
  - Added `hasPermission` cases for `canManagePriceImport` (now denies sales/sales_manager/admin)
    and `canManageCatalogProducts` (denies sales/employee).
  - Added `canAccessPath('/price-import', …)` cases: ceo/import → true, sales/employee/hr → false.
- `frontend/src/components/layout/AppShell.jsx`
  - Catalog nav item (line 27): `hasPermission(user.role, 'canViewTickets') && SALES_ENABLED` →
    `SALES_ENABLED` only — visible to any logged-in user when sales is on.
- `frontend/src/App.jsx`
  - Moved the `/price-import` route from the open `SALES_ENABLED &&` block (previously outside
    any guard, alongside `/ceo-settings`/`/catalog`) to inside the `<Route element={<RequireAccess
    user={user} />}>` wrapper's `SALES_ENABLED` fragment (alongside `/tickets`, `/commissions`),
    keeping it `SALES_ENABLED`-conditional. `/catalog` stays outside the wrapper (open, unchanged).
- `frontend/src/features/catalog/CatalogSearchPage.jsx`
  - Imported `hasPermission`; component now takes `{ user, showToast }` (was `{ showToast }` only —
    `user` was already passed by `App.jsx` but silently ignored).
  - `const canManage = hasPermission(user.role, 'canManageCatalogProducts')`.
  - `ProductCard` (mobile) takes a new `canManage` prop; its edit button is now conditional.
  - Desktop table's edit button (in the last `<td>`) is now conditional on `canManage`; the column
    itself (header + cell) is left in place for non-managers (empty cell), matching the plan's
    "hide the button" scope rather than reflowing the table.
  - `ProductFormModal` render gated on `canManage && editingProduct` (was `editingProduct` only).
- `frontend/src/api/mockApi.js`
  - `priceImport.*` (all 10 methods: factories, createFactory, versions, uploadAndCommit, upload,
    validate, staging, commit, getProfile, updateProfile): `requireSession()` → `hasRole('ceo', 'import')`.
    Updated the two explanatory comments that previously said "no role gate today, mirrors the
    backend as-is" to instead point at `requireImporter(session)`.
  - `catalog.addProduct/updateProduct/deleteProduct`: `requireSession()` → `hasRole('ceo', 'import')`.
    `catalog.search`/`catalog.prices` untouched (`requireSession()` only). Updated the block comment
    that previously deferred this to "a follow-up branch (see #201)" — this is that branch.
- `docs/agent-handoffs/49_price-import-lockdown.md` (new) — this file.

## Commands Run
```bash
cd backend && ./mvnw -B -q -Dtest=PriceImportControllerTest,CatalogControllerTest test
cd backend && ./mvnw -B clean verify
cd backend && ./mvnw -B -q clean test   # full-suite re-run after stash/pop sanity check
git stash -u && cd backend && ./mvnw -B -q -Dtest=LeaveServiceTest test && cd .. && git stash pop
cd frontend && npm run lint
cd frontend && npm test
cd frontend && npm run build
```

## Test / Build Results
All observed directly, not assumed.

- **New backend tests — the load-bearing assertion:**
  - `PriceImportControllerTest`: **9/9 pass.** `employee`/`sales` → 403 on `GET /factories` and
    `POST /commit/1`; `ceo`/`import` → 2xx on both; no session → 401.
  - `CatalogControllerTest`: **12/12 pass.** `employee`/`sales` → 403 on add/update/delete;
    `ceo`/`import` → 2xx on add/update/delete; **`search`/`searchPrices` succeed for `employee`
    and `sales`** (proving catalog reads stay open, per the plan's explicit requirement).
- **Full backend suite** (`./mvnw -B clean verify`, then re-confirmed with `clean test`):
  **378 tests, 1 failure + 1 error — both in `LeaveServiceTest`, pre-existing and unrelated.**
  Verified by `git stash -u` (removing all of this branch's changes) and re-running just
  `LeaveServiceTest` against unmodified `main`: **identical failure** (`expected: 2.00 but was: 1`
  in `submitAutoApprovesWhenQuotaAndAdvanceNoticeAreSatisfied`; `ApiException: Leave request not
  found` in `submissionAutoRejectsWhenQuotaIsInsufficient`). Both tests compute against
  `LocalDate.now(BUSINESS_ZONE)` — date-dependent, not caused by this branch. Stash was popped
  cleanly afterward; all 11 files of this branch's changes confirmed still present.
  - Integration tests **ran** (not skipped) — this environment has a reachable Postgres and Flyway
    migrated schema `hr` to v47 successfully during the run (`OvertimeRepositoryIntegrationTest`
    passed, for example). No `TEST_DB_URL` env var was set explicitly; the default datasource
    config resolved to a live DB in this environment.
- **Frontend lint:** pass — exit 0, 0 errors, 10 warnings, all pre-existing
  `react-hooks/exhaustive-deps` warnings in files this branch does not touch.
- **Frontend tests:** pass — **19 files, 94 tests** (was 91 before this branch;
  `permissions.test.js` grew from 15 to 22 tests), 0 failures, 3.13s.
- **Frontend build:** pass — `✓ built in 139ms`.
- **Typecheck:** not run and not claimed — plain JS project, no `typecheck` script (per CLAUDE.md).
- **Browser/mock-mode drive-through (plan step 3):** **not performed.** Per repo memory
  (`preview-launch-json-primary-worktree.md`), `preview_start` reads the *primary* repo's tracked
  `.claude/launch.json`, not this worktree's — pointing a temp config there would mean editing a
  file outside this worktree, which the task scoped me to. The backend MockMvc tests plus the
  `permissions.test.js` path-guard tests cover the same three assertions the browser pass would
  (nav hidden, URL redirect, catalog open+edit-hidden) at the unit level; a reviewer wanting the
  visual confirmation should point a temp `frontend-mock` launch config at this worktree path and
  log in as `sales@glr.co.th` / `import@glr.co.th` per the plan's verification step 3.

## Decisions Made
- **Read-endpoints in `PriceImportController` are gated too** (`factories`, `versions`, `profile`
  GET, `staging`), not just mutations — per the plan and issue: "the whole tool is ceo/import-only,
  reads included (a sales user shouldn't even list factories)." This differs from
  `CatalogController`, where only the three mutations are gated and `search`/`searchPrices` stay
  open — that asymmetry is the point of the issue (price-import tool vs. browsable catalog).
- **Desktop table's edit `<td>` column is kept, its button hidden** rather than removing the column
  for non-managers. Matches the plan's literal scope ("conditionally render the edit button") and
  keeps the diff to the button, not a table-reflow.
- **Comments in `mockApi.js` updated, not just the gate.** Both `priceImport` and `catalog` had
  explanatory comments explicitly saying "this mirrors the backend's *lack* of a role gate today,
  do not tighten here, see #201/follow-up branch." Since this branch *is* that follow-up, leaving
  the stale comments would mislead the next reader into thinking the gate change was an oversight.

## Assumptions
- `requireAnyRole` compares exact lowercase strings (confirmed by reading
  `SessionContext.java:21-26` and `UserPrincipal.role()` — roles are stored/compared lowercase
  throughout; `ApplicationRoles.normalize()` lowercases on the way in). No case-folding needed in
  the new tests' `UserPrincipal` construction.
- Mocking concrete classes (`CatalogRepository`, `PriceImportService`) with plain `Mockito.mock(...)`
  is the established pattern in this codebase (confirmed via `AttendanceControllerTest`,
  `CommissionServiceTest`, etc. — all mock concrete service/repository classes directly, same as
  `DepositNoticeControllerTest` mocks `DepositNoticeService`).

## Known Risks
1. **`PriceImportPage.jsx` has no component-level role check.** Acceptable per the plan: the route
   is locked (users who lack the role never reach the component — `RequireAccess` redirects to `/`
   before render), and every action the page can take is independently backend-gated. If a future
   change threads `user` into this page for another reason, consider adding the same
   `canManagePriceImport` check for defense-in-depth, but it is not required by this issue.
2. **`LeaveServiceTest` has 2 pre-existing failures unrelated to this branch** (date-dependent,
   confirmed present on unmodified `main` via `git stash`). Not fixed here — out of scope per
   CLAUDE.md (this branch is auth/permissions-only; leave business logic is untouched and a fix
   there would violate "don't change business logic ... unless explicitly requested").
3. **The empty edit-column `<td>` on the desktop catalog table** is visually a blank cell for
   non-managers rather than a removed column. Minor visual roughness, not a security issue (no
   interactive element renders); flagged here rather than silently reflowing a table beyond this
   issue's scope.

## Things Not Finished
- Browser/mock-mode visual verification (see Test Results for why, and how a reviewer can do it).
- Nothing committed or pushed, per instructions.

## Recommended Next Agent
Review (`code-review` or `security-review` skill), focused on the backend gate correctness and the
App.jsx route-nesting change, then merge via PR.

## Exact Next Prompt
```
Review the uncommitted working-tree changes on branch `claude/item-205-price-import-lockdown`
(worktree .claude/worktrees/item-201-885dff) against docs/agent-handoffs/49_price-import-lockdown.md
and the plan at ~/.claude/plans/take-a-look-at-dreamy-honey.md.

Focus your review on:
1. Backend: confirm every PriceImportController endpoint (10 total) routes through
   requireImporter(session), and that CatalogController gates exactly addProduct/updateProduct/
   deleteProduct while search/searchPrices stay open. Re-run
   `cd backend && ./mvnw -B -Dtest=PriceImportControllerTest,CatalogControllerTest test` and confirm
   403/2xx assertions hold.
2. Frontend: confirm the /price-import <Route> now sits inside the <RequireAccess user={user}/>
   wrapper in App.jsx (not the old unguarded SALES_ENABLED block), and that /catalog is still
   outside it (open).
3. mockApi.js: confirm priceImport.* and the three catalog mutations use hasRole('ceo','import'),
   and that catalog.search/prices are untouched (requireSession() only) — a mock stricter than
   production on reads would silently break the "catalog is browsable by anyone" requirement.
4. Drive it live in mock mode if you have a browser preview available: log in as sales@glr.co.th,
   confirm the price-import nav item is gone and typing /price-import redirects to /; confirm
   /catalog is reachable, browsable, and its edit button is hidden. Log in as import@glr.co.th or
   ceo@glr.co.th and confirm both work.
5. Note LeaveServiceTest's 2 pre-existing failures (confirmed unrelated, present on unmodified main)
   — do not let them block this PR, but flag if a separate fix issue should be filed.

Do not commit or push without the requester's say-so.
```
