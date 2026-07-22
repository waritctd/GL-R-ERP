# Agent Handoff

## Task
Implement the DIVISION-MANAGER (non-sales) role-shaped landing: a division manager of a
non-sales division (role `employee` + the manager flag, e.g. ฝ่ายคลังสินค้า/warehouse) should land
on a purpose-built people-ops Overview — approve their division's OT/leave (step 1 of the
`employee → manager → CEO` chain), see their team's attendance today, plus their own self-service —
instead of the generic `EmployeeDashboard` 'manager' mode. No sales pipeline, no deal detail.
Frontend-only.

## Branch
`feat/role-views-division-manager`

## Base Commit
`20a568385b1a293d1447a480ecd9e0a33e3cb836` (origin/main, "Merge pull request #281 from
waritctd/feat/payroll-special-pay-carryforward")

## Current Commit
Not committed — per the task instructions, this session left all changes uncommitted in the
worktree for review before commit.

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- New `DivisionManagerOverview.jsx` Overview component + its landing wiring at `/`.
- `isDivisionManager(user)` detection helper (single source of truth, reused by nav + routing).
- Role-scoped nav additions for this role (new `team` sidebar group).
- `docs/role-scoped-views.md` (new, living spec for the role-scoped-views program).
- Tests: `DivisionManagerOverview.test.jsx`, `permissions.test.js` additions, `App.test.jsx`
  route-branch integration tests.

### Out of Scope
- Backend/DB changes (none made; none needed — every data source is an existing, already
  role-scoped endpoint the mock/Java service already exposes to this role).
- Any authorization change (no role gate, scope, or permission was added, widened, or narrowed).
- The other roles in `~/.claude/plans/valiant-watching-map.md` (Import/Account/Sales/Sales
  Manager/HR/CEO) — untouched, separate branches per that plan.
- The plain-employee `EmployeeDashboard` path — unchanged behavior, verified by test.
- Building a real roster/`/employees`-equivalent page for this role, or granting
  `canViewEmployees` — see "Known deviations" below.

## Files Changed
- `frontend/src/features/dashboard/DivisionManagerOverview.jsx` (new) — the landing: pulse
  (OT/leave pending, late-today, team headcount), "คำขอที่รอคุณอนุมัติ" OT+leave worklist with
  approval-chain badges and deep-link-only CTAs, "การลงเวลาทีมวันนี้" team-attendance rail, "ของฉัน
  (self-service)" quick actions.
- `frontend/src/features/dashboard/DivisionManagerOverview.test.jsx` (new) — 7 tests.
- `frontend/src/app/permissions.js` — added `isDivisionManager(user)` (role `employee` + manager
  flag, narrowed off other manager-flagged roles like `sales_manager`).
- `frontend/src/app/permissions.test.js` — added an `isDivisionManager` describe block (4 tests).
- `frontend/src/App.jsx` — lazy-imports `DivisionManagerOverview`; `/` route element now branches
  on `isDivisionManager(user)` before falling back to the unchanged `EmployeeDashboard` element.
- `frontend/src/App.test.jsx` — extended the mocked `api` with `overtime.list`/`leave.list`/
  `attendance.daily` stubs; added a new describe block asserting the `/` route branch (2 tests).
- `frontend/src/components/layout/Sidebar.jsx` — added the `team` (`ทีมของฉัน`) `NAV_GROUPS` entry.
- `frontend/src/components/layout/AppShell.jsx` — imported `isDivisionManager`; added 3 nav items
  (`การอนุมัติ OT`, `การอนุมัติวันลา`, `ทีมในฝ่าย`) under the new `team` group, gated on
  `isDivisionManager(user)`; existing `self`-group items unchanged.
- `frontend/src/api/queryKeys.js` — added `attendanceDaily(from, to)` key factory (was previously
  built inline by `AttendancePage.jsx`'s imperative fetch; this is the first `useQuery` caller).
- `docs/role-scoped-views.md` (new) — the pattern + the full Division Manager section + change log.

## Commands Run
```bash
cd frontend && npm run lint
cd frontend && npm test -- --run
cd frontend && npm run build
rm -rf frontend/dist
```

## Test / Build Results
- **Lint**: pass — 0 errors, 1 pre-existing unrelated warning (`PayrollPage.jsx:263`, not touched
  by this branch). The one warning this branch introduced (`DivisionManagerOverview.jsx`
  `useMemo` dependency) was fixed before the final run.
- **Frontend tests**: pass — 50 test files, 448 tests, 0 failures (full suite, includes the 7 new
  `DivisionManagerOverview.test.jsx` tests, 4 new `isDivisionManager` tests, 2 new `App.test.jsx`
  route-branch tests).
- **Frontend build**: pass — `vite build` succeeded, no errors; `frontend/dist` removed afterward
  per instructions.
- **Backend**: not run — frontend-only task, no backend files touched.

## Authz Evidence
No authorization change in this task. `isDivisionManager` is a **presentation-only** detection
helper — it does not grant, widen, or narrow any permission. Every data source the new Overview
reads (`api.overtime.list`, `api.leave.list`, `api.attendance.daily`, `dashboardSummary`) is an
endpoint this role could already call before this branch, with the exact same server/mock-side
scoping (`canReviewOvertime`, `canReviewLeave`, `mockAttendanceScope`, `dashboardHeadcount` —
all pre-existing, unmodified). The three new nav items point at three routes
(`/employee-requests`, `/leave`, `/attendance`) this role could already reach — `PATH_GUARDS` in
`app/permissions.js` is unmodified. Verified only under `VITE_USE_MOCKS=true` (this session did not
run against the real Java service) — since nothing here touches a role gate, scope, or filter,
that is consistent with CLAUDE.md's authz-evidence requirement, which applies to authorization
*changes*, not to a UI reading of unmodified, already-scoped endpoints. If a reviewer wants an
extra check: confirm in the Java backend that `OvertimeService`/`LeaveService`/`AttendanceService`
scope a division-manager caller identically to what `mockApi.js`'s `canReviewOvertime`/
`canReviewLeave`/`mockAttendanceScope` model (this mirrors existing, previously-verified manager
scoping already exercised by `OvertimePanel`/`LeavePage`/`AttendancePage`, not new scoping this
branch introduced).

## Decisions Made
- **`isDivisionManager(user) = role === 'employee' && !!user.manager`**, placed in
  `app/permissions.js` (the file's own header already calls it the single source of truth for role
  gates) rather than duplicating `EmployeeDashboard.dashboardMode`'s manager-detection logic. This
  is the same `user?.manager` predicate `dashboardMode`'s 'manager' branch uses, narrowed to
  `role === 'employee'` so `sales_manager` (which also sets `manager`-like state via its own role)
  is not swept into this landing — it has its own dedicated Overview in the wider program.
- **OT and leave chain badges differ** (3-step vs 2-step) because the underlying workflows
  genuinely differ in this codebase today (see `docs/role-scoped-views.md` for the full code
  citations). Rendering a fake uniform "→ CEO" step for leave would have been more polished but
  false — chose accuracy over matching the task prompt's literal (and, on inspection, imprecise)
  framing.
- **Worklist rows are derived by excluding the caller's own SUBMITTED request** from
  `api.overtime.list({status:'SUBMITTED'})`/`api.leave.list({status:'SUBMITTED'})`, rather than
  re-implementing `managesRequest`/`canReviewLeave`'s scoping logic client-side. The API already
  returns exactly "what I may see" (self + reviewable); the only client-side filter needed is
  "and not my own", since self-approval is never valid. This avoids a second, potentially-drifting
  copy of the approval-eligibility rule (the plan document explicitly flags "next-action drift" as
  a risk for this kind of derived-client-side helper).
- **Team-attendance right rail uses `api.attendance.daily`, not `dashboardSummary.attendance`** —
  `dashboardSummary.attendance` for `division`/`self` scope is hardcoded to zeros in the mock
  today (`mockApi.js` `dashboardAttendance`), so it would always render 0 regardless of real team
  state; `attendance.daily` is genuinely computed per employee and is the same data source
  `AttendancePage` uses for a manager's team view. `dashboardSummary.headcount.active` (used for
  the "ทีมทั้งหมด" pulse card), by contrast, IS genuinely computed in the mock, so it was kept.

## Assumptions
- "Division manager (non-sales)" in the task prompt = `isDivisionManager(user)` as defined above;
  `sales_manager` is out of scope here (has its own future Overview in the wider program).
- The task's nav spec ("`ทีมของฉัน` group — แดชบอร์ด, การอนุมัติ, ทีมในฝ่าย, เวลาทำงานทีม;
  `บุคคลของฉัน` group — เวลาทำงาน, คำขอ/วันลา") was read as an intent to group/frame, not to build
  4-6 brand-new distinct pages — see "Known deviations" below for exactly where and why the nav
  ended up with 3 new items instead of the full literal list.

## Known Risks
- **Nav duplication**: `/employee-requests`, `/leave`, and `/attendance` each now appear twice in
  the sidebar for this role (once under `self`, once under the new `team` group), pointing at the
  identical route with different framing copy. Documented as an accepted, deliberate choice (see
  `docs/role-scoped-views.md`) rather than a bug, but a reviewer may prefer collapsing this
  further in a follow-up.
- **Deep links are page-level, not row-level**: the worklist's "อนุมัติ" CTA opens the correct page
  + tab but cannot pre-scroll/pre-filter to the exact request, since neither `OvertimePanel` nor
  `LeavePage` reads a request-id query param today. A team with many pending items may need an
  extra click/search once on the approve page.
- **Mock data gaps upstream of this branch, not fixed here**: `dashboardPending(user).overtime` is
  hardcoded to `0` in `mockApi.js` regardless of role/scope (pre-existing, unrelated to this
  branch — this Overview deliberately avoids that field, using `api.overtime.list()` directly
  instead, so it is not affected).
- **This session's verification is mock-only** (`VITE_USE_MOCKS=true` test suite); no real-backend
  UI smoke was run, consistent with "no authz change" above but worth a real-Java sanity pass
  before this ships to a demo/prod environment, per CLAUDE.md's general mock-vs-Java caveat.

## Things Not Finished
- A dedicated "ทีมในฝ่าย" roster page (would require either a new page + endpoint or granting
  `canViewEmployees` to this role — both out of scope for a frontend-only landing task; noted as a
  deliberate deviation, not a partial implementation).
- Real-Java-backend UI smoke (see Known Risks).

## Recommended Next Agent
Opus review against `~/.claude/plans/valiant-watching-map.md`'s per-role validate-then-build gate
(this role's design was not pre-rendered/user-validated as a widget the way Import/Account were,
per the task's own instructions to build directly from the written spec) — then, if accepted,
merge review + a real-backend smoke pass before merge.

## Exact Next Prompt
```
Review branch feat/role-views-division-manager (worktree
.claude/worktrees/role-views-division-manager) against docs/role-scoped-views.md's Division
Manager section and docs/agent-handoffs/108_feat-role-views-division-manager.md. Confirm:
(1) DivisionManagerOverview.jsx matches DESIGN.md and doesn't leak any sales surface,
(2) the OT-vs-leave approval-chain asymmetry documented there is accurate against the current
OvertimePanel.jsx/LeavePage.jsx/mockApi.js source, (3) the nav duplication tradeoff is acceptable
or should be revised. If accepted, run a VITE_USE_MOCKS=false smoke pass against the real backend
as a division-manager test user (login, land on /, approve one OT + one leave request via the
deep links, confirm /employee-requests, /leave, /attendance render team-scoped data with no 403s)
before merging to main.
```
