# Agent Handoff — Phase 3 of the branching-workflow program (FOR CODEX)

> **Program context.** Phase 1 (lifecycle/policies/actions, PR #228) and Phase 2 (per-recipient
> quotations, PR #229) are done and Opus-reviewed, both DRAFT do-not-merge. This is Phase 3 of
> 5. Phases 4–5 (partial delivery, reports/docs) follow as separate handoffs AFTER this passes
> Opus review. Implement ONLY what this document specifies. There is NO production data.

## Task
On a new branch `feat/deal-workflow-p3-payments` **branched from
`feat/deal-workflow-p2-quotations`** (NOT from base — Phase 3 stacks on Phase 2):

Give the deal a real **payment ledger** so partial payments, credit terms, overdue tracking,
and pay-before-delivery work — replacing today's amount-free `payment_status` string flips
with recorded receipts, while keeping the existing account UI/flow working.

1. `sales.payment_receipt` ledger + billing/credit columns on `sales.ticket`.
2. `recordPayment` action; `confirmDepositPaid` / `confirmFinalPayment` become thin wrappers
   over the same ledger path so status + ledger never diverge.
3. **Derived** paymentStage (NOT_REQUIRED / DEPOSIT_PENDING / DEPOSIT_RECEIVED / PARTIALLY_PAID
   / BALANCE_PENDING / FULLY_PAID) + `overdue` boolean, computed in the DTO — the stored
   `payment_status` string keeps its current values.
4. **Case 9** — balance payable before delivery (AWAITING_FINAL_PAYMENT stops being a required
   gate). **Case 10** — credit customer: due date + overdue + follow-ups; close blocked while
   outstanding > 0.

## Non-negotiable invariants (violating any fails review — carried from Phases 1–2)

1. **Owner scoping.** Plain `sales` users view/act on ONLY their own deals; every new endpoint
   through `requireViewAccess` / existing owner gates.
2. **Money receipts are ฝ่ายบัญชี (account) + CEO fallback** — `ACCOUNT_ROLES`. `recordPayment`,
   the deposit/final confirmations, and billing-term edits are account/ceo ONLY.
   `sales_manager` gets NOTHING here (read-only). sales/import never record money.
3. **CEO price gate untouched.** Phase 3 does not touch pricing or quotation generation. The
   payable derives FROM the CEO-approved quotation total (read-only).
4. **Lifecycle gate.** Recording a payment requires lifecycle ACTIVE (`requireActive`).
5. **Money is NUMERIC(14,2)** everywhere — never float. Guard: amount > 0; cumulative paid may
   not exceed payable UNLESS an explicit `allowOverpayment` reason is given (record it).
   Non-negative invariants enforced at the DB (CHECK) and service layer.
6. **Do not break the existing dual-track flow.** `confirmDepositPaid` / `confirmFinalPayment`
   endpoints keep working (account UI calls them) — they now route through the ledger.
7. **Mock parity** (`contract.test.js`, both directions). Mock authz non-authoritative.
   No skipped tests / lint suppressions / commented-out logic. Keep `// Mirrors` headers.
8. **Do not reuse `sales.invoice_details`** — that table is the commission module's
   (V11.2, salesperson-commission input, no FK to tickets). The new ledger is separate.

## Repo orientation — payment specifics (verified; do not rediscover)

- **`sales.ticket.payment_status`** VARCHAR(40), **NO CHECK constraint** (only enforced in
  Java). Current values: `null → CUSTOMER_CONFIRMED → DEPOSIT_NOTICE_ISSUED → DEPOSIT_PAID →
  AWAITING_FINAL_PAYMENT → FULLY_PAID`. Amount-free today — flips only. Phase 1 added
  `deposit_policy` (REQUIRED/NOT_REQUIRED/WAIVED/CREDIT_CUSTOMER) + reason/set_by.
- **`TicketService`** money transitions (all `ACCOUNT_ROLES`, all `requireActive`):
  - `confirmDepositPaid` (~line 385): requires paymentStatus DEPOSIT_NOTICE_ISSUED → DEPOSIT_PAID;
    if goods already received, carries to AWAITING_FINAL_PAYMENT. Amount-free.
  - `confirmFinalPayment` (~line 494): requires AWAITING_FINAL_PAYMENT → FULLY_PAID. Amount-free.
  - `markGoodsReceived` also carries DEPOSIT_PAID → AWAITING_FINAL_PAYMENT.
  - `close` (~line 330): requires `FULLY_PAID` + `GOODS_RECEIVED` (dual-track) OR legacy
    document_issued path. Sets lifecycle COMPLETED.
  - `waiveDeposit` (Phase 1): policy NOT_REQUIRED/WAIVED/CREDIT_CUSTOMER; issueImportRequest
    bypasses the deposit notice under those policies.
- **Deposit amounts** live on `sales.deposit_notice` (V12): `subtotal, deposit_amount,
  vat_percent, vat_amount, total_payable` all NUMERIC(15,2), `deposit_percent`. Issued via
  `DepositNoticeService.issue` which sets payment_status=DEPOSIT_NOTICE_ISSUED. The remaining
  invoice (`RemainingInvoiceRenderer`) computes remainder = Σ item amounts − depositAmount
  **on the fly, not persisted**.
- **Quotation total** (payable source): `sales.quotation.total_amount` NUMERIC(14,2), now with
  `recipient_type` + `doc_status` incl. ACCEPTED (Phase 2). `TicketDto.quotations()` carries
  them; `QuotationDto` has `recipientType, docStatus, totalAmount, acceptedAt`.
- **Dashboard** (`DashboardRepository`): has an `overdue_over_3days` count based on ticket age,
  NOT payment. Leave dashboards alone (Phase 5) EXCEPT wiring the new derived fields into the
  detail DTO.
- **`TicketSummaryDto`** already carries lifecycle/policy/stage fields (Phases 1–2). Extend it
  with billing fields + derived payment fields (see below). Grep `new TicketSummaryDto(` — many
  test constructors + the `withCustomerAndProject` copier need updating.
- Verification: `cd backend && ./mvnw -B clean verify` (Testcontainers) ·
  `cd frontend && npm run lint && npm test && npm run build` · frontend-mock port 5200.
  Phase 2 baseline: backend 491, frontend 120.

## Backend spec

### V53__payment_ledger_and_billing.sql
```sql
CREATE TABLE sales.payment_receipt (
    receipt_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id         BIGINT NOT NULL REFERENCES sales.ticket(ticket_id),
    kind              VARCHAR(20) NOT NULL
                      CONSTRAINT chk_receipt_kind CHECK (kind IN ('DEPOSIT','BALANCE','ADJUSTMENT')),
    amount            NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    currency          VARCHAR(3) NOT NULL DEFAULT 'THB',
    received_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    recorded_by       BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    note              TEXT,
    deposit_notice_id BIGINT REFERENCES sales.deposit_notice(deposit_notice_id),
    receipt_ref       VARCHAR(60),          -- optional client idempotency key
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_receipt_ticket ON sales.payment_receipt(ticket_id, received_at);
CREATE UNIQUE INDEX ux_payment_receipt_ref
    ON sales.payment_receipt(ticket_id, receipt_ref) WHERE receipt_ref IS NOT NULL;

ALTER TABLE sales.ticket
    ADD COLUMN billing_date       DATE,
    ADD COLUMN due_date           DATE,
    ADD COLUMN credit_term_days   INTEGER,
    ADD COLUMN last_follow_up_at  DATE,
    ADD COLUMN next_follow_up_at  DATE;
-- chk_event_kind: re-declare (full list from V52) + 'PAYMENT_RECORDED','BILLING_UPDATED'
```
No CHECK on `payment_status` — leave the string as-is. ADJUSTMENT is a signed correction but
`amount > 0`; use `kind=ADJUSTMENT` + a note for a credit-note style reduction and subtract it
in the outstanding calc (document the sign convention in the migration header: DEPOSIT and
BALANCE add to paid; ADJUSTMENT subtracts — an over-collection refund).

### Java
- `PaymentReceiptDto.java` (ticket pkg): receiptId, ticketId, kind, amount, currency,
  receivedAt, recordedById, recordedByName, note, depositNoticeId, receiptRef, createdAt.
- `PaymentStage.java` constants (NOT stored; derived): NOT_REQUIRED, DEPOSIT_PENDING,
  DEPOSIT_RECEIVED, PARTIALLY_PAID, BALANCE_PENDING, FULLY_PAID (+ VALID). Document each.
- `TicketRepository`:
  - `insertPaymentReceipt(ticketId, kind, amount, recordedBy, note, depositNoticeId, receiptRef)`
    → returns receiptId; unique-index violation on receipt_ref surfaces as a 409 (idempotency).
  - `findReceiptsByTicket(ticketId)` (join hr.employee for recordedByName, ORDER BY received_at).
  - `sumPaid(ticketId)` = Σ(DEPOSIT+BALANCE) − Σ(ADJUSTMENT) as NUMERIC (or compute in Java
    from findReceipts — either is fine; keep it one place).
  - `updateBilling(ticketId, billingDate, dueDate, creditTermDays, lastFollowUpAt, nextFollowUpAt)`.
- `TicketService`:
  - **Payable derivation** — one private helper `payableAmount(TicketDto)`: the total of the
    latest ACCEPTED quotation, preferring recipient BUYER, then OWNER, then any ACCEPTED; if
    none accepted, the latest ISSUED quotation (same preference); else the deposit_notice
    `total_payable`; else Σ(approvedPrice×qty). **Document this precedence in the workflow notes
    and flag it in the review questions** — the business may want "accepted BUYER only".
  - **`recordPayment(ticketId, request, actor)`** — `ACCOUNT_ROLES`, `requireActive`. request:
    `{kind, amount (NUMERIC>0), receivedAt?, note?, depositNoticeId?, receiptRef?,
    allowOverpayment?}`. Compute `newPaid = sumPaid + signedAmount`; if `newPaid > payable`
    and not `allowOverpayment`, 400 with a clear Thai message; if allowed, require a note.
    Insert receipt. Then **recompute payment_status** via a single internal
    `reconcilePaymentStatus(ticketId)`:
    - deposit received (kind DEPOSIT, or cumulative ≥ deposit due) while status was
      DEPOSIT_NOTICE_ISSUED/CUSTOMER_CONFIRMED/null-with-waived → DEPOSIT_PAID (+ carry to
      AWAITING_FINAL_PAYMENT if goods already received, preserving today's behavior);
    - cumulative paid ≥ payable → FULLY_PAID;
    - else if any balance recorded but < payable → keep AWAITING_FINAL_PAYMENT (or
      DEPOSIT_PAID). Emit `PAYMENT_RECORDED` event with amount + kind + running paid/payable.
  - **`confirmDepositPaid` / `confirmFinalPayment` become wrappers**: compute the amount
    (deposit = the issued deposit_notice.deposit_amount; final = outstanding = payable −
    sumPaid) and call the SAME ledger + reconcile path, so the account UI's existing buttons
    now create receipts. Keep their current status preconditions as the guard, but the status
    transition itself flows from `reconcilePaymentStatus`. (If a deposit_notice amount is
    unavailable, fall back to payable × deposit_percent, else block with a clear message.)
  - **Case 9 (pay before delivery)**: relax `confirmFinalPayment` / balance `recordPayment` so
    the balance can be paid once the deposit is satisfied (paymentStatus DEPOSIT_PAID or
    AWAITING_FINAL_PAYMENT, or a NOT_REQUIRED/WAIVED/CREDIT policy with deposit not owed) —
    NOT requiring GOODS_RECEIVED first. AWAITING_FINAL_PAYMENT becomes an OPTIONAL intermediate.
    `close` UNCHANGED (still needs FULLY_PAID + GOODS_RECEIVED) — payment-complete-before-
    delivery is fine, the deal just isn't closeable until goods land.
  - **Case 10 (credit)**: `setBilling(ticketId, request, actor)` (ACCOUNT_ROLES) sets
    billing_date/due_date/credit_term_days/next_follow_up_at (+ BILLING_UPDATED event). When
    `deposit_policy = CREDIT_CUSTOMER`, the deal may reach delivery with outstanding > 0; the
    balance is collected later via recordPayment. `close` stays blocked while
    `outstanding > 0` (it already requires FULLY_PAID — no change needed).
  - **Derived fields** (in the DTO builder / summary): `paymentStage` (per PaymentStage rules
    from payable + sumPaid + deposit state + policy: NOT_REQUIRED when policy NOT_REQUIRED and
    nothing owed; DEPOSIT_PENDING when deposit required & unpaid; etc.), `amountPayable`,
    `amountPaid`, `amountOutstanding`, `overdue` (`due_date != null && due_date < today &&
    outstanding > 0`). Add these to `TicketSummaryDto` (or a dedicated payment sub-DTO on
    `TicketDto` — pick the pattern that least disturbs existing constructors; a payment
    sub-object on TicketDto is cleaner than 8 more summary fields — your call, document it).
- **Actions API** (`TicketService.actions`, Phase 1 catalog): add `RECORD_PAYMENT` (kind
  `payment`, requiredFields `["kind","amount"]`) for account/ceo when the deal is billable
  (has a payable and isn't already FULLY_PAID); `SET_BILLING` (account/ceo). Keep the existing
  DEPOSIT_PAID / FINAL_PAYMENT actions.

### Endpoints (TicketController)
`POST /{id}/payments` `{kind, amount, receivedAt?, note?, depositNoticeId?, receiptRef?,
allowOverpayment?}` · `GET /{id}/payments` (list receipts) · `POST /{id}/billing`
`{billingDate?, dueDate?, creditTermDays?, nextFollowUpAt?, lastFollowUpAt?}`. Keep
`/deposit-paid` and `/final-payment`. Request records with jakarta validation.

## Frontend spec

- `routes.js`/`hrApi.js`: `recordPayment(id, payload)`, `listPayments(id)`, `setBilling(id,
  payload)`. Mock mirrors all + the derived fields on the detail/list DTO + the reconcile
  logic + the actions additions. Seed 1–2 demo deals with receipts (a partial-paid one and a
  credit-with-due-date one) so the UI is visible.
- `format.js`: `paymentStageLabel(stage)` → {label, tone} (NOT_REQUIRED ไม่ต้องชำระ /
  DEPOSIT_PENDING รอมัดจำ / DEPOSIT_RECEIVED รับมัดจำแล้ว / PARTIALLY_PAID ชำระบางส่วน /
  BALANCE_PENDING รอชำระส่วนที่เหลือ / FULLY_PAID ชำระครบแล้ว), plus an overdue badge helper.
- **Payment section** in `TicketDetailPage.jsx` (near the quotation/deposit area): show
  ยอดที่ต้องชำระ (payable) / ชำระแล้ว (paid) / คงเหลือ (outstanding), due date + an เกินกำหนด
  (overdue) badge when overdue, the receipts history (date/kind/amount/recorder/note), and —
  for account/ceo, gated by the actions endpoint — a **บันทึกรับชำระเงิน** modal
  (kind + amount + received date + note, with an over-payment confirm) and a **ตั้งค่าการวางบิล**
  control (billing/due date, credit term, next follow-up). The cockpit's การชำระเงิน chips
  gain PARTIALLY_PAID / overdue states (extend `PAYMENT_SUBSTEPS` or add a derived indicator).
- Keep the existing ยืนยันรับมัดจำ / ยืนยันชำระครบ cockpit buttons working — they now create
  receipts server-side; no UI removal.

## Out of scope (do NOT build now)
Partial delivery / stock / fulfilment enum (Phase 4). Dashboards/reports/filters, workflow doc,
22-case matrix (Phase 5). No quotation/pricing changes. No stage renames.

## Tests (minimum)
Backend:
- recordPayment: DEPOSIT then BALANCE bringing cumulative to payable → FULLY_PAID; partial
  balance → not FULLY_PAID (derived PARTIALLY_PAID/BALANCE_PENDING); amount ≤ 0 → 400;
  paid > payable without allowOverpayment → 400, with it + note → OK; account/ceo only
  (sales/import/sales_manager 403); ON_HOLD → 409; receipt_ref idempotency (dup → 409).
- confirmDepositPaid/confirmFinalPayment still transition status AND now insert a receipt of
  the right amount (assert both).
- Case 9: balance recordPayment/confirmFinalPayment succeeds from DEPOSIT_PAID before
  GOODS_RECEIVED → FULLY_PAID; `close` still 409 until goods received; after goods received,
  close succeeds.
- Case 10: CREDIT_CUSTOMER deal delivered with outstanding > 0 → derived overdue once past
  due_date; close blocked while outstanding; recordPayment clears it → closeable.
- payable derivation: prefers accepted BUYER > OWNER > any accepted > latest issued.
- actions endpoint surfaces RECORD_PAYMENT/SET_BILLING for account/ceo only.
Frontend: contract.test.js green; a component test that the payment section renders
payable/paid/outstanding + an overdue badge from a mocked ticket, and hides record-payment
absent from `/actions`.

## Definition of done (Codex fills before passing back)
1. All backend + frontend changes on `feat/deal-workflow-p3-payments`.
2. `cd backend && ./mvnw -B clean verify` → BUILD SUCCESS (counts; note Docker skips).
3. `cd frontend && npm run lint && npm test && npm run build` → 0 lint errors, all green.
4. frontend-mock manual pass (describe): record a partial payment (outstanding updates),
   record the balance (→ FULLY_PAID), a credit deal shows overdue, pay-before-delivery leaves
   the deal open until goods received.
5. Fill THIS file's sections below. Commit on the branch. Merge NOTHING.

## Files changed
(fill in)

## Commands run
(fill in)

## Tests / build results
(fill in)

## Known risks / questions for Opus review
(fill in)
