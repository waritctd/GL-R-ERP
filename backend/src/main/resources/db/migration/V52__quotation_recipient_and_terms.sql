-- V52: recipient-scoped quotation chains for Phase 2 branching workflow.
--
-- recipient_type=UNSPECIFIED is the legacy bucket for pre-Phase-2 rows. Versioning
-- moves from ticket-wide to (ticket, recipient_type), allowing designer, owner, and
-- buyer quotations to coexist. There is no production data on this branch; a real
-- backfill would assign recipient_type before swapping the unique index.

ALTER TABLE sales.quotation
    ADD COLUMN recipient_type       VARCHAR(20) NOT NULL DEFAULT 'UNSPECIFIED',
    ADD COLUMN recipient_label      VARCHAR(255),
    ADD COLUMN payment_terms        TEXT,
    ADD COLUMN lead_time            TEXT,
    ADD COLUMN delivery_terms       TEXT,
    ADD COLUMN validity_date        DATE,
    ADD COLUMN sent_at              TIMESTAMPTZ,
    ADD COLUMN accepted_at          TIMESTAMPTZ,
    ADD COLUMN rejected_at          TIMESTAMPTZ,
    ADD COLUMN parent_quotation_id  BIGINT REFERENCES sales.quotation(quotation_id);

ALTER TABLE sales.quotation
    ADD CONSTRAINT chk_quotation_recipient_type CHECK (recipient_type IN (
        'DESIGNER','OWNER','BUYER','UNSPECIFIED'));

ALTER TABLE sales.quotation DROP CONSTRAINT IF EXISTS chk_quotation_doc_status;
ALTER TABLE sales.quotation ADD CONSTRAINT chk_quotation_doc_status CHECK (doc_status IN (
    'DRAFT','ISSUED','SENT','ACCEPTED','REJECTED','EXPIRED','CANCELLED','SUPERSEDED'
));

DROP INDEX IF EXISTS sales.ux_quotation_ticket_version;
CREATE UNIQUE INDEX ux_quotation_ticket_recipient_version
    ON sales.quotation(ticket_id, recipient_type, quotation_version);

-- Full re-declaration to widen event kinds, following V39/V48/V50/V51.
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
    'QUOTATION_SENT','QUOTATION_ACCEPTED','QUOTATION_REJECTED'
));
