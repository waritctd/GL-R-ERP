-- Close verification (three-party close).
--
-- Closing a deal was a single unilateral action by the sales owner. The business
-- rule is stricter: a deal counts as closed only when the goods have been
-- delivered, the balance has been paid, the invoice document is on file, ฝ่ายบัญชี
-- has confirmed, and the CEO has verified. Sales no longer closes deals.
--
-- Account confirms (close_confirmed_by/at) → CEO verifies (status=closed,
-- lifecycle=COMPLETED). The confirmation is recorded on the ticket so "which
-- deals are waiting on the CEO" is a plain query rather than an event scan.
-- The CEO verifies, never overrides: every prerequisite is re-checked at verify
-- time, so a deal that regresses between the two signatures cannot slip through.

ALTER TABLE sales.ticket
    ADD COLUMN IF NOT EXISTS close_confirmed_by BIGINT REFERENCES hr.employee(employee_id),
    ADD COLUMN IF NOT EXISTS close_confirmed_at TIMESTAMPTZ;

COMMENT ON COLUMN sales.ticket.close_confirmed_by IS
    'ฝ่ายบัญชี who confirmed the deal is ready to close; CEO verification still required.';

-- Deals awaiting CEO verification — the CEO''s work queue.
CREATE INDEX IF NOT EXISTS ix_ticket_close_confirmed
    ON sales.ticket(close_confirmed_at)
    WHERE close_confirmed_at IS NOT NULL;

-- Re-declare with the two new kinds (CLOSE_CONFIRMED = account's signature,
-- CLOSE_CONFIRM_REVOKED = confirmation withdrawn before verification).
-- CLOSED keeps its meaning: the deal is closed — now written only by the CEO.
ALTER TABLE sales.ticket_event DROP CONSTRAINT IF EXISTS chk_event_kind;
ALTER TABLE sales.ticket_event ADD CONSTRAINT chk_event_kind CHECK (kind IN (
    'CREATED','SUBMITTED','PICKED_UP','PRICE_PROPOSED','APPROVED','REJECTED',
    'QUOTATION_ISSUED','COMMENTED','CLOSED','CANCELLED','EDITED',
    'DOCUMENT_ISSUED','REVISION_REQUESTED','PRICE_REVISED',
    'CUSTOMER_CONFIRMED','DEPOSIT_NOTICE_ISSUED','DEPOSIT_PAID',
    'IR_ISSUED','IR_SENT','SHIPPING','GOODS_RECEIVED',
    'AWAITING_FINAL_PAYMENT','FULLY_PAID','PRICE_OVERRIDDEN',
    'STAGE_CHANGED','MARKED_LOST','REOPENED',
    'ON_HOLD','DORMANT','RESUMED','POLICY_CHANGED',
    'QUOTATION_SENT','QUOTATION_ACCEPTED','QUOTATION_REJECTED',
    'PAYMENT_RECORDED','BILLING_UPDATED',
    'CLOSE_CONFIRMED','CLOSE_CONFIRM_REVOKED'
));

-- No backfill: already-closed deals keep close_confirmed_* NULL. They were closed
-- under the old single-signature rule and rewriting history would misattribute a
-- confirmation to an accountant who never gave one.
