# Workflow → Screen Matrix

Critical operational workflows mapped to their screens, evaluated against the
"Operations Control Desk" questions (frontend-ui.md §Workflow rules). Grounded in
live evidence (`../evidence/current/`). Scoring: ✅ clear · ⚠️ partial · ❌ missing.

## Legend of questions
**NA** next action obvious · **MINE** distinguishes needs-my-action vs waiting ·
**OWN** ownership visible · **STAGE** workflow stage visible · **BLOCK** blockers
visible · **URG** urgency visible · **1°/2°** primary vs secondary actions
distinguishable · **WHY** unavailable-action reasoning shown · **REC** error
recovery · **MOB** mobile can complete.

## Sales / deal workflow (1 ticket = 1 deal)

| Screen | NA | MINE | OWN | STAGE | BLOCK | URG | 1°/2° | WHY | MOB | Evidence |
|---|---|---|---|---|---|---|---|---|---|---|
| `/tickets` list | ✅ | ⚠️ | ✅ | ✅ | ✅ | ✅ | ⚠️ | — | ✅ cards | `sales/tickets/*` |
| `/tickets/:id` detail | ✅ | ⚠️ | ✅ | ✅ | ✅ | ⚠️ | ✅ | ⚠️ | ⚠️ | `ceo/ticket-detail/*` |
| create-deal modal | ✅ | — | — | ✅ (0/6) | ✅ | — | ✅ | ✅ | ⚠️ cramped | `sales/create-deal-*` |

Notes: pipeline stage is strong (14-stage numbered status, phase chips, progress
bars, on-hold banner with continue/hold actions). *Weakness:* "needs my action vs
waiting on another role" is not a first-class filter on the list (F-05) — a rep scans
status text to infer it. Deal detail's full-width back bar wastes prime vertical
space (F-14). Create-deal is a **6-step wizard inside a modal** — heavy for mobile
(F-06).

## Money / finance workflow (Account)

| Screen | NA | MINE | OWN | STAGE | BLOCK | URG | 1°/2° | REC | MOB | Evidence |
|---|---|---|---|---|---|---|---|---|---|---|
| AccountOverview landing | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ (overdue red) | ✅ | — | ⚠️ | `account/landing/*` |
| `/finance` worklist | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | — | ⚠️ | `account/finance/*` |

**Reference-quality.** Overdue in red, per-row next-action buttons, ownership +
month summary. This is the pattern other landings should converge toward.

## Procurement / import workflow (Import)

| Screen | NA | MINE | OWN | STAGE | URG | MOB | Evidence |
|---|---|---|---|---|---|---|---|
| ImportOverview landing | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ | `import/landing/*` |
| `/procurement` | ✅ | ⚠️ | ✅ | ✅ | ⚠️ | ⚠️ | `import/procurement/*` |

Strong worklist (บันทึกส่งมอบ / ส่ง IR per row, 6-cell status row, คิวของฉัน).

## Payroll workflow (HR)

| Screen | NA | STAGE | 1°/2° | REC | MOB | Evidence |
|---|---|---|---|---|---|---|
| `/payroll` | ✅ | ✅ | ✅ (Process Payroll 1°) | ⚠️ | ❌ desktop-only | `hr/payroll/desktop` |

Clear month/preview/process flow + bank export. **Mobile: blocked** by
`DesktopOnlyNotice` (acceptable for a heavy month-end admin task, but record it).

## Employee self-service (Employee, mobile-first persona)

| Screen | NA | STAGE | OWN | MINE | MOB | Evidence |
|---|---|---|---|---|---|---|
| EmployeeSelfService landing | ✅ | ✅ | ✅ | ✅ | ✅ | `employee/landing/*` |
| `/leave`, `/employee-requests` | ✅ | ✅ | ✅ | ✅ | ✅ | `employee/leave`, `employee/employee-requests` |

**Best next-action clarity.** Attendance prompt, leave/OT balances, own requests
with routing path ("ส่งแล้ว › หัวหน้าฝ่าย › CEO") and status pills.

## HR people-ops (HR)

| Screen | NA | 1°/2° | Sensitive-data control | MOB | Evidence |
|---|---|---|---|---|---|
| `/employees` list | ✅ | ✅ | — | ⚠️ | `hr/employees/*` |
| `/employees/:id` detail | ✅ | ✅ | ✅ HR-only "ข้อมูลอ่อนไหว" tab | ⚠️ | `hr/employee-detail/*` |
| `/requests` review queue | ✅ | ✅ | — | ⚠️ | `hr/requests/*` |

## Approvals (Division Manager, mixed desktop/mobile)

| Screen | NA | MINE | MOB | Evidence |
|---|---|---|---|---|
| DivisionManagerOverview + team approvals | ⚠️ | ⚠️ | ⚠️ | `division_manager/*` |

Reachable; the `ทีมของฉัน` group reuses `/employee-requests` `/leave` `/attendance`
as team-facing entries. Mobile approval-in-seconds flow not deeply exercised (gap).

## Cross-workflow answers

- **"Does the landing communicate the required work?"** — Yes for account, import,
  employee (worklists with next actions). **No** for CEO in the empty state (all-zero
  cards read as "nothing here", no cue). → F-04.
- **Information forced to be memorised across pages?** — Deal detail ↔ list: filters
  are preserved in the URL (good). PCR chain spans several sub-views (not fully
  audited).
- **Tables where appropriate?** — Yes; `DataTable` used for dense lists, reflowing
  to cards on mobile (good). Not card-overused for row data.
- **Cards reducing scan efficiency?** — Landings lean on large **metric-card rows**
  (5–6 across); on CEO/commissions/payroll these are the first and largest thing,
  above the operational list. Borderline against the "oversized metric cards that
  displace operational information" rule. → F-04.
