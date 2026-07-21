-- Step 2 review hardening: add idempotency and financial invariants without
-- rewriting the V61 foundation migration.

CREATE UNIQUE INDEX uq_pricing_costing_creator_client_request
    ON sales.pricing_costing (created_by, client_request_id)
    WHERE client_request_id IS NOT NULL;

CREATE UNIQUE INDEX uq_pricing_costing_item_request_item
    ON sales.pricing_costing_item (pricing_costing_id, pricing_request_item_id);

CREATE UNIQUE INDEX uq_factory_quote_current_factory_id
    ON sales.factory_quote (pricing_request_id, factory_id)
    WHERE factory_id IS NOT NULL AND is_current = TRUE AND status <> 'CANCELLED';

CREATE UNIQUE INDEX uq_factory_quote_creator_client_request
    ON sales.factory_quote (created_by, client_request_id)
    WHERE client_request_id IS NOT NULL;
