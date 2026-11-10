-- V207: CEO-entered freight for a line the freight table has no band for (e.g. 12-16 mm > 800 sqm,
-- 17-20 mm > 450 sqm). Persisted on the item so it survives every cost recalculation and is used only
-- while no band matches (LandedCostCalculator). THB, TOTAL for the line; NULL = none.
ALTER TABLE sales.pricing_request_item
    ADD COLUMN manual_freight_thb NUMERIC(18,4),
    ADD CONSTRAINT chk_pricing_request_item_manual_freight_positive
        CHECK (manual_freight_thb IS NULL OR manual_freight_thb > 0);

COMMENT ON COLUMN sales.pricing_request_item.manual_freight_thb IS
    'CEO-entered freight in THB (TOTAL for this line) used by costing only when the freight table has no band for the line. Set via PUT /api/pricing-requests/{id}/items/{itemId}/freight.';

-- Structured twin of uncostable_reason (comma-separated: MISSING_SQM, MISSING_THICKNESS, MISSING_ORIGIN,
-- NO_FREIGHT_BAND) so the UI picks the right fix without matching Thai text. NULL for a costable line.
ALTER TABLE sales.pricing_costing_item ADD COLUMN uncostable_code VARCHAR(100);
COMMENT ON COLUMN sales.pricing_costing_item.uncostable_code IS
    'Machine-readable reason codes (comma-separated) behind uncostable_reason; NULL when the line costed.';
