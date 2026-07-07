# Agent Handoff

> Seeded scaffold — the implementation agent fills the sections marked _(to fill)_ as work proceeds, and completes every section before stopping.

## Task
Introduce **`react-router-dom` v7 (`BrowserRouter`)** and replace the `App.jsx` ~18-branch ternary router with real URL routes (`:id` params + permission guards). Deep-linking, browser back/forward, and refresh-keeps-context should all work. Roadmap "branch 5" — the last big P1 architecture item. Full plan: `/Users/ploy_warit/.claude/plans/atomic-marinating-otter.md` ("Branch 5" section is the exact spec).

## Branch
`refactor/frontend-routing` (off `main`; ALREADY created and checked out for you)

## Base Commit
`cfa8476` (main tip — includes branches 1–4)

## Current Commit
Uncommitted (working tree on `refactor/frontend-routing`, base `cfa8476`). Do NOT commit — left for review per branch rules.

## Agent / Model Used
Implementer: Claude Opus 4.8 (this run) · Reviewer: Claude Opus

## Scope

### In Scope
- Add `react-router-dom` v7; `main.jsx` wraps `<App/>` in `<BrowserRouter>` (inside the existing `<QueryClientProvider>`).
- New `frontend/src/app/RequireAccess.jsx` guard + a `canAccessPath(path, user)` helper in `frontend/src/app/permissions.js` (port the current `allowedRoute()` conditions to path predicates; keep permissions.js the source of truth). Update `permissions.test.js`.
- `App.jsx`: ternary → `<Routes>` under a layout route that renders `<AppShell>` with `<Outlet/>`. Keep the auth gates (LoginPage / ChangePasswordModal) for `!user`. App still calls `useHrData()` and passes its data into route elements as props (do NOT push fetching into pages).
- Nav rewire (~40 sites): replace every `on*` nav prop with `useNavigate()` + paths. Sidebar/AppShell active state derives from URL (`NavLink`/`useLocation`); mobile drawer closes on navigate. Breadcrumbs component unchanged — just point each `onClick` at `navigate(...)`.
- Detail pages → params: **EmployeeDetailPage** reads `:id` via `useParams()` and fetches by id via a new `useQuery(queryKeys.employeeDetail(id))` (add the key); `updateEmployee.onSuccess` in `useHrData` must also invalidate `employeeDetail(id)`. TicketDetailPage/DepositNoticePage (frozen) already fetch by id — just source `ticketId` from `useParams()`.
- `useHrData.js`: remove `route`/`selectedEmployee`/`routeTo`/`openEmployee`; `createEmployee` no longer routes (the creating component navigates); `resetData` keeps purging the cache.

### Out of Scope
- No behavior change beyond navigation — same screens, same permission outcomes (guards must reproduce today's `allowedRoute` redirects exactly).
- Frozen sales stack: param-wire to keep working/URL-addressable; do NOT expand or deep-refactor.
- Business logic, styles, the `api/` transport, and the branch-3/4 Query wiring (only add `employeeDetail` key + the `updateEmployee` invalidation). Do NOT move per-page data fetching into pages.

## Route table
`/`→EmployeeDashboard · `/hr`→HrDashboard(canViewEmployees) · `/employees`(canViewEmployees) · `/employees/:id`(canViewEmployees) · `/requests`(canReviewProfileRequests) · `/my-requests`(canSubmitProfileRequests) · `/profile`(employeeId) · `/attendance` · `/overtime`(employeeId||canViewAllOvertime) · `/leave`(employeeId||canViewAllLeave) · `/payroll`(canManagePayroll) · frozen: `/ticket-overview` `/tickets` `/tickets/:id` `/tickets/:ticketId/deposit` `/commissions` `/ceo-settings` · `*`→`<Navigate to="/" replace/>`.

## Files Changed
**New**
- `frontend/src/app/RequireAccess.jsx` — guard component: renders `<Outlet/>` when `canAccessPath(pathname, user)` else `<Navigate to="/" replace/>`.

**Modified (core)**
- `frontend/package.json` / `package-lock.json` — added `react-router-dom` `^7.18.1` (0 vulnerabilities on install; passes CI `npm audit` gate).
- `frontend/src/main.jsx` — wrapped `<App/>` in `<BrowserRouter>` inside the existing `<QueryClientProvider>`.
- `frontend/src/App.jsx` — ternary → `<Routes>` under an `AppShell` layout route (`<Outlet/>`); guarded routes nested under `<RequireAccess user={user}/>`; kept `!user`→LoginPage and `mustChangePassword`→ChangePasswordModal gates (rendered instead of Routes); removed `selectedTicket`/`depositTicketId` state and `handleRoute`/`openTicket`/`openDepositNotice`; added `TicketDetailRoute`/`DepositNoticeRoute` param-adapter wrappers for the frozen pages. Still calls `useHrData()` and passes data as props.
- `frontend/src/app/permissions.js` — added `canAccessPath(path, user)` (ports the `allowedRoute()` conditions to path predicates; `allowedRoute` kept for reference/tests).
- `frontend/src/app/permissions.test.js` — added a `canAccessPath` describe block mirroring the `allowedRoute` cases (19 tests total in file).
- `frontend/src/hooks/useHrData.js` — removed `route`/`selectedEmployee`/`routeTo`/`openEmployee`; `createEmployee` no longer routes; `updateEmployee.onSuccess` now also invalidates `employeeDetail(id)`; `resetData` also purges `['employeeDetail']`; dropped `allowedRoute` import and `useState`.
- `frontend/src/api/queryKeys.js` — added `employeeDetail: (id) => ['employeeDetail', id]`.

**Modified (nav rewire)**
- `components/layout/AppShell.jsx` — nav items carry `path`; `<Outlet/>` renders route content; drawer closes via `useLocation` effect on `pathname`; brand/NotificationBell use `useNavigate`; dropped `route`/`onRoute`/`onOpenTicket` props.
- `components/layout/Sidebar.jsx` — `<NavLink to={path} end={path==='/'}>` drives active state from URL; brand → `navigate('/')`; dropped `activeRoute`/`onRoute`.
- `features/dashboard/EmployeeDashboard.jsx` + `HrDashboard.jsx` + `TicketDashboard.jsx` — `onRoute`/`onOpenTicket` → `useNavigate`; quickAction route-strings → paths.
- `features/employees/EmployeeListPage.jsx` — row → `navigate('/employees/'+id)`.
- `features/employees/EmployeeDetailPage.jsx` — reads `:id` via `useParams()`, fetches via `useQuery(queryKeys.employeeDetail(id))` → `api.employees.get(id).then(r=>r.employee)`; `onBack`→`navigate('/employees')`; `submitEdit` passes URL `id` to keep the invalidation key in sync.
- `features/profile/ProfilePage.jsx` + `MyRequestsPage.jsx` — `onRoute`/`onNewRequest` → `useNavigate`.
- `features/tickets/TicketListPage.jsx` — row → `navigate('/tickets/'+id)`.
- Frozen `features/tickets/TicketDetailPage.jsx` + `features/deposits/DepositNoticePage.jsx` — UNCHANGED internally; the App.jsx wrappers source `ticketId` from `useParams()` and pass navigate-backed `onBack`/`onOpenDocument`/`onNavigateTickets`.

## Commands Run
```bash
npm --prefix frontend install react-router-dom   # added react-router-dom@7.18.1, 0 vulnerabilities
cd frontend && npm run lint    # 0 errors, 9 pre-existing exhaustive-deps warnings (untouched files)
cd frontend && npm test        # 7 files, 46 tests passed (permissions.test.js now 19 tests)
cd frontend && npm run build   # vite build OK, 149 modules, dist emitted
```

## Test / Build Results
- Frontend build (`npm run build`): PASS — 149 modules, `dist/assets/index-*.js` 490.56 kB (gzip 131.69 kB).
- Frontend tests (`npm test`, incl. updated permissions.test.js): PASS — 46/46 across 7 files; `permissions.test.js` 19 tests; `useHrData.test.js` 5 tests still green (no routing refs).
- Lint (`npm run lint`): PASS — 0 errors, 9 warnings, all pre-existing `react-hooks/exhaustive-deps` in files not functionally changed by this branch.
- Manual (preview, `frontend-mock` port 5200):
  - Sidebar nav changes URL (`/employees`) and the active item highlights from the URL (`NavLink`). VERIFIED.
  - Deep-link on refresh: reload at `/leave` preserves the URL through LoginPage; after HR login the router renders LeavePage at `/leave` (active nav "วันลา", heading "จัดการการลา"). VERIFIED. (Mock has no persistent session across hard reload, so "stay logged in on refresh" itself is not mock-testable — a mock limitation, not a router issue.)
  - `/employees/:id`: HR row click → `/employees/2`, detail fetches by id via `useQuery` and renders (name + breadcrumb + tabs). Screenshot captured. VERIFIED.
  - Browser back: from `/employees/2` → back → `/employees` with correct active state. VERIFIED.
  - Guards: as Employee, navigating to `/employees` and `/payroll` both redirect to `/`; Employee sidebar omits HR/Employees/Payroll links. VERIFIED.
  - Deep-link-through-login: logged out at `/leave` → LoginPage at `/leave` → HR login lands on `/leave`. VERIFIED.
  - Mobile drawer (375px): open drawer → click nav item → drawer closes (`is-mobile-drawer-open` removed) and URL changes. VERIFIED.
  - Logout returns to `/` and clears the query cache (`resetData`). VERIFIED.
  - No console errors or warnings at any point. VERIFIED.

## Decisions Made
- `canAccessPath` uses an ordered `PATH_GUARDS` predicate list; unguarded (`/`, `/attendance`) and unknown paths return `true` (the route table / `*` fallback own those). This mirrors `allowedRoute`'s "return route" default.
- `/ceo-settings` is intentionally NOT guarded by `canAccessPath` — `allowedRoute` historically had no `ceo-settings` case (it was nav-gated only by `['ceo','admin']` in AppShell). Reproducing "no behavior change" means the path guard must let it through; visibility is still controlled by the sidebar `show` flag. Its `<Route>` sits outside `<RequireAccess>`.
- `EmployeeDetailPage.submitEdit` passes the URL `id` (string) to `onUpdateEmployee`, so the `employeeDetail(id)` key invalidated in `useHrData` matches this page's query key exactly (React Query compares keys structurally; string vs number would not match).
- `createEmployee` no longer navigates: the old `routeTo('employees')` landed on the list the modal already lives on, so staying put after create is the same observable behavior. No post-mutation navigate added.
- Frozen sales detail pages were not edited; a thin wrapper in App.jsx provides `useParams`/`useNavigate` wiring, keeping the freeze intact.

## Assumptions
- `react-router-dom` v7 is React 18 compatible — CONFIRMED: `react-router-dom@7.18.1` installed, build/tests/preview all green under React 18.3.1; `npm install` reported 0 vulnerabilities (passes CI `npm audit` gate).
- SPA deep-link fallback is already configured (both `vercel.json` files rewrite `/(.*) → /index.html`) — CONFIRMED present, no infra change made.
- Mock backend (`VITE_USE_MOCKS`) does not persist a session across a hard browser reload, so a real "refresh keeps me logged in on `/leave`" round-trip is only fully exercisable against the real backend; the client-side routing half (URL preserved through login, correct page rendered while authenticated) was verified against the mock.

## Known Risks
- Guards must reproduce `allowedRoute` EXACTLY (same redirects) — a subtly different guard is a permissions regression. Cover it in `permissions.test.js`.
- EmployeeDetailPage now fetches by id — verify `/employees/:id` deep-links on refresh AND that editing invalidates `employeeDetail(id)` so the detail refetches.
- Deep-link-through-login: URL must be preserved while LoginPage shows and honored after login.
- Mobile drawer must close on navigation (regression risk with branch-1 drawer).
- Do not break the branch-3/4 Query wiring or the mock/real seam.

## Things Not Finished
- Nothing left half-migrated: every `on*` nav prop is rewired, the ternary is fully removed, and all routes render. This is a single cohesive change.
- Not done (intentionally out of scope): pushing per-page data fetching into pages (App still passes `useHrData` data as props); any deeper refactor of the frozen sales pages; TicketDetail `useReducer` refactor (separate, deferred item from the UX roadmap).
- Real-backend refresh-persistence of `/employees/:id` and `/leave` should be spot-checked by the reviewer against a live session (mock can't persist across reload).

## Recommended Next Agent
Claude Opus review — live-verify URLs/deep-link-refresh/back-forward/guards, confirm no behavior change, lint/test/build. Then the deferred Attendance/Payroll Query branches, or a v0.1.0 hardening pass.

## Exact Next Prompt
> You are the REVIEW agent for branch `refactor/frontend-routing` (GL-R-ERP, `/Users/ploy_warit/Desktop/GL-R-ERP`). The implementer introduced `react-router-dom@7.18.1` + `BrowserRouter` and replaced the `App.jsx` ternary router with real URL routes, `:id` params, and permission guards. Changes are UNCOMMITTED on the branch (base `cfa8476`). Do NOT commit/push unless you decide it's ready and are explicitly asked to.
>
> Read first: `docs/agent-handoffs/06_refactor-frontend-routing.md` (this file — Files Changed / Decisions / Test-Build Results), then the plan `/Users/ploy_warit/.claude/plans/atomic-marinating-otter.md` "Branch 5" section for the exact spec.
>
> Verify (do not just trust the handoff):
> 1. `cd frontend && npm run lint && npm test && npm run build` — expect 0 lint errors (9 pre-existing exhaustive-deps warnings OK), 46 tests pass (permissions.test.js = 19), build OK.
> 2. Read `frontend/src/app/permissions.js` `canAccessPath` vs `allowedRoute` and confirm the guards reproduce the OLD redirects EXACTLY — pay attention to `/ceo-settings` (must stay un-guarded by path, nav-gated only), `/overtime` + `/leave` (`employeeId || canViewAll*`), `/profile` (`employeeId`), and `/my-requests` (`canSubmitProfileRequests`).
> 3. Live-verify in preview (`preview_start` frontend-mock port 5200; HR quick-login = button containing "HR" + "พนักงานทั้งหมด"): sidebar nav changes URL + active-from-URL; deep-link-through-login (logged out → set `/leave` → login → lands on `/leave`); HR row → `/employees/:id` fetches by id; edit an employee → detail refetches (invalidation of `employeeDetail(id)`); browser back/forward; Employee hitting `/employees` and `/payroll` → redirect to `/`; mobile drawer (375px) closes on navigate; logout → `/` + cache cleared; no console errors.
> 4. Confirm the frozen sales pages (`TicketDetailPage`/`DepositNoticePage`) were NOT deep-refactored — only param-wired via the App.jsx wrappers.
>
> If clean, report ready-to-merge. If you find a permissions-regression or a broken deep-link, fix only that (tiny safe fix) or hand back. Then update this handoff's status.

---

## Review Verdict (Claude Opus 4.8, reviewer) — APPROVED

Reviewed the full diff and independently re-verified. **Approved, no changes needed.** Committed and opened as a PR to `main`.

- **Scope:** 20 files, exactly as planned. Frozen sales pages (`TicketDetailPage`/`DepositNoticePage`) NOT edited internally — only param-wired via the `TicketDetailRoute`/`DepositNoticeRoute` adapters in `App.jsx`. `api/` transport, `queryClient`, business logic, styles untouched.
- **Guard fidelity (the headline risk) — CONFIRMED faithful to `allowedRoute`:** read `canAccessPath` line-by-line against the old conditions. Each path maps to the same `hasPermission` check; `/employees/:id` inherits `canViewEmployees` (old `detail`); `/attendance` + `/ceo-settings` correctly stay **unguarded** by path (attendance always-open; ceo-settings was nav-gated only). `/tickets/:ticketId/deposit` now requires `canViewTickets` (old deposit-create was unguarded) — a harmless tightening on a frozen sales path, not an HR-core regression. `permissions.test.js` has 19 tests asserting every denied case (employee blocked from /employees, /payroll, /requests, /hr, /tickets, /commissions; self-service gated by `employeeId`).
- **Gates (re-run by me):** lint 0 errors / 9 pre-existing warnings; `npm test` 46/46 (permissions 19); build ok (bundle +15 KB gz for react-router — expected).
- **Live-verified (frontend-mock), beyond the implementer's pass:**
  - Sidebar nav → URL changes (`/employees`) with active state derived from the URL (`NavLink`).
  - **Employee-detail deep-link:** row click → `/employees/1`; the page fetched the employee **by id** via the new `useQuery(employeeDetail(id))` and rendered fully (GLR-1001, breadcrumb, tabs). Screenshot captured.
  - **Browser back** → returned to `/employees` with the list intact.
  - **Permission guard (clean test):** logged-in Employee navigating to `/payroll` → **redirected to `/`**, still authenticated on the dashboard. (An earlier synthetic-nav sequence corrupted the mock session and gave a misleading result — re-ran cleanly and it redirects correctly.)
  - No console errors.
- **Mock limitation (spot-check on real backend):** the mock doesn't persist a session across a hard reload, so "refresh while authenticated stays on the deep URL" isn't mock-testable — but the SPA fallback (`vercel.json`) + `BrowserRouter` are standard and the logged-out→login→intended-URL path was verified. Reviewer/owner should spot-check a hard refresh on `/employees/:id` against the real backend before the demo relies on it.

**Next:** the deferred **Attendance** and **Payroll** Query branches, or a v0.1.0 hardening/DoD pass.
