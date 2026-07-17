-- V53: payment ledger + billing fields for Phase 3 branching workflow.
--
-- sales.payment_receipt is the ticket-scoped cash ledger. DEPOSIT and BALANCE
-- increase paid amount; ADJUSTMENT subtracts from paid amount and represents a
-- refund / credit-note style correction. All receipt amounts are positive
-- NUMERIC(14,2); sign is derived from kind.

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
    receipt_ref       VARCHAR(60),
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

-- Full re-declaration to widen event kinds, following V39/V48/V50/V51/V52.
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
    'PAYMENT_RECORDED','BILLING_UPDATED'
));
