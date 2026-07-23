# Agent Handoff

## Task
Implement the CEO role-shaped landing (executive cockpit) — part of the "role-scoped views" program
(plan: `~/.claude/plans/valiant-watching-map.md`, "Per-role target views → 6. CEO"). CEO keeps its
full nav/pipeline/permissions unchanged; the only new surface is a `/` landing (`CeoOverview`) that
surfaces the cross-domain decisions only the CEO can make. Frontend only, no backend change.

## Branch
`feat/role-views-ceo` (worktree: `.claude/worktrees/role-views-ceo`)

## Base Commit
`20a568385b1a293d1447a480ecd9e0a33e3cb836` (origin/main, PR #281 merged)

## Current Commit
Not committed — working tree only (per task instructions: "Do NOT commit").

## Agent / Model Used
Claude (Sonnet)

## Scope

### In Scope
- New `frontend/src/features/dashboard/CeoOverview.jsx` — CEO exec-cockpit landing.
- New `frontend/src/features/dashboard/CeoOverview.test.jsx` — 9 tests.
- `frontend/src/App.jsx` — `/` route branches to `CeoOverview` when `role==='ceo' && SALES_ENABLED`.
- `frontend/src/api/queryKeys.js` — added `commissionsList` key.
- `frontend/src/api/mockApi.js` — `tickets.list()` mock-parity fix (see below).
- New `docs/role-scoped-views.md` — shared pattern spec + CEO section + change log.
- This handoff file.

### Out of Scope (explicitly not touched)
- CEO nav (`AppShell.jsx`) — unchanged, CEO keeps every existing nav item.
- Any permission/route guard (`app/permissions.js`, `routes.js` `ROLE_PERMISSIONS`) — CEO already
  had every permission this Overview reads; nothing needed adding.
- Any other role's Overview (Import/Account/Sales/Sales Manager/HR) — separate branches per the
  plan's "Branching" note.
- Backend (`backend/`) — not touched, not run.
- `dashboardAttendance()`'s hardcoded `todayPresent:0`/`lateToday:0` for hr/ceo scope in
  `mockApi.js` — a pre-existing mock stub, noted as a known gap, not fixed (out of scope for a
  landing-page task; would need real attendance-day aggregation logic).

## Files Changed
- `frontend/src/features/dashboard/CeoOverview.jsx` (new) — the CEO landing: exec pulse (5 stat
  tiles), cross-domain "รออนุมัติจากคุณ" worklist (ราคา/ปิดงาน/ค่าคอม/OT/ลา, each deep-linking, never
  mutating inline), ยอดตามทีม/ฝ่าย breakdown (by sales rep — see gap below), ผลบริษัทเดือนนี้ snapshot.
- `frontend/src/features/dashboard/CeoOverview.test.jsx` (new) — 9 tests: exec-pulse derivation,
  per-domain worklist row inclusion/exclusion, one deep-link test per domain (via a real
  `MemoryRouter` navigation + a location-probe route, not a mocked `useNavigate`), company snapshot,
  rep breakdown with no invented %.
- `frontend/src/App.jsx` — added lazy `CeoOverview` import; `/` route now renders `CeoOverview` for
  `role==='ceo' && SALES_ENABLED`, else `EmployeeDashboard` (unchanged for every other role).
- `frontend/src/api/queryKeys.js` — added `commissionsList(payrollMonth)` key (was previously only
  ever called imperatively from `CommissionPage.jsx`, no react-query key existed).
- `frontend/src/api/mockApi.js` — `tickets.list()` now includes `closeConfirmedAt`,
  `closeConfirmedByName`, `invoiceOnFile` in its per-row projection, matching the real
  `TicketSummaryDto` (`backend/src/main/java/th/co/glr/hr/ticket/TicketSummaryDto.java`), which
  already returns these three fields at list scope. This is a **mock-parity fix**, not a
  business-logic or authz change — see "Decisions Made" below.
- `docs/role-scoped-views.md` (new) — pattern spec, non-negotiable rules, CEO section (full data-
  source table + gaps), change log.
- `docs/agent-handoffs/100_feat-role-views-ceo.md` (new, this file).

## Commands Run
```bash
cd frontend && npm run lint
cd frontend && npm test -- --run
cd frontend && npm run build
rm -rf frontend/dist
```

## Test / Build Results
- Lint: **pass** (0 errors, 1 pre-existing warning in `PayrollPage.jsx` unrelated to this branch).
- Frontend tests: **pass** — 444/444 across 50 files, including the new `CeoOverview.test.jsx`
  (9/9) and `api/contract.test.js` (mock/hrApi method-surface parity, still green — the `tickets.list()`
  field addition didn't change the method surface, only the DTO shape).
- Frontend build: **pass** (`vite build`, 162ms; `CeoOverview` code-splits into its own ~9.4 kB chunk).
  `dist/` removed after, per instructions.
- Backend: **not run** (frontend-only task, no backend files under `backend/` touched).

## Authz Evidence
**No authorization change.** CEO already had every permission this Overview reads before this
branch — `routes.js`'s `ROLE_PERMISSIONS.canViewTickets` / `canViewCommissions` /
`canViewPricingRequestQueue` / `canViewAllOvertime` / `canViewAllLeave` all already include `'ceo'`.
This branch adds a **presentation-only** landing page and three previously-missing **list-level
fields** on an endpoint CEO could already call in full (`tickets.list()`); it does not add, remove,
or scope any permission, route guard, or `WHERE` clause. No real-DB integration test was added
because there is nothing new to verify server-side — the mock-parity fix only exposes fields the
real `TicketSummaryDto` already serializes at list scope (confirmed by reading
`backend/src/main/java/th/co/glr/hr/ticket/TicketSummaryDto.java` and
`TicketRepository.java`/`TicketService.java` directly), it does not add a field or a row the real
backend does not already return.

## Decisions Made
- **Fixed a real mock/backend divergence in `tickets.list()`** rather than working around it. The
  real `TicketSummaryDto` (list-level, confirmed by reading the backend source) already carries
  `closeConfirmedAt`/`closeConfirmedByName`/`invoiceOnFile`; the mock's `tickets.list()` projection
  was dropping them (only `buildTicketDetail()`, the single-ticket path, had them). Without this fix,
  "which tickets is the CEO's close-verification worklist for" could only be derived via an N+1
  detail fetch per ticket — CLAUDE.md's "mock shapes are a faithful stand-in for the Spring backend"
  contract makes this the correct fix, not a scope creep: the mock was strictly **more limited**
  than prod (the dangerous-in-the-opposite-way direction from CLAUDE.md's usual warning, but still a
  divergence worth closing).
- **OT/leave "manager-less division → CEO" is derived from the real FK, not invented.** Both
  `OvertimeService.managesEmployee()` and `LeaveService.canReviewEmployee()` grant review rights via
  the employee's stored `reports_to_employee_id` (`managerEmployeeId` on the DTO). A manager-less
  division's employees have that FK pointing straight at the CEO, so "the CEO is this employee's FK
  manager" and "manager-less division routed to CEO" are the same real, server-checked condition.
  Verified this is genuinely how the models already work (`canReviewOvertime`/`canReviewLeave` in
  `mockApi.js`) rather than adding new authz logic to make it true.
- **Two bounded status-filtered `overtime.list()` calls, not one unbounded fetch.** Both `from`/`to`
  are optional on the real `OvertimeController` (confirmed by reading the backend source), so an
  unfiltered call would return the entire OT history; two `{status:...}` calls stay small and match
  what the exec cockpit actually needs.
- **`ยอดตามทีม/ฝ่าย` is grouped by sales rep, not division** — tickets carry no `divisionId`, and
  `GET /api/employees` (the only division mapping) is hr-only. Stated as an explicit gap in
  `docs/role-scoped-views.md` rather than silently relabeling the panel or fetching an endpoint the
  CEO shouldn't reach.
- **No % or target/quota anywhere in the rep-breakdown bars** — no such field exists on any endpoint
  the CEO can call; bars are relative to this month's own top performer only.
- **Every worklist CTA deep-links; none mutates inline** — verified in tests via a real
  `MemoryRouter` navigation (a location-probe route), not a mocked `useNavigate`, and the mocked
  `api` surface in the test only exposes `list`/`queue` methods (no approve/verify/decide method
  exists on the mock at all), so the component could not have called a mutation even if it tried.

## Assumptions
- `dashboardSummary` (already fetched once at the `App` level via `useHrData`) is CEO-scoped
  (`role/'all'` scope) — confirmed against `EmployeeDashboard.jsx`'s existing `mode === 'company'`
  usage of the same prop for the same role.
- CEO users always have `employeeId` set (needed for the OT/leave "is this employee's FK manager"
  check) — consistent with every other place in the codebase that reads `user.employeeId` for a CEO
  session (e.g. `OvertimePanel.jsx`).

## Known Risks
- **`dashboardAttendance()`'s hr/ceo branch hardcodes `todayPresent`/`lateToday` to `0`** in
  `mockApi.js` (pre-existing, not introduced by this branch) — under `VITE_USE_MOCKS=true`, the
  "มาสายวันนี้" tile in `ผลบริษัทเดือนนี้` will always read `0` regardless of real data. The real
  backend's `DashboardService` is expected to compute this for real (per memory:
  `attendance-payroll-sync.md` — PR #238/#242/#244 built the daily-attendance aggregation), so this
  is a mock-only limitation, not a backend gap — but it should be spot-checked against a real/demo
  backend before trusting that tile in a live review.
- **`ยอดตามทีม/ฝ่ายbreakdown` groups by rep, not division** (see "Decisions Made") — if a reviewer
  expects a literal division breakdown, this is a stated deviation, not an oversight.
- **`amountPaid ?? amountPayable` is used as "revenue" for closed deals** — for a properly-closed
  deal (`requireClosePrerequisites` requires `amountOutstanding === 0`), these should already be
  equal; this is a defensive fallback, not expected to diverge in practice.

## Things Not Finished
- Import / Account / Sales / Sales Manager / HR Overviews — separate branches per the plan.
- Real-backend (non-mock) smoke test of `CeoOverview` — not run (no backend/demo stack was started
  for this task; verification is `VITE_USE_MOCKS=true` only, as stated in "Authz Evidence").

## Recommended Next Agent
Opus review (per the plan's "Sonnet-implements/Opus-reviews" loop) — verify the CeoOverview design
against the validated widget mock, and confirm the `tickets.list()` mock-parity fix is the right
call before it's merged.

## Exact Next Prompt
```
Review the CEO exec-cockpit landing built on feat/role-views-ceo
(.claude/worktrees/role-views-ceo) against the validated design in
~/.claude/plans/valiant-watching-map.md ("Per-role target views → 6. CEO"). Key things to check:
(1) frontend/src/api/mockApi.js's tickets.list() gained closeConfirmedAt/closeConfirmedByName/
invoiceOnFile — confirm against backend/src/main/java/th/co/glr/hr/ticket/TicketSummaryDto.java
that these are genuinely already on the real list-level DTO, not something only the detail endpoint
returns; (2) the OT/leave "manager-less division routed to CEO" derivation in CeoOverview.jsx uses
managerEmployeeId === CEO's own employeeId — verify this matches OvertimeService.managesEmployee()/
LeaveService.canReviewEmployee() in the real backend, not just the mock's canReviewOvertime/
canReviewLeave; (3) confirm no nav/permission/route-guard file was touched (CEO's existing surfaces
must be byte-for-byte unchanged); (4) run cd frontend && npm run lint && npm test && npm run build
and confirm the same 444/50 test-file counts. See docs/agent-handoffs/100_feat-role-views-ceo.md for
the full writeup and docs/role-scoped-views.md for the CEO data-source table.
```
