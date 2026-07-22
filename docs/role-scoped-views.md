# Role-scoped views — nav + Overview landings

This is the shared spec for the "role-shaped views" program: every role gets a
role-scoped nav (only the workspaces it owns) and a role-shaped Overview
dashboard as its `/` landing, instead of the one-size-fits-all
`EmployeeDashboard` + full sales deal-pipeline browser every role saw before.

Source plan: `~/.claude/plans/valiant-watching-map.md` ("Role-shaped views for
every role — role-scoped nav + Overview dashboards"). This file is the living,
in-repo version of that plan — updated by each role's branch as it ships.

## 1. The pattern

1. **Role-scoped nav** — the sidebar/drawer shows only the workspaces a role
   owns. The deal-pipeline browser item (`รายการดีล`) appears only for roles
   whose job *is* the pipeline (sales, sales_manager, ceo).
2. **Role-shaped Overview = landing** — `/` branches by role to that role's
   Overview (its prioritized worklist / pulse), not the generic dashboard.
3. **Mechanics** — a permission split gates the pipeline browser
   (`canViewDealPipeline`) separately from ticket-detail read
   (`canViewTickets`, which import/account keep). The landing branch lives in
   `frontend/src/App.jsx`'s `/` route; nav `show:` gates live in
   `frontend/src/components/layout/AppShell.jsx`; route guards live in
   `frontend/src/app/permissions.js` (`PATH_GUARDS`).

## 2. Permission / landing / nav mechanics

- **Landing branch** — `App.jsx`'s `/` route element branches on `user.role`
  and renders that role's `*Overview` component; every role not yet migrated
  falls through to the shared `EmployeeDashboard`.
- **Nav gating** — `AppShell.jsx` shows a nav item only when its `show:`
  predicate is true for the signed-in role; predicates call
  `hasPermission(role, key)` against `ROLE_PERMISSIONS` in
  `frontend/src/api/routes.js` — never a hand-rolled role list.
- **Route guards** — `frontend/src/app/permissions.js`'s `PATH_GUARDS` /
  `canAccessPath()` are the single source of truth the router (`RequireAccess`)
  uses to allow or bounce a path. Any Overview's CTAs must only ever offer a
  destination `canAccessPath` would also allow (see `EmployeeDashboard.jsx`'s
  `quickActions` filtering for the pattern).
- **Never loosen a server gate.** This whole program is presentation-only —
  which nav items and landing a role sees. It must never be used to grant a
  role backend access it didn't already have. Every permission-shaped claim
  ("HR cannot approve OT") must be true against the real Java service, not
  just the frontend gate — see CLAUDE.md's "Permission changes must ship
  evidence" section. If a role's Overview surfaces a *new* action affordance
  (a button that calls a mutating endpoint), that is a genuine authz question
  and needs the same real-DB integration-test evidence as any other
  permission change — reusing an existing, already-guarded page (e.g. linking
  to `/requests`, `/leave`, `/payroll`) is not.

## 3. Per-role sections

### HR — people-ops *(shipped, this branch: `feat/role-views-hr`)*

**Landing:** `frontend/src/features/dashboard/HrOverview.jsx`, rendered at `/`
when `user.role === 'hr'` (see `App.jsx`). HR's nav is unchanged by this
branch — HR never had the sales pipeline, and its existing pages
(employees/requests/payroll/attendance/leave/employee-requests) are untouched.

**RECONCILED APPROVAL MODEL — HR approves nothing.** This is the one
substantive divergence from the older `HrDashboard.jsx` it's modeled on:

- OT and leave both route **division-manager → CEO**, or straight to CEO when
  the division has no manager. **HR is not an approver of either** — HR only
  *monitors* status. `HrDashboard.jsx` (still used at `/hr`, unchanged) had
  OT/leave in its "ต้องดำเนินการ" (needs-action) queue as if HR could act on
  them; `HrOverview.jsx` deliberately does not repeat that — its action queue
  only ever contains profile requests (HR's real review task) and unread
  notifications (informational). Leave/OT get their own read-only "ภาพรวมลา /
  OT (ดูอย่างเดียว)" panel, explicitly labelled "อนุมัติโดยหัวหน้าฝ่าย/CEO",
  whose rows link to `/leave` and `/overtime` for *viewing* only — no approve
  affordance anywhere on the page (asserted by `HrOverview.test.jsx`).
- HR's one real review task is **profile-change requests**
  (`canReviewProfileRequests={hr}`, employee bank-account/address changes),
  framed as "ตรวจ/ดำเนินการ" ("review/action"), never "approve OT/leave".

**Overview contents:**
- Greeting ("สวัสดี ฝ่ายบุคคล") + subtitle ("งานบุคคลวันนี้ · การลงเวลา ·
  รอบเงินเดือน").
- KPI cards: profile requests pending review, headcount (active/total),
  today's attendance (present/late).
- "การลงเวลาวันนี้" panel: present / late / not-yet-clocked-out counts.
- "งานของฝ่ายบุคคล": profile-request review row (→ `/requests`) and payroll
  period status row (→ `/payroll`, via `api.payroll.current()`).
- "ภาพรวมลา / OT (ดูอย่างเดียว)": monitoring-only, see above.
- Headcount-by-division bar list (ported from `HrDashboard.jsx`).

**Data:** `api.dashboard.summary()` (HR scope, already role-shaped
server-side) + `api.employees.list()` (already fetched by `useHrData`) +
`api.payroll.current()` (new read on this page; mock always returns
`{ period: null }`, so the panel renders "ยังไม่เริ่มรอบเงินเดือนเดือนนี้" in
mock mode — this is a pre-existing mock gap, not something this branch fixes).
**Backend: none** — no new endpoint, no schema change, no permission change.

**Known pre-existing gap (not fixed here, per task scope):** the mock's
`dashboardAttendance()` returns hardcoded `0`s for the HR/CEO ("company")
scope (`frontend/src/api/mockApi.js`), so `HrOverview`'s attendance panel
will show zeros under `VITE_USE_MOCKS=true` even when there is real
attendance data. This is the same gap `HrDashboard.jsx` already has; it is
pre-existing and intentionally out of scope for this branch.

**Verification:** frontend only, mock-driven (`VITE_USE_MOCKS=true`).
`HrOverview.test.jsx` renders from a mocked HR `dashboard.summary` + mocked
`api.payroll.current`, and asserts: the profile-request review CTA, the
payroll status CTA, attendance counts, the headcount-by-division list, and —
the key regression guard — **no button on the page is an approval action**
(`/^อนุมัติ/` on any button's text, deliberately not matching "รออนุมัติ"
count labels) and the "ต้องดำเนินการ" queue contains no leave/OT row.
No authorization was changed (see "no authz change" in the change-log row
below) so no real-DB integration test was required or written; this is a
UI-only reshuffle of pages HR already had server-side access to.

### Import / Account / Sales / Sales Manager / CEO

Not documented in this file yet — each ships (and fills in its own section
here) on its own branch (`feat/role-views-import`, `feat/role-views-account`,
`feat/role-views-sales`, `feat/role-views-sales-manager`,
`feat/role-views-ceo`), per the plan's parallel per-role execution model.
Shared files (`App.jsx`, `permissions.js`, `AppShell.jsx`, `SalesTabs.jsx`)
will conflict across branches by design — those conflicts are resolved at
integration, not avoided up front.

## 4. Change log

| Date | Role | Branch | Summary |
| --- | --- | --- | --- |
| 2026-07-23 | HR | `feat/role-views-hr` | New `HrOverview.jsx` landing at `/` for `role==='hr'`; reconciled leave/OT to monitoring-only (no HR approve affordance); no authz/backend change. |
