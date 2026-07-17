-- V51: deal lifecycle + structured policy fields for Phase 1 branching workflow.
--
-- lifecycle is separate from sales_stage: paused/terminal states gate mutations
-- while preserving the exact deal stage for resume/reopen.

ALTER TABLE sales.ticket
    ADD COLUMN lifecycle              VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN tender_requirement     VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN deposit_policy         VARCHAR(20) NOT NULL DEFAULT 'REQUIRED',
    ADD COLUMN deposit_policy_reason  TEXT,
    ADD COLUMN deposit_policy_set_by  BIGINT REFERENCES hr.employee(employee_id),
    ADD COLUMN entry_channel          VARCHAR(20) NOT NULL DEFAULT 'DESIGNER_LED';

ALTER TABLE sales.ticket
    ADD CONSTRAINT chk_ticket_lifecycle CHECK (lifecycle IN (
        'ACTIVE','ON_HOLD','DORMANT','CLOSED_LOST','CANCELLED','COMPLETED')),
    ADD CONSTRAINT chk_ticket_tender_requirement CHECK (tender_requirement IN (
        'REQUIRED','NOT_REQUIRED','UNKNOWN')),
    ADD CONSTRAINT chk_ticket_deposit_policy CHECK (deposit_policy IN (
        'REQUIRED','NOT_REQUIRED','WAIVED','CREDIT_CUSTOMER')),
    ADD CONSTRAINT chk_ticket_entry_channel CHECK (entry_channel IN (
        'DESIGNER_LED','OWNER_DIRECT','BUYER_DIRECT'));

UPDATE sales.ticket SET lifecycle = CASE
    WHEN lost_reason IS NOT NULL THEN 'CLOSED_LOST'
    WHEN status = 'cancelled'    THEN 'CANCELLED'
    WHEN status = 'closed'       THEN 'COMPLETED'
    ELSE 'ACTIVE'
END;

CREATE INDEX idx_ticket_lifecycle ON sales.ticket(lifecycle);

-- Full re-declaration to widen event kinds, following V39/V48/V50.
ALTER TABLE sales.ticket_event DROP CONSTRAINT IF EXISTS chk_event_kind;
ALTER TABLE sales.ticket_event ADD CONSTRAINT chk_event_kind CHECK (kind IN (
    'CREATED','SUBMITTED','PICKED_UP','PRICE_PROPOSED','APPROVED','REJECTED',
    'QUOTATION_ISSUED','COMMENTED','CLOSED','CANCELLED','EDITED',
    'DOCUMENT_ISSUED','REVISION_REQUESTED','PRICE_REVISED',
    'CUSTOMER_CONFIRMED','DEPOSIT_NOTICE_ISSUED','DEPOSIT_PAID',
    'IR_ISSUED','IR_SENT','SHIPPING','GOODS_RECEIVED',
    'AWAITING_FINAL_PAYMENT','FULLY_PAID','PRICE_OVERRIDDEN',
    'STAGE_CHANGED','MARKED_LOST','REOPENED',
    'ON_HOLD','DORMANT','RESUMED','POLICY_CHANGED'
));
