# Agent Handoff

## Task
Implement the HR role-shaped landing (people-ops admin console) — one role in
the broader "role-shaped views" program (plan:
`~/.claude/plans/valiant-watching-map.md`, section "Per-role target views →
5. HR"). Build a new `HrOverview.jsx` and make it HR's `/` landing, reconciled
to the owner-confirmed approval model: **HR approves nothing** — OT/leave
route division-manager → CEO (or straight to CEO with no division manager);
HR only monitors. HR's one real review task is profile-change requests.
Frontend only; no commit made (per task instructions).

## Branch
`feat/role-views-hr`

## Base Commit
`20a568385b1a293d1447a480ecd9e0a33e3cb836`

## Current Commit
Same as base — no commit made, per task instructions ("Do NOT commit").
Working tree has the uncommitted changes listed below.

## Agent / Model Used
Claude Sonnet 5 (background job)

## Scope

### In Scope
- New `HrOverview.jsx` landing component + its test.
- `App.jsx` `/` route: branch `role === 'hr'` → `<HrOverview/>`.
- `queryKeys.js`: add a `payrollCurrent` key for the new `api.payroll.current()` read.
- New `docs/role-scoped-views.md` (didn't exist on this branch): shared pattern doc + HR section + change-log row.

### Out of Scope (untouched)
- HR nav (`AppShell.jsx`) — unchanged; HR never had the sales pipeline.
- Sales pipeline, permissions, any approval backend logic.
- HR's existing pages: `/hr` (`HrDashboard.jsx`, unchanged), `/employees`,
  `/requests`, `/payroll`, `/attendance`, `/leave`, `/employee-requests`.
- Backend (no `./mvnw` run, no schema/permission change).

## Files Changed
- `frontend/src/features/dashboard/HrOverview.jsx` (new) — HR's `/` landing: greeting, profile-request + headcount KPIs, today's attendance panel, "งานของฝ่ายบุคคล" (profile-request review + payroll-period status), "ภาพรวมลา / OT (ดูอย่างเดียว)" monitoring-only panel (no approve affordance), headcount-by-division list (ported from `HrDashboard.jsx`).
- `frontend/src/features/dashboard/HrOverview.test.jsx` (new) — 8 tests: greeting, profile-request CTA, payroll status CTA, attendance counts, monitoring-only leave/OT panel + "no approve button anywhere on the page" assertion, monitor-row links, headcount list, and that the "ต้องดำเนินการ" queue has no leave/OT row.
- `frontend/src/App.jsx` — `/` route now branches: `user.role === 'hr'` renders `<HrOverview employees={employees} dashboardSummary={dashboardSummary} />`; every other role still gets `<EmployeeDashboard .../>` unchanged. Added the lazy `HrOverview` import.
- `frontend/src/api/queryKeys.js` — added `payrollCurrent: (payrollMonth) => ['payroll', 'current', payrollMonth ?? '']`, used by `HrOverview`'s `api.payroll.current()` query.
- `docs/role-scoped-views.md` (new) — shared role-views pattern/mechanics doc (didn't exist on this branch) + a filled-in HR section (documents the leave/OT monitoring reconciliation and the pre-existing mock attendance-zero gap) + stub for the other 5 roles (each fills in its own section on its own branch, per the plan) + a change-log table with today's HR row.

## Commands Run
```bash
cd frontend && npm ci
cd frontend && npm run lint
cd frontend && npm test
cd frontend && npm run build
cd frontend && rm -rf dist   # disk-space cleanup, per task instructions
```

## Test / Build Results
- **Lint:** pass — 0 errors, 1 pre-existing warning in `PayrollPage.jsx` (unrelated `react-hooks/exhaustive-deps`, not touched by this branch).
- **Tests:** pass — 443/443 tests across 50 files (includes the new 8-test `HrOverview.test.jsx`).
- **Build:** pass — `npm run build` succeeded in 162ms, `HrOverview` code-splits into its own chunk (`HrOverview-*.js`, ~7.2 kB / 2.5 kB gzip). `dist/` deleted immediately after per instructions to conserve disk.
- **Backend:** not run (frontend-only task, no backend change).
- Note: `frontend/node_modules` did not exist in this worktree at task start (disk was full earlier in the day); ran `npm ci` first — 389 packages, 0 vulnerabilities.

## Authz Evidence
No authorization change in this task. `HrOverview` only re-surfaces reads/CTAs
to pages HR already had server-side access to (`/requests`, `/payroll`,
`/leave`, `/overtime`, `/attendance`, `/employees`) via `ROLE_PERMISSIONS` in
`frontend/src/api/routes.js`, which is unchanged. The leave/OT "monitoring
only" reconciliation removes an approve-looking affordance from the *frontend
queue display* (`HrDashboard.jsx`'s old queue included OT/leave as if HR could
act on them) — it does not touch any backend gate, and the underlying
`/overtime` and `/leave` pages HR can still open were never mutated by this
branch. No real-DB integration test was needed or written.

## Decisions Made
- Kept `HrOverview` visually/structurally close to `HrDashboard.jsx`
  (same `Panel`/`StatGrid`/`StatCard`/`ActionQueue`/`PageStack` primitives,
  same `dashboardSummary` field reads) rather than inventing new components,
  per the task's explicit instruction to reuse that structure.
- Dropped OT/leave from the "ต้องดำเนินการ" action queue entirely (queue only
  has profile requests + notifications) rather than keeping them present but
  disabled — an item HR cannot act on doesn't belong in a "needs action" list
  (same UX-04 principle `EmployeeDashboard.jsx` already applies elsewhere in
  this codebase).
- Payroll-period status reads live via a new `api.payroll.current()` query
  (added a `queryKeys.payrollCurrent` key) rather than piggy-backing on
  `dashboardSummary.latestPayrollPeriodId`, which the mock hardcodes to `null`
  and which doesn't carry the period's status — `api.payroll.current()` is
  the same call `PayrollPage.jsx` already makes and returns `{ period }`
  with `payrollMonth`/`status`, letting the panel show a real status badge
  once a real period exists.
- Removed the (now-unused) `employee` prop from `HrOverview`'s signature
  after ESLint flagged it — the spec's greeting is a fixed
  "สวัสดี ฝ่ายบุคคล", not a personalized "สวัสดี, คุณ...", so the prop
  wasn't needed; the `App.jsx` call site was updated to match (not just
  silently passing a dead prop).

## Assumptions
- `docs/role-scoped-views.md` genuinely did not exist on `main`/this branch
  (confirmed via `ls`) — created fresh rather than merged/edited.
- The plan's `Per-role target views → 5. HR` section explicitly says
  "REUSE the existing `HrDashboard.jsx`... just make it HR's landing" and
  predates the later "RECONCILED APPROVAL MODEL" instruction in this task
  (owner-confirmed 2026-07-22/23) that HR approves nothing. Treated the
  reconciliation instruction as authoritative over the older plan text for
  the leave/OT approve-vs-monitor question, since it's the more recent,
  explicit, owner-confirmed instruction — and built a new `HrOverview.jsx`
  (not a mutation of `HrDashboard.jsx`, which stays as-is at `/hr` for
  anyone who still navigates there directly).

## Known Risks
- `api.payroll.current()` always returns `{ period: null }` under
  `VITE_USE_MOCKS=true` (mock gap, pre-existing, confirmed in
  `frontend/src/api/mockApi.js`), so the payroll-period panel will only ever
  show "ยังไม่เริ่มรอบเงินเดือนเดือนนี้" in mock-driven manual QA — the
  "shows a real period + status badge" path is exercised only by the test's
  explicit mock override, not by browsing the mock app. Not a regression;
  same gap `PayrollPage.jsx` already lives with.
  - **Note per task instructions:** HR mock *attendance* is also hardcoded to
    `0` for the HR/CEO scope in `dashboardAttendance()` (`mockApi.js`) — this
    is explicitly called out as pre-existing and out of scope to fix; the
    attendance panel will show zeros under mocks even with real underlying
    data.
- Did not manually browser-verify (no dev server / browser session used in
  this background run) — verification is lint + 443 automated tests (incl.
  the new suite) + a successful production build. The build's own chunk
  split for `HrOverview` is evidence the route wires up and the module
  resolves cleanly, but a manual click-through as an HR user was not done.

## Things Not Finished
- No commit was made (explicit task instruction). The working tree has the 5
  changed/new files listed above, uncommitted.
- Other roles' sections in `docs/role-scoped-views.md` (Import, Account,
  Sales, Sales Manager, CEO) are left as stubs — each ships on its own
  branch per the plan's parallel execution model; this branch only owns HR.

## Recommended Next Agent
Opus review (per the plan's "Opus reviews the built UI against the
approved design" step) against the validated HR design in
`~/.claude/plans/valiant-watching-map.md`, then commit + PR once reviewed.

## Exact Next Prompt
```
Review the uncommitted HR role-view changes on
/Users/ploy_warit/Desktop/GL-R-ERP/.claude/worktrees/role-views-hr
(branch feat/role-views-hr) against the validated HR design in
~/.claude/plans/valiant-watching-map.md ("Per-role target views → 5. HR")
and the RECONCILED APPROVAL MODEL (HR approves nothing; monitors leave/OT
only). Files to review: frontend/src/features/dashboard/HrOverview.jsx,
frontend/src/features/dashboard/HrOverview.test.jsx, frontend/src/App.jsx's
`/` route branch, frontend/src/api/queryKeys.js's new payrollCurrent key,
and docs/role-scoped-views.md's HR section + docs/agent-handoffs/
100_feat-role-views-hr.md. Confirm: no HR approve affordance for OT/leave
anywhere on the page; profile-request review and payroll-period CTAs land
on the correct existing routes; the page matches DESIGN.md (Tailwind
tokens, no nested cards, no new page-specific CSS). If it matches, commit
with a `feat:` message and open a PR against main; call out any drift from
the approved design before committing.
```
