-- V237: a deal that was ordered before the portal existed (สั่งซื้อแล้ว). It may request a tax invoice, issue an IR and
-- issue invoices without the order-confirmation and deposit steps being done in the portal. The flag is the mark.
--
-- Rollback: ALTER TABLE sales.ticket DROP COLUMN pre_portal;

ALTER TABLE sales.ticket ADD COLUMN pre_portal BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN sales.ticket.pre_portal IS 'deal ordered before the portal (set by the สั่งซื้อแล้ว start or the first delivery request on an old deal)';

-- The deposit a rep says the customer paid outside the portal is ONE receipt of kind DEPOSIT and source PRE_PORTAL.
-- Rollback: delete PRE_PORTAL receipts and re-declare chk_payment_receipt_source without 'PRE_PORTAL'.
ALTER TABLE sales.payment_receipt DROP CONSTRAINT IF EXISTS chk_payment_receipt_source;
ALTER TABLE sales.payment_receipt
    ADD CONSTRAINT chk_payment_receipt_source CHECK (source IN ('MANUAL', 'EXPRESS', 'TAX_INVOICE', 'PRE_PORTAL'));

-- Account may link a PRE_PORTAL receipt to the Express document that is the same money (as for a MANUAL one).
ALTER TABLE sales.payment_receipt DROP CONSTRAINT IF EXISTS chk_payment_receipt_express_link;
ALTER TABLE sales.payment_receipt
    ADD CONSTRAINT chk_payment_receipt_express_link CHECK (express_doc_no IS NULL OR source IN ('MANUAL', 'PRE_PORTAL'));
