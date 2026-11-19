-- Feedback #62: a credit customer pays nothing up front. A zero-deposit quotation gains a fourth
-- full-payment term, CREDIT ("บริษัทขอรับเงินค่าสินค้าภายใน N วัน"), whose N lives in the existing
-- credit_days column. Only the CHECK from V181 changes.
ALTER TABLE sales.quotation
    DROP CONSTRAINT chk_quotation_full_payment_term;

ALTER TABLE sales.quotation
    ADD CONSTRAINT chk_quotation_full_payment_term CHECK (
        full_payment_term IS NULL
        OR full_payment_term IN ('BEFORE_DELIVERY', 'ON_DELIVERY', 'ON_OR_BEFORE_DELIVERY', 'CREDIT')
    );
