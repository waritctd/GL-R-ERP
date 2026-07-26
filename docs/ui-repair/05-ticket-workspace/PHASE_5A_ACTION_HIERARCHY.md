# Phase 5A Action Hierarchy

Date: 2026-07-25

Scope: visible actions in `/tickets/:id` and its ticket workspace child panels.

This document is the Step 16 action audit. It defines which actions may become the single
primary next action, which actions are contextual or secondary, and which actions must move into
overflow or confirmation flows.

## Action Classes

Use only these classes when reviewing workspace actions:

- `PRIMARY NEXT ACTION`
- `CONTEXTUAL ACTION`
- `SECONDARY ACTION`
- `DESTRUCTIVE ACTION`
- `DOCUMENT ACTION`
- `NAVIGATION ACTION`

Action hierarchy is behavioural information architecture. It is not button styling.

## Global Rules

- Each role and deal state has one primary-action slot.
- If the viewer has no permitted transition, the primary slot is empty and the header explains
  who owns the work. Do not invent a disabled primary action that the backend would reject.
- A visible surface must not show five equal-weight workflow buttons together.
- The persistent action bar may contain one primary action, up to two common secondary actions,
  one overflow/other-actions control, and destructive actions separated from ordinary actions.
- Destructive actions are never visually competitive with the primary workflow action.
- Destructive actions must be confirmed and must capture a reason where the business flow needs
  one.
- Document download/upload actions are local to Documents or to the row/document that owns them.
- Navigation actions are links or tab changes; they must not look like workflow approval buttons.
- English/internal vocabulary must not leak into Thai labels unless the business explicitly uses
  the term.

## Persistent Action Bar Contract

Preferred structure:

```text
Primary:
เลื่อนไปขั้นถัดไป

Secondary:
แก้ไขสถานะ

More actions:
พักดีลไว้
พักเป็นดีลไม่เคลื่อนไหว
ทำเครื่องหมายเสียงาน
ยกเลิกดีล
```

Rules:

- `เลื่อนไปขั้นถัดไป` is the primary only when the server offers `ADVANCE_STAGE` and no more
  specific tab-owned action outranks it.
- `แก้ไขสถานะ` is a secondary/manual override action.
- `พักดีลไว้`, `พักเป็นดีลไม่เคลื่อนไหว`, `ทำเครื่องหมายเสียงาน`, `ยกเลิกดีล` move to
  `การดำเนินการอื่น`.
- `ทำเครื่องหมายเสียงาน`, `ยกเลิกดีล`, file delete, pricing-request cancel and negative
  customer outcomes are visually separated from ordinary actions and confirmed.
- On mobile, the sticky bar follows `PHASE_5A_MOBILE_WORKSPACE_CONTRACT.md`: it includes
  safe-area padding, never covers the last row/form action/empty state/timeline entry, and stays
  reachable without requiring users to scroll through history.
- Replace `พัก dormant` with Thai-first wording: `พักเป็นดีลไม่เคลื่อนไหว` or
  `พักดีลระยะยาว`.
- Avoid English button labels such as `Shipping`, `Goods Received`, `WAREHOUSE`, `STOCK` unless
  they are explicit business document names. Prefer `สินค้าออกเดินทาง`, `รับสินค้าเข้าคลัง`,
  `คลัง GL&R`, `สต็อก`.

## Primary Resolver Priority

When multiple backend actions are available, the UI picks one primary next action and demotes the
rest to secondary, contextual, overflow or hidden states.

Priority order:

1. Terminal recovery: `RESUME`, `REOPEN` when the record is paused/lost and the viewer may act.
2. Overdue/payment blocker: `FINAL_PAYMENT`, then `RECORD_PAYMENT`, then `SET_BILLING`.
3. Pricing-chain work: respond info, revise costing, pick up pricing request, start CEO review,
   decide pricing, create customer quotation, confirm order.
4. Ticket chain work: confirm customer, issue deposit notice, confirm deposit paid, issue import
   request, mark IR sent, mark shipping, mark goods received, reserve stock, record delivery,
   complete delivery, confirm close, verify close.
5. Manual stage advance: `ADVANCE_STAGE`.
6. No permitted action: show waiting/blocker/read-only text, not a fake primary button.

This priority follows `dealWorkState.js` and existing action gates. It must not change backend
permissions or status-machine behaviour.

## Primary Next Action By Role And State

`-` means no primary action is shown for that viewer; the workspace shows waiting/read-only
context instead.

| Deal/work state | Sales owner | Sales manager | Import | Account | CEO |
|---|---|---|---|---|---|
| Draft/no pricing request | สร้างใบขอราคา | - | - | - | - |
| Pricing request draft | ส่งใบขอราคา | - | - | - | - |
| Pricing request submitted | - | - | รับงานขอราคา | - | - |
| Pricing request needs sales info | ตอบข้อมูลเพิ่มเติม | - | - | - | - |
| Costing revision required | - | - | แก้ไขต้นทุน | - | - |
| Import costing/factory price in progress | - | - | Continue pricing task in Pricing tab | - | - |
| Ready for CEO review | - | - | - | - | เริ่มตรวจราคาขาย |
| CEO reviewing price | - | - | - | - | ตัดสินใจราคาขาย |
| Approved for customer quotation | สร้างใบเสนอราคาลูกค้า | - | - | - | - |
| Customer quotation draft/editable | ออกใบเสนอราคา | - | - | - | - |
| Customer quotation issued | บันทึกผลจากลูกค้า | - | - | - | - |
| Customer quotation accepted | ยืนยันคำสั่งซื้อ | - | - | - | - |
| Deposit notice required | สร้าง/ออกใบแจ้งยอดมัดจำ | - | - | - | - |
| Deposit notice issued | - | - | - | ยืนยันรับมัดจำ | - |
| Procurement/import step active | - | - | ออก/อัปเดต Import Request | - | ออก/อัปเดต Import Request when explicitly permitted |
| Stock can fulfil order | - | - | จองสินค้าจากสต็อก | - | จองสินค้าจากสต็อก when explicitly permitted |
| Customer delivery active | - | - | บันทึกการส่งสินค้า | - | บันทึกการส่งสินค้า when explicitly permitted |
| Delivery can be completed | - | - | ส่งมอบครบ | - | ส่งมอบครบ when explicitly permitted |
| Payment due or overdue | - | - | - | บันทึกรับชำระเงิน / ยืนยันรับเงินครบ | - |
| Billing details missing | - | - | - | ตั้งค่าการวางบิล | - |
| Ready for account close confirmation | - | - | - | ยืนยันพร้อมปิดงาน | - |
| Account confirmed close | - | - | - | - | ตรวจสอบและปิดงาน |
| On hold/dormant | ดำเนินการต่อ when permitted | ดำเนินการต่อ when permitted | - | - | ดำเนินการต่อ when permitted |
| Lost deal | เปิดดีลอีกครั้ง when permitted | เปิดดีลอีกครั้ง when permitted | - | - | เปิดดีลอีกครั้ง when permitted |
| Closed/cancelled/completed | - | - | - | - | - |
| Informational/waiting/read-only | - | - | - | - | - |

If a row uses "when explicitly permitted", the action may only appear when `availableActions`
contains the matching backend action. Role alone is not enough.

## Visible Action Inventory

| Component / Tab | Current Visible Action | Current Surface | Classification | Target Hierarchy |
|---|---|---|---|---|
| `Breadcrumbs` | `ดีล` back/up navigation | Top of page | NAVIGATION ACTION | Keep compact. It is not part of action bar priority. |
| `DealStateHeader` | Primary action slot (`ลูกค้ายืนยัน`, final payment, close, verify close, etc.) | Header | PRIMARY NEXT ACTION | Keep exactly one primary slot, sourced from the resolver. |
| `DealStateHeader` | `รีเฟรช` icon | Header | CONTEXTUAL ACTION | Keep as icon utility. It must not look like a workflow action. |
| `Tabs` | Switch Overview/Pricing/etc. | Tab row | NAVIGATION ACTION | Keep as navigation. Does not count as workflow action. |
| `DealStagePanel` | `ดูขั้นตอนทั้งหมด` | Overview stage section | CONTEXTUAL ACTION | Keep as disclosure. Full stage detail stays collapsed. |
| `DealStagePanel` | `เลื่อนไป: <stage>` | Overview stage action row | PRIMARY NEXT ACTION | Move to sticky action bar as `เลื่อนไปขั้นถัดไป` only when it wins resolver priority. |
| `DealStagePanel` | `แก้ไขสถานะ...` | Overview stage action row | SECONDARY ACTION | Keep as secondary/manual override. |
| `DealStagePanel` | Tender requirement select | Overview stage section | CONTEXTUAL ACTION | Keep near tender context only. Not an action-bar item. |
| `DealStagePanel` | `พักดีลไว้` | Overview stage action row | SECONDARY ACTION | Move to overflow/other actions. Confirm if note required. |
| `DealStagePanel` | `พัก dormant` | Overview stage action row | SECONDARY ACTION | Move to overflow and rename Thai-first: `พักเป็นดีลไม่เคลื่อนไหว`. |
| `DealStagePanel` | `เสียงาน` | Overview stage action row | DESTRUCTIVE ACTION | Move to destructive group in overflow; keep reason modal/confirmation. |
| `DealStagePanel` | `เปิดดีลอีกครั้ง` | Lost-state panel | PRIMARY NEXT ACTION | Primary only for lost deals when `REOPEN` is offered; otherwise absent. |
| `DealStagePanel` | Hold/resume/dormant modal `บันทึก` | Modal | CONTEXTUAL ACTION | Inherits the selected overflow action. Modal primary is local submit only. |
| `UpdateStageModal` | Submit stage override | Modal | SECONDARY ACTION | Manual override; never competes with primary next action. |
| `MarkLostModal` | Mark lost submit | Modal | DESTRUCTIVE ACTION | Requires reason and confirmation-style separation. |
| Overview `การดำเนินการอื่น ๆ` | `ขอแก้ไข` | Mid-page panel | SECONDARY ACTION | Move into overflow/other actions. |
| Overview `การดำเนินการอื่น ๆ` | `แก้ไขรายการสินค้า` | Mid-page panel | SECONDARY ACTION | Move to Overview item section or overflow depending state. |
| Overview `การดำเนินการอื่น ๆ` | `ยกเลิก` | Mid-page panel | DESTRUCTIVE ACTION | Move to destructive overflow with `CancelDealModal`. |
| Overview revise form | Revise submit `ส่งคำขอแก้ไข` | Inline form | SECONDARY ACTION | Temporary layer opened from overflow; not persistent. |
| Overview item edit | Add item, delete item, save/cancel item edits | Item edit mode | CONTEXTUAL ACTION | Local to item table editing. Delete line is destructive/contextual and icon-only needs accessible name. |
| Pricing tab | `สร้างใบขอราคา` | Pricing panel heading/empty state | PRIMARY NEXT ACTION | Primary only when pricing is the current required work. Otherwise tab-local contextual action. |
| Pricing request row | Expand/collapse row | Pricing list | CONTEXTUAL ACTION | Row disclosure only. |
| Pricing request row | `แก้ไขร่าง` | Pricing row | CONTEXTUAL ACTION | Row-local edit. |
| Pricing request row | `ส่งให้ Import` | Pricing row | PRIMARY NEXT ACTION | Primary when the request draft is the current required work. |
| Pricing request row | `ขอข้อมูลเพิ่มเติม` | Pricing row | CONTEXTUAL ACTION | Row-local request-info action. |
| Pricing request row | `ตอบข้อมูลเพิ่มเติม` | Pricing row | PRIMARY NEXT ACTION | Primary when returned/more-info is the current required work for sales. |
| Pricing request row | `ยกเลิก` | Pricing row | DESTRUCTIVE ACTION | Separate and confirm with reason. |
| Pricing modals | `บันทึก`, `ส่งคำขอ` | Modal | CONTEXTUAL ACTION | Modal submit inherits the row-local trigger. |
| Pricing cancel modal | `ยืนยันยกเลิก` | Modal | DESTRUCTIVE ACTION | Destructive submit; requires reason and separation from ordinary modal actions. |
| Pricing modals | Cancel/close | Modal | SECONDARY ACTION | Modal-local escape action. |
| Quotations tab | `ดูรายละเอียดเต็ม` | Quotation heading | NAVIGATION ACTION | Link to full Pricing Request. |
| Quotations tab | `สร้างร่างใบเสนอราคาลูกค้า` | Quotation empty/current decision | PRIMARY NEXT ACTION | Primary when quotation creation is current work. |
| Quotation row | `PDF`, `Excel` | Quotation document row | DOCUMENT ACTION | Group into compact row action/menu. |
| Quotation row | `แก้ไขรายละเอียด/ส่วนลด` | Quotation document row | NAVIGATION ACTION | Link to quotation/pricing detail. |
| Quotation row | `ออกใบเสนอราคา` | Quotation document row | PRIMARY NEXT ACTION | Primary when draft quote is current work. |
| Quotation outcome | `ลูกค้ายอมรับ` | Quotation decision area | PRIMARY NEXT ACTION | Primary only when waiting on customer outcome. |
| Quotation outcome | `ลูกค้าขอแก้ไข` | Quotation decision area | CONTEXTUAL ACTION | Secondary decision outcome; may require note. |
| Quotation outcome | `ลูกค้าปฏิเสธ` | Quotation decision area | DESTRUCTIVE ACTION | Separate from accept/revision and confirm if irreversible. |
| Quotation accepted | `ยืนยันคำสั่งซื้อ` | Quotation decision area | PRIMARY NEXT ACTION | Primary when customer accepted and order is not confirmed. |
| Money tab | `บันทึกรับชำระเงิน` | Money panel | PRIMARY NEXT ACTION | Primary for account when payment recording is current work; otherwise tab-local contextual action. |
| Money tab | `ตั้งค่าการวางบิล` | Money panel | SECONDARY ACTION | Secondary finance setup; primary only if billing missing is the blocker. |
| Money modal | Payment/billing `บันทึก`, `ยกเลิก` | Modal | CONTEXTUAL ACTION | Modal-local submit/cancel. |
| Header/dialog | `ยืนยันชำระครบ (Final Payment)` | Header + confirm dialog | PRIMARY NEXT ACTION | Account primary when final payment is offered; must confirm. |
| Deposit tab | `เปลี่ยนนโยบายมัดจำ...` | Deposit policy row | SECONDARY ACTION | Row-local policy edit. |
| Deposit tab | `ตัวอย่าง` | Deposit notice row | DOCUMENT ACTION | Document preview. |
| Deposit tab | Deposit `PDF`, `Excel` | Deposit notice row | DOCUMENT ACTION | Compact document row actions/menu. |
| Deposit tab | `ออกเอกสาร` | Deposit notice row | PRIMARY NEXT ACTION | Primary when issuing deposit notice is current work. |
| Deposit tab | `สร้างใบแจ้งยอดเงินรับมัดจำ` | Deposit notice row | PRIMARY NEXT ACTION | Primary when creating notice is current work. |
| Deposit tab | `ออกใบแจ้งยอดมัดจำ` link | Deposit notice row | NAVIGATION ACTION | Navigates to full deposit page; style as navigation unless it is the only current work action. |
| Deposit tab | `ไปที่ใบแจ้งยอดเงินรับมัดจำ...` | Deposit notice row | NAVIGATION ACTION | Keep as text link. |
| Deposit tab | `ยืนยันรับมัดจำ` | Deposit payment row | PRIMARY NEXT ACTION | Account primary when deposit payment is current work. |
| Deposit policy modal | `บันทึก`, `ยกเลิก` | Modal | CONTEXTUAL ACTION | Modal-local submit/cancel. |
| Fulfilment tab | `ออก Import Request (IR)` | Import track | PRIMARY NEXT ACTION | Import/CEO primary when current procurement step is IR issue. |
| Fulfilment tab | `ส่ง IR แล้ว` | Import track | PRIMARY NEXT ACTION | Import/CEO primary when current step is supplier order sent. |
| Fulfilment tab | `สินค้าออกเดินทาง (Shipping)` | Import track | PRIMARY NEXT ACTION | Rename Thai-first: `บันทึกสินค้าออกเดินทาง`. |
| Fulfilment tab | `รับสินค้าแล้ว (Goods Received)` | Import track | PRIMARY NEXT ACTION | Rename Thai-first: `รับสินค้าเข้าคลัง GL&R`. |
| Fulfilment tab | `จองสินค้าจากสต็อก` | Import track | PRIMARY NEXT ACTION | Primary only when stock fulfilment is the current path; otherwise contextual. |
| Fulfilment tab | `บันทึกการส่งสินค้า` | Delivery track | PRIMARY NEXT ACTION | Primary when customer delivery is current work. |
| Fulfilment tab | `ส่งมอบครบ` | Delivery track | PRIMARY NEXT ACTION | Primary when all delivery quantities can be completed. |
| Factory PO row | `รายละเอียด` | Factory PO row | NAVIGATION ACTION | Link to Factory PO detail. |
| Delivery/stock modals | `บันทึก`, `ยกเลิก` | Modal | CONTEXTUAL ACTION | Modal submit inherits trigger. |
| Documents tab | `แนบใบกำกับภาษี` | Documents header | DOCUMENT ACTION | Single upload route for invoice when permitted. |
| Documents tab | `แนบไฟล์` | Documents header | DOCUMENT ACTION | Single general upload route. |
| Documents row | `ดูไฟล์` | File row | DOCUMENT ACTION | Keep row-local. |
| Documents row | Delete file icon | File row | DESTRUCTIVE ACTION | Keep row-local but separated, named and confirmed. |
| Delete file dialog | Confirm delete / cancel | Confirm dialog | DESTRUCTIVE ACTION | Confirm destructive action; cancel is secondary. |
| Activity tab | `แก้ไขข้อมูลติดตาม` | Tracking section | CONTEXTUAL ACTION | Opens edit mode; not global action. |
| Activity edit | `บันทึก`, `ยกเลิก` | Edit form | CONTEXTUAL ACTION | Form-local actions. |
| Activity tab | `+ บันทึกกิจกรรม` / `บันทึกกิจกรรม` | Activity composer | CONTEXTUAL ACTION | Expandable composer; not permanently large. |
| Activity tab | `ส่งความคิดเห็น` | Comment composer | CONTEXTUAL ACTION | Fold into one activity composer. |
| Not-found state | Back button | Not-found | NAVIGATION ACTION | Should route onward via EmptyState CTA contract. |

## Current Screenshot-State Recommendation

The current rendered state shows the problem clearly: workflow controls can appear as a flat row
such as:

```text
เลื่อนไป...
แก้ไขสถานะ...
เสียงาน
พักดีลไว้
พัก dormant
```

Target hierarchy for that shape:

```text
Primary:
เลื่อนไปขั้นถัดไป

Secondary:
แก้ไขสถานะ

More actions:
พักดีลไว้
พักเป็นดีลไม่เคลื่อนไหว
ทำเครื่องหมายเสียงาน
ยกเลิกดีล
```

If the work-state is an overdue payment blocker, the primary changes by role:

- `account`: `บันทึกรับชำระเงิน`, `ยืนยันรับเงินครบ` or `ตั้งค่าการวางบิล` by resolver priority.
- `sales`, `sales_manager`, `import`, `ceo`: no fake payment primary; show waiting/blocker text
  and route to the Money tab as supporting detail.

## Implementation Acceptance

Implementation is not complete until:

- Every visible action in the ticket workspace maps to one of the six classes.
- The persistent action bar renders at most one primary action.
- The persistent action bar renders at most two common secondary actions before overflow.
- Secondary and destructive stage actions move out of the first body panel row.
- Destructive actions are visually separated and confirmed.
- `พัก dormant` is replaced with Thai-first terminology.
- English/internal labels are removed from user-facing action text unless explicitly business
  required.
- Document actions are row-local or Documents-tab-local.
- Navigation links are visually distinct from workflow submit/approval actions.
- Role/state E2E evidence proves the primary action is unique across all Phase 5 viewports.
