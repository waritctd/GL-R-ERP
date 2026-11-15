-- V217: slice 3 re-validation fixes (V214–V216 are taken).
--
-- 1. Review N3: an expected payment can never be planned below what already came in on it — otherwise its OPEN
--    remainder (amount - received_amount) goes negative and drags the cash buckets below zero. The service refuses
--    it with a Thai 409; this is the backstop for any other writer.
-- 2. Review N1: an Express row that may be a receipt account already typed by hand is QUEUED
--    (reason POSSIBLE_MANUAL_DUPLICATE) instead of being linked silently; suggested_receipt_id points at that manual
--    receipt so account can choose "รายการเดียวกับที่บันทึกเอง" (link, write nothing) or "คนละรายการ" (record it).
ALTER TABLE sales.expected_payment DROP CONSTRAINT IF EXISTS chk_expected_payment_received_le_amount;
ALTER TABLE sales.expected_payment
    ADD CONSTRAINT chk_expected_payment_received_le_amount CHECK (amount >= received_amount);

ALTER TABLE sales.receipt_import_row
    ADD COLUMN IF NOT EXISTS suggested_receipt_id BIGINT REFERENCES sales.payment_receipt(receipt_id);
