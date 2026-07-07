-- R3: Factory field on ticket_item for brand-to-factory mapping
-- factory is populated from the product catalog (catalog_extract.py)
-- Import view groups items by factory to separate per-factory requests
ALTER TABLE sales.ticket_item ADD COLUMN factory VARCHAR(200);
