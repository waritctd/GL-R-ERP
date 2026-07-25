# Role Landing Strategy

What each role should see the moment they log in. The Phase-1 audit's worst landing finding
(F-04) is that CEO/HR/payroll lead with an oversized metric-card grid — the CEO opens to
five zeros while the live "waiting on you" list sits below the fold. The reference-quality
landings (account, import, employee) already lead with a **worklist + next action**. This
document makes that the rule and specifies each landing.

> **Design law:** "oversized metric cards that displace operational information" and "icon
> tiles above every heading" are named anti-patterns. Metrics are a *compact strip*, never
> the hero. The fix is fewer, quieter numbers and a promoted worklist — **not** louder tiles.

## Landing archetypes

| Archetype | When | Lead element |
|---|---|---|
| **Work queue** | Role's day is a list of records needing them | "mine to act" list, sorted by urgency |
| **Approval inbox** | Role mostly approves others' work | grouped pending-approvals, one-tap actions |
| **Pipeline** | Role manages many deals through stages | stage-grouped deal list (secondary to the queue) |
| **Operational overview** | Role coordinates money/ops across records | worklist with per-row next action + a compact money strip |
| **Self-service home** | Role acts only on their own records | today's status + balances + my requests |
| **Analytics dashboard** | Genuine trend/reporting need | charts — **only where a real analytics need exists** |

**None default to metric cards.** A metric earns space only as a *compact, non-zero,
actionable* figure in a strip — and only after the worklist.

---

## Per-role landing specs

### CEO — Approval inbox (primary) + pipeline glance (secondary)
- **Archetype** Approval inbox. *Not* an analytics dashboard.
- **Primary question** "What decisions are waiting on me right now?"
- **First section** "รออนุมัติจากคุณ" — a unified inbox: pricing decisions, commissions
  (CEO hop), OT/special-money (CEO hop), deals to verify-close. Each row: what · who ·
  since when · one primary action.
- **Work groups** By decision type (ราคา / ค่าคอม / OT-สวัสดิการ / ปิดงาน), each a small
  labelled cluster; within a group, oldest-waiting first.
- **Sorting** Age of wait (oldest first); overdue/aged escalated.
- **Urgency** Aged pricing decisions and close-verifications first (they block others).
- **Empty state** "ไม่มีรายการที่รอการตัดสินใจ" with a *cue*, not a dead end — link to the
  pipeline glance ("ดูภาพรวมดีล") so an empty inbox routes somewhere useful (today's empty
  CEO landing reads as "nothing here" — the F-04 failure).
- **Secondary metrics** A single compact strip *below* the inbox: deals in flight, awaiting
  price, month's closed value — only non-zero, no icon tiles.
- **Mobile ordering** Inbox first (one-tap approve), strip collapsed, pipeline link last.
- **Primary next action** The top pending decision's action button.
- **What should NOT appear** Five equal zero-cards; icon tiles; a hero banner; anything
  requiring interpretation before it routes the CEO to a decision.
- **OT/leave = oversight, not self-service (business rule).** CEO doesn't submit its own
  OT/leave; the OT and leave surfaces are all-employee **summary/history** oversight views.
  CEO's actionable OT hop-2 approvals still surface inside the approval inbox above — the
  oversight surface itself is reference, not the CEO's landing queue.

### Sales rep — Work queue
- **Archetype** Work queue.
- **Primary question** "Which of my deals need me today, and who am I chasing?"
- **First section** "ต้องดำเนินการ" — my deals in work-state Needs-my-action/Returned/
  Overdue: PCRs with more-info, quotations to issue, orders to confirm, deposit notices to
  issue, follow-ups due today.
- **Work groups** By what the deal needs (ตอบฝ่ายนำเข้า / ออกใบเสนอราคา / ตามงาน), then a
  "รอผู้อื่น" section (waiting on import/CEO/customer/account) clearly separated.
- **Sorting** Follow-up date / age; overdue follow-ups first.
- **Urgency** Overdue follow-ups and expiring quotations first.
- **Empty state** "ไม่มีดีลที่ต้องดำเนินการ — เริ่มดีลใหม่ได้เลย" with the create-deal CTA.
- **Secondary metrics** Compact strip: my open deals, this month's won value, pending
  commission — below the queue.
- **Mobile ordering** Queue first; create-deal as a persistent primary action; waiting
  section collapsed.
- **Primary next action** The top deal's next action, or "สร้างดีล".
- **What should NOT appear** A wall of stage counts; the whole 14-stage legend; other reps'
  deals.

### Sales manager — Approval inbox + team pipeline
- **Archetype** Approval inbox (commissions) + team pipeline glance.
- **Primary question** "What needs my approval, and how's the team's pipeline?"
- **First section** "รออนุมัติ" — commissions at `SUBMITTED` (my hop); then team deals
  flagged (stalled/lost-risk).
- **Work groups** Commissions to approve · team deals needing attention.
- **Sorting** Commission age; then team deals by staleness.
- **Urgency** Oldest submitted commissions first.
- **Empty state** "ไม่มีค่าคอมรออนุมัติ" → link to team pipeline.
- **Secondary metrics** Team open deals, month commission total — compact strip.
- **Mobile ordering** Approvals first (one-tap), pipeline link last.
- **Primary next action** Approve the top commission.
- **What should NOT appear** Per-rep vanity metrics as the hero; margin/cost (not their data).

### Import — Work queue (operational)
- **Archetype** Work queue.
- **Primary question** "Which pricing requests and orders need me to move them?"
- **First section** "คิวของฉัน" — PCRs to pick up / cost, CEO returns to revise, POs/goods
  to move.
- **Work groups** ใบขอราคา (pick up / cost / revise) · จัดซื้อ-ส่งมอบ (IR / shipping / goods)
  — two clusters matching the two phases import owns.
- **Sorting** Age; returned items surfaced with their reason.
- **Urgency** CEO-returned costings (rework) and unpicked PCRs first.
- **Empty state** "ไม่มีงานค้าง" → link to the PCR queue / procurement list.
- **Secondary metrics** Open PCRs, POs in transit — compact strip.
- **Mobile ordering** Queue first; goods-received/shipping updates one-tap (plausible from
  the floor); costing entry noted as desktop-leaning.
- **Primary next action** Pick up / continue the top PCR.
- **What should NOT appear** Selling price/margin framing (import owns cost, not price);
  a six-cell status row as the hero (the current ImportOverview is close — tighten the
  status row into a strip, keep the worklist lead).

### Account — Operational overview (the reference)
- **Archetype** Operational overview — **this is already the model** (`AccountOverview` /
  `AccountFinancePage`): overdue in red, per-row next-action buttons, ownership + month
  summary. Keep and generalise it.
- **Primary question** "What money do I need to confirm, chase, or close today?"
- **First section** "สิ่งที่ต้องทำ" — the finance worklist: chase overdue · confirm deposit
  · take final payment · confirm close-ready · record invoice + commission.
- **Work groups** The five money stages (the existing `STAGE_FILTERS`), overdue pinned.
- **Sorting** Overdue first (urgency by amount/age), then stage order.
- **Urgency** Overdue balances in red (existing behaviour — keep).
- **Empty state** Per-tab empties (e.g. "ไม่มีรายการรอรับมัดจำ"); **note the known scope
  gap** — close-ready/`CLOSED_PAID` rows fall outside account's server scope today, so those
  tabs read empty despite correct wiring (documented in `AccountFinancePage.jsx:58-63`). The
  empty state must not imply "done" when it means "not in scope" — a Phase-4 backend
  follow-up, flagged, not hidden.
- **Secondary metrics** Overdue total (red), month received — compact, already good.
- **Mobile ordering** Worklist first; confirm actions one-tap; month strip below.
- **Primary next action** The top row's action (ติดตามชำระ / ยืนยันรับมัดจำ / …).
- **What should NOT appear** A horizontally-scrolling five-card metric row that clips Thai
  labels on mobile (F-12) — reflow to a wrapping 2-up strip.

### HR — Work queue + operational (people)
- **Archetype** Work queue (profile-request inbox) + operational (attendance/payroll cues).
- **Primary question** "What people-ops work is waiting — requests to review, payroll to run?"
- **First section** "คำขอรอตรวจ" — pending profile-change requests (the badge queue); then a
  payroll-period cue when the month is due.
- **Work groups** Profile requests · payroll status · (leftover leave `SUBMITTED` if any).
- **Sorting** Request age; payroll by month cutoff proximity.
- **Urgency** Payroll-run window and oldest pending requests.
- **Empty state** "ไม่มีคำขอรอตรวจ" → link to employees / attendance.
- **Secondary metrics** Headcount, this month's payroll status — compact strip (HR is one of
  the few roles where a couple of real numbers are genuinely useful, but still below the
  queue, not as zero-tiles).
- **Mobile ordering** Request inbox first; payroll noted desktop-only; attendance check next.
- **Primary next action** Review the top profile request.
- **What should NOT appear** The current payroll-style metric grid as the hero; the
  ค่าคอมมิชชัน entry (removed); any employee PII on the landing.
- **OT/leave = oversight, not self-service (business rule).** HR's OT and leave surfaces
  are all-employee **summary/history** views, not request forms — HR doesn't submit its own
  and cannot approve OT (pure oversight); leave oversight includes the rare `SUBMITTED` row
  it may review. These are *reference* surfaces reached from People & Attendance, not part
  of HR's "needs-my-action" landing queue.

### Employee (plain) / warehouse / qc — Self-service home
- **Archetype** Self-service home — **already the best landing in the app**
  (`EmployeeSelfService`). Keep.
- **Primary question** "Did my attendance record, and what's the status of my requests?"
- **First section** Today's attendance prompt + leave/OT balances.
- **Work groups** My attendance · my requests (with the "ส่งแล้ว › หัวหน้า › CEO" routing) ·
  quick actions (ลา / OT / สวัสดิการ).
- **Sorting** Requests by recency; pending first.
- **Urgency** A missed scan / an over-quota unpaid warning surfaced clearly.
- **Empty state** "ยังไม่มีคำขอ" with the three action buttons.
- **Secondary metrics** Leave/OT balances (real, useful) — as a small strip, not tiles.
- **Mobile ordering** Attendance + primary action first (phone-first persona).
- **Primary next action** Submit the most likely request (context-dependent) / check in.
- **What should NOT appear** Sales/finance anything; metric-card grid.

### Division manager — Approval inbox + self-service
- **Archetype** Approval inbox (team) + self-service home.
- **Primary question** "What team approvals are waiting, and what are my own tasks?"
- **First section** "รออนุมัติจากทีม" — team OT/special-money at `SUBMITTED` (my hop); then
  my own self-service below.
- **Work groups** Team approvals · my requests · team attendance link.
- **Sorting** Approval age.
- **Urgency** Oldest team submissions.
- **Empty state** "ไม่มีคำขอจากทีม" → link to team roster/attendance.
- **Secondary metrics** Team present-today count — compact.
- **Mobile ordering** Team approvals first (one-tap), own tasks next.
- **Primary next action** Approve the top team request.
- **What should NOT appear** Leave-review affordances they can't actually action (the
  direct-manager-only caveat) unless/until that authz is resolved.

---

## Cross-landing rules

1. **Worklist first, metrics second, everywhere.** No landing leads with a metric grid.
2. **Empty states route, never dead-end.** Every empty worklist offers the next best place
   to go (F-04). "Nothing waiting" is a state, not a design.
3. **One primary action per landing**, tied to the top of the queue.
4. **Compact stat strip only:** non-zero, no icon tiles, wrapping on mobile (F-12).
5. **Reuse one worklist primitive** across roles (proposed in PAGE_PATTERN_CATALOG) so the
   "mine to act / waiting" split (WORK_STATE_MODEL) is identical everywhere — the fix for
   F-05.
6. **Analytics is opt-in, not the landing.** No role's *landing* is a chart wall; if a real
   reporting need exists (e.g. CEO monthly value), it's a compact strip or a separate report,
   not the first thing.
