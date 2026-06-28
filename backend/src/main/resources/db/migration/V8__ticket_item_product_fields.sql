-- Rename ticket_item columns to match sales domain (brand/model/texture)
ALTER TABLE sales.ticket_item RENAME COLUMN product_name TO brand;
ALTER TABLE sales.ticket_item RENAME COLUMN product_code TO model;
ALTER TABLE sales.ticket_item RENAME COLUMN size TO texture;

-- Ensure brand is required (was already NOT NULL via product_name constraint)
ALTER TABLE sales.ticket_item ALTER COLUMN brand SET NOT NULL;
