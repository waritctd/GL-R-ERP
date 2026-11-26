-- V249: the delivery date sales asks for on a delivery round (optional), so the warehouse can plan trips by it.
--
-- Rollback: drop the column.

ALTER TABLE sales.delivery_request ADD COLUMN requested_date DATE;
