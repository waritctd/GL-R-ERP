# Role → Handoff Map

Every point where work crosses a role boundary. A handoff is where the UI most often
fails the "whose move is it?" question, so each is documented with its trigger, the
status on both sides, whether the move is manual or automatic, who may cancel/return it,
what notification fires, **what each role sees while waiting**, and the audit trail.

> **Authorization caveat.** All gates below are from the Java services (authoritative).
> The mock diverges; treat as source-verified, not test-verified (`CLAUDE.md`).

---

## Reconciliation — the sales hypothesis vs. the implementation

The Phase-2 brief gave a high-level sales hypothesis. Reconciled against code:

| # | Hypothesis | Verdict | What the code actually does |
|---|-----------|---------|------------------------------|
| H1 | One deal = one ticket | ✅ true | The ticket **is** the deal aggregate; `VIEWER_ROLES` read it, sales owns it. |
| H2 | Sales creates the deal/ticket | ✅ true | `canCreateTickets:['sales']`. |
| H3 | Sales records customer, project, products | ✅ true | Create flow captures all three. |
| H4 | Sales may request pricing | ⚠️ **changed** | Pricing is a **separate PricingRequest (PCR) aggregate**, not a ticket status. Ticket-level `submit()` now **409s** (`TicketService:198-202`). Sales does `createDraft`→`submit` on a PCR; **multiple PCRs per deal** (designer/owner/buyer + revisions). |
| H5 | Import contacts factory, returns factory pricing | ⚠️ **understated** | Import also builds the **landed-cost costing** (goods+freight+insurance+duty+inland) and submits it. Import owns *cost*, not the selling price. |
| H6 | The system applies pricing logic for CEO review | ⚠️ **overstated** | No auto pricing engine in the live chain (`calculatePrices` is `@Deprecated`). Import **manually** costs; CEO **manually** sets margins. "Pricing logic" = landed-cost formula + CEO margin decision, both human-driven. |
| H7 | CEO approves pricing | ✅ true (+more) | `PricingDecisionService.approve` (CEO-only) recomputes the selling price; CEO can also `returnToImport` (→ `COSTING_REVISION_REQUIRED`). |
| H8 | Sales may generate a quotation only after approval | ✅ true | Gate = PCR `APPROVED_FOR_QUOTATION` **and** a current `APPROVED` pricing_decision (`CustomerQuotationService.create:117-120`). Strong gate. |
| H9 | Then deposits, remaining payment, procurement, fulfilment, invoicing, commission, closure | ⚠️ **order + owners differ** | Real order: quotation issued → customer **accepts** → **order confirmation** bridges the deal into the money/fulfilment track → deposit notice (sales) → deposit paid (account) → **procurement/factory-PO** (import, gated on deposit) → delivery → final payment (account) → `CLOSED_PAID` → **invoice uploaded + commission created** (account) → close-ready (account) → **verify-close (CEO)**. Invoice is **uploaded, not generated**; commission is account-initiated then **dual-approved** (mgr→CEO) and pays **M+1**. |

**Net:** the spine of the hypothesis holds, but three things must shape the IA that the
hypothesis hides — (a) pricing is its own multi-status aggregate with its own detail
surface; (b) cost vs. price is a deliberate Import/CEO split with margin hidden from
sales; (c) closing money is a **two-signature** account→CEO gate, and commission is a
separate account-initiated, dual-approved, M+1 flow. Full discrepancy list in
[`IA_DECISION_LOG.md`](IA_DECISION_LOG.md).

---

## Field legend

Every handoff below records: **Trigger · Sender → Receiver · Preconditions · Required
fields · Required attachments · Status before → after · Manual/Auto · Cancel/return ·
Return reason · Notification · SLA/overdue · Waiting view (sender / receiver) · Audit.**

---

## SALES / DEAL WORKFLOW

### B-01 · Sales → Import — submit pricing request (PCR)
- **Trigger** Sales submits a PCR draft. **Sender→Receiver** sales(owner) → import.
- **Preconditions** Deal `ACTIVE`; PCR in `DRAFT`/`MORE_INFO_REQUIRED`; catalog snapshot
  mandatory; recipient (DESIGNER/OWNER/BUYER) identifiable (`PricingRequestService.submit:238-264`).
- **Required fields** recipient, quantityType (REFERENCE/ESTIMATE/CONFIRMED), unit basis,
  line items with catalog snapshot. **Attachments** optional spec/reference (sales may
  add only in DRAFT/`MORE_INFO_REQUIRED`).
- **Status** PCR `DRAFT` → `SUBMITTED`. **Manual.**
- **Cancel/return** Owner or CEO may `cancel` (pre-costing statuses only). Import returns
  via `requestInformation` → `MORE_INFO_REQUIRED`.
- **Return reason** `MORE_INFO_REQUIRED` carries the import question.
- **Notification** → import role (`notifyByRole("import")`, division `PCIM%`) + CEO.
- **SLA/overdue** None modelled. (IA gap — no PCR ageing signal.)
- **Waiting view** Sender (sales): PCR shows `SUBMITTED`/"waiting on import". Receiver
  (import): appears in คิวใบขอราคา to pick up.
- **Audit** PCR status history; notification rows.

### B-02 · Import ↔ Sales — more-info round trip
- **Trigger** Import needs clarification. **Sender→Receiver** import → sales (and back).
- **Preconditions** PCR in a reviewable status. **Status** any → `MORE_INFO_REQUIRED` →
  resumes to prior status on `respondInformation`. **Manual.**
- **Notification** both directions + CEO. **Waiting view** sales sees "import asked a
  question" (needs-my-action); import sees "waiting on sales".
- **Audit** info request/response events.

### B-03 · Import → CEO — costing ready for price decision
- **Trigger** Import submits landed-cost costing. **Sender→Receiver** import → CEO.
- **Preconditions** Factory response(s) received; costing `CALCULATED`; every item priced
  (`PricingCostingService.submit:157-189`).
- **Required fields** landed-cost inputs per item (goods, freight, insurance, duty,
  inland). **Attachments** factory-quote attachments (import-controlled include-in-email flag).
- **Status** PCR `COSTING_IN_PROGRESS` → `READY_FOR_CEO_REVIEW`; costing → `SUBMITTED`
  (immutable). **Manual.**
- **Cancel/return** CEO `returnToImport` later; import can supersede via revision.
- **Notification** costing submitted → CEO. **SLA** none.
- **Waiting view** import: "waiting on CEO"; CEO: PCR in the review queue (needs-my-action).
- **Audit** costing status history; freeze snapshot.

### B-04 · CEO → Sales — pricing decision (approve → quotation-ready) or return
- **Trigger** CEO reviews & decides. **Sender→Receiver** CEO → sales (approve) or CEO →
  import (return).
- **Preconditions** PCR `READY_FOR_CEO_REVIEW`; CEO `startReview` (→ `CEO_REVIEWING`,
  freezes costing, creates DRAFT decision). Approve requires every item margin + minimum
  (`PricingDecisionService.approve:188-256`).
- **Required fields** per-item margin/minimum. **Return** needs `returnReason`.
- **Status** approve: PCR → `APPROVED_FOR_QUOTATION`, decision → `APPROVED`. return: PCR
  → `COSTING_REVISION_REQUIRED`, decision → `RETURNED`. **Manual.**
- **Cancel/return** `returnToImport` is the return path (the one reopen path →
  `COSTING_IN_PROGRESS` via new costing draft).
- **Return reason** `returnReason` (required, surfaced to import).
- **Notification** approve → requesting rep; return → assigned import (or import fallback).
- **SLA** none. **Waiting view** sales: on approve, deal shows "quotation ready to issue"
  (needs-my-action); import on return: "CEO returned — revise costing".
- **Audit** decision status history; margin snapshot; `returnReason`.
- **Note** approve deliberately does **not** create a quotation or move the deal stage —
  it only unlocks quotation issuance (rule kept explicit in `PricingDecisionService`).

### B-05 · Sales → Customer — issue quotation (and record outcome)
- **Trigger** Sales issues the customer quotation. **Sender→Receiver** sales → customer
  (represented in-system; there are no customer accounts, so "customer" notifications go
  to CEO visibility).
- **Preconditions** PCR `APPROVED_FOR_QUOTATION` + current `APPROVED` decision; discount
  not below CEO minimum (422 otherwise).
- **Required fields** quotation lines (from approved sales-view), optional discount/notes.
- **Status** quotation `DRAFT` → `ISSUED`; **first** issue moves PCR →`QUOTATION_ISSUED`
  and auto-advances deal stage → `QUOTE_DESIGN_SIDE`/`QUOTE_BUYER`. `recordOutcome`
  ACCEPTED → PCR `QUOTATION_ACCEPTED` (terminal). **Manual** (issue) → **auto** (stage).
- **Cancel/return** cancel (draft only); `createRevision` from ISSUED/`REVISION_REQUESTED`.
  REJECTED/`REVISION_REQUESTED` outcomes do **not** change PCR status.
- **Return reason** `customerNote` on rejected outcome.
- **Notification** issue/outcome/expiry → CEO. **SLA** quotation expiry sweep
  (`QuotationExpiryWorker`) → `EXPIRED`.
- **Waiting view** sales: "awaiting customer decision"; CEO: visibility of issued/accepted.
- **Audit** quotation status history; outcome + note.
- **Document** customer quotation PDF/XLSX (system-rendered).

### B-06 · Customer accept → Sales — order confirmation (the bridge)
- **Trigger** Sales confirms the accepted order. **Sender→Receiver** sales(owner) →
  system (bridges into money/fulfilment).
- **Preconditions** PCR `QUOTATION_ACCEPTED` (`OrderConfirmationService.confirmOrder:103-200`).
- **Status** the **one** legacy write: ticket `draft` → `quotation_issued`, then
  `confirmCustomer` sets `paymentStatus=CUSTOMER_CONFIRMED` and auto-advances stage →
  `ORDER_RECEIVED`; reconciles `ticket_item.qty` to PCR quantities. **Manual → auto.**
- **Notification** CEO. **Waiting view** sales: deal now in money/fulfilment track;
  account: nothing yet (deposit notice not issued).
- **Audit** order-confirmation event; qty reconciliation.

---

## MONEY / FINANCE WORKFLOW

### B-07 · Sales → Account — deposit notice issued, awaiting deposit
- **Trigger** Sales issues the deposit-notice document. **Sender→Receiver** sales(owner) →
  account.
- **Preconditions** ticket `status=quotation_issued` + `paymentStatus=CUSTOMER_CONFIRMED`
  (`DepositNoticeService.issue:124-128`); deposit not waived by policy.
- **Required fields** deposit % (default 0.50), items (from approved ticket items).
- **Status** deposit doc `DRAFT` → `ISSUED`; `paymentStatus` → `DEPOSIT_NOTICE_ISSUED`.
  **Manual.**
- **Cancel/return** `requestRevision` (scope QTY_OR_NOTE/PRICE_CHANGE/NEW_ITEM) routes the
  ticket back (approved/price_proposed/in_review).
- **Notification** deposit-notice-issued ticket event; import denied all deposit reads.
- **SLA** billing/due dates set by account (`setBilling`); overdue is a computed flag.
- **Waiting view** sales: "deposit notice issued"; account: appears in งานการเงิน tab
  "รอรับมัดจำ" (needs-my-action).
- **Audit** `DEPOSIT_NOTICE_ISSUED` event; doc number.
- **Document** deposit notice PDF/XLSX (system-rendered).

### B-08 · Account → Import — deposit confirmed, procurement unblocked
- **Trigger** Account confirms deposit received. **Sender→Receiver** account → import.
- **Preconditions** `paymentStatus=DEPOSIT_NOTICE_ISSUED` (`confirmDepositPaid`,
  `ACCOUNT_ROLES={account,ceo}`).
- **Status** records DEPOSIT payment → `paymentStatus=DEPOSIT_PAID`; auto-advance stage →
  `DEPOSIT_RECEIVED`. **Manual → auto.**
- **Cancel/return** deposit can be waived earlier (`waiveDeposit`, account, reason
  required); bypassing policy skips this whole step.
- **Notification** deposit-paid ticket event (no role broadcast). **Waiting view** account:
  row clears; import: deal becomes eligible for `issueImportRequest`.
- **Audit** payment record (DEPOSIT); `DEPOSIT_PAID` event.
- **Note** This is an implicit handoff — deposit-paid is a **precondition** for import's
  `issueImportRequest`, but import is **not notified** (pull off the procurement scope).

### B-09 · Account → Customer — chase overdue / remaining payment
- **Trigger** Balance outstanding &/or overdue. **Sender→Receiver** account → customer.
- **Preconditions** `AWAITING_FINAL_PAYMENT`/`DEPOSIT_PAID`/bypass; overdue = computed.
- **Status** `confirmFinalPayment` records BALANCE payment; when nothing outstanding →
  `FULLY_PAID` + `maybeAdvanceClosedPaid`. **Manual → auto.**
- **Notification** none push (worklist-driven). **SLA** overdue flag surfaced in worklist,
  no push. **Waiting view** account: tabs "เกินกำหนด" / "รอชำระส่วนที่เหลือ".
- **Audit** payment records (BALANCE/ADJUSTMENT); `FULLY_PAID` event.

### B-10 · Account → CEO — close-ready → verify close (two-signature)
- **Trigger** All close prerequisites met. **Sender→Receiver** account → CEO.
- **Preconditions** dual-track: `status=quotation_issued` + `FULLY_PAID` + FULLY_DELIVERED
  + `invoiceOnFile` + zero outstanding (`requireClosePrerequisites:544-568`).
- **Status** `confirmCloseReady` (**account only**) sets `closeConfirmedAt`; `verifyClose`
  (**CEO only**, re-checks prereqs) → `status=closed` + `lifecycle=COMPLETED`. **Manual.**
- **Cancel/return** `revokeCloseConfirmation` (account or CEO) clears the first signature.
- **Notification** `CLOSE_CONFIRMED`/`CLOSED` ticket events. **Waiting view** account:
  tab "รอปิดงาน"; CEO: deal awaiting verify-close (needs-my-action).
- **Audit** `CLOSE_CONFIRMED`/`CLOSE_CONFIRM_REVOKED`/`CLOSED` events; two distinct actors.
- **Design note** The two signatures **must** read as distinct in the UI — one person
  cannot hold both (CEO excluded from `CLOSE_CONFIRM_ROLES` by design). Never collapse
  into one "close" button.

### B-11 · Account → Sales/Manager/CEO — invoice recorded → commission created → approved
- **Trigger** Account records the tax invoice on a closed-paid deal. **Sender→Receiver**
  account → (sales_manager → CEO for approval); the rep is the beneficiary.
- **Preconditions** deal `salesStage=CLOSED_PAID`; `createFromDeal` (**account-only**);
  tax-invoice file attached (doubles as invoice-on-file).
- **Required fields** invoice file; salesRepId auto = ticket `createdById`; gross defaults
  to `payableAmount`. **Attachment** tax invoice (required).
- **Status** commission `SUBMITTED` → (`managerApprove`) `MANAGER_APPROVED` → (`ceoApprove`)
  `APPROVED`. **Manual (dual approval).**
- **Cancel/return** `reject` at either hop (reason required); `createClawback` on an
  APPROVED SALE; `VOID`.
- **Return reason** rejection reason (notified to rep).
- **Notification** `COMMISSION_SUBMITTED`+`_PENDING_MANAGER` → rep + managers;
  `_MANAGER_APPROVED`+`_PENDING_CEO` → rep + CEO; `_APPROVED` → rep + manager; `_REJECTED`
  → rep.
- **SLA** payroll cutoff — SALE commission pays month **M+1** (received month M).
- **Waiting view** account: row clears after create; sales_manager: "commission awaiting
  my approval"; CEO: "commission awaiting CEO"; rep: read-only status of own rows.
- **Audit** commission status history; approver ids; `dealAmountMismatch` snapshot.

### B-12 · Commission → HR (payroll) — payroll-ready
- **Trigger** Approved commissions for the month. **Sender→Receiver** (system) → HR.
- **Preconditions** commission `APPROVED`; `payrollReadySummary`/`/payroll-ready`
  (**HR-only**). Only `APPROVED` rows reach payroll; SALE pays M+1.
- **Status** consumed by payroll `process`. **Auto (query-driven).**
- **Notification** **none** ("payroll ready" is not a notification — HR pulls it).
- **Waiting view** HR: payroll-ready summary within the payroll surface.
- **Audit** payroll period line items; commission→payroll linkage.

---

## PROCUREMENT / FULFILMENT WORKFLOW

### B-13 · Import → (self) → Sales/Account — import request → procurement → delivery
- **Trigger** Import issues the import request after deposit. **Sender→Receiver** import
  drives; outputs feed sales (delivery scheduling) and account (final payment).
- **Preconditions** `issueImportRequest`: `status=quotation_issued` + deposit ready (or
  bypass) + fulfilmentStatus null (`FULFILMENT_ROLES={import,ceo}`).
- **Status chain** fulfilmentStatus `IR_ISSUED`→`IR_SENT`→`SHIPPING`→`GOODS_RECEIVED`
  (or `FROM_STOCK`)→`PARTIALLY/FULLY_DELIVERED`; deal stage auto `PROCUREMENT`→
  `DELIVERY_SCHEDULING`→`DELIVERED`. **Manual steps → auto stage advances.**
- **Factory PO** (optional detail layer): `createPurchaseOrders` needs PCR
  `QUOTATION_ACCEPTED` **and** stage `PROCUREMENT`; items frozen from approved costing;
  independent of the ticket fulfilment flags.
- **QC** No gating QC step — only a per-line `qc_note` free-text at goods-receipt.
- **Cancel/return** factory PO cancel; deal-level markLost/cancel cascades PCR cancel
  (pauses don't).
- **Notification** factory-PO create → CEO; fulfilment steps are ticket events (no role
  broadcast — import/sales/account pull off scope).
- **SLA** none modelled (ETD/ETA are free-text). **Waiting view** import: fulfilment
  worklist; sales: "goods received — schedule delivery"; account: "awaiting final payment"
  after goods received.
- **Audit** fulfilment status history; factory-PO records; delivery records.

---

## HR / PEOPLE WORKFLOW

### B-14 · Employee → Manager/HR — leave request (auto-decided)
- **Trigger** Employee (or manager on behalf) submits leave. **Sender→Receiver** employee
  → auto-decision; leftover `SUBMITTED` → HR **or** direct manager.
- **Preconditions** leaveType, start/end, reason; SICK needs a medical-cert attachment
  (else `AUTO_REJECTED`); sufficient advance notice for non-SICK.
- **Status** `submit` → `APPROVED` or `AUTO_REJECTED` (usually); manual path only touches
  `SUBMITTED` → `APPROVED`/`REJECTED`. **Auto (mostly).**
- **Over-quota** request is **APPROVED**, days split paid/unpaid; unpaid = leave-without-pay
  (deducted base/30 per unpaid working day). **Surface this clearly** — a silent unpaid
  split is a money surprise.
- **Cancel/return** employee cancels own `SUBMITTED`; reviewer cancels `SUBMITTED`/
  `APPROVED` (writes a payroll-correction credit if across a processed month). No RETURNED
  status.
- **Reviewers** HR (`REVIEW_ALL_ROLES={hr}`) **or** direct manager. **CEO is not a leave
  reviewer.** Division managers only for direct reports (caveat — nav shows them the queue).
- **Notification** `LEAVE_AUTO_APPROVED`/`_AUTO_REJECTED`/`_APPROVED`/`_REJECTED`; cancel
  fires none.
- **SLA** payroll-month cutoff. **Waiting view** employee: own request with routing;
  HR/manager: the few `SUBMITTED` rows.
- **Audit** leave status; unpaid split; payroll correction rows.
- **Attachment** medical certificate (SICK).

### B-15 · Employee → Manager → CEO — overtime (two-hop)
- **Trigger** Employee (or manager) submits OT. **Sender→Receiver** employee → manager
  (hop 1) → CEO (hop 2).
- **Preconditions** workDate, planned start/end, reason; backdated ≤60 days needs reason
  ≥20 chars; payroll month open.
- **Status** `SUBMITTED` → (`managerApprove`, computes payable = actual×multiplier)
  `MANAGER_APPROVED` → (`ceoApprove`) `APPROVED`. **Manual (two-hop).**
- **Approvers** hop 1 = direct **or division** manager (`managesEmployee`); hop 2 = **CEO
  only**. **HR CANNOT approve OT** (403 — issue-#199 shape). HR is view-only.
- **Cancel/return** reject at either hop (reason); employee/manager cancel (cancelling an
  APPROVED removes credited minutes). No RETURNED status.
- **Notification** `OVERTIME_SUBMITTED`+`_PENDING_MANAGER`; `_MANAGER_APPROVED`+`_PENDING_CEO`;
  `_APPROVED`; `_REJECTED`.
- **SLA** payroll-month-open guard at submit + each hop. **Waiting view** employee: routing
  "ส่งแล้ว › ผู้จัดการ › CEO"; manager/CEO: pending queue for their hop.
- **Audit** OT status; payable minutes; multiplier; approver ids.

### B-16 · Employee → Manager → CEO — special-money / welfare (two-hop)
- **Trigger** Employee submits welfare/special-money. **Sender→Receiver** employee →
  manager (hop 1) → CEO (hop 2).
- **Preconditions** requestType, dates, quantity, amount, reason, detail; some types
  server-compute the amount; policy cap enforced at CEO hop (override needs reason).
- **Status** `SUBMITTED` → `MANAGER_APPROVED` → `APPROVED` (CEO sets `approvedAmount`).
  **Manual (two-hop).**
- **Approvers** manager = direct/division; CEO hop. **HR not special-cased.**
- **Cancel/return** reject at either hop; **only employee/requester may cancel, only while
  `SUBMITTED`** (no manager-cancel — differs from OT).
- **Attachment** evidence upload **not implemented** (disabled placeholder — a real gap;
  some types require evidence but there's no endpoint).
- **Notification** `SPECIAL_MONEY_*`. **Known bug:** notification emails deep-link
  `/requests` (HR profile queue) instead of `/employee-requests` — fix in the nav/deep-link
  work.
- **SLA** payroll cutoff (approved after the 25th pays next period). **Waiting view**
  employee: routing; manager/CEO: pending.
- **Audit** SM status; approved amount; cap-override reason.

### B-17 · Employee → HR — profile-change request (single hop)
- **Trigger** Employee submits a change to phone/email/address/emergency.
  **Sender→Receiver** employee → HR.
- **Preconditions** field ∈ {phone,email,address(house_no only),emergency}; newValue.
- **Status** `pending` → `approved`/`rejected` (HR-only). Approve writes to the registry
  **immediately** (irreversible). **Manual.**
- **Cancel/return** reject needs a reason; no RETURNED status; per-field pending lock
  prevents duplicate requests.
- **Notification** **none** (gap — employee isn't told the outcome in-app).
- **SLA** none. **Waiting view** employee: "รออนุมัติ" per field (locked); HR: `/requests`
  queue with the badge count.
- **Audit** `APPROVE/REJECT_PROFILE_REQUEST`.

---

## Cross-cutting handoff observations (→ feed WORK_STATE_MODEL & IA)

1. **Two notification mechanisms, and account is push-blind.** Sales/PCR uses
   `notifyByRole` (role→division ILIKE: import=`PCIM`, ceo=`MD/MN`, sales=`SA`; **anything
   else is a silent no-op**). HR/commission uses `notify(employeeId)` with explicit CEO
   fan-out. **Account and HR-profile-requests get no push** — they are pull roles. The IA
   must make their worklists strong precisely because nothing pings them.
2. **Implicit handoffs exist** (B-08 deposit→procurement, B-13 goods-received→final
   payment) — a precondition changes for the next role but no notification fires. The
   receiving role only learns by looking. Landing worklists must surface these.
3. **Two-signature close (B-10)** and **dual-approval commission (B-11)** are the two
   places the UI must show *two distinct actors*, never one combined action.
4. **No SLA/ageing anywhere except quotation expiry.** "Overdue" is a computed money flag
   only. Everything else (PCR sitting unpicked, costing waiting on CEO, OT waiting on a
   manager) has no ageing signal — an IA opportunity (WORK_STATE_MODEL "overdue"), but not
   a backend change in this phase.
5. **RETURNED exists only inside pricing** (`COSTING_REVISION_REQUIRED` / decision
   `RETURNED`) and via deposit `requestRevision` / quotation `REVISION_REQUESTED`. Leave/
   OT/SM/profile have no "returned for correction" — only approve/reject. The work-state
   model reflects this (returned is not universal).
6. **No approver delegation / out-of-office (G-2, red-team).** Every hop-1 above requires the
   *direct or division* manager (`managesEmployee`); there is no delegate/deputy/out-of-office
   path in the services, and **CEO is hop-2, not a hop-1 substitute**. An absent manager stalls
   their whole team's OT/leave/special-money with no escalation. Commission/pricing route to a
   single CEO approver, so they are less exposed. Delegation is **[OUT OF SCOPE — backend]**.
   **Resolution (D-15):** the UI does not invent delegation or let HR/CEO approve a hop-1 they'd
   403 on; it applies the *Overdue* ageing (WORK_STATE_MODEL) to a manager-waiting `SUBMITTED`
   past a threshold and lists it in a **"คำขอค้างนาน"** oversight on HR/CEO's existing
   `canViewAll*` surfaces — visibility to chase the manager, not an approval power.
