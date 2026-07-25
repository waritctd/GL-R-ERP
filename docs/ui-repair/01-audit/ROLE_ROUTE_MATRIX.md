# Role → Route Matrix

Per-role navigation and route access for the GL-R ERP frontend, derived from code
(`frontend/src/App.jsx`, `frontend/src/app/permissions.js`,
`frontend/src/api/routes.js` `ROLE_PERMISSIONS`, `frontend/src/components/layout/AppShell.jsx`
nav model + `Sidebar.jsx` groups) and **empirically confirmed** by live capture
(`../evidence/current/<role>/…` — every accessible route rendered with no
unexpected redirect; denied paths captured under `<role>/denied-probe/`).

> ⚠️ **Authorization is UI-level only.** These are frontend guards
> (`canAccessPath` / `ROLE_PERMISSIONS`), the mock's approximation of the Java
> services. Per `CLAUDE.md`, permission truth lives in the backend — nothing here
> is a verified enforcement claim.

## Personas captured

| Role | Persona (mock) | employeeId | Landing component |
|------|----------------|-----------|-------------------|
| ceo | ceo@glr.co.th | 1 | CeoOverview |
| hr | hr@glr.co.th | 21 | HrOverview |
| sales | sales@glr.co.th | **null** | SalesOverview |
| sales_manager | sales.manager@glr.co.th | 2 | ManagerOverview |
| import | import@glr.co.th | **null** | ImportOverview |
| account | account@glr.co.th | **null** | AccountOverview |
| employee (plain) | employee@glr.co.th | 9 | EmployeeSelfService |
| division_manager | warehouse.manager@glr.co.th (role `employee` + manager) | 5 | DivisionManagerOverview |
| **warehouse** | — | — | *not seeded (gap)* |
| **qc** | — | — | *not seeded (gap)* |

**Seed caveat:** `sales`, `import`, `account` personas have `employeeId: null`, so
self-service routes gated on `employeeId` (`/profile`, `/leave`,
`/employee-requests`) are unreachable for them **in this seed** — a data artifact,
not a permission rule (real reps would be linked to an employee row). This also
triggers a visible **"User is not linked to an employee"** error toast on the
sales deal pages (see UI_AUDIT F-16).

## Nav groups (sidebar)

`งานขาย` (Sales) · `บุคคล` (HR) · `การเงิน` (Finance & Payroll) · `ทีมของฉัน` (Team,
division-manager only) · `บุคคลของฉัน` (Self-service). `แดชบอร์ด` (`/`) is ungrouped
at top. Groups are collapsible; `/profile` lives in the topbar UserMenu, not the
sidebar.

## Nav items visible per role

Legend: ✅ nav item · 🔗 reachable but **deep-link/tab only** (no sidebar item) ·
🚫 denied (silent redirect to `/`, see F-03) · — n/a.

| Route (nav label) | ceo | hr | sales | sales_mgr | import | account | employee | div_mgr |
|---|---|---|---|---|---|---|---|---|
| `/` แดชบอร์ด | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/tickets` รายการดีล | ✅ | 🚫 | ✅ | ✅ | 🔗detail | 🔗detail | 🚫 | 🚫 |
| `/ticket-overview` (tab of รายการดีล) | 🔗 | 🚫 | 🔗 | 🔗 | 🚫 | 🚫 | 🚫 | 🚫 |
| `/pricing-requests` คิวใบขอราคา | ✅ | 🚫 | 🚫 | ✅ | ✅ | 🚫 | 🚫 | 🚫 |
| `/pricing-requests/:id` | 🔗 | 🚫 | 🔗(own) | 🔗 | 🔗 | 🚫 | 🚫 | 🚫 |
| `/procurement` จัดซื้อ & นำเข้า | ✅ | 🚫 | 🚫 | 🚫 | ✅ | 🚫 | 🚫 | 🚫 |
| `/factory-purchase-orders(/:id)` | 🔗 | 🚫 | 🚫 | 🚫 | 🔗 | 🚫 | 🚫 | 🚫 |
| `/catalog` แคตตาล็อกสินค้า | ✅ | 🚫 | ✅ | ✅ | ✅ | ✅ | 🚫 | 🚫 |
| `/price-import` นำเข้าราคา | ✅ | 🚫 | 🚫 | 🚫 | ✅ | 🚫 | 🚫 | 🚫 |
| `/ceo-settings` ตั้งค่าราคา | ✅ | 🚫 | 🚫 | 🚫 | 🚫 | 🚫 | 🚫 | 🚫 |
| `/commissions` ค่าคอมมิชชัน | ✅ | ✅ | ✅ | ✅ | 🚫 | 🔗(no nav) | 🚫 | 🚫 |
| `/finance` งานการเงิน | ✅ | 🚫 | 🚫 | 🚫 | 🚫 | ✅ | 🚫 | 🚫 |
| `/hr` ภาพรวม HR | 🚫 | ✅ | 🚫 | 🚫 | 🚫 | 🚫 | 🚫 | 🚫 |
| `/employees(/:id)` พนักงานทั้งหมด | 🚫 | ✅ | 🚫 | 🚫 | 🚫 | 🚫 | 🚫 | 🚫 |
| `/requests` คำขอแก้ไขข้อมูล | 🚫 | ✅ | 🚫 | 🚫 | 🚫 | 🚫 | 🚫 | 🚫 |
| `/payroll` เงินเดือน | 🚫 | ✅ | 🚫 | 🚫 | 🚫 | 🚫 | 🚫 | 🚫 |
| `/attendance` เวลาทำงาน / ทีมในฝ่าย | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `/employee-requests` คำขอ / การอนุมัติ OT | ✅ | ✅ | 🚫seed | ✅ | 🚫seed | 🚫seed | ✅ | ✅ |
| `/leave` วันลา / การอนุมัติวันลา | ✅ | ✅ | 🚫seed | ✅ | 🚫seed | 🚫seed | ✅ | ✅ |
| `/profile` (topbar UserMenu) | 🔗 | 🔗 | 🚫seed | 🔗 | 🚫seed | 🚫seed | 🔗 | 🔗 |

`🚫seed` = denied only because the mock persona has `employeeId: null`; the rule is
"has a linked employee OR view-all permission", not a role exclusion.

## Per-role notes (from live evidence)

- **CEO** — widest nav (12 items across all groups except HR-admin). Landing
  `CeoOverview`: 5 metric cards **all zero** for this seed + empty "รออนุมัติจากคุณ".
  Primary queue: approvals/pricing decisions. *Landing communicates required work
  poorly in the empty state* — reads as "nothing to do" with no onboarding cue.
  Evidence: `ceo/landing`.
- **Account** — nav: dashboard, catalog, **งานการเงิน**, attendance. Landing
  `AccountOverview` is a **model operations desk**: ฿240,000 overdue (red), a
  "สิ่งที่ต้องทำ" worklist with per-row next-action buttons (ติดตามชำระ / รับชำระส่วนที่เหลือ),
  ownership + month summary. `/commissions` reachable but deliberately **no nav item**
  (deep-link only, for the record-invoice step). Evidence: `account/landing`.
- **Import** — nav: dashboard, catalog, price-import, pricing-request queue,
  procurement, attendance. Landing `ImportOverview`: 6-cell status row + worklist
  (บันทึกส่งมอบ / ส่ง IR) + คิวของฉัน. Strong next-action clarity. Evidence: `import/landing`.
- **Sales** — nav: dashboard, deal pipeline, catalog, commissions, attendance.
  Landing `SalesOverview`. `/tickets` shows the 14-stage pipeline. No self-service
  nav (seed `employeeId: null`). Evidence: `sales/tickets`.
- **Sales Manager** — like sales + pricing-request queue; oversight (read/comment)
  role. Has self-service (employeeId 2).
- **HR** — nav: dashboard, **commissions** (surprising — see F-11), บุคคล group
  (HR overview, employees, profile-requests w/ badge), payroll, self-service.
  Landing `HrOverview`. Primary queue: profile-request review + payroll.
- **Employee (plain)** — minimal nav: dashboard + self-service (attendance,
  requests, leave). Landing `EmployeeSelfService` — attendance prompt, leave/OT
  balances, own request statuses with routing ("ส่งแล้ว › หัวหน้าฝ่าย › CEO"). Best
  next-action clarity of all landings. Evidence: `employee/landing`.
- **Division Manager** — role `employee` + manager flag; adds the `ทีมของฉัน`
  group (approve team OT/leave, team attendance). Confirmed reachable via
  `warehouse.manager@glr.co.th`. Evidence: `division_manager/landing`.

## Cross-cutting observations (→ UI_AUDIT)

1. **Permission-denied is a silent redirect to `/`** for every role probed
   (employee→/payroll, sales→/payroll, hr→/finance, import→/finance,
   account→/payroll all landed on `/` with no message). No 403/"not authorized"
   screen. → F-03. Evidence: `*/denied-probe/`.
2. **`/commissions` is nav-visible to HR** (`canViewCommissions` includes hr) but
   HR has **no** `canListCommissionRecords` — the list read is a different gate, so
   the page may render empty/limited for HR. → F-11.
3. **Same route, different nav identity across roles** (`/attendance` = "เวลาทำงาน"
   for self vs "ทีมในฝ่าย" for a manager; `/employee-requests` = "คำขอ" vs "การอนุมัติ OT";
   `/leave` = "วันลา" vs "การอนุมัติวันลา") — deliberate framing, documented here so it
   isn't mistaken for duplication.
