# Phase 5A Tab Simplification Contract

Date: 2026-07-25

Scope: `/tickets/:id` ticket workspace tabs after the persistent command header and tab row.

This contract defines how each tab earns its place in the operational workspace. Tabs are not
containers for every workflow panel. Each tab answers a specific user question with compact,
role-sensitive information and one clear action path when that tab owns the current work.

## Global Tab Rules

- Each tab owns one primary job.
- Each tab opens with the most relevant active content, not a repeated deal summary.
- Current, waiting, blocked, completed and historical information must be visually distinct.
- Do not render large empty panels when a compact empty row or inline alert is enough.
- Do not duplicate the same status in the header, tab heading, record row and record body.
- Do not wrap tables/lists in multiple decorative card layers.
- Do not copy desktop density directly to mobile.
- Do not expose raw cost, margin or unauthorized document/payment data to roles that cannot see it.
- Preserve existing role projection, backend permission assumptions, query invalidation and workflow logic.
- Loading, error, empty, permission-limited, not-applicable and completed states follow
  `PHASE_5A_STATE_FEEDBACK_CONTRACT.md`; do not use the same empty state for all tab conditions.
- Mobile behaviour follows `PHASE_5A_MOBILE_WORKSPACE_CONTRACT.md`: each tab remains concise at
  `390 x 844` and must not recreate the full desktop workspace as a vertical stack.
- Badge use follows `PHASE_5A_BADGE_AUDIT.md`: short sub-record states may use badges; role
  labels, sequence steps, counts, revision numbers, item metadata and event types use text.
- Action use follows `PHASE_5A_ACTION_HIERARCHY.md`: tab-local actions must not duplicate the
  sticky primary, document actions stay row-local, and destructive row actions are confirmed.

## Overview Tab

Purpose: concise operational summary.

The Overview tab must not contain every workflow panel. It should answer: "What is this deal,
what is in it, and where should I look next?"

Content order:

1. Current work-state alert, only when meaningful.
2. Deal summary.
3. Product/item summary.
4. Compact workflow status summary.
5. Recent activity summary.
6. Role-appropriate secondary information.

### Deal Summary

Use a semantic `DescriptionList`.

Recommended fields:

- ลูกค้า
- โครงการ
- ผู้ดูแลดีล
- วันที่สร้าง
- อัปเดตล่าสุด
- ผู้ติดต่อ
- Stage
- Lifecycle

Rules:

- Do not display these fields as disabled inputs.
- Do not use one card per field.
- Empty or unavailable values use the shared compact empty-value treatment.
- Details already shown in the persistent header should appear here only when they support
  reference work, not as another status banner.

### Item Summary

Show the item list as a table or structured list.

The item summary must show:

- Product identity.
- Finish/variant.
- Quantity.
- Pricing readiness.
- Approved price where authorized.
- Fulfilment progress where relevant.

Rules:

- Do not wrap the item table in several card layers.
- Use role-sensitive columns.
- Do not expose raw cost or margin to unauthorized roles.
- Mobile may reflow item rows into compact records, but each record keeps the same information
  priority.

### Workflow Summary

Show one compact cross-track summary:

- ราคา
- ใบเสนอราคา
- มัดจำ
- จัดซื้อ/นำเข้า
- ส่งมอบ
- ชำระส่วนที่เหลือ
- ปิดงาน

Each row states:

- Current state.
- Responsible role.
- Whether the track is complete, active, waiting, blocked or not started.

Rules:

- This is one summary, not seven separate cards.
- Rows may link to the owning tab.
- The row for the current blocker/action may receive modest emphasis.
- Do not repeat every child-tab badge here.

### Recent Activity

Show only the latest three meaningful events on Overview.

Required action:

- `ดูกิจกรรมทั้งหมด` activates the Activity tab.

Rules:

- Do not render the complete timeline on Overview.
- System noise should not displace meaningful current work.

## Pricing Tab

Purpose: pricing-request ownership and action state.

The Pricing tab should answer:

1. How many pricing requests exist?
2. Which request is active?
3. Who currently owns it?
4. What is blocking it?
5. What action can this role take?

### Pricing-Request List

Each pricing request is an independent record, so a bordered row or compact disclosure item is
justified.

Preferred structure:

```text
Pricing tab surface
├── Compact heading + create action
├── Pricing request row
│   ├── request number
│   ├── recipient
│   ├── current state
│   ├── owner
│   ├── deadline
│   └── open action
└── Additional requests
```

Rules:

- Do not put the list inside another oversized card.
- Do not expand every request by default.
- The currently active request may be expanded.
- Other requests remain collapsed summary rows.
- Deep request work should link to or disclose request detail intentionally.

### Pricing Empty State

When no pricing request exists:

- Use a compact inline empty state.
- Explain the next step.
- Show create action only when permitted.
- Do not reserve a large blank panel.

### Pricing Status Duplication

Do not repeat the same pricing state in:

- Persistent header.
- Pricing tab heading.
- Request row.
- Request body.
- Multiple badges.

One primary status label per level is sufficient.

## Quotations Tab

Purpose: customer quotation documents and customer outcome.

Group quotation records by recipient or workflow relevance. Each quotation revision may be a row
because it is an independent document.

Use a dense document list with:

- เลขที่เอกสาร
- ผู้รับ
- Revision
- สถานะ
- วันที่ออก
- ผู้ดำเนินการ
- ดาวน์โหลด

Avoid:

- One large card per quotation.
- Repeating the entire deal summary.
- Multiple large download buttons.
- Nested panels for PDF and Excel.

Rules:

- Download formats may use a compact menu or grouped row action.
- Customer outcome is visually distinct from document status.
- Do not merge quotation document status with customer accepted/rejected state.
- Historical revisions stay in the document list; the current decision, when present, gets the
  active decision treatment.

## Money Tab

Purpose: payment state, billing dates, payment history and permitted finance actions.

The payment area must not use three large metric cards for:

- Amount payable.
- Amount paid.
- Outstanding amount.

Replace them with a compact financial summary:

```text
ยอดที่ต้องชำระ     ฿0
รับชำระแล้ว        ฿0
คงเหลือ             ฿0
วันวางบิล           -
วันครบกำหนด         -
สถานะ               ไม่ต้องชำระ
```

Rules:

- Use aligned rows.
- Use tabular numbers.
- Apply one visual emphasis to the most relevant amount.
- Do not create one decorative card per metric.
- Keep `บันทึกรับชำระเงิน`, `ตั้งค่าการวางบิล`, and `ยืนยันพร้อมปิดงาน` only where permitted.
- Do not display actions irrelevant to the current role.
- Do not repeat the same primary action in both the tab and sticky bar.

### Payment History

Use a compact table or timeline.

Do not place each payment inside a separate card unless it includes independently actionable
evidence.

## Procurement And Fulfilment Tab

Purpose: connected supply and customer-delivery progress.

The existing fulfilment area must not read as multiple independent nested blocks. Reframe it as
two connected tracks:

- A. การจัดหา / นำเข้า
- B. การส่งมอบลูกค้า

Each track uses one ordered step list.

### Procurement / Import Steps

Example steps:

- ออก Import Request
- ส่งคำสั่งซื้อไปผู้ผลิต
- รับจากผู้ผลิต
- อยู่ระหว่างเดินทาง
- รอออกของ
- ถึงโกดัง GL&R
- ใช้สินค้าจากสต็อก

### Customer-Delivery Steps

Example steps:

- ยังไม่เริ่มส่งมอบ
- ส่งมอบบางส่วน
- ส่งมอบครบแล้ว

Rules:

- Use step rows.
- Use status labels.
- Use one current-step emphasis.
- Use compact supporting text.
- Do not place each step inside its own full card.

### Item Fulfilment

Item-level progress should be a compact list or table with:

- สินค้า
- จำนวนสั่ง
- จำนวนรับ
- จำนวนส่งมอบ
- คงเหลือ

Avoid nested card structures.

### Terminology Guardrail

Never confuse:

- `สินค้าถึงโกดัง GL&R`

with:

- `ส่งมอบถึงลูกค้า`

These are separate states and must remain visually and semantically separate.

## Documents Tab

Purpose: canonical document and attachment list.

The Documents tab must not show a large blank attachment card.

Use a dense file list grouped by type:

- ใบเสนอราคา
- ใบแจ้งมัดจำ
- ใบแจ้งหนี้
- ใบกำกับภาษี
- PO
- เอกสารอื่น

Each file row shows:

- Filename.
- Document type.
- Uploaded/issued date.
- Actor.
- Download action.
- Delete action where permitted.

### Documents Empty State

Use a compact empty row:

```text
ยังไม่มีเอกสารแนบ
แนบ PO หรือใบเซ็นได้จากปุ่ม "แนบไฟล์"
```

Rules:

- Do not center a large paperclip illustration in a mostly empty panel.
- Use one clear upload action.
- Show progress, success, failure, accepted file types and maximum size when known.
- Do not duplicate upload controls in several tabs.

## Activity Tab

Purpose: one chronological activity structure.

The Activity tab combines:

- System events.
- Stage transitions.
- Comments.
- Sales follow-up activity.
- Payment events.
- Fulfilment events.

Rules:

- Do not maintain multiple separate history panels.
- Do not display a separate permanent comment panel alongside another activity form.
- Comments belong in the same Activity tab.

### Activity Filters

Optional compact filters:

- ทั้งหมด
- สถานะ
- ความคิดเห็น
- การติดตาม
- การเงิน
- การส่งมอบ

Do not use a large filter card.

### New Activity Form

The add-activity form must not permanently occupy a large bordered block.

Use one of:

- Compact composer.
- Expandable section.
- Drawer on mobile.

Default state:

- `+ บันทึกกิจกรรม`

Expanded state contains:

- Date.
- Type.
- Note.
- Submit.

After submission, collapse the form and place the new event into the timeline.

## Acceptance Checks

Implementation is not complete until:

- Overview shows a concise operational summary, not every workflow panel.
- Overview uses a semantic `DescriptionList` for deal summary.
- Overview item summary uses role-sensitive table/list columns and hides unauthorized cost/margin.
- Overview workflow status is one compact cross-track summary, not seven cards.
- Overview shows at most three meaningful recent events and links to Activity.
- Pricing shows request count, active request, owner, blocker and role action without expanding all requests.
- Pricing empty state is compact and action-aware.
- Quotations are dense document rows and keep customer outcome separate from document status.
- Money uses compact financial rows instead of metric cards.
- Fulfilment uses two connected ordered tracks and never confuses warehouse receipt with customer delivery.
- Documents uses grouped dense file rows and compact empty/upload states.
- Activity uses one chronological structure for events, comments and follow-up.
- Mobile layouts keep each tab concise rather than reproducing the whole desktop stack.
- Each tab distinguishes loading, error, empty, permission-limited, not-applicable and completed
  content with distinct Thai-first messages and tab-local retry where recoverable.
