-- Feedback #74, Ploy's ruling "do it the same manner as ใบแจ้งยอด make it flexible": a remaining invoice may be issued
-- in the name of a customer-list row other than its quotation's customer (ออกในนาม), exactly as the deposit notice (V244).
-- NULL keeps the #65 default. Name, tax id and address are read from the row at render time.
-- Rollback: ALTER TABLE sales.remaining_invoice DROP COLUMN payer_customer_id;
-- DELETE FROM flyway_schema_history WHERE version = '245' (in the schema Flyway uses).

ALTER TABLE sales.remaining_invoice
    ADD COLUMN payer_customer_id BIGINT REFERENCES customers.customer(customer_id);
COMMENT ON COLUMN sales.remaining_invoice.payer_customer_id IS 'Customer the invoice is issued to (ออกในนาม) instead of the quotation''s party customer (feedback #74); NULL = the quotation''s customer.';
