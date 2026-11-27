-- V250: a delivery round can be a customer pickup (ลูกค้ามารับเอง): no trip, the customer collects at the warehouse.
-- Existing rounds are deliveries.
--
-- Rollback: drop the column.

ALTER TABLE sales.delivery_request ADD COLUMN customer_pickup BOOLEAN NOT NULL DEFAULT FALSE;
