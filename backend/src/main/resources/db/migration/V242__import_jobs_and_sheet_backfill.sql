-- V242: งานนำเข้า that import creates themselves, and the one time backfill of the import team's sheet (spec 0001).
--
-- An import job is the existing no-project import request (V239) with a new source mark and a new purpose:
--   source   PORTAL (raised by sales, every row until now), IMPORT (import created it), BACKFILL (loaded from the sheet)
--   purpose  CUSTOMER: goods ordered for a customer whose job is not in the portal yet
-- An IMPORT or BACKFILL row may have no requester and no wanted-by date, because the sheet often has neither.
-- A shipment round gains the two dates the sheet tracks and the portal did not, and backfill_key, which lets a later run
-- find the same block of the sheet again.
--
-- Deploy order: the branch claude/quotation-sample-mark holds V241. Deploy V241 before or with this migration, because
-- production does not validate on migrate and would skip a lower version without an error.
--
-- Rollback, in this order, and only after every IMPORT and BACKFILL row is gone:
--   1. DROP TABLE sales.import_sheet_backfill_run;
--   2. DROP INDEX sales.ux_shipment_round_backfill_key;
--      ALTER TABLE sales.shipment_round DROP COLUMN backfill_key, DROP COLUMN insured_on, DROP COLUMN transship_eta_on;
--   3. ALTER TABLE sales.import_request DROP CONSTRAINT chk_import_request_project_or_order,
--        DROP CONSTRAINT chk_import_request_order_purpose, DROP COLUMN source;
--   4. Re-add the two checks exactly as V239 declared them.
--   Flyway history keeps version 242: a rollback is a new migration that does steps 1 to 4, or a restore from the backup.

ALTER TABLE sales.import_request
    ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'PORTAL'
        CONSTRAINT chk_import_request_source CHECK (source IN ('PORTAL', 'IMPORT', 'BACKFILL'));

ALTER TABLE sales.import_request
    DROP CONSTRAINT chk_import_request_order_purpose,
    ADD CONSTRAINT chk_import_request_order_purpose
        CHECK (order_purpose IS NULL OR order_purpose IN ('SAMPLE', 'SHOWROOM', 'STOCK', 'CUSTOMER'));

ALTER TABLE sales.import_request
    DROP CONSTRAINT chk_import_request_project_or_order,
    ADD CONSTRAINT chk_import_request_project_or_order CHECK (
        ticket_id IS NOT NULL
        OR (order_purpose IS NOT NULL AND purpose_note IS NOT NULL
            AND (source IN ('IMPORT', 'BACKFILL') OR (requester_id IS NOT NULL AND wanted_by IS NOT NULL))));

COMMENT ON COLUMN sales.import_request.source IS 'PORTAL = raised by sales; IMPORT = a งานนำเข้า import created; BACKFILL = loaded from the import sheet.';
COMMENT ON COLUMN sales.import_request.order_purpose IS 'SAMPLE / SHOWROOM / STOCK / CUSTOMER for an import request with no deal (ticket_id NULL); NULL on a deal IR.';

ALTER TABLE sales.shipment_round
    ADD COLUMN transship_eta_on DATE,
    ADD COLUMN insured_on       DATE,
    ADD COLUMN backfill_key     VARCHAR(40);

CREATE UNIQUE INDEX ux_shipment_round_backfill_key ON sales.shipment_round (backfill_key) WHERE backfill_key IS NOT NULL;

COMMENT ON COLUMN sales.shipment_round.transship_eta_on IS 'ETA at the transshipment port (the second vessel).';
COMMENT ON COLUMN sales.shipment_round.insured_on IS 'Date the shipment was insured.';
COMMENT ON COLUMN sales.shipment_round.backfill_key IS 'First IR number of the sheet block plus its count in the file: how a later backfill run finds this round.';

-- One row per committed backfill run: the audit trail, the input to a targeted undo, and the source of the list of items
-- import still has to check. Holds no cost figure and no copy of the file.
CREATE TABLE sales.import_sheet_backfill_run (
    run_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id        BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    actor_name      VARCHAR(200) NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    file_name       VARCHAR(255) NOT NULL,
    file_size       BIGINT NOT NULL,
    file_sha256     CHAR(64) NOT NULL,
    rounds_created  INT NOT NULL DEFAULT 0,
    rounds_updated  INT NOT NULL DEFAULT 0,
    irs_created     INT NOT NULL DEFAULT 0,
    irs_adopted     INT NOT NULL DEFAULT 0,
    irs_skipped     INT NOT NULL DEFAULT 0,
    blocks_excluded INT NOT NULL DEFAULT 0,
    deals_advanced  INT NOT NULL DEFAULT 0,
    summary         JSONB NOT NULL DEFAULT '{}'
);
