# Status Presentation

How every status and work-state is displayed. **Proposal/spec — no code change.**
It ratifies the system that already exists (`frontend/src/utils/format.js` is the
canonical value→{label,tone} hub; `StatusBadge` applies the tone) and fills the
gaps.

## Governing rules

1. **Colour is never sufficient — always pair with text.** Every status carries a
   Thai word; the tone reinforces it. This is a hard WCAG 1.4.1 rule. (Verified:
   `StatusBadge` always renders `children` text — safe. The violations are
   *outside* it — see Defects.)
2. **~85 status values → 6 tones.** Do **not** give a status its own colour. The
   six tones are `neutral · info · success · warning · danger · indigo` (`teal`
   aliases `success`). A new status picks an existing tone by meaning.
3. **Tone by meaning, fixed:** neutral = inert/terminal-neutral/draft; info =
   in-progress/"someone is working"; success = approved/paid/delivered/done;
   warning = pending/awaiting/needs-attention; danger = rejected/cancelled/void/
   error; indigo = a meta/field accent, **not** a lifecycle status.
4. **Backend lifecycle status ≠ UX work-state.** The badge shows the *record's*
   persisted status. The **work-state** ("whose move is it?") is computed per
   viewer (WORK_STATE_MODEL) and drives worklist placement. A row shows both: the
   status badge (what state the record is in) and its work-state framing (is it
   mine?).
5. **Attendance late/early is reporting-only** — never a punitive `danger` tone
   (§76). `warning` is the ceiling.
6. **Badge vs plain text:** use the `StatusBadge` pill for a lifecycle/work state.
   Use **plain text** for a non-state value (a role label "เข้า/ออก", an empty
   "-", a count). Do not badge everything — a wall of pills is as noisy as a wall
   of colour.

## The 9 UX work-states (computed, per viewer)

From [`../02-information-architecture/WORK_STATE_MODEL.md`](../02-information-architecture/WORK_STATE_MODEL.md). These drive **worklist placement and weight**, layered over the status badge.

| Work-state | Canonical meaning | Default Thai label | Tone / weight | Colour sufficient? | Actionable by viewer | Placement |
|---|---|---|---|---|---|---|
| **Needs my action** | Next transition is mine, now | ต้องดำเนินการ (tune per surface) | warning/indigo, **high** | No — always text | **Yes** | Top of "mine to act" |
| **Waiting** | Someone else is up | รอ<role> | neutral/info, **muted** | No | No | "Waiting/reference" list |
| **Blocked** | Stuck on a precondition | ติดขัด / รอ<precondition> | warning, **high** | No | Maybe (someone) | Attention, distinct from waiting |
| **Overdue** | A time expectation passed | เกินกำหนด | danger, **highest** (modifier) | No | Yes (usually) | Escalates the underlying state |
| **Draft** | Not submitted; mine only | ฉบับร่าง | neutral, **low** | No | Yes (owner) | My drafts |
| **Completed** | Terminal-success | เสร็จสมบูรณ์ / อนุมัติแล้ว / ปิดงาน | success/neutral, **archived** | No | No | Archived tone |
| **Cancelled** | Terminal by cancellation | ยกเลิก | danger/neutral, **archived** | No | No | Greyed/struck |
| **Returned** | Sent back to me to fix | ส่งกลับให้แก้ไข | warning, **high** (rework) | No | Yes | "Mine to act", framed as rework |
| **Informational** | Visible, no action | ดูอย่างเดียว | neutral, **lowest** | No | No | Not in a worklist |

- **Overdue is a modifier**, not a slot — render it as an escalation of Needs-my-action or Waiting.
- **Already-decided** (race, no `@Version`): on 409/422 refetch, render the new work-state and **disable the stale action with a reason** — never a raw toast. Required, not optional.

## Backend status inventory (value → Thai label → tone → responsible role)

Each row: the persisted value, the Thai label shown, the semantic tone, and the
role whose move it typically is (for the work-state layer). All render via
`StatusBadge` unless noted. Source: `format.js` with the cited line ranges.

### Ticket status — `ticketStatusLabel` (format.js:63-77)
⚠️ Frontend keys are **lowercase**; backend `TicketStatus` is **UPPERCASE** — see Defects D-S1.

| Value | Thai label | Tone | Next move |
|---|---|---|---|
| draft | แบบร่าง | neutral | owner (sales) |
| submitted | รอรับเรื่องจากฝ่าย Import | warning | import |
| in_review | กำลังดำเนินการ | info | import |
| price_proposed | รอการอนุมัติ | warning | CEO |
| approved | อนุมัติแล้ว | success | sales |
| rejected | ตีกลับ | danger | sales |
| quotation_issued | ออกใบเสนอราคาแล้ว | success | customer/sales |
| document_issued | ออกใบแจ้งยอดแล้ว | success | account |
| closed | ปิดแล้ว | neutral | — |
| cancelled | ยกเลิกแล้ว | danger | — |

### Pricing-request status — `pricingRequestStatusLabel` (format.js:82-102)
The pricing chain; keys match backend `PricingRequestStatus`.

| Value | Thai label | Tone | Next move |
|---|---|---|---|
| DRAFT | แบบร่าง | neutral | owner |
| SUBMITTED | รอ Import รับเรื่อง | warning | import |
| IMPORT_REVIEWING | Import ตรวจคำขอราคา | info | import |
| AWAITING_FACTORY_RESPONSE | รอราคาโรงงาน | warning | import (factory) |
| COSTING_IN_PROGRESS | กำลังร่างต้นทุน | info | import |
| READY_FOR_CEO_REVIEW | ส่งให้ CEO ตรวจแล้ว | success | CEO |
| CEO_REVIEWING | CEO กำลังพิจารณาราคาขาย | info | CEO |
| APPROVED_FOR_QUOTATION | อนุมัติราคาขายแล้ว | success | sales |
| COSTING_REVISION_REQUIRED | CEO ตีกลับให้แก้ไขต้นทุน | danger | import (**Returned**) |
| QUOTATION_ISSUED | ออกใบเสนอราคาลูกค้าแล้ว | success | customer |
| QUOTATION_ACCEPTED | ลูกค้ายอมรับใบเสนอราคาแล้ว | success | sales |
| MORE_INFO_REQUIRED | รอข้อมูลเพิ่มเติม | warning | sales |
| SUPERSEDED | ถูกแทนที่แล้ว | neutral | — |
| CANCELLED | ยกเลิกแล้ว | danger | — |

### Deal stage — `dealStageLabel` (format.js:180-197) — 14 stages
`LEAD_APPROACH` neutral · `PRESENTATION` info · `SPEC_APPROVED` info · `QUOTE_DESIGN_SIDE` info · `OWNER_SIGNOFF` success · `AWAITING_BUYER` warning · `QUOTE_BUYER` info · `NEGOTIATION` warning · `ORDER_RECEIVED` success · `DEPOSIT_RECEIVED` success · `PROCUREMENT` info · `DELIVERY_SCHEDULING` warning · `DELIVERED` success · `CLOSED_PAID` success. *(A stage strip/timeline visualises order — see COMPONENT_CONTRACTS Timeline.)*

### Deal lifecycle — `dealLifecycleLabel` (format.js:215-225)
`ACTIVE` กำลังดำเนินการ / success · `ON_HOLD` พักไว้ชั่วคราว / warning · `DORMANT` พักยาว (dormant) / neutral · `CLOSED_LOST` เสียงาน / danger · `CANCELLED` ยกเลิก / danger · `COMPLETED` เสร็จสมบูรณ์ / success.

### Leave — `leaveStatusLabel` (format.js:166-174)
`SUBMITTED` รออนุมัติ / warning · `APPROVED` อนุมัติแล้ว / success · `REJECTED` ปฏิเสธแล้ว / danger · `CANCELLED` ยกเลิกแล้ว / neutral · `AUTO_REJECTED` โควตาไม่พอ / danger (**Blocked** — fix cert/notice & resubmit).

### Overtime — `overtimeStatusLabel` (format.js:138-146) · Special-money — same shape (format.js:153-161) · Commission — `commissionStatusLabel` (format.js:125-133)
Shared two-hop shape: `SUBMITTED` รอผู้จัดการ / warning → `MANAGER_APPROVED` รอ CEO / info → `APPROVED` อนุมัติแล้ว / success · `REJECTED` ปฏิเสธ / danger · `CANCELLED`/`VOID` ยกเลิก / neutral(OT/SM)·danger(commission VOID). Two-hop flows must show **which hop** this is.

### Payroll run — `payrollStatusLabel` (format.js:328-336)
`PREVIEW` ตัวอย่าง / info · `OPEN` เปิดรอบ / warning · `PROCESSED` ประมวลผลแล้ว / success · `CLOSED` ปิดรอบ / neutral · `VOID` ยกเลิก / danger.

### Quotation — `quotationStatusLabel` (format.js:312-323)
`DRAFT` แบบร่าง / neutral · `ISSUED` ออกแล้ว / success · `SENT` ส่งแล้ว / info · `ACCEPTED` รับแล้ว / success · `REJECTED` ปฏิเสธ / danger · `EXPIRED` หมดอายุ / warning · `CANCELLED` ยกเลิก / danger · `SUPERSEDED` ถูกแทนที่ / neutral. ⚠️ `READY_TO_ISSUE`, `REVISION_REQUESTED` **unmapped** → Defect D-S2.

### Deposit policy — `depositPolicyLabel` (format.js:236-245) · Payment stage — `paymentStageLabel` (format.js:247-256)
Policy: `REQUIRED` ต้องเก็บมัดจำ / neutral · `NOT_REQUIRED` ไม่เก็บมัดจำ / warning · `WAIVED` ยกเว้นมัดจำ / warning · `CREDIT_CUSTOMER` ลูกค้าเครดิต / info.
Payment: `NOT_REQUIRED` ไม่ต้องชำระ / neutral · `DEPOSIT_PENDING` รอมัดจำ / warning · `DEPOSIT_RECEIVED` รับมัดจำแล้ว / success · `PARTIALLY_PAID` ชำระบางส่วน / warning · `BALANCE_PENDING` รอชำระส่วนที่เหลือ / warning · `FULLY_PAID` ชำระครบแล้ว / success. *(Deposit issued/not-issued is rendered inline `success/neutral`, DepositNoticePage.jsx:498 — not an enum.)*

### Fulfilment — `fulfilmentStatusLabel` (format.js:265-277) · Factory PO — `factoryPurchaseOrderStatusLabel` (format.js:283-291)
Fulfilment: `IR_ISSUED`/`IR_SENT`/`PICKED_UP`/`SHIPPING` info · `CUSTOMS_CLEARANCE` warning · `GOODS_RECEIVED`/`FROM_STOCK` success · `PARTIALLY_DELIVERED` warning · `FULLY_DELIVERED` success.
Factory PO: `OPEN` info · `SHIPPING` warning · `RECEIVED` success · `CANCELLED` danger.

### Attendance — `attendanceStatusLabel` (format.js:392-405) + flags (410-448)
`PRESENT` ปกติ / success · `LATE` มาสาย / **warning (never danger)** · `WFH` WFH / info · `MISSING_CHECK_IN` ขาดสแกนเข้า / warning · `MISSING_CHECK_OUT` ขาดสแกนออก / warning · `NON_WORKDAY` วันหยุด / neutral · `NO_RECORD` "-" / **plain muted text, not a badge** (AttendancePage.jsx:766). Flags are dynamic-number labels (`สาย {n} นาที` / warning, `โอที {h:mm}` / info, …).

### Employee — `referenceData.js:22-26` · Priority — `ticketPriorityLabel` (format.js:105-111) · Generic request — `requestStatus` (format.js:114-120)
Employee: `ACT` ทำงานปกติ / success · `PRB` ทดลองงาน / warning · `RSG` ลาออก / danger. Priority: `LOW` ต่ำ / neutral · `NORMAL` กลาง / warning · `HIGH` สูง / danger. Generic: `pending` รออนุมัติ / warning · `approved` อนุมัติแล้ว / success · `rejected` ปฏิเสธแล้ว / danger.

## Badge treatment reference

- **Badge (pill):** `min-height:26px`, `radius-pill`, `font-weight:800`, `12px`, tinted bg + matching dark text (`styles.css:1379-1397`). Interactive badge (button/link) grows to a **44px** touch target. Use for lifecycle/work states.
- **Plain text:** for non-state values (role labels, "-", counts, field meta). No pill.
- **Icon usage:** optional and additive (`StatusBadge icon` prop) — an icon never *replaces* the text. Use an icon only where it adds scanning value (e.g. `triangleAlert` on a Blocked/Overdue row); do not decorate every badge.
- **StatChip / StatCard tones** (`indigo/teal/amber/blue/rose`) are a **separate KPI-tile palette**, not status — do not use them for record state.

## Where each is actionable / responsible role

Actionability is the work-state layer, computed per viewer with the **real Java
role gates** (never the mock). A badge tone does not imply the viewer can act —
e.g. a `warning` "รอผู้จัดการ" commission is Needs-my-action for the manager,
Waiting for the rep, Informational for the CEO. The worklist uses work-state, not
the badge, to decide placement.

## Defects to fix in Phase 4 (recorded, not fixed here)

- **D-S1 — Ticket status case mismatch.** `ticketStatusLabel` keys are lowercase; backend `TicketStatus` is UPPERCASE (`DRAFT`…). A raw backend value falls through to `{label: rawValue, tone: neutral}` — an English raw string with the wrong tone. Confirm the API layer lowercases, or normalise the map. *(Any behavioural change here is a contract question, not UI polish — verify against the service.)*
- **D-S2 — Quotation unmapped values.** `READY_TO_ISSUE`, `REVISION_REQUESTED` exist in backend `QuotationStatus` but are absent from `quotationStatusLabel` → render as raw English + neutral. Add Thai labels + correct tones.
- **D-S3 — NotificationBell colour-alone + hardcoded hex.** Notification *type* is a coloured icon circle using raw hex (`#f59e0b/#3b82f6/#22c55e/#ef4444/#94a3b8`, `NotificationBell.jsx:7-16`), and read/unread is a coloured dot with **no text** — a colour-alone signal and a token bypass. Add a text/aria label for state; swap hex → tokens.

## Misuse to avoid

- **Unique colour per backend status** — collapse to the 6 tones by meaning.
- **A punitive (danger) tone on attendance lateness** — reporting-only (§76).
- **Colour-only status** anywhere (the NotificationBell dot is the current offender).
- **Badging non-states** — a role label or a "-" is plain text, not a pill.
- **Inferring actionability from tone** — actionability is the per-viewer work-state, gated by the real service.
- **A raw English enum leaking to the user** (the ticket-case and quotation-gap defects) — every surfaced value has a Thai label.
- **Reusing the StatCard KPI tones as status colours**, or vice-versa.
