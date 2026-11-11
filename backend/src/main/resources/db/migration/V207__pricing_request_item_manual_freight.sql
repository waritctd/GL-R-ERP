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

-- One-time backfill for rows costed before this migration: derive the code from the Thai reason text the
-- calculator has always produced (LandedCostCalculator#uncostableReason): "ตร.ม./แผ่น (sqm per piece)",
-- "ความหนา (thickness_mm)" / "ความหนา", "ประเทศต้นทาง (origin country)" / "ประเทศต้นทาง" (the old "ไม่มีประเทศต้นทาง
-- (origin country) จาก Price Catalog" included). Rows that match nothing keep a NULL code.
UPDATE sales.pricing_costing_item
   SET uncostable_code = NULLIF(concat_ws(',',
           CASE WHEN uncostable_reason LIKE '%ตร.ม./แผ่น (sqm per piece)%' THEN 'MISSING_SQM' END,
           CASE WHEN uncostable_reason LIKE '%ความหนา (thickness_mm)%' THEN 'MISSING_THICKNESS' END,
           CASE WHEN uncostable_reason LIKE '%ประเทศต้นทาง%' THEN 'MISSING_ORIGIN' END,
           CASE WHEN uncostable_reason LIKE '%ไม่มีอัตราค่าขนส่งในตาราง%' THEN 'NO_FREIGHT_BAND' END), '')
 WHERE uncostable_reason IS NOT NULL AND uncostable_code IS NULL;
