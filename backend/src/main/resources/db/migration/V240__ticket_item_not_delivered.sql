-- V240: a deal line the factory never sent. The sales manager or CEO marks a quantity of a line "ไม่ได้รับของ" with a reason,
-- so the deal can reach FULLY_DELIVERED and close. Amounts are NOT touched: account handles any refund or credit as before.
-- deliverable quantity of a line = qty - qty_not_delivered (every "is it open" / "is it fully delivered" check reads that).
-- No backfill. Also: the view that says how much of each ISSUED import-request line is still wanted (the marked quantity
-- is taken off the line's unplanned part, earliest IR first, so it stops showing as waiting and cannot be planned), and
-- chk_event_kind re-declared (V236's list plus the two new kinds).
--
-- Rollback: DROP VIEW sales.import_item_wanted; delete LINE_NOT_DELIVERED / LINE_NOT_DELIVERED_CLEARED events and re-declare
-- chk_event_kind as V236 did; ALTER TABLE sales.ticket_item DROP the four columns.

ALTER TABLE sales.ticket_item
    ADD COLUMN qty_not_delivered NUMERIC(12,2) NOT NULL DEFAULT 0
        CONSTRAINT chk_ticket_item_qty_not_delivered CHECK (qty_not_delivered >= 0),
    ADD COLUMN not_delivered_reason VARCHAR(255),
    ADD COLUMN not_delivered_by BIGINT REFERENCES hr.employee(employee_id),
    ADD COLUMN not_delivered_at TIMESTAMPTZ;

CREATE VIEW sales.import_item_wanted AS
SELECT x.import_request_item_id,
       x.qty - LEAST(x.unplaced, GREATEST(x.nd - x.before_unplaced, 0)) AS wanted_qty
  FROM (
    SELECT i.import_request_item_id, i.qty, COALESCE(ti.qty_not_delivered, 0) AS nd,
           GREATEST(i.qty - COALESCE(p.placed, 0), 0) AS unplaced,
           COALESCE(SUM(GREATEST(i.qty - COALESCE(p.placed, 0), 0)) OVER (
               PARTITION BY i.ticket_item_id ORDER BY i.import_request_id, i.seq
               ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING), 0) AS before_unplaced
      FROM sales.import_request_item i
      JOIN sales.import_request ir ON ir.import_request_id = i.import_request_id AND ir.status = 'ISSUED'
      LEFT JOIN sales.ticket_item ti ON ti.item_id = i.ticket_item_id
      LEFT JOIN (SELECT import_request_item_id, SUM(qty) AS placed FROM sales.shipment_lot GROUP BY import_request_item_id) p
             ON p.import_request_item_id = i.import_request_item_id
  ) x;

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
    'ADD_ON_ORDER_CREATED',
    'DELIVERY_REQUEST_SENT','DELIVERY_REQUEST_RETURNED','DELIVERY_REQUEST_FORWARDED','TAX_INVOICE_CONFIRMED','TAX_INVOICE_VOIDED','TAX_INVOICE_FILE_REPLACED',
    'LINE_NOT_DELIVERED','LINE_NOT_DELIVERED_CLEARED'
)) NOT VALID;
ALTER TABLE sales.ticket_event VALIDATE CONSTRAINT chk_event_kind;
