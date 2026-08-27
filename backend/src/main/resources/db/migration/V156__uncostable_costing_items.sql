-- V156: let an item that CANNOT be costed exist, so the deal reaches the CEO instead of dying.
--
-- ── The defect this fixes ────────────────────────────────────────────────────
-- LandedCostCalculator#resolveThicknessMm throws 422 when a catalogue row has no thickness_mm,
-- and it throws from inside PricingDecisionService#startReview -- i.e. BEFORE any costing row is
-- written and therefore before the CEO can see the decision screen at all. The escape hatch the
-- CEO needs already exists on both sides:
--
--   * cost  side: sales.pricing_costing_item.manual_landed_cost_per_unit_thb (V141)
--   * price side: sales.pricing_decision_item.manual_selling_price_per_requested_unit (V150)
--
-- ...and PricingDecisionService#approve already treats a manually-priced line as needing no
-- margin ("Phase 1 UI simplification", :341-350). So the capability was there and the ROUTE to it
-- was blocked: costing aborted before the screen that owns the override could ever render. This
-- is the same shape as the stage-13/14 defect where an advertisement gate sat narrower than its
-- mutation gate -- a panel with no reachable button.
--
-- 41.7% of the production catalogue (9,387 of 22,522 rows, measured 2026-08-28) has no thickness,
-- and for ~232 of those products NO source carries it: Bode's price list is a quotation sheet with
-- no weight/area/thickness at all, REFIN's OUT2.0 is not in REFIN's current workbook, and four
-- Vives rows have no collection. Those cannot be derived by any means, so "fix the data first" is
-- not a complete answer and the pipeline has to tolerate them.
--
-- ── What changes, and what deliberately does not ─────────────────────────────
-- An uncostable item now PERSISTS with NULL freight-dependent costs and a stated reason, instead
-- of aborting the whole calculation. The gate moves from startReview to approve(), exactly where
-- the margin gate already sits. Nothing is ever priced on a guessed thickness: approve() refuses
-- while any line still has neither a computed cost nor a manual override.
--
-- Only the SHIPMENT-DERIVED columns become nullable. goods_cost_thb, insurance_cost_thb,
-- inland_transport_cost_thb and other_cost_thb stay NOT NULL because they are computable without
-- a thickness -- narrowing the relaxation keeps "NULL means we genuinely could not compute it"
-- true, rather than turning the whole row into an optional bag.
--
-- import_duty_thb and clearance_fee_thb are in the relaxed set because they are NOT independent:
-- duty is charged on CIF (which contains freight), and the clearance fee is a shipment-level
-- amount allocated across the costable lines only (see LandedCostCalculator#costShipment). A row
-- with no freight has neither. calculation_snapshot deliberately stays NOT NULL: the repository
-- already coerces a null snapshot to an empty '{}' document, so an uncostable row carries an empty
-- snapshot rather than a null one -- less churn than changing a binding every other row relies on.

ALTER TABLE sales.pricing_costing_item
    ALTER COLUMN freight_cost_thb          DROP NOT NULL,
    ALTER COLUMN cif_cost_thb              DROP NOT NULL,
    ALTER COLUMN landed_cost_per_unit_thb  DROP NOT NULL,
    ALTER COLUMN total_landed_cost_thb     DROP NOT NULL,
    ALTER COLUMN import_duty_thb           DROP NOT NULL,
    ALTER COLUMN clearance_fee_thb         DROP NOT NULL;

ALTER TABLE sales.pricing_costing_item
    ADD COLUMN uncostable_reason TEXT;

-- The invariant that keeps a NULL cost honest: a row is EITHER fully costed OR carries a stated
-- reason it is not. Never both, never neither. Existing rows all have a total and no reason, so
-- they satisfy this unchanged.
ALTER TABLE sales.pricing_costing_item
    ADD CONSTRAINT chk_pricing_costing_item_uncostable_xor CHECK (
        (total_landed_cost_thb IS NOT NULL AND uncostable_reason IS NULL)
     OR (total_landed_cost_thb IS NULL     AND uncostable_reason IS NOT NULL)
    );

-- Every shipment-derived column moves together with the total -- they are all outputs of the same
-- freight lookup, so a row can never be half-costed.
ALTER TABLE sales.pricing_costing_item
    ADD CONSTRAINT chk_pricing_costing_item_freight_cols_together CHECK (
        (total_landed_cost_thb IS NULL)
            = (freight_cost_thb IS NULL
               AND cif_cost_thb IS NULL
               AND landed_cost_per_unit_thb IS NULL
               AND import_duty_thb IS NULL
               AND clearance_fee_thb IS NULL)
    );

COMMENT ON COLUMN sales.pricing_costing_item.uncostable_reason IS
    'Why this line could not be costed automatically (no thickness_mm or no origin country on the '
    'catalogue row, so sales.pricing_freight_rate cannot be looked up). NON-NULL means every '
    'freight-dependent cost column on this row is NULL by design, not by accident -- see '
    'chk_pricing_costing_item_uncostable_xor. The CEO clears it by supplying '
    'manual_landed_cost_per_unit_thb (V141) on the decision screen; PricingDecisionService#approve '
    'refuses while any line is still uncostable AND un-overridden.';

-- The decision snapshot inherits the same nullability, for the same reason: a decision item is
-- created from a costing item at startReview, and an uncostable costing item has no cost to
-- freeze. approve() is what guarantees these are non-null by the time anything is APPROVED.
ALTER TABLE sales.pricing_decision_item
    ALTER COLUMN frozen_landed_cost_per_piece_thb          DROP NOT NULL,
    ALTER COLUMN frozen_landed_cost_per_requested_unit_thb DROP NOT NULL;

COMMENT ON COLUMN sales.pricing_decision_item.frozen_landed_cost_per_requested_unit_thb IS
    'Landed cost per REQUESTED unit, frozen at startReview. NULL only while the source costing '
    'item is uncostable (sales.pricing_costing_item.uncostable_reason) -- the CEO must supply a '
    'manual cost or a manual selling price before approve() will accept the decision, so an '
    'APPROVED decision never carries a NULL here.';
