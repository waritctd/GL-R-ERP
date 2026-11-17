-- V224: ORDER_CONFIRMATION_UNDONE, the ticket event DealQuotationService#undoOrderConfirmation writes when a rep takes
-- back an order confirmation. Full re-declaration of chk_event_kind: V208's list (verified against the live schema; every
-- other kind in TicketEventKind is notification-only and never reaches ticket_event) plus the new kind. The re-declaration
-- is ADD ... NOT VALID then VALIDATE CONSTRAINT as a second statement (V147's idiom). Flyway runs the file in one
-- transaction, so the ACCESS EXCLUSIVE lock ADD takes is still held to the end of it; the split keeps the table scan out of
-- the ADD itself and costs nothing. TicketEventKindCheckGuardIntegrationTest fails if a concurrently numbered migration
-- re-declares this list without one of these kinds.
--
-- Also: sales.deal_party remembers the status (and not-awarded reason) a party had before DealQuotationRepository#markPartyAwarded
-- overwrote it, so the undo can put it back; NULL (a party awarded before this migration) means QUOTED, as the undo always did.
--
-- Rollback: re-declare chk_event_kind as V208 did (delete any ORDER_CONFIRMATION_UNDONE rows first); DROP COLUMN the two
-- deal_party columns.

ALTER TABLE sales.deal_party ADD COLUMN IF NOT EXISTS status_before_award VARCHAR(16);
ALTER TABLE sales.deal_party ADD COLUMN IF NOT EXISTS not_awarded_reason_before_award VARCHAR(30);

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
    'STOCK_RESERVED','DELIVERY_RECORDED','DELIVERY_COMPLETED',
    'CLOSE_CONFIRMED','CLOSE_CONFIRM_REVOKED',
    'ORDER_CONFIRMED_FROM_QUOTATION',
    'DEAL_QUOTATION_REORDERED',
    'DEAL_QUOTATION_SUPERSEDED',
    'IMPORT_STEP_ADVANCED',
    'IMPORT_REQUEST_EMAIL_SENT',
    'DEAL_PROMOTED_FROM_QUOTATION',
    'DEAL_PARTY_ADDED',
    'BID_OUTCOME_RECORDED',
    'DEAL_SPLIT_FROM_PARENT',
    'DEAL_MERGED_INTO',
    'PROCUREMENT_SOURCE_DECIDED',
    'ORDER_CONFIRMATION_UNDONE'
)) NOT VALID;
ALTER TABLE sales.ticket_event VALIDATE CONSTRAINT chk_event_kind;
