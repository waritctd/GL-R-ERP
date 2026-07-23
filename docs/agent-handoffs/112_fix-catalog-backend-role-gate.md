# Agent Handoff

## Task
Add a real backend role gate on the catalog browsing endpoints (`GET /api/catalog`,
`GET /api/catalog/prices`). `routes.js`'s `canViewCatalog` comment and PR #296's follow-up note both
recorded that the frontend route guard (`/catalog`, fixed in `fix/catalog-route-guard`, #296) was
enforced client-side only — the API itself had no role check at all, so any authenticated user
(hr/employee/warehouse/qc included) could read the full supplier price catalog with a direct call.
Stage L (release runbook) follow-up item.

## Branch
`fix/catalog-backend-role-gate`

## Base Commit
`eff039e7ea9d1f31fb3c9a1925d152ea8f011126` (origin/main tip at branch creation, merge of PR #296)

## Current Commit
See `git log -1` after the commit step (this handoff is written just before committing).

## Agent / Model Used
Claude Sonnet

## Scope

### In Scope
- `backend/src/main/java/th/co/glr/hr/catalog/CatalogController.java` — new
  `requireCatalogViewer` gate on `search`/`searchPrices`.
- Test updates/additions proving the gate against the real service (see below).
- Stale-comment cleanup in `routes.js`/`App.jsx`/`mockApi.js` that documented the gap as
  intentional/unresolved — now points at the fix.

### Out of Scope
- No change to `requireCatalogEditor` (ceo/import product CRUD) — unchanged.
- No change to `/api/price-import/*` (already ceo/import-gated).
- No change to sales/pricing business logic.

## Files Changed
- `backend/src/main/java/th/co/glr/hr/catalog/CatalogController.java` — added
  `requireCatalogViewer(session)` (role gate: `sales`, `import`, `ceo`, `account`,
  `sales_manager` — exactly `canViewCatalog` from `routes.js`), called from `search` and
  `searchPrices` in place of the bare `sessions.requireUser(session)`.
- `backend/src/test/java/th/co/glr/hr/catalog/CatalogControllerTest.java` — **updated**. The two
  existing tests (`searchIsNotForbiddenForAnyAuthenticatedRole`,
  `searchPricesIsNotForbiddenForAnyAuthenticatedRole`) explicitly asserted the *old, insecure*
  behavior (employee got 2xx). Replaced with 4 tests: allowed-role positive controls
  (`searchIsAllowedForCatalogViewerRoles`, `searchPricesIsAllowedForCatalogViewerRoles`) and
  denied-role negative controls (`searchIsForbiddenForNonCatalogViewerRoles`,
  `searchPricesIsForbiddenForNonCatalogViewerRoles`, asserting 403 for
  employee/hr/warehouse/qc). Write-endpoint tests (add/update/delete) untouched.
- `backend/src/test/java/th/co/glr/hr/catalog/CatalogViewerScopeIntegrationTest.java` — **new**.
  Real-Postgres integration test wiring the real `CatalogController` + real `CatalogRepository`
  (jdbc) + real `SessionContext` (only `PriceImportService`, unused by the read paths, is mocked).
  4 tests: allowed roles get real rows back from a real seeded `price_catalog.product_prices` row
  (via the existing `insertCatalogProduct` fixture helper) and from the migration-seeded
  `sales.catalog` table; denied roles (`hr`, `employee`, `warehouse`, `qc`) get `403 FORBIDDEN`
  before any data is returned, for both `search` and `searchPrices`.
- `frontend/src/api/routes.js` — `canViewCatalog` comment updated: no longer says "no backend role
  check yet."
- `frontend/src/App.jsx` — the `/catalog` route comment updated the same way.
- `frontend/src/api/mockApi.js` — `catalog.search`/`catalog.prices` now call
  `hasRole('sales', 'import', 'ceo', 'account', 'sales_manager')` instead of bare
  `requireSession()`, mirroring the new backend gate exactly (same role set already used by
  `requireTicketViewer` in this file, for consistency). Stale comment removed ("catalog browsing
  is open to any logged-in user").

## Commands Run
```bash
git checkout -b fix/catalog-backend-role-gate main

cd backend
./mvnw -q -B test-compile

# Local Postgres 15 (Homebrew), throwaway DB — Docker not used/verified this session.
psql -h localhost -p 5432 -U "$USER" -d postgres -c 'CREATE DATABASE "glr_catalog_authz_1784839997";'
export TEST_DB_URL="jdbc:postgresql://localhost:5432/glr_catalog_authz_1784839997"
export TEST_DB_USERNAME="$USER"
export TEST_DB_PASSWORD=""

./mvnw -B -Dtest.fork.count=1 \
  -Dtest='CatalogViewerScopeIntegrationTest,CatalogControllerTest,PriceImportControllerTest' \
  -DfailIfNoTests=true test
# → ran three times total: once pre-mutation (green), once with the gate neutralized
#   (mutation-check, 4 tests red), once post-revert (green again).

./mvnw -B -Dtest.fork.count=1 clean verify   # full backend suite, post-revert

psql -h localhost -p 5432 -U "$USER" -d postgres -c 'DROP DATABASE "glr_catalog_authz_1784839997";'

cd ../frontend
npm ci        # node_modules was missing in this worktree
npm run lint
npm test
npm run build
```

## Test / Build Results
- Backend compile (`test-compile`): pass.
- Backend targeted tests (catalog package + PriceImportControllerTest): **27/27 pass**, real
  Postgres, integration tests **ran** (not skipped).
- Backend full suite (`clean verify`, real Postgres): **1196/1196 pass, 0 failures, 0 errors, 2
  skipped** (pre-existing skips, unrelated to this branch — not introduced here). BUILD SUCCESS.
- Frontend lint: pass (1 pre-existing unrelated warning in `PayrollPage.jsx`, not touched by this
  branch).
- Frontend tests: **545/545 pass**.
- Frontend build: pass.

## Authz Evidence
Verified against the real Java service — role gate added, with a real mutation-check actually
executed (product code edited, targeted test run, result observed, then reverted):

**Mutation-check.** Temporarily replaced `CatalogController.requireCatalogViewer`'s body with a
bare `sessions.requireUser(session)` (the pre-fix "any authenticated user" gate — i.e. deleted the
`requireAnyRole` call). Ran `CatalogViewerScopeIntegrationTest` + `CatalogControllerTest`: exactly
4 tests went red — `CatalogViewerScopeIntegrationTest.deniedRole_cannotSearch`,
`.deniedRole_cannotSearchPrices`, and `CatalogControllerTest.searchIsForbiddenForNonCatalogViewerRoles`,
`.searchPricesIsForbiddenForNonCatalogViewerRoles` (each: expected 403/thrown exception, got
200/no exception) — while the allowed-role positive-control tests in both classes stayed green.
Reverted `requireCatalogViewer` to the intended gate; `git diff --stat -- backend/src/main` was
empty afterwards (confirmed again just before writing this handoff).

This proves the role gate is load-bearing at both layers CLAUDE.md asks for:
1. **Unit-level decision** — `CatalogControllerTest` (Mockito-mocked repository, MockMvc through
   the real controller): the right role set is accepted/rejected.
2. **Real-service enforcement** — `CatalogViewerScopeIntegrationTest` (real Postgres, real
   `CatalogRepository`, real `SessionContext`, real controller method call): allowed roles reach
   real seeded rows; denied roles are stopped by `requireAnyRole` before any SQL runs, proven
   wrong-way-round (can `hr`/`employee`/`warehouse`/`qc` reach catalog data → no).

Note: this is a pure role gate, not a row-scope predicate — every allowed role sees the identical
catalog (there is no per-user filtering to prove, unlike e.g. `CommissionListScopeIntegrationTest`'s
`sales_rep_id` predicate). The integration test's job here is proving the gate survives from the
controller method into the real repository call, not proving a SQL `WHERE` clause.

## Decisions Made
- Matched the frontend's existing `canViewCatalog` set exactly (`sales`, `import`, `ceo`,
  `account`, `sales_manager`) rather than inventing a new set — this was already the documented
  intended audience in `routes.js`, just unenforced server-side.
- Did not gate `/api/price-import/factories` (used by `CatalogSearchPage`'s factory dropdown) —
  it was already `ceo`/`import`-only before this branch, a pre-existing minor inconsistency (a
  `sales`/`account`/`sales_manager` catalog viewer gets an empty factory dropdown, silently
  swallowed by the frontend's `.catch(() => {})`). Out of scope for this authz-gap fix; flagging
  for a possible follow-up rather than expanding this branch's diff.
- Reused the existing `AbstractPostgresIntegrationTest.insertCatalogProduct` fixture helper (built
  for the pricing-request catalog-gate tests) instead of hand-rolling factory/version/product-price
  inserts.
- Named the new integration test class `CatalogViewerScopeIntegrationTest` (not
  `...RoleGateIntegrationTest`) to match the repo's established `*ScopeIntegrationTest` naming
  convention for this category of authz test, even though this particular case is a role gate
  rather than a row-scope.

## Assumptions
- None beyond what's stated in the task — the target role set was already unambiguous from
  `routes.js`'s `canViewCatalog`.

## Known Risks
- None identified. This narrows access (a security fix in the safe direction), and both the unit
  and integration test suites plus the full backend/frontend verify are green.
- The frontend already handles a 403 gracefully: `CatalogSearchPage.doSearch`'s `catch` block sets
  `items` to `[]` and shows the existing "ไม่พบสินค้าที่ตรงกัน" (no results) empty state — no crash,
  no unhandled promise rejection. Confirmed by reading `CatalogSearchPage.jsx`, not by manual
  browser testing (no role is normally denied `/catalog` in the first place, since the frontend
  route guard already matches this exact set — so this 403 path is effectively defense-in-depth for
  a direct API call, not something a normal user would hit in the UI).

## Things Not Finished
- `/api/price-import/factories` gap noted above (pre-existing, out of scope) — not filed as a
  separate issue, just recorded here for whoever picks up the follow-up.

## Recommended Next Agent
Claude Opus review (per CLAUDE.md's Sonnet-implements/Opus-reviews loop), then owner say-so to
merge. Confirm: (1) diff matches `canViewCatalog` exactly, (2) the mutation-check record above is
consistent with what a re-run would show, (3) no other catalog-adjacent endpoint was missed.

## Exact Next Prompt
```
Review PR <PR_URL> (branch fix/catalog-backend-role-gate) — adds a real backend role gate on
GET /api/catalog(/prices), closing the gap where the API had no role check while the frontend
route guard (#296) enforced canViewCatalog client-side only. Confirm: (1) CatalogController's new
requireCatalogViewer matches routes.js's canViewCatalog exactly (sales/import/ceo/account/
sales_manager), (2) the mutation-check record in CatalogViewerScopeIntegrationTest.java is
consistent with a re-run (temporarily strip requireAnyRole from requireCatalogViewer, confirm
exactly 4 tests go red, revert), (3) mockApi.js/routes.js/App.jsx comments are now accurate. If
approved, merge to main; do not merge without explicit owner/Opus say-so per CLAUDE.md branch
discipline.
```
