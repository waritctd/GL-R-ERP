# Role-scoped views — nav + Overview landings

This is the shared spec for the "role-shaped views" program: every role gets a
role-scoped nav (only the workspaces it owns) and a role-shaped Overview
dashboard as its `/` landing, instead of the one-size-fits-all
`EmployeeDashboard` + full sales deal-pipeline browser every role saw before.

Source plan: `~/.claude/plans/valiant-watching-map.md` ("Role-shaped views for
every role — role-scoped nav + Overview dashboards"). This file is the living,
in-repo version of that plan — updated by each role's branch as it ships.

There is no single generic "dashboard" any more — each role lands on a view
built around *its* work.

## 1. The pattern

1. **Role-scoped nav** — the sidebar/drawer shows only the workspaces a role
   owns. The deal-pipeline browser item (`รายการดีล`) appears only for roles
   whose job *is* the pipeline (sales, sales_manager, ceo) — Import/Account
   lose it in their own branches; Sales keeps it (see §3).
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
- **Never duplicate a mutation.** An Overview's worklist rows deep-link to the
  page that owns the action (`/pricing-requests/:id`, `/tickets/:id`,
  `/commissions`, …) — an Overview component must never call an
  approve/verify/decide endpoint itself. If a row's CTA and the destination
  page's own button ever drift on what action they represent, that is a bug
  in the Overview, not a second source of truth to reconcile.
- **Branching note** — each role ships on its own branch
  (`feat/role-views-<role>`), off `origin/main`, so builds run concurrently.
  Shared files (`routes.js`, `permissions.js`, `AppShell.jsx`, `App.jsx`,
  `SalesTabs.jsx`) conflict across branches by design — that is expected and
  resolved at integration, not avoided up front. Each role's own Overview
  component (`ImportOverview.jsx`, `AccountOverview.jsx`, `SalesOverview.jsx`,
  `ManagerOverview.jsx`, `HrOverview.jsx`, `CeoOverview.jsx`) is a unique file
  and never conflicts.

## 3. Per-role sections

### HR — people-ops *(shipped, branch: `feat/role-views-hr`)*

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

### Sales — own deals (keeps the pipeline) *(shipped, branch: `feat/role-views-sales`)*

Sales is the "own deals" role. Unlike Import/Account, Sales **keeps the full
deal-pipeline browser** (`รายการดีล` / `TicketListPage`) as-is — Sales already
only ever receives its own deals from `api.tickets.list()` (server-scoped by
`created_by`), so there is no separate "worklist vs. full list" distinction to
build for this role (see `TicketListPage.jsx`'s `WORKLIST_ROLES`, which
deliberately excludes `sales`). The only new surface is a personal-cockpit
**Overview landing**, plus a visual de-emphasis tweak to the pipeline list
itself.

- **Nav:** unchanged — แดชบอร์ด (now `SalesOverview`) · `รายการดีล` (kept,
  gated on `canViewDealPipeline`) · ค่าคอมมิชชัน (read-only) · แคตตาล็อก ·
  self-service.
- **Overview (`frontend/src/features/dashboard/SalesOverview.jsx`, NEW):** a
  personal cockpit —
  - Greeting + subtitle: "สวัสดี คุณ&lt;firstName&gt;" / "ดีลของฉัน ·
    &lt;Thai date&gt; — งานและลูกค้าที่ต้องติดตามวันนี้".
  - Pulse (`StatGrid`): `เกินกำหนดติดตาม` (overdue follow-up) ·
    `ติดตามวันนี้` (follow-up due today) · `รอออกใบเสนอราคา` (pricing
    requests at `APPROVED_FOR_QUOTATION`) · `มูลค่า pipeline` (Σ
    `amountPayable` across the rep's own `ACTIVE` deals).
  - "สิ่งที่ต้องทำ" worklist — one next-action CTA per deal, overdue-first,
    via a new shared helper `frontend/src/features/tickets/salesActions.js`
    (`nextSalesAction`/`sortWorklist`): a 5-bucket priority cascade (create
    PCR → issue quotation → confirm order → follow up → log activity), each
    row linking to `/tickets/:id`.
  - Right rail: "ค่าคอมเดือนนี้" (estimated commission + commissionable base,
    mirroring `features/commissions/commissionCalc.js` / `CommissionPage.jsx`'s
    own `monthlyTierSummary` — a read-only informational mirror, never
    authoritative) and "ติดตามที่ครบกำหนด" (deals due/overdue for follow-up,
    soonest first).
- **Data:** `api.tickets.list({})` (own-scoped server-side) +
  `api.pricingRequests.queue({})` (own-scoped server-side, same viewer-role
  gate Import's Overview uses) + `api.commissions.list({ payrollMonth })`.
  **Backend: none** — every field consumed already exists on these three
  endpoints' response shapes.
- **Pipeline tweak (`TicketListPage.jsx`):** owner feedback — the LIFECYCLE
  and FLAGS chip rows were competing with the deal list for attention. Both
  now sit behind a single collapsed "ตัวกรองเพิ่มเติม" expander (closed by
  default, auto-opens if a filter from that group is already active via URL
  params). All existing filter functionality is unchanged — this is a
  visual-prominence change only, not a functional one. Applies to every role
  that renders `TicketListPage` (sales, sales_manager, ceo, import, account)
  since it is one shared component; the primary view for every role now leads
  with the phase cards + deal list (+ the `ต้องดำเนินการ`/`ทั้งหมด` inbox
  toggle for import/account) instead of the lifecycle/flags rows.
- **Unchanged:** deal detail (`TicketDetailPage.jsx`), the commission page,
  the catalog — none of these were touched. `รายการดีล` stays in the nav for
  sales.
- **Verification:** frontend only, mock-driven (`VITE_USE_MOCKS=true`). No
  authorization was changed.

### Sales Manager — team cockpit *(shipped, branch: `feat/role-views-sales-manager`)*

**Landing:** `frontend/src/features/dashboard/ManagerOverview.jsx`, rendered
at `/` when `user.role === 'sales_manager'` (gated on `SALES_ENABLED`,
degrades to `EmployeeDashboard` when off, same as every other sales-gated
landing in this file). Manager keeps every existing nav item (`รายการดีล` /
คิวใบขอราคา / ค่าคอมมิชชัน / แคตตาล็อก) — this is only a new landing, nothing
here removes a route or a permission.

**Team pulse** (4 stat tiles): ยอดทีมเดือนนี้ (Σ `amountPayable` across deals
won this month) · ค่าคอมรออนุมัติ (commissions at `SUBMITTED`, the manager's
own review step) · ดีลต้องดูแล (open deals that are stale/overdue/past their
follow-up date) · pipeline ทีม (Σ `amountPayable` across open team deals).

**Centerpiece — "ค่าคอมรออนุมัติ" worklist:** one row per `SUBMITTED`
commission record, each with a "ตรวจ · อนุมัติ" CTA that deep-links to
`/commissions` (`CommissionPage`'s existing manager-approve flow) — the
Overview never calls the approve endpoint itself, consistent with this
program's "never duplicate a mutation" rule (§2).

**"ดีลทีมที่ต้องดูแล" worklist:** open deals flagged `stale`, `overdue`, or
with a `nextFollowUpAt` already in the past, overdue-payment first then
longest-untouched first, each linking to `/tickets/:id`.

**Right rail:** team leaderboard (top 5 reps by amount closed this month) and
close-rate (won / (won + lost) this period).

**Data:** `api.tickets.list({})` + `api.commissions.list({ status:
'SUBMITTED' })`, both filtered/derived client-side (no server-side status
filter exists on `GET /api/commissions` — mirrors `CommissionController#list`,
same pattern `CommissionPage`'s own `totals.submitted` already uses).
**Backend: none.**

**Documented data gap:** `api.tickets.list({})` currently returns every
ticket for `sales_manager` in the mock (same as `ceo` — `mockApi.js`'s
`tickets.list` only narrows the response for `sales`/`import`/`account`), so
this view treats the response as "team" scope per the plan, matching how
`TicketDashboard` already consumes this same endpoint for this role today. If
the real `TicketRepository.appendRoleScope` narrows `sales_manager` to their
own division/team, this component needs no change — only the row count moves.
No authorization was loosened or tightened by this branch. There is also no
sales-target/quota field anywhere in the data model (mock or Java DTO), so
"ยอดทีมเดือนนี้" renders as a plain ฿ total rather than a target-progress bar
— no target number exists to measure against, and inventing one would be
fabricating business data.

**Verification:** frontend only, mock-driven (`VITE_USE_MOCKS=true`,
`ManagerOverview.test.jsx`). No authorization was changed.

### CEO — exec cockpit *(shipped, branch: `feat/role-views-ceo`)*

CEO **keeps every existing nav item, route, and permission unchanged** — full company pipeline, all
sales surfaces, ceo-settings. The only new surface is the `/` landing: `CeoOverview`
(`frontend/src/features/dashboard/CeoOverview.jsx`), an exec cockpit that surfaces the cross-domain
decisions only the CEO can make, in one worklist, instead of the CEO hunting across ใบขอราคา /
รายการดีล / ค่าคอมมิชชัน / คำขอ / ลา separately.

**Approval model this Overview encodes** (owner-confirmed): the CEO is the *final* approver across
domains (price, close verification, commission — after the manager step), and the *direct* approver
for OT/leave when an employee's division has no manager in between (that employee's manager FK
points straight at the CEO).

**Exec pulse** (5 stat tiles, each deep-links to its queue): อนุมัติราคา · ตรวจปิดงาน · ค่าคอมรออนุมัติ ·
OT·ลา รออนุมัติ (combined) · ยอดขายเดือนนี้ (฿, informational).

**"รออนุมัติจากคุณ" cross-domain worklist** — one list, each row tagged by domain, entity, and a CTA
that deep-links (never mutates inline):

| Domain | Source | CEO-actionable when | CTA | Destination |
|---|---|---|---|---|
| ราคา | `api.pricingRequests.queue({activeOnly:true})` | `status` is `READY_FOR_CEO_REVIEW` or `CEO_REVIEWING` | ตั้งราคา (override-purple CTA) | `/pricing-requests/:id` |
| ปิดงาน | `api.tickets.list({})` | `lifecycle==='ACTIVE'`, `status!=='closed'`, `closeConfirmedAt` set (ฝ่ายบัญชี already confirmed) | ตรวจปิดงาน | `/tickets/:id` |
| ค่าคอม | `api.commissions.list({})` | `status==='MANAGER_APPROVED'` | อนุมัติ | `/commissions` |
| OT | `api.overtime.list({status})` | `MANAGER_APPROVED`, or `SUBMITTED` with `managerEmployeeId === CEO's own employeeId` | อนุมัติ | `/employee-requests?tab=ot` |
| ลา | `api.leave.list({status:'SUBMITTED'})` | `managerEmployeeId === CEO's own employeeId` | อนุมัติ | `/leave` |

**Why the OT/leave "manager-less division" derivation is real, not invented:** `OvertimeService`'s
`managesEmployee()` and `LeaveService`'s `canReviewEmployee()` both grant review rights via the
employee's stored `reports_to_employee_id` FK (`managerEmployeeId` on the DTO). An employee whose
division has no manager has that FK pointing straight at the CEO's own `employeeId` — so
"manager-less division routed to the CEO" and "the CEO is literally this employee's FK manager" are
the same real, server-enforced condition, not a UI-only guess. `canReviewLeave` has no
`MANAGER_APPROVED` middle step at all (unlike overtime/commission) — a CEO-reviewable leave request
*is*, by definition, one whose FK manager is the CEO.

**ผลบริษัทเดือนนี้ (company snapshot, right rail):** `/api/dashboard/summary` has no ฿ fields at all
(only ticket-status counts) — every ฿ figure here (ยอดขายปิดแล้ว, Pipeline บริษัท,
ลูกหนี้ค้าง/เกินกำหนด) is derived client-side from the CEO's own `tickets.list({})`, using the same
`amountPayable`/`amountPaid`/`amountOutstanding`/`overdue` fields `TicketDetailPage`'s money views
already trust (`derivePaymentFields`). ปิดงานแล้ว prefers the real
`dashboardSummary.tickets.closedThisMonth` over the client-derived count when the summary provides
it. กำลังพล / มาสายวันนี้ come straight from `dashboardSummary.headcount`/`.attendance`.

**ยอดตามทีม/ฝ่าย breakdown — stated gap:** grouped by **sales rep** (`createdByName`), not division.
Tickets carry no `divisionId`, and `GET /api/employees` (the only place a division mapping lives) is
hr-only — the CEO has no endpoint that returns a division breakdown of deals. Bars are sized
relative to this month's own top performer; **no % or target/quota is shown**, since no such field
exists anywhere in the data this component can reach.

**Mock-parity fix landed alongside this Overview (not a business-logic change):**
`frontend/src/api/mockApi.js`'s `tickets.list()` was missing `closeConfirmedAt`,
`closeConfirmedByName`, and `invoiceOnFile` — fields the real `TicketSummaryDto`
(`backend/.../ticket/TicketSummaryDto.java`) already returns on the **list** endpoint, not just the
single-ticket detail one. Without them, "which tickets are already confirmed by ฝ่ายบัญชี and
awaiting CEO verification" could only be answered with an N+1 detail fetch per ticket. Added the
three fields to the list projection so it matches the real DTO — this is CLAUDE.md's "mock shapes
are a faithful stand-in for the Spring backend" contract, not a scope/authz change (the endpoint's
authorization and row-scoping are untouched).

**Verification:** `VITE_USE_MOCKS=true` only (`frontend/src/features/dashboard/CeoOverview.test.jsx`,
9 tests). No authorization change — CEO already had every permission this Overview reads
(`routes.js`: `canViewTickets`/`canViewCommissions`/`canViewPricingRequestQueue`/
`canViewAllOvertime`/`canViewAllLeave` all already include `ceo`), so there is nothing to verify
against the real Java service here; **do** verify with the real backend before treating the
`dashboardAttendance` company-scope numbers as reliable — the mock hardcodes
`todayPresent`/`lateToday` to `0` for `hr`/`ceo` scope (a pre-existing mock stub unrelated to this
branch, not fixed here — out of scope for a frontend-only landing-page task).

### Import / Account

Not documented in this file yet — each ships (and fills in its own section
here) on its own branch (`feat/role-views-import`, `feat/role-views-account`),
per the plan's parallel per-role execution model.

## 4. Change log

| Date | Role | Branch | Summary |
| --- | --- | --- | --- |
| 2026-07-23 | HR | `feat/role-views-hr` | New `HrOverview.jsx` landing at `/` for `role==='hr'`; reconciled leave/OT to monitoring-only (no HR approve affordance); no authz/backend change. |
| 2026-07-23 | Sales | `feat/role-views-sales` | New `SalesOverview` landing + `salesActions.js` next-action helper; `TicketListPage` LIFECYCLE/FLAGS collapsed behind "ตัวกรองเพิ่มเติม". Frontend-only, no authz change. |
| 2026-07-23 | Sales Manager | `feat/role-views-sales-manager` | New `ManagerOverview.jsx` team-cockpit landing at `/` for `role==='sales_manager'` (commission-approval worklist as centerpiece, team pipeline pulse, leaderboard). No nav, route guard, or permission changes. |
| 2026-07-23 | CEO | `feat/role-views-ceo` | New `CeoOverview.jsx` cross-domain exec-cockpit landing at `/` for `role==='ceo'` (guarded by `SALES_ENABLED`, degrades to `EmployeeDashboard` when off); `tickets.list()` mock-parity fix (`closeConfirmedAt`/`closeConfirmedByName`/`invoiceOnFile`). No nav, route guard, or permission changes. |
