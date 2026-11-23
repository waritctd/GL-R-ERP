-- A no-project order may now be for a PROJECT: the project is known, the deal is not yet (track now, pair later). The
-- project's name is the order's purpose_note, and its project_name label reads โครงการ. Rows with this purpose are created only by
-- data scripts until a later release lets the portal create and pair them.
-- Rollback: ALTER TABLE sales.import_request DROP CONSTRAINT chk_import_request_order_purpose, then re-add it as V239 defined it with
-- order_purpose IN ('SAMPLE','SHOWROOM','STOCK') (first UPDATE sales.import_request SET order_purpose = 'STOCK', project_name = 'สต็อก' WHERE order_purpose = 'PROJECT', which keeps the project's name in
-- purpose_note and restores the project_name label the old purposes use);
-- restore the column comment; DELETE FROM flyway_schema_history WHERE version = '242' (in the schema Flyway uses).

ALTER TABLE sales.import_request DROP CONSTRAINT chk_import_request_order_purpose;
ALTER TABLE sales.import_request
    ADD CONSTRAINT chk_import_request_order_purpose CHECK (order_purpose IS NULL OR order_purpose IN ('SAMPLE','SHOWROOM','STOCK','PROJECT'));
COMMENT ON COLUMN sales.import_request.order_purpose IS 'SAMPLE / SHOWROOM / STOCK / PROJECT for an order raised with no deal (ticket_id NULL until paired); NULL on a deal IR.';
