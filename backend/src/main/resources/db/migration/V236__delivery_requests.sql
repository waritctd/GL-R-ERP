-- V236: delivery rounds and the tax invoice before delivery. An order's delivery is split into rounds; each round is one
-- request from sales to the warehouse (ขอออกใบกำกับ), reviewed, forwarded to whoever issues the tax invoice, then
-- delivered against that invoice. This migration holds the whole round model so the later slices (warehouse review,
-- the invoice, the delivery rule) need none: sales.delivery_request (+ its covered lines), the link columns on
-- sales.attachment and sales.delivery_record, the hr.employee issuer grant, TAX_INVOICE as a receipt source, and the six
-- new ticket event kinds (chk_event_kind re-declared once, V231's list plus the new kinds).
--
-- Rollback: delete DELIVERY_REQUEST_* / TAX_INVOICE_CONFIRMED events and TAX_INVOICE receipts, re-declare chk_event_kind
-- as V231 did and chk_payment_receipt_source as V213 did, drop the added columns, then drop the two new tables.

CREATE TABLE sales.delivery_request (
    delivery_request_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id             BIGINT NOT NULL REFERENCES sales.ticket(ticket_id),
    round_no              INT NOT NULL,
    status                VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
        CONSTRAINT chk_delivery_request_status CHECK (status IN ('DRAFT','SENT','RETURNED','FORWARDED','INVOICED')),
    quotation_id          BIGINT REFERENCES sales.quotation(quotation_id),
    note                  TEXT,
    -- The system documents the rep unticked on the draft (comma-separated KIND:id keys), so a trip to a generator and back
    -- does not tick them again.
    unticked_docs         TEXT,
    requested_by          BIGINT REFERENCES hr.employee(employee_id),
    sent_at               TIMESTAMPTZ,
    return_reason_codes   TEXT[],
    return_reason_text    TEXT,
    returned_by           BIGINT REFERENCES hr.employee(employee_id),
    returned_at           TIMESTAMPTZ,
    forwarded_by          BIGINT REFERENCES hr.employee(employee_id),
    forwarded_at          TIMESTAMPTZ,
    invoice_number        VARCHAR(60),
    invoice_date          DATE,
    invoice_total         NUMERIC(14,2),
    invoice_attachment_id BIGINT,
    recorded_amount       NUMERIC(14,2),
    receipt_id            BIGINT REFERENCES sales.payment_receipt(receipt_id),
    -- What the confirm's receipt did to the deal's expected payments: [{id, added, closed}], so a void subtracts exactly that.
    expected_deltas       JSONB,
    invoiced_by           BIGINT REFERENCES hr.employee(employee_id),
    invoiced_at           TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_delivery_request_round UNIQUE (ticket_id, round_no)
);
-- One round per order on the sales/warehouse side. A FORWARDED round is with the invoice issuer, so voiding an earlier
-- invoice may bring a round back to FORWARDED beside the one sales has since opened.
CREATE UNIQUE INDEX ux_delivery_request_open ON sales.delivery_request(ticket_id)
    WHERE status IN ('DRAFT','SENT','RETURNED');

-- One confirmed round per invoice number, whatever the order (case and padding ignored). Voiding a round clears the number.
CREATE UNIQUE INDEX ux_delivery_request_invoice_number ON sales.delivery_request(upper(btrim(invoice_number)))
    WHERE invoice_number IS NOT NULL;

CREATE TABLE sales.delivery_request_line (
    delivery_request_id BIGINT NOT NULL REFERENCES sales.delivery_request(delivery_request_id) ON DELETE CASCADE,
    item_id             BIGINT NOT NULL REFERENCES sales.ticket_item(item_id),
    qty                 NUMERIC(12,2) NOT NULL CHECK (qty > 0),
    PRIMARY KEY (delivery_request_id, item_id)
);

ALTER TABLE sales.attachment
    ADD COLUMN delivery_request_id BIGINT REFERENCES sales.delivery_request(delivery_request_id),
    ADD COLUMN uploader_side VARCHAR(12);
CREATE INDEX idx_attachment_delivery_request ON sales.attachment(delivery_request_id)
    WHERE delivery_request_id IS NOT NULL;
-- delivery_request_id: the round a delivery was made through (its invoiced round, or a sent round the goods left before its
-- invoice). no_invoice_note: the note/reference required when a delivery is recorded with no confirmed tax invoice (audit;
-- null on every delivery made against an invoice).
ALTER TABLE sales.delivery_record
    ADD COLUMN delivery_request_id BIGINT REFERENCES sales.delivery_request(delivery_request_id),
    ADD COLUMN no_invoice_note VARCHAR(500);

ALTER TABLE hr.employee ADD COLUMN can_issue_tax_invoice BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN hr.employee.can_issue_tax_invoice IS
    'Per-employee grant: may confirm the tax invoice of a delivery round (any role). Read live on every call, never cached on the session.';
COMMENT ON COLUMN sales.attachment.uploader_side IS
    'Who attached a delivery-round file, fixed at upload time from the uploader''s role: SALES, WAREHOUSE, ISSUER or SYSTEM. NULL on every other attachment.';

ALTER TABLE sales.payment_receipt DROP CONSTRAINT IF EXISTS chk_payment_receipt_source;
ALTER TABLE sales.payment_receipt
    ADD CONSTRAINT chk_payment_receipt_source CHECK (source IN ('MANUAL', 'EXPRESS', 'TAX_INVOICE'));

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
    'DELIVERY_REQUEST_SENT','DELIVERY_REQUEST_RETURNED','DELIVERY_REQUEST_FORWARDED','TAX_INVOICE_CONFIRMED','TAX_INVOICE_VOIDED','TAX_INVOICE_FILE_REPLACED'
)) NOT VALID;
ALTER TABLE sales.ticket_event VALIDATE CONSTRAINT chk_event_kind;

-- V232 added quotation_item.source_quotation_item_id (an FK) without an index, so every quotation-item delete scanned the
-- table; the index here is the same fix, kept in this still-unapplied migration so no new version is needed.
CREATE INDEX IF NOT EXISTS idx_quotation_item_source_item ON sales.quotation_item(source_quotation_item_id)
    WHERE source_quotation_item_id IS NOT NULL;
