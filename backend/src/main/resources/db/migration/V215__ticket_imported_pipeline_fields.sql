-- V215: the two pipeline-workbook facts a deal had no home for (spec 2026-11-15 §7.9, plan Task 4.1).
-- Numbered V215, not V211, on purpose: V211 is taken by the visits slice and V212+ may be taken by the cash slice on
-- sibling branches of the same stack, so this leaves a gap rather than colliding when the stack merges. Flyway's
-- out-of-order is off and validate-on-migrate is false on prod (CLAUDE.md), so the IMAGE that carries this must also
-- carry every lower pending migration of the stack, or those are silently skipped.
--
-- * imported_value: the rep's stated deal value (THB) from the workbook. The board's value comes from the deal's
--   latest customer-facing quotation; an imported deal usually has none yet, so the board falls back to this.
--   Written only by the pipeline import, only into a NULL column.
-- * imported_quotation_no: the quotation number the rep's workbook carries (often issued before the app). It is
--   the import's idempotency key: a re-run matches (owner, this number) and creates nothing.
--
-- Additive and nullable, no backfill. Rollback: drop the index, the constraint and both columns (only the pipeline
-- import and the board read them).
ALTER TABLE sales.ticket ADD COLUMN IF NOT EXISTS imported_value NUMERIC(14, 2);
ALTER TABLE sales.ticket ADD COLUMN IF NOT EXISTS imported_quotation_no TEXT;

ALTER TABLE sales.ticket DROP CONSTRAINT IF EXISTS chk_ticket_imported_value;
ALTER TABLE sales.ticket ADD CONSTRAINT chk_ticket_imported_value
    CHECK (imported_value IS NULL OR imported_value >= 0);

CREATE INDEX IF NOT EXISTS ix_ticket_owner_imported_quotation_no
    ON sales.ticket (created_by, lower(btrim(imported_quotation_no)))
    WHERE imported_quotation_no IS NOT NULL;

COMMENT ON COLUMN sales.ticket.imported_value IS
    'Deal value (THB) from the one-time pipeline import; the board shows it when no quotation gives a value.';
COMMENT ON COLUMN sales.ticket.imported_quotation_no IS
    'Quotation number from the rep pipeline workbook; the pipeline import match key together with the owner.';
