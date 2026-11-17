-- V213: Express receipts upload (spec 2026-11-15 §7.8 / §8, slice 3 task 3.3; the plan's "V212", shifted by one).
--
-- The owner's ruling (§8): the Express upload REPLACES account's manual receipt entry. Uploaded receipts are written as
-- ordinary sales.payment_receipt rows with source = 'EXPRESS', deduplicated by the Express document number, so cash is
-- counted once; manual entry (source = 'MANUAL', every existing row) stays only for receipts the upload could not
-- match or that never went through Express.
--
-- 1. payment_receipt.source / customer_name. DEFAULT 'MANUAL' backfills every existing row as what it was — typed by
--    account. customer_name is the payer name exactly as the Express file printed it (audit; never used for money).
-- 2. ux_payment_receipt_express_ref: one EXPRESS receipt per document number, whatever deal it landed on — the
--    structural half of "re-upload is idempotent". (V53's ux_payment_receipt_ref stays: per-ticket, any source.)
-- 3. sales.receipt_import — one row per uploaded file, with its outcome counts.
-- 4. sales.receipt_import_row — every row the matcher could NOT place (UNMATCHED), until a person attaches it to a deal
--    (RESOLVED, receipt_id set) or sets it aside (IGNORED). ux_receipt_import_row_doc_no: a document number is queued
--    at most once, so re-uploading a file never re-queues a row already waiting or already handled.
--
-- Additive: two nullable/defaulted columns, two new tables, two indexes. No existing row's money changes.
ALTER TABLE sales.payment_receipt
    ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS customer_name TEXT;

ALTER TABLE sales.payment_receipt DROP CONSTRAINT IF EXISTS chk_payment_receipt_source;
ALTER TABLE sales.payment_receipt
    ADD CONSTRAINT chk_payment_receipt_source CHECK (source IN ('MANUAL', 'EXPRESS'));

CREATE UNIQUE INDEX IF NOT EXISTS ux_payment_receipt_express_ref
    ON sales.payment_receipt (receipt_ref) WHERE source = 'EXPRESS';

CREATE TABLE IF NOT EXISTS sales.receipt_import (
    import_id    BIGSERIAL PRIMARY KEY,
    file_name    TEXT NOT NULL,
    uploaded_by  BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    total_rows   INTEGER NOT NULL DEFAULT 0 CHECK (total_rows >= 0),
    matched      INTEGER NOT NULL DEFAULT 0 CHECK (matched >= 0),
    unmatched    INTEGER NOT NULL DEFAULT 0 CHECK (unmatched >= 0),
    duplicates   INTEGER NOT NULL DEFAULT 0 CHECK (duplicates >= 0),
    invalid      INTEGER NOT NULL DEFAULT 0 CHECK (invalid >= 0),
    matched_amount    NUMERIC(14,2) NOT NULL DEFAULT 0,
    unmatched_amount  NUMERIC(14,2) NOT NULL DEFAULT 0,
    duplicate_amount  NUMERIC(14,2) NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sales.receipt_import_row (
    row_id             BIGSERIAL PRIMARY KEY,
    import_id          BIGINT NOT NULL REFERENCES sales.receipt_import(import_id),
    doc_no             TEXT NOT NULL CHECK (btrim(doc_no) <> ''),
    doc_date           DATE NOT NULL,
    customer_name      TEXT,
    amount             NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    reference          TEXT,
    -- Why the matcher left it: NO_CANDIDATE, AMBIGUOUS, OVERPAYMENT or DEAL_NOT_ACTIVE (ReceiptMatcher.Reason).
    reason             TEXT NOT NULL,
    status             TEXT NOT NULL DEFAULT 'UNMATCHED' CHECK (status IN ('UNMATCHED', 'RESOLVED', 'IGNORED')),
    resolved_ticket_id BIGINT REFERENCES sales.ticket(ticket_id),
    receipt_id         BIGINT REFERENCES sales.payment_receipt(receipt_id),
    resolved_by        BIGINT REFERENCES hr.employee(employee_id),
    resolved_at        TIMESTAMPTZ,
    CONSTRAINT chk_receipt_import_row_resolved CHECK (
        (status = 'RESOLVED') = (resolved_ticket_id IS NOT NULL AND receipt_id IS NOT NULL)),
    CONSTRAINT chk_receipt_import_row_handled CHECK ((status = 'UNMATCHED') = (resolved_at IS NULL))
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_receipt_import_row_doc_no ON sales.receipt_import_row (upper(btrim(doc_no)));
CREATE INDEX IF NOT EXISTS idx_receipt_import_row_open ON sales.receipt_import_row (status) WHERE status = 'UNMATCHED';

COMMENT ON COLUMN sales.payment_receipt.source IS
    'MANUAL (typed by account) or EXPRESS (uploaded Express export, deduped by receipt_ref = Express doc no).';
COMMENT ON TABLE sales.receipt_import_row IS
    'Express receipt rows the matcher could not place; resolved onto a deal or ignored by a person (spec 2026-11-15 §7.8).';
