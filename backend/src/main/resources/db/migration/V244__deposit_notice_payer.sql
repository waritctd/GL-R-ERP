-- Feedback #74: a deposit notice may be issued to a payer other than the quotation's customer (the company that asked
-- for the price is not always the one who pays). The payer is a customer-master row picked by sales; NULL keeps the
-- #65 default, the referenced quotation's party customer. Name, tax id and address are read from the row at render time.
-- Rollback: ALTER TABLE sales.deposit_notice DROP COLUMN payer_customer_id;
-- DELETE FROM flyway_schema_history WHERE version = '244' (in the schema Flyway uses).

ALTER TABLE sales.deposit_notice
    ADD COLUMN payer_customer_id BIGINT REFERENCES customers.customer(customer_id);
COMMENT ON COLUMN sales.deposit_notice.payer_customer_id IS 'Customer the notice is issued to instead of the quotation''s party customer (feedback #74); NULL = the quotation''s customer.';
