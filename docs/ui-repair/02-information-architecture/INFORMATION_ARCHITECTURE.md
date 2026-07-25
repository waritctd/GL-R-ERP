# Information Architecture — navigation around work, not modules

Today's sidebar is organised by **database module** (งานขาย / บุคคล / การเงิน / ทีมของฉัน /
บุคคลของฉัน) and hardcoded per role in one `navItems` array (`AppShell.jsx:22-119`) with
five groups (`Sidebar.jsx:8-17`). This proposal reorganises around **the work a role does**,
keeps every existing route, and does not force any role to see every group.

**Constraint honoured:** this is an IA *proposal*. No route, guard, or permission changes
in Phase 2. The mapping to real routes and the "route change needed later?" column live in
[`NAVIGATION_MIGRATION_MAP.md`](NAVIGATION_MIGRATION_MAP.md).

## Design principles (from the design law + Phase-1 audit)

1. **Lead with "my work," not a module list.** Every role that has a queue starts at what
   needs them (work-state 1/4/8), reusing the account/import worklist idiom that already
   works well (F-04/F-05).
2. **One destination, one control.** Kill the duplicate nav entries where they don't earn
   their place (F-15). Where the *same* route is a deliberately different job for a
   different role (`/attendance` = self vs team), keep it but label it by job, in the right
   group — not twice for the same viewer.
3. **Group by workflow phase, not by table.** Pricing, ordering, and money are distinct
   phases of one deal; a role sees the phases it works.
4. **Role-scoped groups.** A group renders only if the role has ≥1 item in it. No empty
   scaffolding.
5. **Mobile priority is explicit.** Each item carries a mobile rank; the drawer shows the
   role's top items first.

## Proposed top-level concepts

Seven work-oriented concepts. A role sees only the ones it works. (Emoji/icon choices are
Phase-3; names are Thai-first with an English helper, matching today's pattern.)

| Concept | Thai | Helper | Serves | Replaces today's |
|---|---|---|---|---|
| **My Work** | งานของฉัน | My work / approvals | The role's needs-my-action + overdue + returned queue, unified across workflows | (new — folds today's role-specific dashboard "worklist" to the top) |
| **Sales Pipeline** | ดีล | Deal pipeline | Browse/advance deals | งานขาย → รายการดีล / ภาพรวม |
| **Pricing & Import** | ราคา & นำเข้า | Pricing & import | PCR queue, costing, factory, price-import, catalog | งานขาย → คิวใบขอราคา / นำเข้าราคา / แคตตาล็อก |
| **Orders & Fulfilment** | คำสั่งซื้อ & ส่งมอบ | Orders & fulfilment | Procurement, factory POs, delivery | งานขาย → จัดซื้อ & นำเข้า |
| **Finance** | การเงิน | Finance | Deposit/final/close worklist, commissions | การเงิน → งานการเงิน / ค่าคอมมิชชัน |
| **People & Attendance** | บุคคล & เวลาทำงาน | People & attendance | Employees, profile-requests, attendance, payroll, self-service | บุคคล / บุคคลของฉัน / ทีมของฉัน |
| **Administration** | ตั้งค่า | Administration | CEO price/FX config (and future admin) | งานขาย → ตั้งค่าราคา |

**Ungrouped, always first:** `งานของฉัน` (My Work) for roles with a queue, else the role's
`หน้าหลัก` (self-service home) for plain employees. The current `แดชบอร์ด` becomes
role-branched "My Work / Home" rather than a generic dashboard label.

## Per-role navigation (proposed)

Only groups with items appear. Order = mobile priority (top = highest).

### CEO
1. **งานของฉัน** — approval inbox: pricing decisions · commissions (CEO hop) · OT/SM (CEO hop) · deals to verify-close *(badge: total pending)*
2. **ดีล** — pipeline (all)
3. **ราคา & นำเข้า** — PCR queue (read/decide) · catalog · price-import
4. **คำสั่งซื้อ & ส่งมอบ** — procurement (oversight)
5. **การเงิน** — finance worklist · commissions
6. **บุคคล & เวลาทำงาน** — attendance · (payroll preview) · **ภาพรวม OT / วันลา (oversight)** · self-service
7. **ตั้งค่า** — price/FX config

*OT/leave for CEO are oversight summaries over all employees (business rule), with CEO's
own OT hop-2 approvals surfaced as actionable rows inside งานของฉัน. CEO does not self-request
OT/leave.*

### Sales rep
1. **งานของฉัน** — my deals needing action · follow-ups due · PCRs with more-info · quotations to issue *(badge)*
2. **ดีล** — my pipeline
3. **ราคา & นำเข้า** — catalog (read) *(PCR create happens inside a deal, not here)*
4. **การเงิน** — commissions (read-only, own rows)
5. **บุคคล & เวลาทำงาน** — self-service (attendance/leave/OT/welfare/profile)

### Sales manager
1. **งานของฉัน** — commissions awaiting my approval · team deals needing attention *(badge)*
2. **ดีล** — team pipeline
3. **ราคา & นำเข้า** — PCR queue (read) · catalog
4. **การเงิน** — commissions
5. **บุคคล & เวลาทำงาน** — self-service

### Import
1. **งานของฉัน** — PCRs to pick up / cost · POs to move · CEO returns to revise *(badge)*
2. **ราคา & นำเข้า** — PCR queue · costing/factory (in PCR detail) · price-import · catalog
3. **คำสั่งซื้อ & ส่งมอบ** — procurement/fulfilment · factory POs
4. **บุคคล & เวลาทำงาน** — self-service

### Account
1. **การเงิน** — the finance worklist **is** account's My Work (deposit/final/close/invoice tabs) *(badge: overdue + pending)*
2. **ราคา & นำเข้า** — catalog (read)
3. **บุคคล & เวลาทำงาน** — self-service
   *(commissions stays deep-link-only — no nav item; unchanged from today)*

### HR
1. **บุคคล & เวลาทำงาน** — profile-request inbox *(badge)* · employees · attendance · payroll · **ภาพรวม OT (OT oversight)** · **ภาพรวมวันลา (leave oversight)**
   *(HR has no sales work; the ค่าคอมมิชชัน item is **removed** — dead end, F-11)*
   - **OT/leave for HR are oversight summaries, not self-service** (business rule): the
     `/employee-requests` and `/leave` surfaces show all-employee history (`canViewAll*`),
     labelled "ภาพรวม…" not "คำขอ/วันลา". HR does not submit its own and cannot approve OT.
     (If HR is also linked to an employee and wants to file its own leave, that remains
     possible but is not the surface's primary framing.)

### Employee (plain) / warehouse / qc
1. **หน้าหลัก** — today's attendance + balances + my requests
2. **บุคคล & เวลาทำงาน** — attendance · leave · OT/welfare · profile

### Division manager (employee + manager)
1. **งานของฉัน** — team OT/SM to approve · my own tasks *(badge)*
2. **ทีมของฉัน** — team roster & attendance *(kept distinct; team-facing)*
3. **บุคคล & เวลาทำงาน** — my self-service

## Badges

Today only `/requests` carries a badge (`pendingRequestCount`, `AppShell.jsx:103`) and the
topbar bell shows unread notifications (`NotificationBell.jsx:77`). Proposed badge sources
(all computed from data already fetched; no new endpoint required):

| Badge | Count = | Roles |
|---|---|---|
| **งานของฉัน** | records where `workState ∈ {Needs-my-action, Overdue, Returned}` for the viewer | ceo, sales, sales_manager, import, division_manager |
| **การเงิน** | account: overdue + deposit/final/close-pending rows | account, ceo |
| **บุคคล (profile inbox)** | `pending` profile-requests (today's `pendingRequestCount`) | hr |
| Topbar bell | unread notifications (unchanged) | all |

The "My Work" badge is the app-wide realisation of the WORK_STATE_MODEL count. It replaces
the guesswork of scanning metric cards.

## Mobile IA

- The sidebar is a single component that is a rail ≥721px and an off-canvas drawer ≤720px
  (`AppShell`/`Sidebar`, styles.css:2047-2069) — keep this; it works.
- **Mobile drawer order = the per-role order above.** Top item is always งานของฉัน / หน้าหลัก.
- On mobile, collapse rarely-used groups by default (Administration, People-for-sales-roles).
  The active-route auto-expand already exists (`Sidebar.jsx:62-74`) — keep it.
- **Payroll** stays desktop-only (`DesktopOnlyNotice`); the nav item remains but the page
  states its desktop requirement (unchanged; F-21).
- A future consideration (Phase 4, not decided here): a bottom action bar on mobile for the
  1–2 most common self-service actions. Recorded as an option, not a decision.

## What this IA deliberately does **not** do

- Does not merge `/attendance`'s two identities into one — self vs team is a real job
  difference; it stays one route surfaced by-job in the right group per viewer (removing the
  *within-one-viewer* duplication only).
- Does not give account a commissions nav item (deep-link-only is correct).
- Does not create warehouse/qc surfaces (latent; tracked gap).
- Does not change `SALES_ENABLED` gating — the sales concepts still disappear wholesale when
  sales is off (they degrade to the employee experience, as today).
