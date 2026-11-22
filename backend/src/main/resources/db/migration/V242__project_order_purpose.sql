-- A no-project order may now be for a PROJECT: the project is known, the deal is not yet (track now, pair later). The
-- project's name is the order's purpose_note. Pairing it to a deal (ImportRequestService#pairToDeal) sets ticket_id and the
-- lines' ticket_item_id; the purpose columns stay as the record of where it started.
ALTER TABLE sales.import_request DROP CONSTRAINT chk_import_request_order_purpose;
ALTER TABLE sales.import_request
    ADD CONSTRAINT chk_import_request_order_purpose CHECK (order_purpose IS NULL OR order_purpose IN ('SAMPLE','SHOWROOM','STOCK','PROJECT'));
COMMENT ON COLUMN sales.import_request.order_purpose IS 'SAMPLE / SHOWROOM / STOCK / PROJECT for an order raised with no deal (ticket_id NULL until paired); NULL on a deal IR.';
