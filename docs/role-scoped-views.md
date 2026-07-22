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

### Import — ops *(shipped, branch: `feat/role-views-import`)*

**Nav.** แดชบอร์ด (its own Overview) · คิวใบขอราคา · **จัดซื้อ & นำเข้า** (combined procurement +
fulfilment, `/procurement`) · แคตตาล็อก · นำเข้าราคา · self-service. `รายการดีล` removed — Import's
job is procurement → delivery on deals already in its worklist, not browsing the full pipeline
(`canViewDealPipeline` excludes `import`; `canViewTickets` still includes it for ticket-detail deep
links).

**Overview (`frontend/src/features/dashboard/ImportOverview.jsx`).**
- **Conveyor pulse** — six clickable count tiles: `เกินกำหนด` (overdue, cross-cutting — any in-scope
  deal with `ticket.overdue`) · `ตั้งราคา` (pricing requests still in flight toward a price) ·
  `จัดซื้อ/IR` · `ขนส่ง` · `รับเข้าคลัง` (teal — DESIGN.md's "live" accent, the actionable-now stage) ·
  `ส่งมอบ`. Clicking a tile filters the worklist below to that bucket; clicking again (or
  "ล้างตัวกรอง") clears it.
- **"สิ่งที่ต้องทำ" worklist** — one row per in-scope deal with a real next action, overdue-first,
  each row's CTA sourced from `nextImportAction(ticket, pricingRequestsForTicket)`
  (`frontend/src/features/tickets/importActions.js`) and deep-linking to `/tickets/:id` (or
  `/pricing-requests` for the pickup step).
- **"คิวของฉัน"** — two shortcut cards to `/pricing-requests` and `/procurement` with live counts.
- **"กำลังขนส่ง"** — a small tracker of deals whose `fulfillmentStatus` is `IR_SENT` / `SHIPPING` /
  `CUSTOMS_CLEARANCE` (physically in transit).

**`nextImportAction` mapping** (shared with `DealFulfilmentPanel`'s `can.*` gates — never a second
source of truth for "what stage is this deal at"):

| Condition | `code` | Label | Deep-link |
| --- | --- | --- | --- |
| Ticket has an unpicked (`SUBMITTED`) pricing request | `pickupPricingRequest` | รับงาน · ขอราคา | `/pricing-requests` |
| `status=quotation_issued`, `fulfillmentStatus=null` | `issueImportRequest` | ออก IR | `/tickets/:id` |
| `fulfillmentStatus=IR_ISSUED` | `markIrSent` | ส่ง IR | `/tickets/:id` |
| `fulfillmentStatus=IR_SENT` | `markShipping` | บันทึกออกเดินทาง | `/tickets/:id` |
| `fulfillmentStatus=SHIPPING` | `markGoodsReceived` | ยืนยันรับเข้าคลัง | `/tickets/:id` |
| `fulfillmentStatus` ∈ `{GOODS_RECEIVED, FROM_STOCK, PARTIALLY_DELIVERED}` | `recordDelivery` | บันทึกส่งมอบ | `/tickets/:id` |
| none of the above | `null` (no worklist row) | — | — |

**`/procurement` (`ProcurementFulfilmentPage.jsx`)** — section 1 is the same fulfilment worklist
(via `nextFulfilmentActionCode`, the fulfilment-only half of the table above — no pricing pickup,
that's `/pricing-requests`'s job), section 2 reuses `ProcurementListPage` wholesale (the raw
per-factory PO list, Import/CEO only). `/factory-purchase-orders/:id` stays registered for detail
deep-links from section 2's rows.

**Files changed:** `frontend/src/App.jsx`, `frontend/src/api/routes.js`, `frontend/src/app/permissions.js`,
`frontend/src/components/layout/AppShell.jsx`, `frontend/src/features/sales/SalesTabs.jsx`,
`frontend/src/features/tickets/DealFulfilmentPanel.jsx`. New:
`frontend/src/features/tickets/importActions.js`, `frontend/src/features/dashboard/ImportOverview.jsx`,
`frontend/src/features/procurement/ProcurementFulfilmentPage.jsx`.

**Backend:** none. Every count/row is derived client-side from `api.tickets.list({})` (already
import-scoped server/mock side — `importListScopeIncludes`) and
`api.pricingRequests.queue({activeOnly:true})` (already role-scoped —
`PRICING_REQUEST_VIEWER_ROLES` + the draft-privacy filter). No `DashboardSummaryDto` change.

**Authz evidence:** no authz change — this PR only narrows a frontend presentation surface
(`canViewDealPipeline` vs the unchanged `canViewTickets`); the backend audience for every endpoint
Import calls is unmoved. See `TicketScopeIntegrationTest` (backend) for the existing real-DB
coverage of Import's list/detail scope, which this PR does not touch.

**Integration note:** Import's branch was built off an older base and temporarily included `account`
in `canViewDealPipeline` (so Account wasn't left without a landing mid-flight). At integration,
Account shipped its own `AccountOverview` landing in the same batch, so `account` was dropped from
`canViewDealPipeline` — the reconciled final list is `['sales', 'sales_manager', 'ceo']` (see the
Account section below and §1).

### Account — money *(shipped, branch: `feat/role-views-account`)*

**Job:** confirm deposits received, collect final payment, confirm
close-ready, chase overdue balances, and at close record the tax invoice
which auto-creates the rep's commission. Account is **not** a commission
console — it has no `GET /api/commissions` list route
(`canListCommissionRecords` excludes it; mirrors
`CommissionController`'s `PreAuthorize`), cannot approve/edit/manually create
commissions. Its only commission action is the single create-from-deal step
at close (`canCreateCommissionFromDeal: ['account']`, mirrors
`CommissionService.CREATE_FROM_DEAL_ROLES`).

### Nav

- `แดชบอร์ด` → `AccountOverview` (money Overview, landing).
- `งานการเงิน` → `/finance` → `AccountFinancePage` (full money-lifecycle
  worklist). Gated `canConfirmPayments && SALES_ENABLED` (account/ceo — mirrors
  `TicketService.ACCOUNT_ROLES`).
- `รายการดีล` (deal-pipeline browser) — **removed** for account. `routes.js`'s
  `canViewDealPipeline` is `['sales', 'sales_manager', 'ceo']`; account is
  deliberately excluded (this is the branch that removes it — earlier
  role-views branches, if merged first, may have shipped
  `canViewDealPipeline` including `account` as a temporary hold so account
  wasn't stranded before its own branch landed; this branch's version, which
  excludes account, is the one to keep at integration).
- `ค่าคอมมิชชัน` — **hidden from account's nav** (its one action folds into
  งานการเงิน as the final close step), but the **route stays reachable**
  (`canViewCommissions` still includes account) so งานการเงิน's commission row
  can deep-link `/commissions?ticketId=NN` (the existing
  `CommissionPage.jsx` create-from-deal flow, unchanged).
- `แคตตาล็อก`, self-service — unchanged (`canViewCatalog` already includes
  account).

### Route guards (`app/permissions.js` `PATH_GUARDS`)

- `/tickets` (exact), `/ticket-overview` → `canViewDealPipeline` (account
  blocked).
- `/tickets/:id` (`startsWith('/tickets/')`) → `canViewTickets` (account
  allowed — worklist rows deep-link here).
- `/finance` → `canConfirmPayments` (account/ceo).
- Legacy `allowedRoute()` twins kept in sync: `route === 'tickets'` /
  `'ticket-dashboard'` → `canViewDealPipeline`; `route === 'ticket-detail'` →
  `canViewTickets`; `route === 'finance'` → `canConfirmPayments`.

### `nextAccountAction(ticket)` — single source of truth

`frontend/src/features/tickets/accountActions.js`. Factored out of the
account-only gates already in `DealDepositPanel.jsx` (step 3, "รับชำระมัดจำ")
and `TicketDetailPage.jsx` (`can.confirmFinalPayment` / `can.confirmClose`),
so `AccountOverview` and `AccountFinancePage` never invent a second copy of
"what does account do next on this deal." Priority order (overdue-first):

| Condition (mirrors the backend gate)                                                    | Action key                | Label                        | Target                          |
|-------------------------------------------------------------------------------------------|----------------------------|-------------------------------|----------------------------------|
| Overdue balance (`overdue && amountOutstanding > 0`)                                       | `chaseOverdue`             | ติดตามชำระ                    | `/tickets/:id`                   |
| `status=quotation_issued && paymentStatus=DEPOSIT_NOTICE_ISSUED`                           | `confirmDeposit`           | ยืนยันรับมัดจำ                | `/tickets/:id`                   |
| Final payment due (`canConfirmFinalPaymentNow`: `AWAITING_FINAL_PAYMENT` / `DEPOSIT_PAID` / deposit-bypassed) | `confirmFinalPayment`      | รับชำระส่วนที่เหลือ            | `/tickets/:id`                   |
| Close-ready (`canConfirmClose`/`requireClosePrerequisites`: fully paid + fully delivered [+ invoice on file] + not yet confirmed) | `confirmCloseReady`        | ยืนยันพร้อมปิดงาน              | `/tickets/:id`                   |
| `salesStage=CLOSED_PAID`                                                                    | `recordInvoiceCommission`  | บันทึกใบกำกับ + ออกค่าคอม       | `/commissions?ticketId=NN`       |

Also exports `accountMoneyBucket(ticket)` for the Overview's five money-pulse
buckets (same priority order, one bucket key per row above plus a bare
`overdue`/`depositPending`/`finalPaymentPending`/`closeReady`/
`commissionPending` naming).

### KNOWN DATA GAP — close-ready / CLOSED_PAID buckets read empty today

Account's server-side ticket-list scope
(`TicketRepository.appendRoleScope` / mock's `accountListScopeIncludes`) only
ever returns deals with a payment action pending
(`DEPOSIT_NOTICE_ISSUED`/`AWAITING_FINAL_PAYMENT`) or an overdue balance —
**never** a close-ready or `CLOSED_PAID` deal, since both have
`amountOutstanding = 0` by definition and neither condition can be true. That
scope was a deliberate, reviewed authz decision
(`docs/agent-handoffs/100_feat-sales-role-scoped-views.md`), not a bug this
branch introduces, and per CLAUDE.md this branch must never loosen it.

Consequence: the "รอปิดงาน" (close-ready) and "ออกค่าคอม" (`CLOSED_PAID`)
buckets/worklist rows will read empty under the current backend scope even
when such deals exist. `AccountOverview`/`AccountFinancePage` additionally
query `api.tickets.list({ salesStage: 'CLOSED_PAID' })` (mirrors
`CommissionPage.jsx`'s own pre-existing `eligibleTickets` picker) so the
commission bucket becomes correct for free if that scope is ever widened —
but under the scope as it stands today, that query also returns nothing for
account. **Follow-up decision needed from the plan owner**: either widen
account's list scope to include close-ready/`CLOSED_PAID` deals (an authz
change requiring the full evidence pipeline — unit + real-DB integration test
+ wrong-way-round cases + mutation-check, per CLAUDE.md), or accept that these
two buckets are visually present but structurally empty until a deal is
looked up by ticket ID directly (single-ticket detail read is **not** scoped
this way — `get(id)` has no such restriction for account).

### Files

- `frontend/src/features/tickets/accountActions.js` (new) — `nextAccountAction`,
  `accountMoneyBucket`.
- `frontend/src/features/tickets/accountActions.test.js` (new).
- `frontend/src/features/dashboard/AccountOverview.jsx` (new) — landing.
- `frontend/src/features/dashboard/AccountOverview.test.jsx` (new).
- `frontend/src/features/finance/AccountFinancePage.jsx` (new) — `/finance`.
- `frontend/src/features/finance/AccountFinancePage.test.jsx` (new).
- `frontend/src/api/routes.js` — added `canViewDealPipeline`.
- `frontend/src/app/permissions.js` — split `/tickets` vs `/tickets/:id`
  guards, added `/finance` guard, synced `allowedRoute()`.
- `frontend/src/components/layout/AppShell.jsx` — `รายการดีล` gated on
  `canViewDealPipeline`; `ค่าคอมมิชชัน` hidden for `role === 'account'`; new
  `งานการเงิน` nav item.
- `frontend/src/components/layout/AppShell.test.jsx` (new).
- `frontend/src/App.jsx` — lazy-loads `AccountOverview`/`AccountFinancePage`;
  `/` route branches `role === 'account' && SALES_ENABLED` to
  `AccountOverview`; new `/finance` route.
- `frontend/src/features/sales/SalesTabs.jsx` — pipeline tabs
  (`ดีลทั้งหมด`/`ภาพรวม`) gated on `canViewDealPipeline` (defensive; account is
  already route-guarded off both pages, so this never actually renders for
  it, but the gate is there too).
- `frontend/src/api/mockApi.js` — `tickets.list()` row projection gained
  `closeConfirmedAt`/`invoiceOnFile` (present on the real
  `TicketSummaryDto` for `list()` same as `get()`, but missing from the mock's
  list row before this branch — a pre-existing DTO-parity gap, fixed here
  because `nextAccountAction` needs both fields from list rows).
- `frontend/src/api/queryKeys.js` — added `ticketListBySalesStage`.

## 4. Change log

| Date | Role | Branch | Summary |
| --- | --- | --- | --- |
| 2026-07-23 | HR | `feat/role-views-hr` | New `HrOverview.jsx` landing at `/` for `role==='hr'`; reconciled leave/OT to monitoring-only (no HR approve affordance); no authz/backend change. |
| 2026-07-23 | Sales | `feat/role-views-sales` | New `SalesOverview` landing + `salesActions.js` next-action helper; `TicketListPage` LIFECYCLE/FLAGS collapsed behind "ตัวกรองเพิ่มเติม". Frontend-only, no authz change. |
| 2026-07-23 | Sales Manager | `feat/role-views-sales-manager` | New `ManagerOverview.jsx` team-cockpit landing at `/` for `role==='sales_manager'` (commission-approval worklist as centerpiece, team pipeline pulse, leaderboard). No nav, route guard, or permission changes. |
| 2026-07-23 | CEO | `feat/role-views-ceo` | New `CeoOverview.jsx` cross-domain exec-cockpit landing at `/` for `role==='ceo'` (guarded by `SALES_ENABLED`, degrades to `EmployeeDashboard` when off); `tickets.list()` mock-parity fix (`closeConfirmedAt`/`closeConfirmedByName`/`invoiceOnFile`). No nav, route guard, or permission changes. |
| 2026-07-23 | Import | `feat/role-views-import` | New `ImportOverview.jsx` landing + `/procurement` (`ProcurementFulfilmentPage.jsx`); `รายการดีล` removed from Import's nav. Introduced the `canViewDealPipeline` vs `canViewTickets` split (pipeline browser vs ticket-detail read). No backend change. |
| 2026-07-23 | Account | `feat/role-views-account` | New `AccountOverview.jsx` landing + `/finance` (`AccountFinancePage.jsx`); `รายการดีล` and `ค่าคอมมิชชัน` removed from Account's nav (commission action folds into งานการเงิน). Reconciled `canViewDealPipeline` to exclude `account` (Import's temporary hold dropped). No backend change. |
