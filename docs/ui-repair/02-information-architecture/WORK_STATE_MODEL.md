# Work-State Model

A single, consistent vocabulary for "whose move is it?" across every workflow — the
question the design law says every record must answer. These are **UX classifications
computed from existing data**, not new backend statuses. Nothing here changes schema.

## The five layers (keep them distinct)

The app already carries several orthogonal status axes. The UI's job is to collapse them
into **one** work-state per record *for the current viewer*, without losing the detail.

1. **Backend lifecycle status** — the persisted DB status (`ticket.status`,
   `DealLifecycle`, `LeaveStatus`, `CommissionStatus`, `PricingRequestStatus`, …).
2. **Sales stage** — the 14-step `DealStage` pipeline (deal only).
3. **Operational sub-workflow status** — `paymentStatus`, `fulfilmentStatus`, factory-quote
   / costing / decision statuses, etc.
4. **UX work-state** — the *computed* classification below (the 9). A function of layers
   1–3 **plus the current actor** (their role + whether they own/can-transition the record).
5. **Display label** — the Thai string shown to the user for the work-state, tuned per
   surface (e.g. "รอคุณอนุมัติราคา" is more useful than a generic "ต้องดำเนินการ").

**Critical:** work-state is **per-viewer**. The same PCR at `READY_FOR_CEO_REVIEW` is
*Needs-my-action* for the CEO and *Waiting* for the import user who submitted it. The
classifier takes `(record, viewer)`, never `record` alone.

---

## The 9 work-states

| # | Work-state | Meaning (for the viewer) | Default Thai label | Signal weight |
|---|-----------|--------------------------|--------------------|---------------|
| 1 | **Needs my action** | The next transition is mine to make, and I can make it now. | ต้องดำเนินการ (tune per surface) | Highest — top of every worklist |
| 2 | **Waiting for another role** | Progressing, but the next move belongs to someone else. | รอ<role> | Muted — visible, not urgent |
| 3 | **Blocked** | Cannot progress until a precondition outside the normal flow is met (missing attachment, unmet gate, dependency). | ติดขัด / รอ<precondition> | High — needs attention, distinct from waiting |
| 4 | **Overdue** | A time expectation has passed (payment past due; or, proposed, an item aged past a threshold). | เกินกำหนด | Highest — overrides "waiting" styling |
| 5 | **Draft** | Not yet submitted; owned by me, no one else sees it (or it's private). | ฉบับร่าง | Low — my private scratchpad |
| 6 | **Completed** | Terminal-success; no action expected. | เสร็จสมบูรณ์ / อนุมัติแล้ว / ปิดงาน | Lowest — archived tone |
| 7 | **Cancelled** | Terminal by cancellation; opportunity gone. | ยกเลิก | Lowest — struck/greyed |
| 8 | **Returned for correction** | Sent back to me to fix and resubmit. | ส่งกลับให้แก้ไข | High — it's my move, but framed as rework |
| 9 | **Informational / read-only** | I can see it but have no action (oversight/visibility). | ดูอย่างเดียว | Lowest — no worklist placement |

Notes:
- **Needs-my-action vs Returned** are both "my move," but Returned carries *why it came
  back* and should read as rework, not fresh work.
- **Blocked vs Waiting.** Waiting = the flow is healthy, someone else is up. Blocked = the
  flow is stuck on a gap (e.g. deposit not paid so procurement can't start; SICK leave with
  no cert). Blocked is actionable by *someone* even if not the viewer.
- **Overdue is a modifier**, not a slot — an Overdue record is usually also Needs-my-action
  or Waiting. Render it as an escalation of the underlying state.
- **Returned** only exists where the backend models it (pricing chain, deposit revision,
  quotation revision). Do not invent it for leave/OT/SM/profile (they only approve/reject).

---

## Derivation rule

```
workState(record, viewer):
  if record is a private draft owned by viewer      → Draft
  if record.status is terminal-success              → Completed
  if record.status is terminal-cancel/lost/void     → Cancelled
  if record was sent back to viewer to fix          → Returned for correction
  if viewer can perform the next transition now      → Needs my action
                                                       (escalate to Overdue if past due)
  if the record is stuck on an unmet precondition    → Blocked
  if the next move belongs to another role           → Waiting for another role
                                                       (escalate to Overdue if past due)
  else (viewer has visibility but no stake)          → Informational / read-only
```

"Can perform the next transition now" is decided by the **same role gates the Java
services enforce** (mirrored in `routes.js`/`permissions.js`) — never a looser mock rule.
The classifier must not claim *Needs-my-action* for an action the backend would 403.

---

## Mapping — Deal / ticket (money & pipeline)

Viewer-dependent. `S` = sales(owner), `SM` = sales_manager, `I` = import, `A` = account,
`C` = ceo.

| Backend status / stage / sub-status | Responsible role (next move) | Work-state per viewer | Display label (owner's view) |
|---|---|---|---|
| `lifecycle=ACTIVE`, stage `LEAD_APPROACH…NEGOTIATION`, no open PCR | S | S: Needs-my-action · SM/C: Info | ดำเนินการดีล / บันทึกกิจกรรม |
| PCR `SUBMITTED`/`IMPORT_REVIEWING`/`AWAITING_FACTORY_RESPONSE`/`COSTING_IN_PROGRESS` | I | S: Waiting · I: Needs-my-action · C: Info | รอฝ่ายนำเข้าคิดราคา |
| PCR `MORE_INFO_REQUIRED` | S | S: Needs-my-action · I: Waiting | ฝ่ายนำเข้าขอข้อมูลเพิ่ม |
| PCR `READY_FOR_CEO_REVIEW`/`CEO_REVIEWING` | C | S/I: Waiting · C: Needs-my-action | รอ CEO อนุมัติราคา |
| PCR `COSTING_REVISION_REQUIRED` (CEO returned) | I | I: Returned · S: Waiting | CEO ส่งกลับให้แก้ต้นทุน |
| PCR `APPROVED_FOR_QUOTATION` (decision APPROVED) | S | S: Needs-my-action | ออกใบเสนอราคาได้ |
| quotation `ISSUED`, awaiting outcome | customer/S | S: Waiting (customer) | รอลูกค้าตัดสินใจ |
| PCR `QUOTATION_ACCEPTED`, ticket still `draft` | S | S: Needs-my-action | ยืนยันคำสั่งซื้อ |
| `paymentStatus=CUSTOMER_CONFIRMED` (order received) | S | S: Needs-my-action (issue deposit) | ออกใบแจ้งมัดจำ |
| `paymentStatus=DEPOSIT_NOTICE_ISSUED` | A | S: Waiting · A: Needs-my-action | รอบัญชียืนยันมัดจำ |
| `DEPOSIT_PAID`, stage `DEPOSIT_RECEIVED` (procurement eligible) | I | I: Needs-my-action (issue IR) · A: Waiting | รอฝ่ายนำเข้าเปิดสั่งซื้อ |
| fulfilment `IR_ISSUED…SHIPPING` | I | I: Needs-my-action · S/A: Waiting | กำลังจัดซื้อ/นำเข้า |
| fulfilment `GOODS_RECEIVED`, `AWAITING_FINAL_PAYMENT` | S (deliver) + A (final pay) | S: Needs-my-action (schedule) · A: Needs-my-action (final) | นัดส่ง / เก็บเงินส่วนที่เหลือ |
| overdue balance | A | A: **Overdue** (Needs-my-action) | เกินกำหนดชำระ |
| `FULLY_PAID` + `FULLY_DELIVERED`, stage `CLOSED_PAID`, no invoice | A | A: Needs-my-action (record invoice + commission) | บันทึกใบกำกับ + ออกค่าคอม |
| close prerequisites met, `closeConfirmedAt=null` | A | A: Needs-my-action (confirm close-ready) | ยืนยันพร้อมปิดงาน |
| `closeConfirmedAt` set, not verified | C | A: Waiting · C: Needs-my-action | รอ CEO ตรวจปิดงาน |
| `status=closed`, `lifecycle=COMPLETED` | — | all: Completed | ปิดงานแล้ว |
| `lifecycle=CLOSED_LOST` | — | all: Cancelled (lost) | เสียงาน |
| `lifecycle=CANCELLED` | — | all: Cancelled | ยกเลิก |
| `lifecycle=ON_HOLD`/`DORMANT` | S | S: Blocked (paused) · others: Info | พักไว้ / พักยาว |

Import/account/manager see only their **scoped** subset (import: live-PCR or stage ≥
PROCUREMENT; account: money-pending; sales: own deals) — the classifier runs after scoping.

---

## Mapping — Commission

| `CommissionStatus` | Next move | Work-state per viewer | Label |
|---|---|---|---|
| (not yet created; deal `CLOSED_PAID`) | A | A: Needs-my-action (createFromDeal) | ออกค่าคอมจากดีล |
| `SUBMITTED` | SM | SM: Needs-my-action · rep: Info(read-own) · C: Waiting | รอผู้จัดการอนุมัติ |
| `MANAGER_APPROVED` | C | C: Needs-my-action · SM: Waiting · rep: Info | รอ CEO อนุมัติ |
| `APPROVED` | HR (payroll pull) | rep: Completed(read-own) · HR: Info(payroll-ready) | อนุมัติแล้ว |
| `REJECTED` | SM/rep | rep: Info (with reason) | ถูกปฏิเสธ |
| `VOID` / clawed back | — | Cancelled | ยกเลิก/เรียกคืน |

Account never lists commissions — its only commission work-state is the *deal-side*
"record invoice + commission" (Needs-my-action) reached by deep link.

---

## Mapping — Leave / OT / Special-money / Profile-request

| Record | Status | Next move | Work-state (employee / approver) |
|---|---|---|---|
| Leave | `SUBMITTED` (rare) | HR or direct mgr | emp: Waiting · reviewer: Needs-my-action |
| Leave | `APPROVED` (incl. over-quota unpaid split) | — | emp: Completed (**flag unpaid days as Blocked-ish warning**) |
| Leave | `AUTO_REJECTED` | emp | emp: **Blocked** (fix cert / notice, resubmit) |
| Leave | `REJECTED`/`CANCELLED` | — | Cancelled |
| OT / SM | `SUBMITTED` | direct/division mgr | emp: Waiting(mgr) · mgr: Needs-my-action · CEO: Info |
| OT / SM | `MANAGER_APPROVED` | CEO | emp: Waiting(CEO) · CEO: Needs-my-action · mgr: Info |
| OT / SM | `APPROVED` | — | Completed |
| OT / SM | `REJECTED`/`CANCELLED` | — | Cancelled |
| Profile-request | `pending` | HR | emp: Waiting(HR) · HR: Needs-my-action |
| Profile-request | `approved`/`rejected` | — | Completed / (rejected → Info with reason) |

Employee "routing" strip ("ส่งแล้ว › ผู้จัดการ › CEO") is the *per-record* visualisation of
this — it already exists for self-service and is the model to reuse (see PAGE_PATTERN_CATALOG
"audit timeline / routing").

**HR & CEO viewing OT/leave = Informational (oversight), by business rule.** HR and CEO do
not submit OT/leave and their own don't need approval, so for these viewers every OT/leave
row is **work-state 9 (Informational / read-only)** — an all-employee **summary/history**,
not a request queue — *except* the rows where the viewer is a live approver: CEO's OT
`MANAGER_APPROVED` rows are **Needs-my-action** (CEO hop-2), and HR's rare leave `SUBMITTED`
rows are **Needs-my-action** (shared review). So the classifier for these surfaces is:
oversight-list by default, with the viewer's own actionable rows promoted. HR never gets a
Needs-my-action OT row (it cannot approve OT — would 403).

---

## What this model changes (and doesn't)

- **Does not** add or rename any backend status, stage, or DB value.
- **Does** define one computed field — `workState(record, viewer)` — that the frontend
  derives from data it already has, using the **real role gates**.
- **Does** standardise the two-way split every worklist needs: *mine to act* (states 1, 4,
  8) vs *waiting/for-reference* (states 2, 3-when-not-mine, 6, 7, 9). This is the
  app-wide worklist contract that Phase-1 F-05 said is missing.
- **Enables** consistent labels and badge counts (a role's "needs my action" count is just
  `count(records where workState==Needs-my-action||Overdue||Returned)`).

**Auto-transition failure (G-5, red-team).** The lifecycle leans on auto-advances
(`maybeAdvanceClosedPaid`, stage bumps on issue/confirm/goods-received). Computing work-state
from the **axes** (`salesStage`+`paymentStatus`+`fulfilmentStatus`) rather than one flag makes
this resilient — a stuck stage bump still classifies from the money/fulfilment truth. But a
genuinely stuck auto-advance has no "something didn't fire — here's how to recover" surface.
Low probability; recorded so Phase 4 does not assume auto-advances are infallible. Retries are
**[OUT OF SCOPE — backend]**. **Resolution (D-18):** axis-computed work-state is the resilience
(keep it), plus a **non-blocking** "สถานะไม่สอดคล้อง" advisory when computed work-state and
persisted `salesStage` disagree in a missed-auto-advance shape (e.g. `FULLY_PAID`+
`FULLY_DELIVERED` but stage ≠ `CLOSED_PAID`), shown on CEO/account oversight — informational,
never gating an action.

**Phase-4 validation required:** the classifier's "can perform next transition" branch must
be unit-tested against the same role sets the Java services enforce, and any surface that
acts on it re-checked against the backend (never the mock). Mis-classifying *Waiting* as
*Needs-my-action* would invite a user to attempt a 403 action.
