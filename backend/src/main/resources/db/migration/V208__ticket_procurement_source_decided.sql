-- Stage-10 "จัดซื้อสินค้า": the rep decides once, per deal, which lines are imported and which come from
-- stock when the order was confirmed before the quotation carried that choice (see TicketService
-- .decideProcurementSource). procurement_source_decided_at is the once-only latch.
--
-- Re-runnable (IF NOT EXISTS / DROP IF EXISTS) so the backfill can be replayed in a test.
--
-- Rollback: drop the two columns; re-declare chk_event_kind without PROCUREMENT_SOURCE_DECIDED (as V204).

ALTER TABLE sales.ticket
    ADD COLUMN IF NOT EXISTS procurement_source_decided_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS procurement_source_decided_by BIGINT NULL REFERENCES hr.employee(employee_id);

COMMENT ON COLUMN sales.ticket.procurement_source_decided_at IS 'When the import/stock split of this deal was decided (stage-10 จัดซื้อสินค้า dialog). NULL = not decided yet. Backfilled for deals already on the import axis (V208).';

-- In-flight deals (already have an import request or a fulfilment status) must not be pulled back into the dialog.
UPDATE sales.ticket t
   SET procurement_source_decided_at = now()
 WHERE t.procurement_source_decided_at IS NULL
   AND (t.fulfillment_status IS NOT NULL
        OR EXISTS (SELECT 1 FROM sales.import_request ir WHERE ir.ticket_id = t.ticket_id));

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
    'PROCUREMENT_SOURCE_DECIDED'
));
