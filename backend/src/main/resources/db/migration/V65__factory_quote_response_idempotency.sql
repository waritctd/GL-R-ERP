-- Idempotency receipts for factory quote responses (receive()).
--
-- The first-response path UPDATEs an existing sales.factory_quote row and the
-- revision path INSERTs a new one, so factory_quote.client_request_id could
-- not cover both paths with a single column. A separate receipt table keyed
-- by (created_by, client_request_id) records which factory_quote row a given
-- idempotency key resolved to, regardless of which path produced it.
CREATE TABLE sales.factory_quote_response_receipt (
    factory_quote_response_receipt_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    factory_quote_id BIGINT NOT NULL REFERENCES sales.factory_quote(factory_quote_id) ON DELETE CASCADE,
    created_by BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    client_request_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_factory_quote_response_receipt_creator_client
    ON sales.factory_quote_response_receipt (created_by, client_request_id);

CREATE INDEX idx_factory_quote_response_receipt_quote
    ON sales.factory_quote_response_receipt (factory_quote_id);
