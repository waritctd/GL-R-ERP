-- V214: slice 3 review fixes (Express receipts upload, expected payments). V212/V213 are not edited in place.
--
-- 1. expected_payment.received_amount (M1, partial payments). A receipt adds to the nearest OPEN row of the matching
--    kind; the row turns RECEIVED only when the cumulative amount reaches its amount within the matcher tolerance.
--    Existing RECEIVED rows backfill as fully received.
-- 2. payment_receipt.express_doc_no (H1). A MANUAL receipt account typed by hand that an uploaded Express row turns out
--    to be (same deal, same amount, within ±7 days) is LINKED to that Express document number instead of a second
--    receipt being written. The manual row's own fields are not touched. One link per Express document number.
-- 3. The EXPRESS receipt_ref unique index becomes case/space-insensitive (M3): 'RE-1' and ' re-1' are one document.
--    (receipt_import_row's doc_no index was already upper(btrim(...)) in V213.)
-- 4. receipt_import: non_cash (H4 — IV/AI rows are not cash), matched_manual (H1), failed (M3) counts.
-- 5. receipt_import_row: manager_flagged_at/_by (M5 "ส่งให้ผู้จัดการขายดู" — account still resolves).
-- 6. receipt_import_allocation (M7): a row resolved across several deals (a billing note covering several deals) —
--    one line per deal, each with its own EXPRESS receipt; the lines sum exactly to the row amount.
ALTER TABLE sales.expected_payment
    ADD COLUMN IF NOT EXISTS received_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (received_amount >= 0);
UPDATE sales.expected_payment SET received_amount = amount WHERE status = 'RECEIVED' AND received_amount = 0;

ALTER TABLE sales.payment_receipt ADD COLUMN IF NOT EXISTS express_doc_no TEXT;
ALTER TABLE sales.payment_receipt DROP CONSTRAINT IF EXISTS chk_payment_receipt_express_link;
ALTER TABLE sales.payment_receipt
    ADD CONSTRAINT chk_payment_receipt_express_link CHECK (express_doc_no IS NULL OR source = 'MANUAL');
CREATE UNIQUE INDEX IF NOT EXISTS ux_payment_receipt_express_link
    ON sales.payment_receipt (upper(btrim(express_doc_no))) WHERE express_doc_no IS NOT NULL;

DROP INDEX IF EXISTS sales.ux_payment_receipt_express_ref;
CREATE UNIQUE INDEX IF NOT EXISTS ux_payment_receipt_express_ref
    ON sales.payment_receipt (upper(btrim(receipt_ref))) WHERE source = 'EXPRESS';

ALTER TABLE sales.receipt_import
    ADD COLUMN IF NOT EXISTS non_cash INTEGER NOT NULL DEFAULT 0 CHECK (non_cash >= 0),
    ADD COLUMN IF NOT EXISTS matched_manual INTEGER NOT NULL DEFAULT 0 CHECK (matched_manual >= 0),
    ADD COLUMN IF NOT EXISTS failed INTEGER NOT NULL DEFAULT 0 CHECK (failed >= 0);

ALTER TABLE sales.receipt_import_row
    ADD COLUMN IF NOT EXISTS manager_flagged_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS manager_flagged_by BIGINT REFERENCES hr.employee(employee_id);

CREATE TABLE IF NOT EXISTS sales.receipt_import_allocation (
    allocation_id BIGSERIAL PRIMARY KEY,
    row_id        BIGINT NOT NULL REFERENCES sales.receipt_import_row(row_id),
    ticket_id     BIGINT NOT NULL REFERENCES sales.ticket(ticket_id),
    receipt_id    BIGINT NOT NULL REFERENCES sales.payment_receipt(receipt_id),
    amount        NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    UNIQUE (row_id, ticket_id)
);
