-- V50: deal-level sales pipeline (14-stage model + lost reasons) on sales.ticket.
--
-- One ticket = one deal. Each deal runs the standardized 14-stage pipeline
-- (LEAD_APPROACH → CLOSED_PAID, 5 phases). A โครงการ (customers.project) stays a
-- thin grouping — it can hold many deals over time, and carries no stage itself.
--
-- Stages whose facts the ticket flow already records are auto-advanced by
-- TicketService transitions (confirmCustomer→ORDER_RECEIVED, confirmDepositPaid→
-- DEPOSIT_RECEIVED, issueImportRequest→PROCUREMENT, confirmFinalPayment→
-- CLOSED_PAID); the rest are manual. Mid-fulfillment states (IR_SENT/SHIPPING/
-- GOODS_RECEIVED) live INSIDE the PROCUREMENT stage via fulfillment_status.
--
-- "Lost" is deliberately orthogonal to the stage: lost_reason + lost_at travel
-- together, and reopening a lost deal resumes at the stage it was in
-- (PROJECT_ON_HOLD explicitly implies a later reopen). is_lost is derived
-- (lost_reason IS NOT NULL) — no boolean column that can drift.

ALTER TABLE sales.ticket
    ADD COLUMN sales_stage      VARCHAR(30) NOT NULL DEFAULT 'LEAD_APPROACH',
    ADD COLUMN lost_reason      VARCHAR(30),
    ADD COLUMN lost_at          TIMESTAMPTZ,
    ADD COLUMN stage_updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE sales.ticket
    ADD CONSTRAINT chk_ticket_sales_stage CHECK (sales_stage IN (
        'LEAD_APPROACH','PRESENTATION',
        'SPEC_APPROVED','QUOTE_DESIGN_SIDE','OWNER_SIGNOFF',
        'AWAITING_BUYER','QUOTE_BUYER','NEGOTIATION',
        'ORDER_RECEIVED','DEPOSIT_RECEIVED','PROCUREMENT',
        'DELIVERY_SCHEDULING','DELIVERED','CLOSED_PAID')),
    ADD CONSTRAINT chk_ticket_lost_reason CHECK (lost_reason IN (
        'PRODUCT_FIT','PRICE','LEAD_TIME','PAYMENT_TERMS',
        'RELATIONSHIP','PROJECT_ON_HOLD','PROJECT_CANCELLED','ALREADY_PURCHASED')),
    ADD CONSTRAINT chk_ticket_lost_pair CHECK ((lost_reason IS NULL) = (lost_at IS NULL));

CREATE INDEX idx_ticket_sales_stage ON sales.ticket(sales_stage);

-- Backfill existing deals from the operational lifecycle. Order matters:
-- most-advanced condition first. Pre-pipeline tickets were all created inside
-- the price-request flow, so anything not further along maps to the quote phase.
UPDATE sales.ticket SET sales_stage = CASE
    WHEN payment_status = 'FULLY_PAID'                                    THEN 'CLOSED_PAID'
    WHEN status = 'closed'                                                THEN 'CLOSED_PAID'
    WHEN fulfillment_status IS NOT NULL                                   THEN 'PROCUREMENT'
    WHEN payment_status IN ('DEPOSIT_PAID','AWAITING_FINAL_PAYMENT')      THEN 'DEPOSIT_RECEIVED'
    WHEN payment_status IN ('CUSTOMER_CONFIRMED','DEPOSIT_NOTICE_ISSUED') THEN 'ORDER_RECEIVED'
    WHEN status IN ('quotation_issued','document_issued')                 THEN 'QUOTE_BUYER'
    ELSE 'QUOTE_DESIGN_SIDE'
END;

-- Stage history reuses sales.ticket_event (one timeline per deal): allow the
-- three pipeline kinds. Full re-declaration, as V39/V48 did.
ALTER TABLE sales.ticket_event DROP CONSTRAINT IF EXISTS chk_event_kind;
ALTER TABLE sales.ticket_event ADD CONSTRAINT chk_event_kind CHECK (kind IN (
    'CREATED','SUBMITTED','PICKED_UP','PRICE_PROPOSED','APPROVED','REJECTED',
    'QUOTATION_ISSUED','COMMENTED','CLOSED','CANCELLED','EDITED',
    'DOCUMENT_ISSUED','REVISION_REQUESTED','PRICE_REVISED',
    'CUSTOMER_CONFIRMED','DEPOSIT_NOTICE_ISSUED','DEPOSIT_PAID',
    'IR_ISSUED','IR_SENT','SHIPPING','GOODS_RECEIVED',
    'AWAITING_FINAL_PAYMENT','FULLY_PAID','PRICE_OVERRIDDEN',
    'STAGE_CHANGED','MARKED_LOST','REOPENED'
));
