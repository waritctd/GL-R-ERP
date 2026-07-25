# Ticket (Deal) Information Architecture

Because **one deal = one ticket** (confirmed against code), the ticket is the core sales
record and the app's most complex screen. This defines *what information it holds and where
that information belongs* — persistent header, tabs, context panel, action bar, mobile —
without designing the visuals. It reconciles the 18 required information regions against the
real data model (PCR aggregate, payment/fulfilment axes, two-signature close).

> Structure only. No component styling here (Phase 3) and no build (Phase 4). Role-sensitive
> projection below mirrors the **Java** visibility rules (import gets a projected DTO;
> account is money-scoped; sales sees no cost/margin) — not the mock.

## The 18 information regions → placement

| # | Region | What it is | Placement | Mobile |
|---|--------|-----------|-----------|--------|
| 1 | **Identity** | deal #, title, customer name, owner | **Persistent header** | Always visible (condensed) |
| 2 | **Customer & project** | customer, project, contact | Header (summary) + **Overview tab** (detail) | Summary in header; detail in tab |
| 3 | **Current stage** | 14-step `DealStage` + phase (เฟส 1–5) | **Persistent header** (stage strip) | Compact stage chip + "ดูขั้นตอน" |
| 4 | **UX work state** | computed (WORK_STATE_MODEL) for this viewer | **Persistent header** (the "whose move" banner) | Always visible — top priority |
| 5 | **Current owner** | ticket `createdById` (the rep) | Header | Visible |
| 6 | **Waiting-on role** | who the next move belongs to | **Persistent header** (part of the work-state banner) | Visible |
| 7 | **Blocker** | unmet precondition (deposit unpaid, more-info, returned) | **Header alert** when present; detail in the relevant tab | Visible when blocking |
| 8 | **Next action** | the primary allowed action for this viewer | **Action bar** (sticky) | Sticky bottom action bar |
| 9 | **Items / products** | ticket_item lines (qty reconciled from PCR) | **Overview tab** | Tab; card reflow |
| 10 | **Pricing requests** | the PCR aggregate(s): designer/owner/buyer + revisions, each with its own status chain | **Pricing tab** (list → PCR detail) | Tab; deep-links to `/pricing-requests/:id` |
| 11 | **Approved pricing** | approved selling price (sales-view; **cost/margin CEO+import only**) | **Pricing tab** (approved section) | Tab |
| 12 | **Quotations** | customer quotation(s), status, outcome, doc | **Quotations tab** | Tab; doc download |
| 13 | **Deposits & payments** | paymentStatus, deposit notice, payment ledger, billing | **Money tab** (account/ceo/sales-owner; **import denied**) | Tab |
| 14 | **Procurement / fulfilment** | fulfilmentStatus, factory POs, delivery records | **Fulfilment tab** (import/ceo; scoped) | Tab |
| 15 | **Documents** | quotation / deposit-notice / remaining-invoice (generated) + tax invoice / attachments (uploaded) | **Documents tab** (or context panel) | Tab; list |
| 16 | **Notes** | free-text notes, stage-change notes, cancel/lost reasons | **Context panel** (side) + inline on activity | Collapsible section |
| 17 | **Activity history** | tracking/activity events, follow-ups | **Activity tab** (or context panel timeline) | Tab timeline |
| 18 | **Audit history** | status transitions with actor + timestamp | **Activity tab** (audit sub-view) or Documents-adjacent | Tab; read-only |

## Persistent header (always visible)

The header answers "which deal, where is it, whose move, what next" without scrolling —
the four questions the design law demands. It holds:

- **Identity** (1): deal #, customer, project title, owner.
- **Stage strip** (3): the 14-step pipeline with phase grouping and the current step — the
  existing pipeline strip, kept; "ดูขั้นตอนทั้งหมด (14 ขั้น)" expands detail.
- **Work-state banner** (4/6/7): "รอคุณอนุมัติราคา" / "รอฝ่ายนำเข้า" / "CEO ส่งกลับให้แก้ไข"
  — the single most important line, viewer-specific, with the blocker if any.
- **Not** a full-width centered "กลับ" back bar (F-14) — the breadcrumb is the single up-nav;
  drop the marketing-ish back bar.

## Tabs (the deal's depth)

Tabs are **role-projected** — a viewer only sees tabs they have data/permission for:

| Tab | Contents | Visible to |
|---|---|---|
| **ภาพรวม (Overview)** | customer/project/contact (2), items (9), summary, notes | all viewers of the deal |
| **ราคา (Pricing)** | PCR list + detail (10), approved selling price (11) for all listed; **cost / margin / factory-raw-price sub-sections import+ceo ONLY** (CF1/CF3) | approved-price view: sales(owner), **sales_manager (price only)**, import, ceo · **cost/margin/factory sub-sections: import, ceo only** |
| **ใบเสนอราคา (Quotations)** | quotations (12), outcomes, docs | sales(owner), sales_manager, ceo, import(view); **account excluded** |
| **การเงิน (Money)** | payments/deposit/billing (13) | account, ceo, sales(owner); **import denied** |
| **จัดซื้อ-ส่งมอบ (Fulfilment)** | fulfilment + factory POs + delivery (14) | import, ceo; scoped |
| **เอกสาร (Documents)** | generated + uploaded docs (15) | per-doc visibility (deposit notice hidden from import) |
| **กิจกรรม (Activity)** | activity/tracking (17) + audit history (18) | all viewers (audit read-only) |

Tab visibility follows the frontend `salesViewScope` mirror, which itself mirrors the Java
projection (import sees pricingRequest+delivery, account sees payment/delivery/quotation/
depositNotice, both hidden from dealTracking). **This is presentation projection, not the
security boundary** — the backend still enforces per-endpoint. The UI must not render a tab
whose data the backend would 403.

## Context / side panel

A persistent right-side (desktop) context panel for **cross-tab, always-relevant** info:
- **Next action** summary + who's waiting (mirrors the action bar).
- **Notes** (16) — quick-add + recent.
- **Key dates** — follow-up, billing/due, quotation expiry.
- **People** — owner, assigned import (on the PCR), account contact.

On mobile the context panel collapses into sections below the active tab (no side rail).

## Action bar (sticky)

The **allowed actions for this viewer in this state** — never a wall of every possible
button. Driven by the same role+state gates as the backend (`salesActions`/`importActions`/
`accountActions` resolvers, re-checked server-side):
- **Primary** = the single next action (8) for the work-state (e.g. "ออกใบเสนอราคา",
  "ยืนยันรับมัดจำ", "อนุมัติราคา").
- **Secondary** = contextual (hold/resume, revise, cancel/mark-lost) behind a clearer
  affordance.
- **Disabled actions explain why** (design law) — e.g. "ออกใบเสนอราคา" disabled until CEO
  approves, with the reason inline (WHY was a Phase-1 gap on the ticket detail).
- **Two-signature close** renders as two distinct states: account sees "ยืนยันพร้อมปิดงาน";
  CEO sees "ตรวจและปิดงาน" — never one combined "close" button (B-10).

## Mobile — what stays, what hides

- **Always visible on mobile:** identity (1), stage chip (3), work-state banner (4/6/7),
  the sticky primary next action (8), blocker (7) when present.
- **Hidden/behind tabs on mobile:** full stage detail, cost/margin tables, the payment
  ledger grid, factory-PO tables — reachable but not default (dense grids reflow to cards).
- **Never on mobile:** nothing is *forbidden*, but cost/margin and the full audit table are
  low-priority; the deal must be *actionable* on mobile (approve, confirm, log activity,
  check status) even if deep editing (costing, quotation building) is desktop-leaning.

## Role-sensitive information (must not leak)

| Data | Who may see | Enforcement |
|---|---|---|
| **Cost / landed cost** | import, ceo | costing/decision `RAW_*` roles |
| **Margin / selling-price math** | ceo (decision), import (cost side) | `RAW_DECISION_ROLES={import,ceo}`; sales gets `salesView` (price only) |
| **Payment ledger / deposit notice** | account, ceo, sales(owner) | import denied (`DepositNoticeService`, `listPayments`) |
| **Customer quotation content** | sales(owner), sales_manager, ceo, import(view) | account excluded |
| **Deal at all** | `VIEWER_ROLES={sales,import,ceo,account,sales_manager}`, sales own-only | `requireViewAccess`; hr/employee cannot read tickets |

The ticket screen is **not** a place to show employee PII — that lives only on the HR
employee record. Keep the two record types' sensitive surfaces separate.

## Completed sub-flows

When a phase is done, it should **collapse to a confirmed summary**, not vanish and not keep
shouting:
- Approved pricing → a compact "ราคาอนุมัติแล้ว" summary in the Pricing tab (the working
  costing/decision detail collapses).
- Paid deposit → "รับมัดจำแล้ว ✓ <date/amount>" in the Money tab; the confirm action is gone.
- Delivered → "ส่งมอบครบ ✓" in Fulfilment.
- The stage strip shows completed steps as done (existing behaviour) — keep.

## Returned / rejected work

Returned work must read as **rework with a reason**, distinct from fresh work:
- CEO-returned costing (`COSTING_REVISION_REQUIRED`) → the Pricing tab surfaces the
  `returnReason` prominently and marks the PCR "ส่งกลับให้แก้ไข" (work-state Returned).
- Rejected quotation outcome / deposit revision → the relevant tab shows the reason and the
  path to revise (`createRevision` / `requestRevision`).
- Lost/cancelled deal → the whole record reads terminal (Cancelled): greyed, reason shown
  (`lostReason`/`cancelReason`), actions removed except reopen (owner/mgr/ceo).

## Step 2.2 review corrections (see SALES_LIFECYCLE_REVIEW)

- **Work-state is never keyed off `ticket.status`** (MS1/CS2): `status=quotation_issued`
  persists from the order-confirmation bridge all the way to close, spanning ~6 work-states
  across 3 roles. The header work-state banner is computed from `salesStage`+`paymentStatus`+
  `fulfilmentStatus`+viewer (WORK_STATE_MODEL), not the ticket status.
- **Ambiguous-ownership stages show per-role next-actions, not one owner** (AO1/SC1/SC2):
  `DELIVERY_SCHEDULING` combines import's delivery with account's final-payment ("นัดส่งสินค้า /
  นัดรับเงินส่วนที่เหลือ"), and `PROCUREMENT` folds IR/shipping/goods-received + factory-PO +
  stock reservation. The Fulfilment/Money tabs surface each role's own action, and the action
  bar shows the viewer's move — never a single "owner" for these multi-role stages.
- **Warehouse-in ≠ delivered** (CS3): the Fulfilment tab distinguishes `GOODS_RECEIVED`
  (GLR warehouse) from `FULLY_DELIVERED` (customer). Labels must not conflate them.
- **Two return paths in the Pricing tab** (MB3/IR1/IR2): expose *re-quote* (`createRevision`,
  same approved price) distinctly from *re-price* (`createCustomerChangeRevision` → new PCR →
  re-cost → re-approve), and disambiguate a rejected quotation (PCR still `QUOTATION_ISSUED`)
  from an awaiting-outcome one via the quotation doc status.
- **Disabled-with-why** for the three concrete gates (DA1/DA2/DA3): quotation until CEO
  approves; issue-IR until deposit confirmed; confirm-close until fully-paid+delivered+invoice.

## What is editable when (G-4, red-team → resolved D-17)

**Resolution (D-17):** this matrix is the **source of truth for UI enable/disable** (rendered
via disabled-with-why); the UI never offers an edit the backend would reject, and each item line
carries a computed edit-state — **Free** / **Priced-locked** / **Document-bound**. Server-side
enforcement of the same matrix is the scoped backend follow-up (the UI mirror is not the security
boundary).

The happy item→PCR→approved-price→document chain is well modelled; the **guards** around it
are not, and three red-team scenarios (3/7/24) converge on that seam. State them on the
Overview/Pricing tabs so Phase 4 designs the locks, not just the flow:

- **Items with no PCR (scenario 3).** A deal may hold `ticket_item`s that never enter a PCR;
  only PCR'd items get an approved sales-view and can be quoted. The Overview tab must make
  "priced vs not-yet-priced" legible per line, not imply every item is quotable.
- **Editing items after CEO approval (scenario 7).** Once a PCR is `APPROVED_FOR_QUOTATION`,
  editing its items staleness the approved sales-view; the correct path is *re-price*
  (`createCustomerChangeRevision` → new PCR), not a silent line edit. The Pricing tab surfaces
  the re-price path (IR1); the Overview tab should lock/annotate lines bound to an approved PCR.
- **Removing an item cited by a document (scenario 24).** A `ticket_item` referenced by an
  issued quotation / deposit notice / invoice must not be silently deletable — referential
  integrity. Enforcement is **[OUT OF SCOPE — backend]**; the UI must not offer a delete that
  the backend would (or should) reject, and must explain why when it's locked.

## Reconciliation notes carried into this IA

- **PCR is an aggregate, not a field** (region 10) — the Pricing tab is a *list of PCRs*
  (designer/owner/buyer + revisions), each opening its own detail chain (factory quotes →
  costing → CEO decision → quotation). This is bigger than the hypothesis implied; the tab
  must accommodate multiple concurrent PCRs.
- **Invoice is uploaded, not generated** (region 15) — the Documents tab distinguishes
  system-generated (quotation, deposit notice, remaining invoice) from uploaded (tax
  invoice), because the uploaded tax invoice is also the close-gate artifact.
- **Two money confirmers, two signatures** — the Money tab and action bar reflect the
  account-then-CEO close, never one actor.
