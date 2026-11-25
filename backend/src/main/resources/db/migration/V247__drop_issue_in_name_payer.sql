-- Feedback #74, final design (Ploy: "basically no need for ในนาม but its like changing the customer and their details
-- entire so for deposit and notive they should be able to select the same customer for quotation (default) or create an
-- entirely new customer"). เรียน (addressee_customer_id, V246) is the whole customer; the separate ออกในนาม person
-- (V244 payer_customer_id, V245 payer_customer_id, V246 payer_name / payer_phone / payer_email) is gone from the code.
-- Production held no row with a payer when this was written.
-- Rollback: re-add the columns as V244/V245/V246 defined them (they come back empty).

ALTER TABLE sales.deposit_notice
    DROP COLUMN payer_customer_id,
    DROP COLUMN payer_name,
    DROP COLUMN payer_phone,
    DROP COLUMN payer_email;
ALTER TABLE sales.remaining_invoice
    DROP COLUMN payer_customer_id,
    DROP COLUMN payer_name,
    DROP COLUMN payer_phone,
    DROP COLUMN payer_email;
