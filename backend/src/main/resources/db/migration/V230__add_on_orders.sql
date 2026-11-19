-- V230: add-on orders (สั่งเพิ่ม). A contractor buys more later: sales starts a second order under the same project as a
-- linked deal (sales.ticket.parent_ticket_id = the root deal), the same shape a bid-split child already has. child_kind
-- says why the link exists, so an ADD_ON child is not mistaken for a BID_SPLIT one (the undo of the parent's order
-- confirmation, the split-letter numbering). Existing children are all bid splits. Also the ADD_ON_ORDER_CREATED ticket
-- event: a full re-declaration of chk_event_kind in V225's shape (V225's list plus the new kind).
--
-- Rollback: drop the two child_kind checks and the column; re-declare chk_event_kind as V225 did (delete any
-- ADD_ON_ORDER_CREATED rows first).

ALTER TABLE sales.ticket ADD COLUMN IF NOT EXISTS child_kind VARCHAR(16);
UPDATE sales.ticket SET child_kind = 'BID_SPLIT' WHERE parent_ticket_id IS NOT NULL AND child_kind IS NULL;
ALTER TABLE sales.ticket ADD CONSTRAINT chk_ticket_child_kind CHECK (child_kind IN ('BID_SPLIT', 'ADD_ON'));
ALTER TABLE sales.ticket ADD CONSTRAINT chk_ticket_child_kind_iff_child
    CHECK ((child_kind IS NULL) = (parent_ticket_id IS NULL));

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
    'ORDER_CONFIRMATION_UNDONE',
    'ADD_ON_ORDER_CREATED'
)) NOT VALID;
ALTER TABLE sales.ticket_event VALIDATE CONSTRAINT chk_event_kind;
