-- Stock lines on a pricing request (IA .design/stock-item-pricing/INFORMATION_ARCHITECTURE.md,
-- section 1.1, slice 1): a line's SOURCE is one of
--
--     NULL          สั่งนำเข้า          (import - the default; factory quote + landed cost)
--     IN_THAILAND   สต็อกในไทย         (stock already in Thailand)
--     IN_TRANSIT    สต็อกกำลังเดินทาง   (stock on the water; import confirms an ETA per line)
--
-- A stock line is never sent to a factory and never costed; the CEO types its ราคาตั้ง.
--
-- MIGRATION NUMBERING: V194. Verified free against db/migration (tops out at V193), db/migration-demo
-- (V21/V32/V46/V91.1/V139) and every remote branch's tree immediately before writing this file.
--
-- THIS FILE IS TEST-FIRST SCAFFOLDING for slice 1: it is exactly the schema the slice-1 tests need in
-- order to fail on ASSERTIONS instead of on a missing column. It carries no behaviour.

-- ── pricing_request_item: the source + the ETA import confirms for an in-transit line ──────────
ALTER TABLE sales.pricing_request_item
    ADD COLUMN stock_source               VARCHAR(20),
    ADD COLUMN expected_arrival_date      DATE,
    ADD COLUMN expected_arrival_set_by_id BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    ADD COLUMN expected_arrival_set_at    TIMESTAMPTZ;

ALTER TABLE sales.pricing_request_item
    ADD CONSTRAINT chk_pricing_request_item_stock_source
        CHECK (stock_source IS NULL OR stock_source IN ('IN_THAILAND', 'IN_TRANSIT')),
    -- An ETA only means something for a line that is in transit. NB: COALESCE is load-bearing.
    -- Written as `stock_source = 'IN_TRANSIT'` the predicate is NULL (= passes a CHECK) for an
    -- import line (stock_source IS NULL), which would let an ETA sit on an import line - caught by
    -- StockLineSchemaIntegrationTest#anEtaOnARowThatIsNotInTransit_isRejectedByTheDatabase.
    ADD CONSTRAINT chk_pricing_request_item_eta_only_in_transit
        CHECK (expected_arrival_date IS NULL OR COALESCE(stock_source, '') = 'IN_TRANSIT');

COMMENT ON COLUMN sales.pricing_request_item.stock_source IS
    'NULL = สั่งนำเข้า (factory quote + landed cost). IN_THAILAND / IN_TRANSIT = a stock line: never '
    'sent to a factory, never costed, priced by the CEO (ราคาตั้ง). Locked for sales after submit.';
COMMENT ON COLUMN sales.pricing_request_item.expected_arrival_date IS
    'วันที่คาดว่าจะถึง - set by import (or the CEO) on an IN_TRANSIT line; required before the request '
    'may reach the CEO. Allowed only when stock_source = IN_TRANSIT.';

-- ── quotation_item: the same source, copied by the pipeline quotation from the pricing-request item ─
ALTER TABLE sales.quotation_item
    ADD COLUMN stock_source VARCHAR(20);
ALTER TABLE sales.quotation_item
    ADD CONSTRAINT chk_quotation_item_stock_source
        CHECK (stock_source IS NULL OR stock_source IN ('IN_THAILAND', 'IN_TRANSIT'));

-- ── pricing_decision_item: a stock line has no factory quote, so it has no costing row ──────────
ALTER TABLE sales.pricing_decision_item
    ALTER COLUMN pricing_costing_item_id DROP NOT NULL;

-- "NULL only for a stock line". A CHECK cannot look at another table, and a composite FK onto
-- pricing_request_item(id, stock_source) would make convertInTransitToImport (which CLEARS
-- stock_source on the request item) fail whenever a RETURNED decision still references the line.
-- So the rule is a BEFORE INSERT/UPDATE trigger: it is evaluated when the decision item is written,
-- and later history (a stock line converted to import afterwards) is left alone.
CREATE FUNCTION sales.fn_pricing_decision_item_costing_link_requires_stock_line() RETURNS trigger AS $$
BEGIN
    IF NEW.pricing_costing_item_id IS NULL AND NOT EXISTS (
        SELECT 1
          FROM sales.pricing_request_item pri
         WHERE pri.pricing_request_item_id = NEW.pricing_request_item_id
           AND pri.stock_source IS NOT NULL
    ) THEN
        RAISE EXCEPTION
            'pricing_decision_item.pricing_costing_item_id may be NULL only for a stock line (pricing_request_item %)',
            NEW.pricing_request_item_id
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'chk_pricing_decision_item_null_costing_only_for_stock_line';
    END IF;
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_pricing_decision_item_null_costing_only_for_stock_line
    BEFORE INSERT OR UPDATE OF pricing_costing_item_id, pricing_request_item_id
    ON sales.pricing_decision_item
    FOR EACH ROW
    EXECUTE FUNCTION sales.fn_pricing_decision_item_costing_link_requires_stock_line();
