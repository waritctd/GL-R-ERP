-- V54: per-line fulfilment and auditable delivery records for Phase 4.
--
-- qty_from_stock is a manual staff declaration for this deal only. It is not a
-- real warehouse reservation ledger and must not be treated as on-hand stock.

ALTER TABLE sales.ticket_item
    ADD COLUMN qty_delivered NUMERIC(12,2) NOT NULL DEFAULT 0
        CONSTRAINT chk_ticket_item_qty_delivered CHECK (qty_delivered >= 0 AND qty_delivered <= qty);

CREATE TABLE sales.delivery_record (
    delivery_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id     BIGINT NOT NULL REFERENCES sales.ticket(ticket_id),
    source        VARCHAR(20) NOT NULL
                  CONSTRAINT chk_delivery_source CHECK (source IN ('WAREHOUSE','STOCK')),
    delivered_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_by  BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    note          TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_delivery_record_ticket ON sales.delivery_record(ticket_id, delivered_at);

CREATE TABLE sales.delivery_record_item (
    delivery_item_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    delivery_id      BIGINT NOT NULL REFERENCES sales.delivery_record(delivery_id) ON DELETE CASCADE,
    item_id          BIGINT NOT NULL REFERENCES sales.ticket_item(item_id),
    qty              NUMERIC(12,2) NOT NULL CHECK (qty > 0)
);

ALTER TABLE sales.ticket_item
    ADD COLUMN qty_from_stock NUMERIC(12,2) NOT NULL DEFAULT 0
        CONSTRAINT chk_ticket_item_qty_from_stock CHECK (qty_from_stock >= 0 AND qty_from_stock <= qty),
    ADD COLUMN stock_note TEXT;

-- Full re-declaration to widen event kinds, following V39/V48/V50/V51/V52/V53.
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
    'STOCK_RESERVED','DELIVERY_RECORDED','DELIVERY_COMPLETED'
));
