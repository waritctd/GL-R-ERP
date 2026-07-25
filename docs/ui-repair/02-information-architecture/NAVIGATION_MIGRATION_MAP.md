# Navigation Migration Map

Item-by-item bridge from today's nav (`AppShell.jsx:22-119`, `Sidebar.jsx`) to the
[INFORMATION_ARCHITECTURE](INFORMATION_ARCHITECTURE.md) proposal. **Every existing route is
preserved.** The "route change later?" column flags the (few) places a route *rename* would
help — none is required, and none happens in Phase 2 or 3.

> Rule from `UI_REPAIR_RULES.md`: routes, guards, and permissions do not change as a side
> effect of IA/UI work. Anything in the "route change later?" column that is more than a
> label is an explicit, separately-reviewed change with authz evidence — never smuggled in.

## Legend
**Job** = the user goal · **Route now** = current path · **Group now → proposed** ·
**Route change later?** = does the *path* need to move (vs. just relabel/regroup) ·
**Active-match** = how the sidebar highlights it · **Badge** · **Mobile rank** (per role).

---

## A. Every current nav item, mapped

| # | Item (TH / EN) | Job | Route now | Group now → proposed | Route change later? | Active-match | Badge | Mobile rank |
|---|---|---|---|---|---|---|---|---|
| 1 | แดชบอร์ด / Dashboard | See what needs me | `/` | ungrouped → **งานของฉัน** (relabel by role) | No (label only) | exact `/` (NavLink `end`) | **My-Work count** (new) | 1 |
| 2 | การอนุมัติ OT / Approve team OT | Approve team overtime | `/employee-requests` | team → **งานของฉัน** (div-mgr) | No | `['/employee-requests','/overtime']` | pending team OT | 1 (div-mgr) |
| 3 | การอนุมัติวันลา / Approve team leave | Approve team leave | `/leave` | team → **ทีมของฉัน** | No — but resolve the **div-mgr leave-review inconsistency** (authz, Phase 4) | `/leave` | — | 3 (div-mgr) |
| 4 | ทีมในฝ่าย / Team roster | Team attendance | `/attendance` | team → **ทีมของฉัน** | No | `/attendance` | — | 2 (div-mgr) |
| 5 | รายการดีล / Deal pipeline | Browse/advance deals | `/tickets` | sales → **ดีล** | No | `['/tickets','/ticket-overview']` | — | 2 (sales/mgr/ceo) |
| 6 | ตั้งค่าราคา / CEO price config | Price/FX config | `/ceo-settings` | sales → **ตั้งค่า (Administration)** | No | `/ceo-settings` | — | 7 (ceo) |
| 7 | แคตตาล็อกสินค้า / Product catalog | Look up products/prices | `/catalog` | sales → **ราคา & นำเข้า** | No | `/catalog` | — | 3–4 |
| 8 | นำเข้าราคา / Price import | Upload price lists | `/price-import` | sales → **ราคา & นำเข้า** | No | `/price-import` | — | 4 (import/ceo) |
| 9 | คิวใบขอราคา / Pricing request queue | Work PCRs | `/pricing-requests` | sales → **ราคา & นำเข้า** | No | `/pricing-requests` (+ `/pricing-requests/:id`) | PCRs needing me | 2 (import) |
| 10 | จัดซื้อ & นำเข้า / Procurement | Procure & fulfil | `/procurement` | sales → **คำสั่งซื้อ & ส่งมอบ** | No | `['/procurement','/factory-purchase-orders']` | POs to move | 3 (import) |
| 11 | ค่าคอมมิชชัน / Commissions | Commission records | `/commissions` | sales → **การเงิน** (sales/mgr/ceo); **removed for HR** (F-11) | No (label/scope only; HR removal is authz-adjacent — see below) | `/commissions` | commissions to approve (mgr/ceo) | 4–5 |
| 12 | งานการเงิน / Finance worklist | Money lifecycle | `/finance` | finance → **การเงิน** (account: primary) | No | `/finance` | overdue+pending (account) | 1 (account) |
| 13 | ภาพรวม HR / HR overview | HR landing | `/hr` | hr → **บุคคล & เวลาทำงาน** | No | `/hr` | — | — |
| 14 | พนักงานทั้งหมด / Employees | Manage employees | `/employees` | hr → **บุคคล & เวลาทำงาน** | No | `/employees` (+ `/employees/:id`) | — | 3 (hr) |
| 15 | คำขอแก้ไขข้อมูล / Profile requests | Review profile changes | `/requests` | hr → **บุคคล & เวลาทำงาน** (inbox, lead) | No | `/requests` | **pendingRequestCount** (exists) | 1 (hr) |
| 16 | เงินเดือน / Payroll | Run payroll | `/payroll` | finance → **บุคคล & เวลาทำงาน** | No | `/payroll` | — | 4 (hr) · desktop-only |
| 17 | เวลาทำงาน / Attendance | My attendance | `/attendance` | self → **บุคคล & เวลาทำงาน** | No | `/attendance` | — | 2 (self) |
| 18 | คำขอ / Requests (OT+welfare) | My OT/welfare | `/employee-requests` | self → **บุคคล & เวลาทำงาน** | No | `['/employee-requests','/overtime']` | — | 3 (self) |
| 19 | วันลา / Leave | My leave | `/leave` | self → **บุคคล & เวลาทำงาน** | No | `/leave` | — | 4 (self) |

### Per-role framing of items 18/19 — self-service vs. oversight (business rule)
The **same routes** `/employee-requests` and `/leave` are framed differently by viewer, and
the code already supports this via `canViewAllOvertime`/`canViewAllLeave` (`['hr','ceo']`) and
the nav `show:` condition `!!user.employeeId || canViewAll…` (`AppShell.jsx:109-118`):

| Viewer | Framing | Label | Actions on the page |
|---|---|---|---|
| employee / warehouse / qc / division-mgr (own) | **Self-service** — submit & track own | คำขอ / วันลา | submit, cancel own |
| division manager (team) | **Team approval** | การอนุมัติ OT / วันลา | hop-1 approve (OT/SM); leave = direct-reports only (caveat) |
| **HR** | **Oversight summary** (all employees' history) | **ภาพรวม OT / ภาพรวมวันลา** | OT: view-only (cannot approve — 403); leave: view + rare `SUBMITTED` review |
| **CEO** | **Oversight summary** (all employees' history) | **ภาพรวม OT / ภาพรวมวันลา** | OT: CEO hop-2 approvals actionable within; leave: view-only |

No route change (same paths); this is a **label + page-framing** difference by role, driven by
permissions that already exist. HR/CEO do not submit their own OT/leave and their own don't
need approval — so the surface leads with an all-employee summary/history, not a request form.

### Notable consolidations
- **Items 5 duplicate destinations (F-15):** the sales landing's "ดูรายการดีลทั้งหมด" link
  and the `/tickets` vs `/ticket-overview` tabs are one workspace — keep the single **ดีล**
  nav item with `match:['/tickets','/ticket-overview']` (already the case); remove the
  redundant landing shortcut in Phase 4.
- **Items 3/4 (team) vs 17/18/19 (self):** today `/attendance`, `/leave`,
  `/employee-requests` each appear **twice** for a division manager (team group + self
  group). Proposed: team-facing copies live under **ทีมของฉัน** / **งานของฉัน**; the
  self copies under **บุคคล & เวลาทำงาน**. Same route, two jobs, two groups — but never the
  *same job twice* for one viewer.
- **Item 11 for HR:** HR currently sees ค่าคอมมิชชัน but has no list access (F-11). Proposed:
  drop the item for HR. The nav condition today is `hasPermission('canViewCommissions') && …
  && role !== 'account'` (`AppShell.jsx:94`) — adding `&& role !== 'hr'` (or gating on
  `canListCommissionRecords`) is a **nav-permission mapping change**; per the rules it must
  be recorded and, since it touches who-sees-what, verified against the Java service, not
  treated as cosmetic. HR keeps `/payroll-ready` access (that's a different, correct gate).

---

## B. Routes preserved (no path change, ever, in this effort)

All 24 route registrations in `App.jsx` stay exactly as-is. The IA only changes **grouping,
labels, ordering, and which landing leads** — never the URL. Explicitly preserved:

`/`, `/hr`, `/employees`, `/employees/:id`, `/requests`, `/my-requests`(→`/profile`),
`/profile`, `/employee-requests`, `/overtime`(→`/employee-requests?tab=ot`), `/leave`,
`/payroll`, `/ticket-overview`, `/tickets`, `/tickets/:id`,
`/tickets/:ticketId/deposit`, `/pricing-requests`, `/pricing-requests/:id`,
`/commissions`, `/finance`, `/price-import`, `/ceo-settings`,
`/factory-purchase-orders`, `/factory-purchase-orders/:id`, `/procurement`, `/catalog`,
`/attendance`, and `*`→`/`.

## C. Deep links & params that MUST remain compatible

Any nav change must not break these (all verified in code):

| Deep link / param | Why it matters | Source |
|---|---|---|
| `/my-requests` → `/profile` (replace) | old notification links | `App.jsx:318`, guard `permissions.js:71` |
| `/overtime` → `/employee-requests?tab=ot` (replace) | `OvertimeService` emails hardcode `/overtime` | `App.jsx:338` |
| `?tab=` on `/employee-requests` | OT vs welfare tab in URL | `AppShell.jsx:106-108` |
| `/commissions?ticketId=NN` | account's finance worklist deep-links the record-invoice flow (account has route access, no list access) | `AppShell.jsx:90-93` |
| `/tickets` list filters + search in query string | preserved on detail round-trip via `navigate(-1)` | `App.jsx:64-70` |
| `/tickets/:ticketId/deposit` back → `/tickets/${ticketId}` | deposit page nav | `App.jsx:75-87` |
| `/pricing-requests/:id` | sales can reach their own PCR from a notification | `App.jsx:371`, guard `permissions.js:104` |
| `/factory-purchase-orders/:id` | raw PO detail deep-link kept even though `/procurement` is the combined page | `App.jsx:396` |
| **Known bug to fix in this work:** special-money notification emails link `/requests` (HR queue) instead of `/employee-requests` | wrong landing for the employee | `RequestsPage.jsx:19-25` |

The special-money deep-link bug is the one deep-link that *should* change — it's a defect,
not a contract. Fixing it is a copy/link change in the notification builder (backend
`SpecialMoneyService`), so it's outside a pure-UI PR; flag it to the owning workflow, or fix
as an explicit small change with a note (not a silent UI edit).

## D. Active-navigation matching behaviour (keep as-is)

`isItemActive` (`Sidebar.jsx:19-23`) already does: exact match OR prefix (`path + '/'`) OR
any `match[]` entry exact/prefix. The proposal reuses it verbatim. Items needing a `match`
array (so a detail route highlights its parent): `/tickets`→`['/tickets','/ticket-overview']`,
`/procurement`→`['/procurement','/factory-purchase-orders']`, `/employee-requests`→
`['/employee-requests','/overtime']`. `/pricing-requests`, `/employees`, `/commissions`,
`/finance` rely on prefix-match for their `:id` detail routes — no `match` needed. `/`
stays exact-only (NavLink `end`) so it doesn't light up everywhere.

## E. Migration order (for Phase 4, recorded — not executed here)

1. Relabel + regroup only (no route touch) — pure `navItems`/`NAV_GROUPS` data change.
2. Promote role worklists to "งานของฉัน" leads (depends on WORK_STATE_MODEL classifier).
3. Remove HR ค่าคอมมิชชัน item (authz-adjacent — needs the evidence step).
4. Fix the special-money deep-link (backend copy change — separate PR).
5. Retire the redundant sales landing "view all deals" shortcut.

Nothing above ships before Phase 4; this is the sequence, not a to-do for now.
