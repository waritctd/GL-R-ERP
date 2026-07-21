-- Pricing Request review remediation:
-- - product_description is the dedicated free-text product identity field.
-- - client_request_id makes createDraft idempotent per requesting sales user.

ALTER TABLE sales.pricing_request_item
    ADD COLUMN product_description TEXT;

ALTER TABLE sales.pricing_request
    ADD COLUMN client_request_id UUID;

CREATE UNIQUE INDEX uq_pricing_request_requested_by_client_request_id
    ON sales.pricing_request (requested_by, client_request_id)
    WHERE client_request_id IS NOT NULL;
